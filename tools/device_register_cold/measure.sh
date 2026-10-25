#!/usr/bin/env bash
# D-B7-1 — cold-start measurement of POST /device/register on the ISOLATED stack (compose project
# isc-b3a), never the served stack. Lane B, 2026-10-25 (qualification note
# docs/03-revues/2026-10-25-qualification-D-B7-1.md §3). Handed to lane A as the proof tool for the
# PDP warm-up (supervisor decision, 2026-10-25).
#
# Series = restart the isolated PDP (cold JVM), wait for healthy, then 3 POST /device/register with
# fresh self-consistent bodies produced by GenRegister.java (first enrolment: attestation chain +
# challenge signature + PBKDF2 of the recovery code + insertion = the full path). curl's own timers;
# the TLS handshake is reported apart (time_appconnect). Response bodies are NEVER kept (a 200
# carries a recovery code); only the status is printed.
#
# Usage, from the VM (run under a VM-OCCUPEE marker):
#   1. on the workstation: "$JAVA_HOME/bin/java" tools/device_register_cold/GenRegister.java bodies 9
#      scp bodies/body-*.json tools/device_register_cold/measure.sh isc@<vm>:/tmp/d-b7-1/
#   2. isolated stack up (infra/, images already present) — two disposable overrides are needed
#      until compose.b3a-isolement.yml is fixed: the mobile BRIDGE name (br-isc-mobile is fixed by
#      the shared file since L7-b) and the PDP image (isc-pdp-b3a predates /device/*):
#        printf 'networks:\n  isc-mobile:\n    name: isc-b3a-mobile\n    driver_opts:\n      com.docker.network.bridge.name: br-isc-b3a-mob\n' > /tmp/isc-b3a-reseau-mobile.yml
#        printf 'services:\n  pdp:\n    image: isc-pdp\n' > /tmp/isc-b3a-pdp-image.yml
#        docker compose -p isc-b3a -f docker-compose.yml -f compose.b3a.yml -f compose.b3a-isolement.yml \
#          -f /tmp/isc-b3a-reseau-mobile.yml -f /tmp/isc-b3a-pdp-image.yml up -d --no-build
#   3. bash /tmp/d-b7-1/measure.sh ; then the same compose command with "down -v" (listed first).
# Environment: B (bodies dir), PDP (container), NET, CERTS, TOOLS (image with curl+openssl).
set -u
B="${B:-/tmp/d-b7-1}"
PDP="${PDP:-isc-b3a-pdp}"
NET="${NET:-isc-b3a-net}"
CERTS="${CERTS:-isc-b3a-certs}"
TOOLS="${TOOLS:-isc-tools-b3a}"

call() {
  docker run --rm --network "$NET" -v "$CERTS":/certs:ro -v "$B":/b:ro "$TOOLS" sh -c '
    code=$(curl -s -o /tmp/resp -m 30 \
      -w "http=%{http_code} total=%{time_total}s tls_done=%{time_appconnect}s first_byte=%{time_starttransfer}s" \
      --cacert /certs/ca.crt --cert /certs/core.crt --key /certs/core.key \
      -H "Content-Type: application/json" --data @/b/body-'"$1"'.json https://pdp:8443/device/register)
    echo "body-'"$1"' $code"
    case "$code" in http=200*) echo "   (corps non affiche : contient le code de recuperation)";;
      *) echo "   corps : $(head -c 300 /tmp/resp)";; esac'
}

series() {
  echo "=== serie $1 : redemarrage a froid de $PDP $(date -u +%H:%M:%S.%3NZ)"
  docker restart "$PDP" >/dev/null
  t0=$(date +%s.%N)
  for _ in $(seq 1 120); do
    st=$(docker inspect -f "{{.State.Health.Status}}" "$PDP")
    [ "$st" = "healthy" ] && break
    sleep 1
  done
  t1=$(date +%s.%N)
  printf "pdp healthy=%s apres %.1f s, 1er appel a %s\n" "$st" "$(echo "$t1 - $t0" | bc)" "$(date -u +%H:%M:%S.%3NZ)"
  for i in $2 $3 $4; do call "$i"; done
}

# SERIES restarts (3 by default: the proof asked for the PDP warm-up is "three restarts, first
# complete /device/register < 1000 ms"), 3 bodies each: generate at least 3*SERIES bodies.
SERIES="${SERIES:-3}"
for s in $(seq 1 "$SERIES"); do
  series "$s" $((3 * s - 2)) $((3 * s - 1)) $((3 * s))
done

PG="${PG:-isc-b3a-postgres}"
echo "=== journaux PDP isole (10 dernieres lignes, aucun secret attendu)"
docker logs --since 10m "$PDP" 2>&1 | grep -v -i "password\|secret\|recovery" | tail -10
echo "=== preuve d'insertion : pdp.device sur la base ISOLEE"
docker exec "$PG" psql -U isc_admin -d identishield -tAc \
  "select state, left(id::text,8), enrolled_at from pdp.device order by enrolled_at"
echo "=== recovery codes crees (compte seulement)"
docker exec "$PG" psql -U isc_admin -d identishield -tAc "select count(*) from pdp.recovery_code"
