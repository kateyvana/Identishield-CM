#!/bin/bash
# T2-16 — Verifie l'outil attaquant : AC-02 de bout en bout (EF-40, ISC-TM v1.2.1 §8), EN MODE SANS
# COUCHE. C'est la PHASE 1 de T-AC-02 : l'attaque REUSSIT sans la couche. La phase 2 (echec AVEC la
# couche) viendra au mois 4, quand les regles existeront -- non simulee ici.
#
# L'outil ne connait RIEN du mode : c'est CE script (le proprietaire) qui bascule le mode AVANT de le
# lancer. Prouve l'oracle d'ISC-TM §8, verifie independamment en base :
#   - le solde de la victime passe a zero ;
#   - au moins une sortie est executee vers un beneficiaire controle par l'attaquant ;
#   - l'OTP consomme etait parti vers l'IMSI de l'attaquant, pas celui de la victime ;
#   - aucune notification n'est parvenue a l'ancien appareil de la victime.
# Necessite la pile debout (postgres, pdp, coeur service, console).
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2
CONSOLE=${CONSOLE_URL:-http://localhost:3000}

VICTIM=699000007
BENEFICIARY=699000008
VICTIM_IMSI=624010000000010
ATTACKER_IMSI=624010000000666
BALANCE=500000
fail=0

adm() { docker exec isc-postgres psql -U isc_admin -d identishield -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }
setmode() {
  curl -sS -X POST "$CONSOLE/api/mode" -H 'Content-Type: application/json' \
    -d "{\"layerless\": $1}" >/dev/null 2>&1
}

# Porte de disponibilite console (premier appel serveur->coeur a froid : cf. observation cold-start).
for _ in $(seq 1 15); do curl -sS "$CONSOLE/api/mode" 2>/dev/null | grep -q '"layerless"' && break; sleep 2; done

# 1) Le proprietaire bascule en MODE SANS COUCHE (l'outil, lui, n'en sait rien).
setmode true

# 2) Mise en scene : la victime a un compte approvisionne et une SIM (son ancien IMSI).
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

# 3) Lancer l'outil attaquant : une commande, contre le coeur, sur isc-net.
echo "======== SORTIE BRUTE DE isc-attacker ac02 ========"
out=$(docker run --rm --network isc-net -e CORE_URL=http://core:8080 -e SIM_URL=http://sim:8080 \
  -e PYTHONPATH=/src -v "$(pwd)/attacker/src:/src:ro" python:3.12-alpine \
  python -m isc_attacker ac02 --victim $VICTIM --victim-imsi $VICTIM_IMSI \
  --attacker-imsi $ATTACKER_IMSI --beneficiary $BENEFICIARY \
  --agent-id agent-kyc-07 --motif "remplacement SIM" 2>&1)
rc=$?
echo "$out"
echo "==================================================="
[ $rc -eq 0 ] || { echo "[ECHEC] l'outil a termine avec le code $rc"; fail=1; }

# 4) Oracle d'ISC-TM §8, verifie INDEPENDAMMENT en base.
echo "--- oracle ISC-TM §8 (verifie en base) ---"
bal=$(adm "SELECT balance_xaf FROM core.account WHERE msisdn='$VICTIM';")
if [ "$bal" = "0" ]; then echo "[OK] solde de la victime vide : $bal FCFA"; else echo "[ECHEC] solde victime=$bal (attendu 0)"; fail=1; fi

outs=$(adm "SELECT count(*) FROM core.transaction t JOIN core.account a ON a.id=t.account_id WHERE a.msisdn='$VICTIM' AND t.beneficiary_id='$BENEFICIARY' AND t.state='EXECUTED';")
if [ "${outs:-0}" -ge 1 ]; then echo "[OK] $outs sortie(s) executee(s) vers le beneficiaire attaquant ($BENEFICIARY)"; else echo "[ECHEC] aucune sortie executee vers $BENEFICIARY"; fail=1; fi

tgt=$(adm "SELECT target_imsi FROM core.otp WHERE msisdn='$VICTIM' AND consumed_at IS NOT NULL ORDER BY expires_at DESC LIMIT 1;")
if [ "$tgt" = "$ATTACKER_IMSI" ]; then echo "[OK] l'OTP consomme etait parti vers l'IMSI ATTAQUANT ($ATTACKER_IMSI), pas la victime"; else echo "[ECHEC] IMSI cible de l'OTP consomme=$tgt (attendu $ATTACKER_IMSI)"; fail=1; fi

oldn=$(adm "SELECT count(*) FROM core.notification WHERE msisdn='$VICTIM' AND recipient_ref='$VICTIM_IMSI';")
if [ "${oldn:-0}" = "0" ]; then echo "[OK] aucune notification parvenue a l'ancien appareil de la victime ($VICTIM_IMSI)"; else echo "[ECHEC] $oldn notification(s) a l'ancien appareil"; fail=1; fi

# 5) Restaurer le mode protege : ne pas laisser la pile desarmee apres la verification.
setmode false
echo "[OK] mode restaure a PROTEGE"

[ $fail -eq 0 ] && echo "verify-attacker: VERT" || echo "verify-attacker: ROUGE"
exit $fail
