#!/bin/bash
# T2-14 — Verifie les notifications simulees (EF-09, ENF-17) dans la VM. Necessite la pile debout.
# Prouve : un SMS part vers l'IMSI COURANT ; apres reattribution par l'outil agent (T2-11) la notif
# suivante part vers le NOUVEL IMSI (meme faiblesse que l'OTP) ; un push est seulement stocke ; les
# notifications sont persistees et consultables par numero ; une notif est emise lors d'un transfert
# execute. Assertions NEGATIVES obligatoires, chacune prouvee par un refus effectif AVEC son message :
# un lien, un numero de rappel, une demande de code sont REFUSES a l'emission.
set -uo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.." || exit 2

MSISDN=677000004
FROM=677000001
IMSI0=624010000000001
IMSI1=624010000000002
fail=0

adm() { docker exec isc-postgres psql -U isc_admin -d identishield -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }
core_notif() {
  docker run --rm --network isc-net --env-file infra/.env -e CORE_MODE=notification "$@" \
    -v isc-certs:/certs:ro -v "$(pwd)/config:/config:ro" --entrypoint java isc-core \
    -Dspring.main.web-application-type=none -jar /app/app.jar 2>/dev/null
}
field() { echo "$1" | sed -n "s/^$2=//p"; }

# Hygiene : repart d'un etat connu pour le numero de test.
adm "DELETE FROM core.notification WHERE msisdn='$MSISDN'; DELETE FROM core.hlr_reassignment WHERE msisdn='$MSISDN'; DELETE FROM core.hlr_subscriber WHERE msisdn='$MSISDN';" >/dev/null

# 1) SMS emis vers l'IMSI courant.
out=$(core_notif -e NOTIF_MSISDN=$MSISDN -e NOTIF_INITIAL_IMSI=$IMSI0 -e NOTIF_CHANNEL=SMS \
      -e NOTIF_EVENT=SECURITY_ALERT -e NOTIF_TEXT="Rendez-vous en agence pour toute verification.")
tgt=$(field "$out" NOTIF_TARGET)
if [ "$tgt" = "$IMSI0" ]; then echo "[OK] SMS emis vers l'IMSI courant ($IMSI0)"; else echo "[ECHEC] cible SMS: $out"; fail=1; fi

# 2) apres reattribution PAR L'OUTIL (T2-11), la notif suivante cible le nouvel IMSI.
docker run --rm --network isc-net -e CORE_URL=http://core:8080 -e PYTHONPATH=/src \
  -v "$(pwd)/hlr-agent/src:/src:ro" python:3.12-alpine \
  python -m isc_hlr_agent reassign --msisdn $MSISDN --new-imsi $IMSI1 \
  --agent-id agent-notif --motif reassign-notif >/dev/null 2>&1
tgt2=$(core_notif -e NOTIF_MSISDN=$MSISDN -e NOTIF_CHANNEL=SMS -e NOTIF_TEXT="Rendez-vous en agence." | sed -n 's/^NOTIF_TARGET=//p')
if [ "$tgt2" = "$IMSI1" ]; then
  echo "[OK] apres reattribution, la notif SMS part vers le NOUVEL IMSI ($IMSI1) -- meme faiblesse que l'OTP"
else echo "[ECHEC] cible SMS apres reattribution: $tgt2"; fail=1; fi

# 3) un push est SEULEMENT stocke (aucun dispositif enrole au mois 2).
push=$(core_notif -e NOTIF_MSISDN=$MSISDN -e NOTIF_CHANNEL=PUSH -e NOTIF_TEXT="Consultez votre historique en agence.")
if [ -n "$(field "$push" NOTIF_ID)" ]; then echo "[OK] push stocke seulement (cible=$(field "$push" NOTIF_TARGET))"; else echo "[ECHEC] push: $push"; fail=1; fi

# 4) persistance + consultation par numero.
lst=$(core_notif -e NOTIF_MSISDN=$MSISDN -e NOTIF_ACTION=list)
cnt=$(field "$lst" NOTIF_COUNT)
if [ "${cnt:-0}" -ge 3 ]; then echo "[OK] notifications persistees et consultables par numero ($cnt pour $MSISDN)"; else echo "[ECHEC] consultation: $lst"; fail=1; fi

# 5) une notification est emise lors d'un transfert execute.
adm "UPDATE core.account SET balance_xaf = 1000000 WHERE msisdn='$FROM' AND balance_xaf < 100000;" >/dev/null
adm "DELETE FROM core.notification WHERE msisdn='$FROM';" >/dev/null
tout=$(docker run --rm --network isc-net --env-file infra/.env -e CORE_MODE=transfer -e FROM_MSISDN=$FROM \
       -e BENEFICIARY_MSISDN=677000002 -e AMOUNT_XAF=15000 \
       -v isc-certs:/certs:ro -v "$(pwd)/config:/config:ro" --entrypoint java isc-core \
       -Dspring.main.web-application-type=none -jar /app/app.jar 2>/dev/null)
tstate=$(echo "$tout" | sed -n 's/^TRANSFER_STATE=//p' | tr -d '[:space:]')
tlst=$(core_notif -e NOTIF_MSISDN=$FROM -e NOTIF_ACTION=list)
if echo "$tlst" | grep -q "P2P_EXECUTED"; then
  echo "[OK] notification emise lors d'un transfert execute (etat=$tstate, type P2P_EXECUTED)"
else echo "[ECHEC] aucune notif de transfert (etat=$tstate): $tlst"; fail=1; fi

# ---- Assertions NEGATIVES : trois refus effectifs, chacun prouve avec son message ----
echo "--- refus ENF-17 (chacun prouve par une tentative reelle) ---"
check_refus() {  # $1 libelle ; $2 texte ; $3 motif attendu
  o=$(core_notif -e NOTIF_MSISDN=$MSISDN -e NOTIF_CHANNEL=SMS -e NOTIF_TEXT="$2")
  r=$(field "$o" NOTIF_REFUSED); m=$(field "$o" NOTIF_MESSAGE)
  if [ "$r" = "$3" ]; then echo "[OK] $1 -> REFUSE ($r) : $m"
  else echo "[ECHEC] $1 non refuse comme prevu ($3): $o"; fail=1; fi
}
check_refus "adresse web"         "Recuperez votre compte sur https://recuperer-compte.cm" WEB_LINK
check_refus "numero de telephone" "Rappelez le service au 677 88 77 66"                    PHONE_NUMBER
check_refus "demande de code"     "Communiquez votre code recu par SMS pour valider"       SECRET_REQUEST

[ $fail -eq 0 ] && echo "verify-notification: VERT" || echo "verify-notification: ROUGE"
exit $fail
