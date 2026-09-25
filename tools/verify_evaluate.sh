#!/bin/bash
# T2-05 — Verifie /evaluate sur le PDP reel (mTLS) : idempotence par decisionId
# (ENF-03, porte ISC-DEV #3) et mesure de latence (porte #12). Deux appels du
# MEME decisionId doivent rendre la MEME decision et laisser UNE SEULE entree
# d'audit. Ecrit une entree de test dans le journal (append-only ; make reset
# repart a zero). Necessite la pile debout (pdp) et les images isc-tools.
set -uo pipefail
export MSYS_NO_PATHCONV=1
NET=isc-net
CERTS=isc-certs
fail=0

DID=$(docker run --rm isc-tools cat /proc/sys/kernel/random/uuid)
REQ='{"decisionId":"'"$DID"'","accountId":"acc-000123","msisdn":"677000123","currentImsi":"624019900001230","channel":"APP","type":"CASH_OUT","amountXaf":25000,"beneficiaryId":"ben-000045","serverTimestamp":"2026-08-26T09:15:00Z","proofType":"NONE"}'

resp() {
  docker run --rm -i --network "$NET" -v "$CERTS":/certs:ro isc-tools \
    curl -sS --cert /certs/core.crt --key /certs/core.key --cacert /certs/ca.crt \
    -H "Content-Type: application/json" -d "$REQ" https://pdp:8443/evaluate
}

r1=$(resp)
r2=$(resp)
echo "--- /evaluate #1 : $r1"
echo "--- /evaluate #2 : $r2"

# Champs de decision (hors latencyMs, qui varie) : doivent etre identiques.
dec1=$(echo "$r1" | sed 's/,"latencyMs":[0-9]*//')
dec2=$(echo "$r2" | sed 's/,"latencyMs":[0-9]*//')
if [ "$dec1" = "$dec2" ] && echo "$r1" | grep -q '"decision":"ALLOW"'; then
  echo "[OK] rejeu idempotent : meme decision (ALLOW), meme evaluatedAt"
else
  echo "[ECHEC] rejeu non idempotent"; fail=1
fi

# Exactement une entree d'audit pour ce decisionId (ENF-03).
n=$(docker exec isc-postgres psql -U isc_admin -d identishield -tAc \
     "SELECT count(*) FROM pdp.audit_entry WHERE payload->>'decisionId'='$DID';" 2>/dev/null | tr -d '[:space:]')
if [ "$n" = "1" ]; then
  echo "[OK] exactement une entree d'audit pour le decisionId (ENF-03)"
else
  echo "[ECHEC] $n entree(s) d'audit pour le decisionId (attendu 1)"; fail=1
fi

# Porte #12 : la latence de /evaluate est mesuree et rapportee.
lat=$(echo "$r2" | grep -oE '"latencyMs":[0-9]+' | grep -oE '[0-9]+')
echo "[OK] latence /evaluate (chaud) rapportee : ${lat} ms (reference ENF-01)"

[ $fail -eq 0 ] && echo "verify-evaluate: VERT" || echo "verify-evaluate: ROUGE"
exit $fail
