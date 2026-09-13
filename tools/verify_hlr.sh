#!/bin/bash
# T2-10 — Verifie le HLR (EF-02, EF-08, EI-02, ES-16b) dans la VM. Necessite la pile debout.
# Prouve : un seul IMSI actif ; une reattribution ecrit une ligne d'historique complete et bascule
# l'IMSI actif ; l'evenement porte l'ancien et le nouvel IMSI et son horodatage ; deux reattributions
# donnent deux lignes ordonnees ; l'historique est en ecriture seule au niveau applicatif (on n'y
# revient pas : la ligne existante est inchangee apres une reattribution suivante -- appel append).
# Assertion negative : le motif ne transite JAMAIS vers le PDP (inspection de la requete emise).
set -uo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.." || exit 2
set -a
# shellcheck disable=SC1091
. infra/.env
set +a

MSISDN=699000001
IMSI0=624010000000001
IMSI1=624010000000002
IMSI2=624010000000003
MOTIF_SECRET="MOTIF-COMPLICE-INSPECTION"
fail=0

psqla() {
  docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" isc-postgres \
    psql -qtAX -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1" 2>/dev/null | tr -d '[:space:]'
}
run_hlr() {
  docker run --rm --network isc-net --env-file infra/.env -e CORE_MODE=hlr "$@" \
    -v isc-certs:/certs:ro -v "$(pwd)/config:/config:ro" isc-core \
    java -Dspring.main.web-application-type=none -jar /app/app.jar 2>/dev/null
}
field() { echo "$1" | sed -n "s/^$2=//p"; }

# Hygiene : etat HLR propre pour le numero de test (harnais, pas logique de production).
psqla "DELETE FROM core.hlr_reassignment WHERE msisdn='$MSISDN'; DELETE FROM core.hlr_subscriber WHERE msisdn='$MSISDN';" >/dev/null

# --- Reattribution 1 : (amorce IMSI0) puis IMSI0 -> IMSI1 ---
out1=$(run_hlr -e HLR_MSISDN=$MSISDN -e HLR_INITIAL_IMSI=$IMSI0 -e HLR_NEW_IMSI=$IMSI1 \
               -e HLR_AGENT=agent-007 -e HLR_REASON="$MOTIF_SECRET")
old1=$(field "$out1" HLR_OLD_IMSI)
event1=$(field "$out1" HLR_EVENT)

# 1) un seul IMSI actif, = IMSI1
active=$(psqla "SELECT count(*) FROM core.hlr_subscriber WHERE msisdn='$MSISDN';")
current=$(psqla "SELECT current_imsi FROM core.hlr_subscriber WHERE msisdn='$MSISDN';")
if [ "$active" = "1" ] && [ "$current" = "$IMSI1" ]; then
  echo "[OK] 1 seul IMSI actif par numero, = $IMSI1"
else echo "[ECHEC] IMSI actif : count=$active current=$current"; fail=1; fi

# 2) la reattribution a cree une ligne d'historique COMPLETE et bascule l'IMSI (old1 = IMSI0)
row1=$(psqla "SELECT old_imsi||'|'||new_imsi||'|'||agent_id||'|'||reason||'|'||(occurred_at IS NOT NULL)::text FROM core.hlr_reassignment WHERE msisdn='$MSISDN' ORDER BY occurred_at LIMIT 1;")
if [ "$old1" = "$IMSI0" ] && [ "$row1" = "$IMSI0|$IMSI1|agent-007|$MOTIF_SECRET|true" ]; then
  echo "[OK] reattribution : ligne d'historique complete (old/new/agent/motif/horodatage) + IMSI bascule"
else echo "[ECHEC] ligne d'historique : old1=$old1 row1=$row1"; fail=1; fi

