#!/usr/bin/env bash
# OUTIL (pas une porte) — mesure T-AC-16 du gel des ré-enrôlements (ordre du superviseur du
# 2026-10-25, point 4) : DeviceEnrolment.register appelé directement sur une base PostgreSQL JETABLE
# dans la VM (même image, même init.sql, mêmes migrations que la pile, pile non touchée), avec
# RecoveryCodes (PBKDF2 de production), DeviceRegistry, AuditLog et TrustStateMachine réels et le
# throttle neutralisé ; N appels « compte lié, code faux, non gelé » et N « …, gelé », médiane et p95
# de chaque cas, sortie brute. Aucun seuil n'est appliqué : l'écart est déclaré, la décision
# appartient au superviseur (fenêtre statistique du T-AC-16).
#
# Même mécanique que tools/verify_device_enrolment.sh. Rien n'est supprimé ici (CLAUDE.md, R-S) :
# la commande de retrait du conteneur est affichée à la fin.
# Usage (racine du dépôt, poste) : bash tools/measure_tac16_freeze.sh [isc@<vm>] [N]
set -euo pipefail

VM="${1:-isc@100.93.166.19}"
N="${2:-200}"
NAME=isc-tac16-pg
PORT=55434

admin_pw=$(openssl rand -hex 16)
core_pw=$(openssl rand -hex 16)
pdp_pw=$(openssl rand -hex 16)

echo ">>> base jetable $NAME dans la VM ($VM)"
DIR=$(ssh "$VM" "mktemp -d /tmp/isc-tac16.XXXXXX")
ssh "$VM" "mkdir -p $DIR/migrations"
scp -q infra/init/init.sql infra/init/01-apply.sh infra/init/apply-migrations.sh "$VM:$DIR/"
scp -q infra/init/migrations/[0-9][0-9][0-9]-*.sql "$VM:$DIR/migrations/"
ssh "$VM" "ls $DIR/migrations"
ssh "$VM" "docker run -d --name $NAME \
  -e POSTGRES_USER=isc_admin -e POSTGRES_DB=identishield \
  -e POSTGRES_PASSWORD=$admin_pw -e ISC_CORE_APP_PASSWORD=$core_pw -e ISC_PDP_APP_PASSWORD=$pdp_pw \
  -v $DIR/init.sql:/opt/isc/init.sql:ro -v $DIR/01-apply.sh:/docker-entrypoint-initdb.d/01-apply.sh:ro \
  -v $DIR/apply-migrations.sh:/opt/isc/apply-migrations.sh:ro \
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
echo ">>> pdp : FreezeTimingTac16Tool (role isc_pdp_app, N=$N)"
(cd pdp && ISC_TAC16=1 ISC_TAC16_N="$N" ISC_TEST_PG_URL="$URL" ISC_TEST_PG_USER=isc_pdp_app ISC_TEST_PG_PASSWORD="$pdp_pw" \
  ./gradlew --quiet cleanTest test --tests 'cm.identishield.pdp.device.FreezeTimingTac16Tool' 2>&1 \
  | grep -E "FAILED|tests completed" || true)

grep -h -o '<testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  pdp/build/test-results/test/TEST-cm.identishield.pdp.device.FreezeTimingTac16Tool.xml
echo ">>> mesures brutes (system-out du test)"
grep -oE 'TAC16_[A-Z_]+[^<]*' pdp/build/test-results/test/TEST-cm.identishield.pdp.device.FreezeTimingTac16Tool.xml
echo "measure_tac16_freeze : TERMINÉ (mesure, pas verdict)"
echo "Retrait de la base jetable (a faire a la main) : ssh $VM docker rm -f $NAME"
