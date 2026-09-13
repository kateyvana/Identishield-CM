#!/bin/bash
# T2-08 — Verifie le canal USSD (EF-06, porte ISC-DEV #2 : "une transaction USSD
# traverse le fil et est executee"). Rejoue une session scriptee (montant ->
# beneficiaire -> confirmation) qui mene au transfert P2P, et verifie que la
# transaction est bien persistee channel=USSD, etat EXECUTED. Delai de sonde eleve
# (JVM froide sous charge). Necessite la pile debout (postgres, pdp).
set -uo pipefail
export MSYS_NO_PATHCONV=1
FROM=699000001
fail=0
adm() { docker exec isc-postgres psql -U isc_admin -d identishield -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }

adm "UPDATE core.account SET balance_xaf = 1000000 WHERE msisdn='$FROM' AND balance_xaf < 100000;" >/dev/null

out=$(docker run --rm --network isc-net --env-file infra/.env -e CORE_MODE=ussd \
      -v isc-certs:/certs:ro -v "$(pwd)/config:/config:ro" isc-core \
      java -Dspring.main.web-application-type=none -Dcore.pdp.timeout-ms=5000 -jar /app/app.jar 2>&1)
final=$(echo "$out" | sed -n 's/^USSD_FINAL=//p')
did=$(echo "$final" | grep -oiE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -1)

if echo "$final" | grep -q "EXECUTED"; then
  echo "[OK] une transaction USSD traverse le fil et est EXECUTED (EF-06, porte #2)"
else echo "[ECHEC] session USSD non executee : $final"; fail=1; fi

n=$(adm "SELECT count(*) FROM core.transaction WHERE decision_id='$did' AND channel='USSD' AND state='EXECUTED';")
if [ "$n" = "1" ]; then
  echo "[OK] transaction USSD EXECUTED persistee (canal USSD, decisionId $did)"
else echo "[ECHEC] transaction USSD persistee : $n (attendu 1)"; fail=1; fi

[ $fail -eq 0 ] && echo "verify-ussd: VERT" || echo "verify-ussd: ROUGE"
exit $fail
