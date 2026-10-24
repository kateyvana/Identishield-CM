#!/usr/bin/env bash
# B3a — preuve EN BASE REELLE : cycle de vie de la cle d'appareil (pdp.device PENDING/ACTIVE),
# code de recuperation (pdp.recovery_code), sous le role applicatif isc_pdp_app ; enrolement, PIN
# et session du coeur (core.enrolment, core.session, core.account), sous le role isc_core_app.
#
# Meme methode que tools/verify_trust_state.sh (couloir A) : PostgreSQL JETABLE dans la VM, meme
# image (postgres:16), meme infra/init/init.sql, memes migrations (le schema B3a est la migration
# 002) et memes roles que la pile, SANS toucher a la pile en service. Port lie a 127.0.0.1 dans la
# VM, amene sur le poste par un tunnel SSH.
# Mots de passe : aleatoires, ephemeres, jamais ecrits sur disque ni dans le depot.
# Le conteneur n'est PAS supprime ici (CLAUDE.md §4, commandes destructrices) : la commande de
# retrait est affichee a la fin.
#
# Usage (racine du depot, poste) : bash tools/verify_device_enrolment.sh [isc@<vm>]
set -euo pipefail

VM="${1:-isc@100.93.166.19}"
NAME=isc-b3a-pg
PORT=55433

admin_pw=$(openssl rand -hex 16)
core_pw=$(openssl rand -hex 16)
pdp_pw=$(openssl rand -hex 16)

echo ">>> base jetable $NAME dans la VM ($VM)"
# Dossier NEUF a chaque passage : aucune migration d'un passage precedent ne peut s'y glisser.
DIR=$(ssh "$VM" "mktemp -d /tmp/isc-b3a.XXXXXX")
ssh "$VM" "mkdir -p $DIR/migrations"
scp -q infra/init/init.sql infra/init/01-apply.sh "$VM:$DIR/"
scp -q infra/init/migrations/[0-9][0-9][0-9]-*.sql "$VM:$DIR/migrations/"
ssh "$VM" "ls $DIR/migrations"
ssh "$VM" "docker run -d --name $NAME \
  -e POSTGRES_USER=isc_admin -e POSTGRES_DB=identishield \
  -e POSTGRES_PASSWORD=$admin_pw -e ISC_CORE_APP_PASSWORD=$core_pw -e ISC_PDP_APP_PASSWORD=$pdp_pw \
  -v $DIR/init.sql:/opt/isc/init.sql:ro -v $DIR/01-apply.sh:/docker-entrypoint-initdb.d/01-apply.sh:ro \
  -v $DIR/migrations:/opt/isc/migrations:ro \
  -p 127.0.0.1:$PORT:5432 postgres:16" >/dev/null

echo ">>> attente de la fin d'init.sql"
for i in $(seq 1 60); do
  if ssh "$VM" "docker logs $NAME 2>&1 | grep -q 'init process complete' \
      && docker exec $NAME pg_isready -q -h 127.0.0.1 -U isc_admin -d identishield"; then
    break
  fi
  if [ "$(ssh "$VM" "docker inspect -f '{{.State.Running}}' $NAME")" != "true" ]; then
    ssh "$VM" "docker logs $NAME 2>&1 | tail -20"
    echo "ECHEC : init.sql n'a pas abouti (conteneur arrete)" >&2
    exit 1
  fi
  sleep 2
done

echo ">>> tunnel 127.0.0.1:$PORT -> VM"
ssh -N -o ExitOnForwardFailure=yes -L "$PORT:127.0.0.1:$PORT" "$VM" &
tunnel=$!
trap 'kill $tunnel' EXIT
sleep 3

URL="jdbc:postgresql://127.0.0.1:$PORT/identishield"
echo ">>> pdp : DeviceEnrolmentDbTest (role isc_pdp_app)"
(cd pdp && ISC_TEST_PG_URL="$URL" ISC_TEST_PG_USER=isc_pdp_app ISC_TEST_PG_PASSWORD="$pdp_pw" \
  ./gradlew --quiet cleanTest test --tests 'cm.identishield.pdp.device.DeviceEnrolmentDbTest')

echo ">>> coeur : MobileEnrolmentDbTest (role isc_core_app)"
(cd core && ISC_TEST_PG_URL="$URL" ISC_TEST_PG_USER=isc_core_app ISC_TEST_PG_PASSWORD="$core_pw" \
  ./gradlew --quiet cleanTest test --tests 'cm.identishield.core.mobile.MobileEnrolmentDbTest')

# Preuve que les tests ont TOURNE (et non ete ignores faute de base).
grep -h -o '<testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  pdp/build/test-results/test/TEST-cm.identishield.pdp.device.DeviceEnrolmentDbTest.xml \
  core/build/test-results/test/TEST-cm.identishield.core.mobile.MobileEnrolmentDbTest.xml
echo "verify_device_enrolment : VERT"
echo "Retrait de la base jetable (a faire a la main) : ssh $VM docker rm -f $NAME"
