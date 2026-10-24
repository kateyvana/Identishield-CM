#!/usr/bin/env bash
# Preuve du mécanisme de migrations (carte #70 + sa seconde moitié), sur deux bases PostgreSQL
# JETABLES dans la VM : même image (postgres:16), même init.sql, même 01-apply.sh et même
# apply-migrations.sh que la pile.
#
#   base NEUVE     : init.sql puis apply-migrations.sh, à la création (01-apply.sh dans initdb) ;
#   base EXISTANTE : init.sql seul à la création (dossier de migrations vide à l'initdb), puis
#                    apply-migrations.sh exécuté dans le conteneur DEUX fois (la seconde doit être
#                    sans effet). C'est ce que fait isc-migrate à chaque make up.
#
# Les migrations appliquées sont celles d'infra/init/migrations/, plus deux migrations de PREUVE
# (tools/verify_migrations/9NN-*.sql) : 900 ajoute une colonne (vrai changement de schéma à
# comparer, enveloppée dans BEGIN/COMMIT) ; 901 est volontairement NON enveloppée et doit être
# REFUSÉE par apply-migrations.sh (règle 2 du README des migrations).
#
# Exigé : schéma de la base neuve = schéma de la base migrée = schéma après la seconde application,
# la colonne de preuve présente des deux côtés, et la migration non enveloppée refusée sans effet.
# Mots de passe : aléatoires, éphémères, jamais écrits sur disque ni dans le dépôt. Rien n'est
# supprimé ici (CLAUDE.md, R-S) : les commandes de retrait sont affichées à la fin.
#
# Usage (racine du dépôt, poste) : bash tools/verify_migrations.sh [isc@<vm>]
set -euo pipefail
VM="${1:-isc@100.93.166.19}"
fail=0
DIR=$(ssh "$VM" "mktemp -d /tmp/isc-migr.XXXXXX")

echo ">>> envoi d'init.sql, 01-apply.sh, apply-migrations.sh et des migrations dans la VM ($VM:$DIR)"
ssh "$VM" "mkdir -p $DIR/avec $DIR/vide $DIR/sans-tx"
scp -q infra/init/init.sql infra/init/01-apply.sh infra/init/apply-migrations.sh "$VM:$DIR/"
for f in infra/init/migrations/[0-9][0-9][0-9]-*.sql tools/verify_migrations/900-*.sql; do
  [ -e "$f" ] || continue
  scp -q "$f" "$VM:$DIR/avec/"
done
if [ -e tools/verify_migrations/901-sans-transaction.sql ]; then
  scp -q tools/verify_migrations/901-sans-transaction.sql "$VM:$DIR/sans-tx/"
fi
ssh "$VM" "ls $DIR/avec; echo '---'; ls $DIR/sans-tx"

base() { # $1 nom du conteneur ; $2 dossier de migrations monté à l'initdb
  local admin core pdp
  admin=$(openssl rand -hex 16); core=$(openssl rand -hex 16); pdp=$(openssl rand -hex 16)
  ssh "$VM" "docker run -d --name $1 \
    -e POSTGRES_USER=isc_admin -e POSTGRES_DB=identishield \
    -e POSTGRES_PASSWORD=$admin -e ISC_CORE_APP_PASSWORD=$core -e ISC_PDP_APP_PASSWORD=$pdp \
    -v $DIR/init.sql:/opt/isc/init.sql:ro -v $DIR/01-apply.sh:/docker-entrypoint-initdb.d/01-apply.sh:ro \
    -v $DIR/apply-migrations.sh:/opt/isc/apply-migrations.sh:ro \
    -v $2:/opt/isc/migrations:ro \
    -v $DIR/sans-tx:/opt/isc/sans-tx:ro postgres:16" >/dev/null
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

apply() {  # $1 conteneur ; $2 dossier de migrations monté dans le conteneur
  ssh "$VM" "docker exec -e POSTGRES_USER=isc_admin -e POSTGRES_DB=identishield $1 \
    bash /opt/isc/apply-migrations.sh $2"
}

# Schéma des deux schémas applicatifs, droits compris, sans les lignes variables d'un vidage à
# l'autre (commentaires, SET, et les lignes \restrict / \unrestrict à clé aléatoire de pg_dump 16).
schema() {
  ssh "$VM" "docker exec $1 pg_dump -U isc_admin -d identishield --schema-only --no-owner -n core -n pdp" \
    | grep -v -E '^(--|SET |SELECT pg_catalog\.set_config|\\restrict|\\unrestrict)' | grep -v '^$'
}

echo ">>> base NEUVE (init.sql + apply-migrations.sh à la création)"
base isc-migr-neuve "$DIR/avec"
echo ">>> base EXISTANTE (init.sql seul), puis apply-migrations.sh deux fois"
base isc-migr-existante "$DIR/avec"
apply isc-migr-existante /opt/isc/migrations
s_neuve=$(schema isc-migr-neuve)
s_migree=$(schema isc-migr-existante)
apply isc-migr-existante /opt/isc/migrations
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

# Règle 2 du README : une migration non enveloppée dans BEGIN/COMMIT est REFUSÉE par
# apply-migrations.sh. On l'applique seule depuis /opt/isc/sans-tx, on exige rc != 0, et on exige
# qu'elle n'ait AUCUN effet sur le schéma.
if ssh "$VM" "test -e $DIR/sans-tx/901-sans-transaction.sql"; then
  echo ">>> règle BEGIN/COMMIT : apply-migrations.sh refuse 901-sans-transaction.sql"
  s_avant=$(schema isc-migr-existante)
  if apply isc-migr-existante /opt/isc/sans-tx >/dev/null 2>&1; then
    echo "[ECHEC] apply-migrations.sh n'a pas refusé la migration non enveloppée"
    fail=1
  else
    echo "[OK] apply-migrations.sh a refusé la migration non enveloppée (code de sortie non nul)"
  fi
  s_apres=$(schema isc-migr-existante)
  if [ "$s_avant" = "$s_apres" ]; then
    echo "[OK] schéma inchangé après le refus (la migration n'a eu AUCUN effet)"
  else
    echo "[ECHEC] le schéma a changé malgré le refus :"; diff <(echo "$s_avant") <(echo "$s_apres") | head -10 || true; fail=1
  fi
fi

echo "Retrait (à faire à la main, une commande par cible) :"
echo "  ssh $VM docker rm -f -v isc-migr-neuve"
echo "  ssh $VM docker rm -f -v isc-migr-existante"
[ $fail -eq 0 ] && echo "verify-migrations: VERT" || echo "verify-migrations: ROUGE"
exit $fail
