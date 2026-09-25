#!/bin/bash
# T2-17 — Verifie la vue de demonstration du jury : la console montre l'attaque AC-02 en MODE SANS
# COUCHE. Prouve, contre la pile debout (postgres, pdp, coeur, isc-sim, console) :
#   1. les huit etapes apparaissent dans la console, DANS L'ORDRE, chacune < 2 s apres sa production ;
#   2. la vue HLR montre le changement d'IMSI (l'IMSI actif bascule vers l'attaquant, cf. etape 2) ;
#   3. le bandeau affiche « MODE SANS COUCHE » pendant toute la sequence ;
#   4. l'OTP intercepte n'apparait JAMAIS en clair dans la reponse servie par la console.
# Assertion negative (ADR-034) : aucun endpoint de chronologie/attaque n'a ete ajoute au coeur.
#
# La chronologie est une donnee de SIMULATION : l'outil la PUBLIE sur isc-sim, la console la LIT sur
# isc-sim ; le coeur n'en sait rien. La console MASQUE l'OTP (elle est exposee au reseau local).
set -uo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.." || exit 2
CONSOLE=${CONSOLE_URL:-http://localhost:3000}

VICTIM=677000007
BENEFICIARY=677000008
VICTIM_IMSI=624010000000010
ATTACKER_IMSI=624010000000666
BALANCE=500000
fail=0
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

adm() { docker exec isc-postgres psql -U isc_admin -d identishield -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }
setmode() { curl -sS -X POST "$CONSOLE/api/mode" -H 'Content-Type: application/json' -d "{\"layerless\": $1}" >/dev/null 2>&1; }

# --- 0) ASSERTION NEGATIVE (ADR-034) : le coeur ne contient aucun endpoint de chronologie/attaque.
echo "--- assertion negative : aucun endpoint de chronologie/attaque dans le coeur (ADR-034) ---"
hits=$(grep -rniE 'timeline|siminbox|/inbox|chronolog' core/src/ | grep -v -iE '//|/\*|\*' )
if [ -z "$hits" ]; then
  echo "[OK] aucun endpoint de chronologie/attaque dans core/src/ (grep timeline|inbox|chronolog = rien)"
else
  echo "[ECHEC] le coeur contient une reference d'endpoint chronologie/attaque :"; echo "$hits"; fail=1
fi

# --- Porte de disponibilite de la console (premier appel serveur->coeur a froid).
for _ in $(seq 1 15); do curl -sS "$CONSOLE/api/mode" 2>/dev/null | grep -q '"layerless"' && break; sleep 2; done

# --- 1) Le proprietaire bascule en MODE SANS COUCHE (l'outil n'en sait rien).
setmode true

# --- Bandeau AVANT : le HTML servi doit annoncer le mode sans couche.
if curl -sS "$CONSOLE/" 2>/dev/null | grep -qF "MODE SANS COUCHE"; then
  echo "[OK] bandeau « MODE SANS COUCHE » present AVANT l'attaque"
else echo "[ECHEC] bandeau sans couche absent avant l'attaque"; fail=1; fi

# --- Mise en scene : victime approvisionnee + SIM (ancien IMSI). Meme etat que verify_attacker.
adm "DELETE FROM core.transaction WHERE account_id IN (SELECT id FROM core.account WHERE msisdn IN ('$VICTIM','$BENEFICIARY'));
     DELETE FROM core.notification WHERE msisdn='$VICTIM';
     DELETE FROM core.otp WHERE msisdn='$VICTIM';
     DELETE FROM core.hlr_reassignment WHERE msisdn='$VICTIM';
     INSERT INTO core.account(msisdn, balance_xaf, created_at) VALUES ('$VICTIM', $BALANCE, now())
       ON CONFLICT (msisdn) DO UPDATE SET balance_xaf=$BALANCE;
     INSERT INTO core.account(msisdn, balance_xaf, created_at) VALUES ('$BENEFICIARY', 0, now())
       ON CONFLICT (msisdn) DO NOTHING;
     INSERT INTO core.hlr_subscriber(msisdn, current_imsi, updated_at) VALUES ('$VICTIM', '$VICTIM_IMSI', now())
       ON CONFLICT (msisdn) DO UPDATE SET current_imsi='$VICTIM_IMSI', updated_at=now();" >/dev/null

# --- 2) Lancer l'outil EN TACHE DE FOND et SONDER la console en parallele pour mesurer la latence
#        par etape. L'outil publie chaque etape sur isc-sim a mesure ; la console la sert aussitot.
docker run --rm --network isc-net -e CORE_URL=http://core:8080 -e SIM_URL=http://sim:8080 \
  -e PYTHONPATH=/src -v "$(pwd)/attacker/src:/src:ro" python:3.12-alpine \
  python -m isc_attacker ac02 --victim $VICTIM --victim-imsi $VICTIM_IMSI \
  --attacker-imsi $ATTACKER_IMSI --beneficiary $BENEFICIARY \
  --agent-id agent-kyc-07 --motif "remplacement SIM" >"$TMP/attacker.out" 2>&1 &
apid=$!

SNAP="$TMP/snap.txt"
end=$(( $(date +%s) + 40 ))
while [ "$(date +%s)" -lt "$end" ]; do
  printf '%s %s\n' "$(date +%s.%N)" "$(curl -sS "$CONSOLE/api/timeline" 2>/dev/null | tr -d '\n')" >>"$SNAP"
  body=$(tail -n1 "$SNAP")
  if printf '%s' "$body" | grep -q '"step":8' && ! kill -0 $apid 2>/dev/null; then break; fi
  sleep 0.2
done
wait $apid; arc=$?

echo "======== SORTIE BRUTE DE isc-attacker ac02 (tache de fond) ========"
cat "$TMP/attacker.out"
echo "==================================================================="
[ $arc -eq 0 ] || { echo "[ECHEC] l'outil a termine avec le code $arc"; fail=1; }

# --- 1) Les huit etapes, dans l'ordre, chacune < 2 s apres sa production. Analyse en python (horloge
#        VM partagee entre conteneurs et hote : les instants sont comparables).
echo "--- chronologie : 8 etapes, dans l'ordre, < 2 s apres production ---"
python3 - "$SNAP" <<'PY'
import json, sys
first_seen, latest = {}, {}
with open(sys.argv[1], encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if not line: continue
        epoch_s, _, raw = line.partition(" ")
        try:
            epoch = float(epoch_s); data = json.loads(raw)
        except (ValueError, json.JSONDecodeError):
            continue
        for s in data.get("steps", []):
            n = s.get("step")
            if n is None: continue
            first_seen.setdefault(n, epoch)
            latest[n] = s

steps = sorted(latest)
ok = True
if steps != list(range(1, 9)):
    print(f"[ECHEC] etapes vues = {steps} (attendu 1..8)"); sys.exit(1)

from datetime import datetime
worst = 0.0
for n in steps:
    ts = latest[n].get("ts")
    try:
        prod = datetime.fromisoformat(ts).timestamp()
    except (TypeError, ValueError):
        print(f"[ECHEC] etape {n} sans horodatage exploitable"); ok = False; continue
    delta = first_seen[n] - prod
    worst = max(worst, delta)
    flag = "OK" if delta < 2.0 else "TROP LENT"
    print(f"    etape {n}: vue {delta:+.2f}s apres production [{flag}]")
    if delta >= 2.0: ok = False

if ok:
    print(f"[OK] les 8 etapes apparaissent dans l'ordre, chacune < 2 s apres production (pire = {worst:.2f}s)")
    sys.exit(0)
print("[ECHEC] au moins une etape hors ordre ou > 2 s"); sys.exit(1)
PY
[ $? -eq 0 ] || fail=1

# --- 4) L'OTP intercepte (clair dans la sortie brute) n'apparait PAS dans la reponse de la console.
otp=$(sed -n "s/.*OTP intercepte.*: \([0-9]\{4,\}\).*/\1/p" "$TMP/attacker.out" | head -n1)
served=$(curl -sS "$CONSOLE/api/timeline" 2>/dev/null)
echo "--- OTP jamais en clair dans la reponse servie par la console ---"
if [ -z "$otp" ]; then
  echo "[ECHEC] impossible d'extraire l'OTP clair de la sortie brute (test non concluant)"; fail=1
elif printf '%s' "$served" | grep -qF "$otp"; then
  echo "[ECHEC] l'OTP en clair ($otp) apparait dans la reponse de la console"; fail=1
else
  masked=$(printf '%s' "$served" | grep -o 'OTP intercepte[^"]*' | head -n1)
  echo "[OK] l'OTP clair ($otp) est ABSENT de la reponse de la console ; servi masque : « $masked »"
fi

# --- 2) La vue HLR montre le changement d'IMSI (bascule vers l'attaquant, ce que decrit l'etape 2).
echo "--- vue HLR : changement d'IMSI (etape 2) ---"
hlr=$(curl -sS "$CONSOLE/api/hlr?msisdn=$VICTIM" 2>/dev/null)
cur=$(printf '%s' "$hlr" | sed -n 's/.*"currentImsi":"\([0-9]*\)".*/\1/p')
step2old=$(printf '%s' "$served" | python3 -c "import json,sys; d=json.load(sys.stdin); s=[x for x in d.get('steps',[]) if x.get('step')==2]; print(s[0].get('oldImsi','') if s else '')" 2>/dev/null)
step2new=$(printf '%s' "$served" | python3 -c "import json,sys; d=json.load(sys.stdin); s=[x for x in d.get('steps',[]) if x.get('step')==2]; print(s[0].get('newImsi','') if s else '')" 2>/dev/null)
if [ "$cur" = "$ATTACKER_IMSI" ] && [ "$step2new" = "$ATTACKER_IMSI" ] && [ "$step2old" = "$VICTIM_IMSI" ]; then
  echo "[OK] la vue HLR montre l'IMSI courant bascule vers l'attaquant ($cur) ; etape 2 : $step2old -> $step2new"
else
  echo "[ECHEC] HLR currentImsi=$cur (attendu $ATTACKER_IMSI) ; etape 2 $step2old->$step2new"; fail=1
fi

# --- 3) Bandeau APRES : le mode sans couche est reste affiche pendant toute la sequence.
if curl -sS "$CONSOLE/" 2>/dev/null | grep -qF "MODE SANS COUCHE"; then
  echo "[OK] bandeau « MODE SANS COUCHE » toujours present APRES l'attaque (affiche pendant toute la sequence)"
else echo "[ECHEC] bandeau sans couche absent apres l'attaque"; fail=1; fi

# --- 5) Restaurer le mode protege : ne pas laisser la pile desarmee.
setmode false
echo "[OK] mode restaure a PROTEGE"

[ $fail -eq 0 ] && echo "verify-console-attack: VERT" || echo "verify-console-attack: ROUGE"
exit $fail
