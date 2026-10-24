#!/usr/bin/env bash
# Preuve de la migration 004 (B3b), sur des bases PostgreSQL JETABLES dans la VM, meme methode que
# tools/verify_migration_002.sh : meme image (postgres:16), meme init.sql, meme 01-apply.sh et meme
# apply-migrations.sh que la pile, sans toucher a la pile en service.
#
#   CAS ROUGE : base existante dont la contrainte d'etape de core.enrolment porte un AUTRE nom que
#     enrolment_stage_check. DROP CONSTRAINT IF EXISTS n'y fait rien ; la migration DOIT echouer en
#     nommant la contrainte, et la transaction DOIT tout annuler (aucune table pdp.enrolment_attempt,
#     contrainte d'etape inchangee).
#   CAS VERT : base existante (002 appliquee par la 002 elle-meme, puis la 004) : la migration passe
#     deux fois, la contrainte d'etape porte les cinq etapes, pdp.enrolment_attempt existe avec ses
#     droits, une etape RECOVERY s'insere.
#
# Mots de passe : aleatoires, ephemeres, jamais ecrits sur disque ni dans le depot. Rien n'est
# supprime ici (CLAUDE.md, R-S) : les commandes de retrait sont affichees a la fin.
#
# Usage (racine du depot, poste) : bash tools/verify_migration_004.sh [isc@<vm>]
set -euo pipefail
VM="${1:-isc@100.93.166.19}"
fail=0
DIR=$(ssh "$VM" "mktemp -d /tmp/isc-migr004.XXXXXX")

echo ">>> envoi d'init.sql, 01-apply.sh, apply-migrations.sh et des migrations dans la VM ($VM:$DIR)"
ssh "$VM" "mkdir -p $DIR/avant $DIR/apres"
scp -q infra/init/init.sql infra/init/01-apply.sh infra/init/apply-migrations.sh "$VM:$DIR/"
# avant : tout sauf la 004 (base « existante » au niveau 002/003/005) ; apres : la 004 seule.
for f in infra/init/migrations/[0-9][0-9][0-9]-*.sql; do
  case "$(basename "$f")" in
    004-*) scp -q "$f" "$VM:$DIR/apres/" ;;
    *) scp -q "$f" "$VM:$DIR/avant/" ;;
  esac
done
ssh "$VM" "ls $DIR/avant $DIR/apres"

base() { # $1 nom du conteneur ; base EXISTANTE : init.sql + migrations « avant » a la creation
  local admin core pdp
  admin=$(openssl rand -hex 16); core=$(openssl rand -hex 16); pdp=$(openssl rand -hex 16)
  ssh "$VM" "docker run -d --name $1 \
    -e POSTGRES_USER=isc_admin -e POSTGRES_DB=identishield \
    -e POSTGRES_PASSWORD=$admin -e ISC_CORE_APP_PASSWORD=$core -e ISC_PDP_APP_PASSWORD=$pdp \
    -v $DIR/init.sql:/opt/isc/init.sql:ro -v $DIR/01-apply.sh:/docker-entrypoint-initdb.d/01-apply.sh:ro \
    -v $DIR/apply-migrations.sh:/opt/isc/apply-migrations.sh:ro \
    -v $DIR/avant:/opt/isc/migrations:ro -v $DIR/apres:/opt/isc/apres:ro postgres:16" >/dev/null
  for _ in $(seq 1 60); do
    if ssh "$VM" "docker logs $1 2>&1 | grep -q 'init process complete' \
        && docker exec $1 pg_isready -q -h 127.0.0.1 -U isc_admin -d identishield"; then
      return 0
    fi
    sleep 2
  done
  ssh "$VM" "docker logs $1 2>&1 | tail -20"
  echo "ECHEC : la base $1 n'a pas demarre" >&2
  exit 1
}

apply() { # $1 conteneur : la 004 seule, par le script d'isc-migrate
  ssh "$VM" "docker exec -e POSTGRES_USER=isc_admin -e POSTGRES_DB=identishield $1 \
    bash /opt/isc/apply-migrations.sh /opt/isc/apres"
}

