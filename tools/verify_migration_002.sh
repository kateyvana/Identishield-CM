#!/usr/bin/env bash
# Preuve de la migration 002 (B3a), sur des bases PostgreSQL JETABLES dans la VM : meme image
# (postgres:16), meme init.sql, meme 01-apply.sh et meme migrate.sh que la pile, sans toucher a la
# pile en service. Complete tools/verify_migrations.sh (base neuve = base migree, rejouable), qui
# porte sur TOUTES les migrations ; ici, ce qui est propre a la 002 :
#
#   CAS ROUGE : base existante dont la contrainte d'etat de pdp.device porte un AUTRE nom que
#     device_state_check. DROP CONSTRAINT IF EXISTS n'y fait rien ; la migration DOIT echouer en
#     nommant la contrainte, et la transaction DOIT tout annuler (aucune table, aucune colonne B3a).
#   CAS VERT : base existante portant des cles ACTIVE et REVOKED ; la migration passe deux fois, les
#     lignes restent intactes, une cle PENDING s'insere, une seconde PENDING du meme compte est
#     refusee (device_one_pending_per_account).
#
# Mots de passe : aleatoires, ephemeres, jamais ecrits sur disque ni dans le depot. Rien n'est
# supprime ici (CLAUDE.md, R-S) : les commandes de retrait sont affichees a la fin.
#
# Usage (racine du depot, poste) : bash tools/verify_migration_002.sh [isc@<vm>]
set -euo pipefail
VM="${1:-isc@100.93.166.19}"
fail=0
DIR=$(ssh "$VM" "mktemp -d /tmp/isc-migr002.XXXXXX")

echo ">>> envoi d'init.sql, 01-apply.sh, migrate.sh et des migrations dans la VM ($VM:$DIR)"
ssh "$VM" "mkdir -p $DIR/avec $DIR/vide"
scp -q infra/init/init.sql infra/init/01-apply.sh infra/init/migrate.sh "$VM:$DIR/"
scp -q infra/init/migrations/[0-9][0-9][0-9]-*.sql "$VM:$DIR/avec/"
ssh "$VM" "ls $DIR/avec"

