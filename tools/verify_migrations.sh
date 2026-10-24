#!/usr/bin/env bash
# Preuve du mécanisme de migrations (carte #70), sur deux bases PostgreSQL JETABLES dans la VM : même
# image (postgres:16), même init.sql, même 01-apply.sh que la pile, sans toucher à la pile en service.
#
#   base NEUVE     : init.sql puis les migrations, à la création (01-apply.sh) ;
#   base EXISTANTE : init.sql seul à la création (dossier de migrations vide), puis migrate.sh,
#                    DEUX fois (la seconde doit être sans effet).
#
# Les migrations appliquées sont celles d'infra/init/migrations/, plus une migration de PREUVE
# (tools/verify_migrations/900-*.sql), pour que la comparaison porte sur un vrai changement de schéma.
# Exigé : schéma de la base neuve = schéma de la base migrée = schéma après la seconde migration, et
# la colonne de preuve présente des deux côtés.
# Mots de passe : aléatoires, éphémères, jamais écrits sur disque ni dans le dépôt. Rien n'est
# supprimé ici (CLAUDE.md, R-S) : les commandes de retrait sont affichées à la fin.
#
# Usage (racine du dépôt, poste) : bash tools/verify_migrations.sh [isc@<vm>]
set -euo pipefail
VM="${1:-isc@100.93.166.19}"
fail=0
# Dossier NEUF dans la VM à chaque passage : aucune migration d'un passage précédent ne peut s'y
# glisser (rien n'est supprimé ici, R-S ; les dossiers restent dans /tmp de la VM).
DIR=$(ssh "$VM" "mktemp -d /tmp/isc-migr.XXXXXX")

echo ">>> envoi d'init.sql, 01-apply.sh, migrate.sh et des migrations dans la VM ($VM:$DIR)"
ssh "$VM" "mkdir -p $DIR/avec $DIR/vide"
scp -q infra/init/init.sql infra/init/01-apply.sh infra/init/migrate.sh "$VM:$DIR/"
for f in infra/init/migrations/[0-9][0-9][0-9]-*.sql tools/verify_migrations/9[0-9][0-9]-*.sql; do
  [ -e "$f" ] || continue
  scp -q "$f" "$VM:$DIR/avec/"
done
ssh "$VM" "ls $DIR/avec"

base() { # $1 nom du conteneur ; $2 dossier de migrations monté à la création
  local admin core pdp
  admin=$(openssl rand -hex 16); core=$(openssl rand -hex 16); pdp=$(openssl rand -hex 16)
  ssh "$VM" "docker run -d --name $1 \
    -e POSTGRES_USER=isc_admin -e POSTGRES_DB=identishield \
    -e POSTGRES_PASSWORD=$admin -e ISC_CORE_APP_PASSWORD=$core -e ISC_PDP_APP_PASSWORD=$pdp \
    -v $DIR/init.sql:/opt/isc/init.sql:ro -v $DIR/01-apply.sh:/docker-entrypoint-initdb.d/01-apply.sh:ro \
    -v $2:/opt/isc/migrations:ro postgres:16" >/dev/null
  for _ in $(seq 1 60); do
    if ssh "$VM" "docker logs $1 2>&1 | grep -q 'init process complete' \
        && docker exec $1 pg_isready -q -h 127.0.0.1 -U isc_admin -d identishield"; then
      return 0
    fi
    sleep 2
  done
  ssh "$VM" "docker logs $1 2>&1 | tail -20"
  echo "ECHEC : la base $1 n'a pas démarré" >&2
  exit 1
}

# Schéma des deux schémas applicatifs, droits compris, sans les lignes variables d'un vidage à
# l'autre (commentaires, SET, et les lignes \restrict / \unrestrict à clé aléatoire de pg_dump 16).
schema() {
  ssh "$VM" "docker exec $1 pg_dump -U isc_admin -d identishield --schema-only --no-owner -n core -n pdp" \
    | grep -v -E '^(--|SET |SELECT pg_catalog\.set_config|\\restrict|\\unrestrict)' | grep -v '^$'
}

echo ">>> base NEUVE (init.sql + migrations à la création)"
base isc-migr-neuve "$DIR/avec"
echo ">>> base EXISTANTE (init.sql seul), puis migrate.sh deux fois"
base isc-migr-existante "$DIR/vide"
ssh "$VM" "cd $DIR && bash migrate.sh isc-migr-existante $DIR/avec"
s_neuve=$(schema isc-migr-neuve)
s_migree=$(schema isc-migr-existante)
ssh "$VM" "cd $DIR && bash migrate.sh isc-migr-existante $DIR/avec"
s_rejouee=$(schema isc-migr-existante)

if [ "$s_neuve" = "$s_migree" ]; then
  echo "[OK] schéma de la base neuve = schéma de la base existante migrée ($(echo "$s_neuve" | wc -l) lignes)"
else
  echo "[ECHEC] la base neuve et la base migrée diffèrent :"; diff <(echo "$s_neuve") <(echo "$s_migree") | head -20 || true; fail=1
fi
if [ "$s_migree" = "$s_rejouee" ]; then
  echo "[OK] seconde application des migrations : schéma inchangé (rejouable)"
else
  echo "[ECHEC] la seconde application a changé le schéma :"; diff <(echo "$s_migree") <(echo "$s_rejouee") | head -20 || true; fail=1
fi
for c in isc-migr-neuve isc-migr-existante; do
  col=$(ssh "$VM" "docker exec $c psql -U isc_admin -d identishield -tAc \"SELECT count(*) FROM information_schema.columns WHERE table_schema='core' AND table_name='account' AND column_name='preuve_migration'\"")
  if [ "$col" = "1" ]; then echo "[OK] $c : colonne de preuve présente (la migration a bien été appliquée)"; else echo "[ECHEC] $c : colonne de preuve absente"; fail=1; fi
done

echo "Retrait (à faire à la main, une commande par cible) :"
echo "  ssh $VM docker rm -f -v isc-migr-neuve"
echo "  ssh $VM docker rm -f -v isc-migr-existante"
[ $fail -eq 0 ] && echo "verify-migrations: VERT" || echo "verify-migrations: ROUGE"
exit $fail