sql() { # $1 conteneur ; $2 requete ; une valeur par ligne, sans en-tete
  ssh "$VM" "docker exec $1 psql -v ON_ERROR_STOP=1 -U isc_admin -d identishield -tAc \"$2\""
}

check() { # $1 libelle ; $2 obtenu ; $3 attendu
  if [ "$2" = "$3" ]; then echo "[OK] $1"; else echo "[ECHEC] $1 : obtenu '$2', attendu '$3'"; fail=1; fi
}

STAGE_CHECK="SELECT string_agg(conname || ' ' || pg_get_constraintdef(oid), ' ; ' ORDER BY conname) FROM pg_constraint WHERE conrelid='core.enrolment'::regclass AND contype='c' AND pg_get_constraintdef(oid) ~ 'stage'"

echo ">>> CAS ROUGE : contrainte d'etape renommee"
base isc-migr004-rouge
sql isc-migr004-rouge "ALTER TABLE core.enrolment RENAME CONSTRAINT enrolment_stage_check TO enrolment_stage_ancienne" >/dev/null
if out=$(apply isc-migr004-rouge 2>&1); then
  echo "$out"; echo "[ECHEC] la migration a reussi malgre une contrainte d'etape hors du nom attendu"; fail=1
else
  echo "$out" | grep -E 'ERROR|REFUS' | head -2
  if echo "$out" | grep -q 'enrolment_stage_ancienne'; then
    echo "[OK] la migration echoue et nomme la contrainte (enrolment_stage_ancienne)"
  else
    echo "[ECHEC] la migration echoue sans nommer la contrainte"; fail=1
  fi
fi
check "transaction annulee : pdp.enrolment_attempt absente" \
  "$(sql isc-migr004-rouge "SELECT to_regclass('pdp.enrolment_attempt') IS NULL")" "t"
check "transaction annulee : contrainte d'etape inchangee (ancienne seule, 4 etapes)" \
  "$(sql isc-migr004-rouge "$STAGE_CHECK")" \
  "enrolment_stage_ancienne CHECK ((stage = ANY (ARRAY['OTP'::text, 'BIND'::text, 'CONFIRM'::text, 'DONE'::text])))"

echo ">>> CAS VERT : base existante, la 004 deux fois"
base isc-migr004-vert
apply isc-migr004-vert
apply isc-migr004-vert
check "une seule contrainte d'etape, enrolment_stage_check, a cinq etapes" \
  "$(sql isc-migr004-vert "$STAGE_CHECK")" \
  "enrolment_stage_check CHECK ((stage = ANY (ARRAY['OTP'::text, 'RECOVERY'::text, 'BIND'::text, 'CONFIRM'::text, 'DONE'::text])))"
check "pdp.enrolment_attempt existe, droits du role applicatif" \
  "$(sql isc-migr004-vert "SELECT string_agg(privilege_type, ',' ORDER BY privilege_type) FROM information_schema.role_table_grants WHERE table_schema='pdp' AND table_name='enrolment_attempt' AND grantee='isc_pdp_app'")" \
  "DELETE,INSERT,SELECT,UPDATE"
sql isc-migr004-vert "INSERT INTO core.account (msisdn, balance_xaf, created_at) VALUES ('677009900', 0, '2026-10-24T00:00:00Z')" >/dev/null
check "une etape RECOVERY s'insere" \
  "$(sql isc-migr004-vert "WITH ins AS (INSERT INTO core.enrolment (account_id, stage, expires_at, created_at) SELECT id, 'RECOVERY', '2026-10-24T01:00:00Z', '2026-10-24T00:00:00Z' FROM core.account WHERE msisdn='677009900' RETURNING stage) SELECT stage FROM ins")" \
  "RECOVERY"

echo "Retrait (a faire a la main, une commande par cible) :"
echo "  ssh $VM docker rm -f -v isc-migr004-rouge"
echo "  ssh $VM docker rm -f -v isc-migr004-vert"
[ $fail -eq 0 ] && echo "verify-migration-004: VERT" || echo "verify-migration-004: ROUGE"
exit $fail
