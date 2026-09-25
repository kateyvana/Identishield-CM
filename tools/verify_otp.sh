#!/bin/bash
# T2-12 — Verifie l'OTP (EF-03) dans la VM. Necessite la pile debout. Prouve : un OTP est emis vers
# l'IMSI COURANT ; un code correct est accepte puis CONSOMME (usage unique) ; un rejeu est refuse ;
# un mauvais code est refuse ; un OTP EXPIRE est refuse ; et apres une reattribution de SIM (par
# l'outil agent T2-11) le NOUVEL OTP part vers le NOUVEL IMSI -- la faiblesse reelle reproduite.
set -uo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.." || exit 2
set -a
# shellcheck disable=SC1091
. infra/.env
set +a

MSISDN=677000001
IMSI0=624010000000001
IMSI1=624010000000002
fail=0

psqla() {
  docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" isc-postgres \
    psql -qtAX -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1" 2>/dev/null | tr -d '[:space:]'
}
core_otp() {
  docker run --rm --network isc-net --env-file infra/.env -e CORE_MODE=otp "$@" \
    -v isc-certs:/certs:ro -v "$(pwd)/config:/config:ro" isc-core \
    java -Dspring.main.web-application-type=none -jar /app/app.jar 2>/dev/null
}
field() { echo "$1" | sed -n "s/^$2=//p"; }

# Hygiene + amorcage de l'IMSI initial du numero de test.
psqla "DELETE FROM core.otp WHERE msisdn='$MSISDN'; DELETE FROM core.hlr_reassignment WHERE msisdn='$MSISDN'; DELETE FROM core.hlr_subscriber WHERE msisdn='$MSISDN';" >/dev/null

# 1) scenario : OTP emis vers l'IMSI courant ; correct accepte (consomme) ; rejeu refuse ; mauvais refuse
out=$(core_otp -e OTP_MSISDN=$MSISDN -e OTP_INITIAL_IMSI=$IMSI0)
tgt=$(field "$out" OTP_TARGET_IMSI)
if [ "$tgt" = "$IMSI0" ]; then echo "[OK] OTP emis vers l'IMSI courant ($IMSI0)"; else echo "[ECHEC] cible OTP: $tgt"; fail=1; fi
if [ "$(field "$out" OTP_VERIFY_CORRECT)" = "ACCEPTED" ]; then echo "[OK] code correct accepte (consomme)"; else echo "[ECHEC] correct: $out"; fail=1; fi
if [ "$(field "$out" OTP_VERIFY_REUSE)" = "REFUSED_CONSUMED" ]; then echo "[OK] rejeu du meme code refuse (usage unique)"; else echo "[ECHEC] rejeu: $out"; fail=1; fi
if [ "$(field "$out" OTP_VERIFY_WRONG)" = "REFUSED_UNKNOWN" ]; then echo "[OK] mauvais code refuse"; else echo "[ECHEC] mauvais: $out"; fail=1; fi

# 2) expiration : emettre, expirer la ligne en base, verifier -> refuse expire
code=$(core_otp -e OTP_MSISDN=$MSISDN -e OTP_INITIAL_IMSI=$IMSI0 -e OTP_ACTION=issue | sed -n 's/^OTP_CODE=//p')
psqla "UPDATE core.otp SET expires_at = now() - interval '1 hour' WHERE msisdn='$MSISDN' AND consumed_at IS NULL;" >/dev/null
exp=$(core_otp -e OTP_MSISDN=$MSISDN -e OTP_ACTION=verify -e OTP_CODE="$code" | sed -n 's/^OTP_OUTCOME=//p')
if [ "$exp" = "REFUSED_EXPIRED" ]; then echo "[OK] OTP expire refuse"; else echo "[ECHEC] expire: $exp"; fail=1; fi

# 3) l'OTP suit l'IMSI courant : apres une reattribution PAR L'OUTIL (T2-11), il cible le nouvel IMSI
docker run --rm --network isc-net -e CORE_URL=http://core:8080 -e PYTHONPATH=/src \
  -v "$(pwd)/hlr-agent/src:/src:ro" python:3.12-alpine \
  python -m isc_hlr_agent reassign --msisdn $MSISDN --new-imsi $IMSI1 \
  --agent-id agent-otp --motif reassign-otp >/dev/null 2>&1
tgt2=$(core_otp -e OTP_MSISDN=$MSISDN -e OTP_ACTION=issue | sed -n 's/^OTP_TARGET_IMSI=//p')
if [ "$tgt2" = "$IMSI1" ]; then
  echo "[OK] apres reattribution, l'OTP part vers le NOUVEL IMSI ($IMSI1) -- la faiblesse reproduite (AC-02)"
else echo "[ECHEC] cible apres reattribution: $tgt2"; fail=1; fi

[ $fail -eq 0 ] && echo "verify-otp: VERT" || echo "verify-otp: ROUGE"
exit $fail