base() { # $1 nom du conteneur ; base EXISTANTE : init.sql seul a la creation
  local admin core pdp
  admin=$(openssl rand -hex 16); core=$(openssl rand -hex 16); pdp=$(openssl rand -hex 16)
  ssh "$VM" "docker run -d --name $1 \
    -e POSTGRES_USER=isc_admin -e POSTGRES_DB=identishield \
    -e POSTGRES_PASSWORD=$admin -e ISC_CORE_APP_PASSWORD=$core -e ISC_PDP_APP_PASSWORD=$pdp \
    -v $DIR/init.sql:/opt/isc/init.sql:ro -v $DIR/01-apply.sh:/docker-entrypoint-initdb.d/01-apply.sh:ro \
    -v $DIR/vide:/opt/isc/migrations:ro postgres:16" >/dev/null
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

sql() { # $1 conteneur ; $2 requete ; une valeur par ligne, sans en-tete
  ssh "$VM" "docker exec $1 psql -v ON_ERROR_STOP=1 -U isc_admin -d identishield -tAc \"$2\""
}

check() { # $1 libelle ; $2 obtenu ; $3 attendu
  if [ "$2" = "$3" ]; then echo "[OK] $1"; else echo "[ECHEC] $1 : obtenu '$2', attendu '$3'"; fail=1; fi
}

echo ">>> CAS ROUGE : contrainte d'etat renommee"
base isc-migr002-rouge
sql isc-migr002-rouge "ALTER TABLE pdp.device RENAME CONSTRAINT device_state_check TO device_state_ancienne" >/dev/null
if out=$(ssh "$VM" "cd $DIR && bash migrate.sh isc-migr002-rouge $DIR/avec" 2>&1); then
  echo "$out"
  echo "[ECHEC] la migration a reussi malgre une contrainte d'etat hors du nom attendu"; fail=1
else
  echo "$out" | tail -3
  if echo "$out" | grep -q 'device_state_ancienne'; then
    echo "[OK] la migration echoue et nomme la contrainte (device_state_ancienne)"
  else
    echo "[ECHEC] la migration echoue sans nommer la contrainte"; fail=1
  fi
fi
check "transaction annulee : pdp.recovery_code absente" \
  "$(sql isc-migr002-rouge "SELECT to_regclass('pdp.recovery_code') IS NULL")" "t"
check "transaction annulee : core.account.pin_hash absente" \
  "$(sql isc-migr002-rouge "SELECT count(*) FROM information_schema.columns WHERE table_schema='core' AND table_name='account' AND column_name='pin_hash'")" "0"
check "transaction annulee : core.enrolment et core.session absentes" \
  "$(sql isc-migr002-rouge "SELECT to_regclass('core.enrolment') IS NULL AND to_regclass('core.session') IS NULL")" "t"
check "transaction annulee : contraintes d'etat inchangees (ancienne seule)" \
  "$(sql isc-migr002-rouge "SELECT string_agg(conname, ',' ORDER BY conname) FROM pg_constraint WHERE conrelid='pdp.device'::regclass AND contype='c'")" \
  "device_state_ancienne"

echo ">>> CAS VERT : base existante portant des cles ACTIVE et REVOKED, migration deux fois"
base isc-migr002-vert
sql isc-migr002-vert "INSERT INTO pdp.device (id, account_id, public_key, state, enrolled_at, revoked_at) VALUES
  ('dev-a', 'acc-1', 'cle-a', 'ACTIVE', '2026-10-01T00:00:00Z', NULL),
  ('dev-r', 'acc-1', 'cle-r', 'REVOKED', '2026-09-01T00:00:00Z', '2026-10-01T00:00:00Z')" >/dev/null
avant=$(sql isc-migr002-vert "SELECT id || '|' || state FROM pdp.device ORDER BY id")
ssh "$VM" "cd $DIR && bash migrate.sh isc-migr002-vert $DIR/avec"
ssh "$VM" "cd $DIR && bash migrate.sh isc-migr002-vert $DIR/avec"
check "lignes existantes intactes apres deux applications" \
  "$(sql isc-migr002-vert "SELECT id || '|' || state FROM pdp.device ORDER BY id")" "$avant"
check "une seule contrainte d'etat, device_state_check, a trois etats" \
  "$(sql isc-migr002-vert "SELECT conname || ' ' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='pdp.device'::regclass AND contype='c'")" \
  "device_state_check CHECK ((state = ANY (ARRAY['PENDING'::text, 'ACTIVE'::text, 'REVOKED'::text])))"
sql isc-migr002-vert "INSERT INTO pdp.device (id, account_id, public_key, state, enrolled_at) VALUES ('dev-p1', 'acc-1', 'cle-p1', 'PENDING', '2026-10-24T00:00:00Z')" >/dev/null
check "une cle PENDING s'insere" \
  "$(sql isc-migr002-vert "SELECT state FROM pdp.device WHERE id='dev-p1'")" "PENDING"
if out=$(sql isc-migr002-vert "INSERT INTO pdp.device (id, account_id, public_key, state, enrolled_at) VALUES ('dev-p2', 'acc-1', 'cle-p2', 'PENDING', '2026-10-24T00:00:00Z')" 2>&1); then
  echo "[ECHEC] une seconde cle PENDING du meme compte a ete acceptee"; fail=1
elif echo "$out" | grep -q 'device_one_pending_per_account'; then
  echo "[OK] une seconde cle PENDING du meme compte est refusee (device_one_pending_per_account)"
else
  echo "$out"; echo "[ECHEC] seconde cle PENDING refusee pour une autre cause"; fail=1
fi

echo "Retrait (a faire a la main, une commande par cible) :"
echo "  ssh $VM docker rm -f -v isc-migr002-rouge"
echo "  ssh $VM docker rm -f -v isc-migr002-vert"
[ $fail -eq 0 ] && echo "verify-migration-002: VERT" || echo "verify-migration-002: ROUGE"
exit $fail
