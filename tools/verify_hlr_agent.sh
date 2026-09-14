#!/bin/bash
# T2-11 — Verifie l'outil agent HLR (EF-42) dans la VM. Necessite la pile debout (core avec
# HlrController). Prouve : une reattribution PAR L'OUTIL bascule l'IMSI actif et cree une ligne
# d'historique ; l'evenement est emis ; l'outil refuse un IMSI identique a l'actuel et un numero
# inconnu du HLR, avec un message clair ; deux reattributions successives donnent deux lignes
# ordonnees. Assertion negative : apres une reattribution par l'outil, la requete /evaluate emise
# par le coeur ne contient NI le motif NI l'identifiant d'agent (ES-16b) -- prouve sur la charge
# utile reelle. L'outil tourne en conteneur python sur isc-net (il joint le coeur en HTTP).
set -uo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.." || exit 2
set -a
# shellcheck disable=SC1091
. infra/.env
set +a

MSISDN=699000001
UNKNOWN=699009999
IMSI0=624010000000001
IMSI1=624010000000002
IMSI2=624010000000003
MOTIF=MOTIF-AGENT-INSPECTION
AGENT=agent-agence-042
fail=0

psqla() {
  docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" isc-postgres \
    psql -qtAX -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1" 2>/dev/null | tr -d '[:space:]'
}
# L'outil agent, en conteneur python sur isc-net (joint le coeur en HTTP, comme un agent d'agence).
tool() {
  docker run --rm --network isc-net -e CORE_URL=http://core:8080 -e PYTHONPATH=/src \
    -v "$(pwd)/hlr-agent/src:/src:ro" python:3.12-alpine python -m isc_hlr_agent "$@" 2>&1
}
# Le mode hlr de T2-10 : amorce l'IMSI initial et imprime la requete /evaluate EXACTE (inspection).
core_hlr() {
  docker run --rm --network isc-net --env-file infra/.env -e CORE_MODE=hlr "$@" \
    -v isc-certs:/certs:ro -v "$(pwd)/config:/config:ro" isc-core \
    java -Dspring.main.web-application-type=none -jar /app/app.jar 2>/dev/null
}

# Hygiene + amorcage de l'IMSI initial sur le numero de test (harnais, pas logique de production).
psqla "DELETE FROM core.hlr_reassignment WHERE msisdn IN ('$MSISDN','$UNKNOWN'); DELETE FROM core.hlr_subscriber WHERE msisdn IN ('$MSISDN','$UNKNOWN');" >/dev/null
core_hlr -e HLR_MSISDN=$MSISDN -e HLR_INITIAL_IMSI=$IMSI0 >/dev/null

# 1) une reattribution PAR L'OUTIL bascule l'IMSI actif et cree une ligne d'historique
out1=$(tool reassign --msisdn $MSISDN --new-imsi $IMSI1 --agent-id $AGENT --motif $MOTIF)
rc1=$?
current=$(psqla "SELECT current_imsi FROM core.hlr_subscriber WHERE msisdn='$MSISDN';")
hcount=$(psqla "SELECT count(*) FROM core.hlr_reassignment WHERE msisdn='$MSISDN';")
if [ $rc1 -eq 0 ] && [ "$current" = "$IMSI1" ] && [ "$hcount" = "1" ]; then
  echo "[OK] reattribution par l'outil : IMSI bascule ($IMSI1) + 1 ligne d'historique"
else echo "[ECHEC] reattribution: rc=$rc1 current=$current hcount=$hcount out=$out1"; fail=1; fi

# 2) l'evenement de changement est emis (l'outil le confirme ; la ligne d'historique existe)
if echo "$out1" | grep -q "REASSIGN_OK" && echo "$out1" | grep -qi "evenement.*emis"; then
  echo "[OK] evenement de changement d'IMSI emis (confirme par l'outil)"
else echo "[ECHEC] evenement non confirme : $out1"; fail=1; fi

# 3) l'outil refuse un IMSI identique a l'actuel, message clair
out2=$(tool reassign --msisdn $MSISDN --new-imsi $IMSI1 --agent-id $AGENT --motif $MOTIF)
rc2=$?
if [ $rc2 -ne 0 ] && echo "$out2" | grep -qi "identique"; then
  echo "[OK] refus d'un IMSI identique a l'actuel, message clair"
else echo "[ECHEC] IMSI identique non refuse : rc=$rc2 out=$out2"; fail=1; fi

# 4) l'outil refuse un numero inconnu du HLR, message clair
out3=$(tool reassign --msisdn $UNKNOWN --new-imsi $IMSI1 --agent-id $AGENT --motif $MOTIF)
rc3=$?
if [ $rc3 -ne 0 ] && echo "$out3" | grep -qi "inconnu"; then
  echo "[OK] refus d'un numero inconnu du HLR, message clair"
else echo "[ECHEC] numero inconnu non refuse : rc=$rc3 out=$out3"; fail=1; fi

# 5) deux reattributions successives par l'outil => deux lignes d'historique, dans l'ordre
tool reassign --msisdn $MSISDN --new-imsi $IMSI2 --agent-id agent-suivant --motif motif-2 >/dev/null
count=$(psqla "SELECT count(*) FROM core.hlr_reassignment WHERE msisdn='$MSISDN';")
chain=$(psqla "SELECT string_agg(old_imsi||'>'||new_imsi, ',' ORDER BY occurred_at) FROM core.hlr_reassignment WHERE msisdn='$MSISDN';")
if [ "$count" = "2" ] && [ "$chain" = "$IMSI0>$IMSI1,$IMSI1>$IMSI2" ]; then
  echo "[OK] 2 reattributions par l'outil => 2 lignes ordonnees, chaine $chain"
else echo "[ECHEC] historique : count=$count chain=$chain"; fail=1; fi

# Assertion negative OBLIGATOIRE : ni le motif ni l'identifiant d'agent dans la requete /evaluate
req=$(core_hlr -e HLR_MSISDN=$MSISDN | sed -n 's/^HLR_EVALUATE_REQUEST=//p')
if [ -z "$req" ]; then
  echo "[ECHEC] requete /evaluate emise introuvable"; fail=1
elif echo "$req" | grep -q "$MOTIF" || echo "$req" | grep -q "$AGENT"; then
  echo "[ECHEC] le motif ou l'identifiant d'agent transite vers le PDP (ES-16b viole)"; fail=1
else
  echo "[OK] assertion negative : ni motif ni agent dans la requete /evaluate emise (ES-16b)"
fi
echo "--- requete /evaluate emise (inspectee) ---"; echo "$req"

[ $fail -eq 0 ] && echo "verify-hlr-agent: VERT" || echo "verify-hlr-agent: ROUGE"
exit $fail
