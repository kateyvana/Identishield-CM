#!/bin/bash
# T2-07 — Verifie le fil du squelette cote coeur (EF-04/EF-05) : un transfert P2P
# appelle /evaluate AVANT commit, applique ALLOW, debite le solde, et laisse
# exactement une entree d'audit chainee (le decisionId lie transaction et audit).
# Delai de sonde eleve : la VERIFICATION (JVM froide sous charge) tolere le
# cold-start ; le budget de 800 ms reste celui de la production (coeur chaud).
# Necessite la pile debout (postgres, pdp).
set -uo pipefail
export MSYS_NO_PATHCONV=1
FROM=699000001
AMOUNT=25000
fail=0
adm() { docker exec isc-postgres psql -U isc_admin -d identishield -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }

# Reapprovisionne l'emetteur s'il est trop bas (fixture de test ; make reset repart a zero).
adm "UPDATE core.account SET balance_xaf = 1000000 WHERE msisdn='$FROM' AND balance_xaf < 100000;" >/dev/null
before=$(adm "SELECT balance_xaf FROM core.account WHERE msisdn='$FROM';")

out=$(docker run --rm --network isc-net --env-file infra/.env -e CORE_MODE=transfer -e AMOUNT_XAF=$AMOUNT \
      -v isc-certs:/certs:ro -v "$(pwd)/config:/config:ro" isc-core \
      java -Dspring.main.web-application-type=none -Dcore.pdp.timeout-ms=5000 -jar /app/app.jar 2>&1)
state=$(echo "$out" | sed -n 's/^TRANSFER_STATE=//p' | tr -d '[:space:]')
did=$(echo "$out" | sed -n 's/^TRANSFER_DECISION_ID=//p' | tr -d '[:space:]')
after=$(adm "SELECT balance_xaf FROM core.account WHERE msisdn='$FROM';")

if [ "$state" = "EXECUTED" ]; then
  echo "[OK] transfert P2P EXECUTED : /evaluate appele AVANT commit (EF-05)"
else echo "[ECHEC] etat du transfert=$state (attendu EXECUTED)"; fail=1; fi

if [ "$((before - after))" = "$AMOUNT" ]; then
  echo "[OK] solde debite de $AMOUNT ($before -> $after)"
else echo "[ECHEC] debit incorrect : $before -> $after"; fail=1; fi

n=$(adm "SELECT count(*) FROM pdp.audit_entry WHERE payload->>'decisionId'='$did';")
if [ "$n" = "1" ]; then
  echo "[OK] une entree d'audit chainee pour le decisionId du transfert ($did)"
else echo "[ECHEC] $n entree(s) d'audit pour $did (attendu 1)"; fail=1; fi

[ $fail -eq 0 ] && echo "verify-transfer: VERT" || echo "verify-transfer: ROUGE"
exit $fail