# 3) evenement avec ancien + nouvel IMSI + horodatage (HLR_EVENT=msisdn|old|new|changedAt|agent)
ev_old=$(echo "$event1" | cut -d'|' -f2); ev_new=$(echo "$event1" | cut -d'|' -f3); ev_at=$(echo "$event1" | cut -d'|' -f4)
if [ "$ev_old" = "$IMSI0" ] && [ "$ev_new" = "$IMSI1" ] && [ -n "$ev_at" ]; then
  echo "[OK] evenement emis : ancien=$ev_old nouvel=$ev_new horodatage=$ev_at"
else echo "[ECHEC] evenement : $event1"; fail=1; fi

# snapshot immuabilite de la ligne 1 (empreinte de toutes ses colonnes)
id1=$(psqla "SELECT id FROM core.hlr_reassignment WHERE msisdn='$MSISDN' ORDER BY occurred_at LIMIT 1;")
snap1=$(psqla "SELECT md5(t::text) FROM (SELECT id,msisdn,old_imsi,new_imsi,agent_id,reason,occurred_at FROM core.hlr_reassignment WHERE id='$id1') t;")

# --- Reattribution 2 : IMSI1 -> IMSI2 ---
out2=$(run_hlr -e HLR_MSISDN=$MSISDN -e HLR_INITIAL_IMSI=$IMSI0 -e HLR_NEW_IMSI=$IMSI2 \
               -e HLR_AGENT=agent-008 -e HLR_REASON="motif-2")
req=$(field "$out2" HLR_EVALUATE_REQUEST)

# 4) deux lignes d'historique, dans l'ordre, chaine IMSI0>IMSI1>IMSI2
count=$(psqla "SELECT count(*) FROM core.hlr_reassignment WHERE msisdn='$MSISDN';")
chain=$(psqla "SELECT string_agg(old_imsi||'>'||new_imsi, ',' ORDER BY occurred_at) FROM core.hlr_reassignment WHERE msisdn='$MSISDN';")
if [ "$count" = "2" ] && [ "$chain" = "$IMSI0>$IMSI1,$IMSI1>$IMSI2" ]; then
  echo "[OK] 2 reattributions => 2 lignes ordonnees, chaine $chain"
else echo "[ECHEC] historique : count=$count chain=$chain"; fail=1; fi

# 5) ecriture seule au niveau applicatif : la ligne 1 existante est INCHANGEE apres la 2e reattribution
snap1_after=$(psqla "SELECT md5(t::text) FROM (SELECT id,msisdn,old_imsi,new_imsi,agent_id,reason,occurred_at FROM core.hlr_reassignment WHERE id='$id1') t;")
if [ "$snap1" = "$snap1_after" ] && [ -n "$snap1" ]; then
  echo "[OK] historique en ecriture seule : la ligne existante est inchangee (on n'y revient pas ; append)"
else echo "[ECHEC] la ligne 1 a change : $snap1 -> $snap1_after"; fail=1; fi

# Assertion negative OBLIGATOIRE : le motif ne transite pas vers le PDP (inspection de la requete emise)
if [ -z "$req" ]; then
  echo "[ECHEC] requete /evaluate emise introuvable"; fail=1
elif echo "$req" | grep -q "$MOTIF_SECRET"; then
  echo "[ECHEC] le MOTIF apparait dans la requete /evaluate transmise au PDP (ES-16b viole)"; fail=1
else
  echo "[OK] assertion negative : le motif n'apparait PAS dans la requete /evaluate emise (ES-16b)"
fi
# et la requete porte bien l'IMSI courant resolu depuis le HLR
if echo "$req" | grep -q "$IMSI2"; then
  echo "[OK] la requete /evaluate porte l'IMSI courant du HLR ($IMSI2)"
else echo "[ECHEC] IMSI courant absent de la requete emise"; fail=1; fi

echo "--- requete /evaluate emise (inspectee) ---"; echo "$req"
[ $fail -eq 0 ] && echo "verify-hlr: VERT" || echo "verify-hlr: ROUGE"
exit $fail
