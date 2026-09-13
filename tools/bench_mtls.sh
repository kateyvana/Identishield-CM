#!/bin/bash
# T2-04 — Mesure de latence : /health du PDP en mTLS + connexions PERSISTANTES
# (le mode reel, OB-6) contre un appel HTTP NU sans TLS, pour isoler le surcout
# mTLS. Le PDP n'expose pas /evaluate au mois 2 (T2-05) ; le surcout mesure est
# transport (poignee de main + keepalive), independant de l'endpoint. A comparer
# au 1,7 ms p95 (0,34 % du budget ENF-01 de 500 ms) mesure en SPIKE-4.
#
# La base sans-TLS est un nginx nu ephemere (200 sur :80), jamais dans la pile
# permanente. Necessite la pile debout (pdp) et l'image isc-core.
set -uo pipefail
export MSYS_NO_PATHCONV=1
NET=isc-net
CERTS=isc-certs
COUNT=${1:-1000}

cleanup() { docker rm -f isc-plain >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker rm -f isc-plain >/dev/null 2>&1 || true  # au cas ou un run precedent aurait laisse le conteneur
docker run -d --name isc-plain --network "$NET" --network-alias plain nginx:1.27-alpine >/dev/null
sleep 2
docker run --rm --network "$NET" \
  -e CORE_MODE=bench -e CORE_BENCH_COUNT="$COUNT" \
  -e CORE_BENCH_MTLS=https://pdp:8443/health \
  -e CORE_BENCH_PLAIN=http://plain:80/ \
  -v "$CERTS":/certs:ro -v "$(pwd)/config:/config:ro" isc-core java -Dspring.main.web-application-type=none -jar /app/app.jar 2>&1 | grep -E '^BENCH_'
