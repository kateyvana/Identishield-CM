#!/bin/bash
# AC-02 PHASE 2 de bout en bout, sur la pile : la même attaque que tools/verify_attacker.sh (phase 1),
# en MODE PROTÉGÉ. Démonstration de soutenance ; procédure et résultats :
# docs/03-revues/2026-10-22-ac02-bout-en-bout.md. Ce n'est pas le test automatisé de la carte A7b
# (qui couvre aussi « titulaire servi ») : c'est la vérification manuelle de la démonstration.
#
# Oracle, vérifié INDÉPENDAMMENT en base :
#   - le solde de la victime est intact, aucune sortie n'est exécutée ;
#   - le PDP a rendu UNE décision FREEZE avec le veto PROBATION_VIOLATION ;
#   - le compte est FROZEN côté PDP ;
#   - la chaîne d'audit ne présente aucune anomalie (pdp.audit_verify).
# Le compte de la victime est RECRÉÉ à chaque passage (identifiant neuf, jamais vu du PDP) : le
# résultat ne dépend pas d'un gel laissé par un passage précédent.
# Nécessite la pile debout (postgres, pdp, cœur, isc-sim, console).
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2
CONSOLE=${CONSOLE_URL:-http://localhost:3000}

VICTIM=677000007
BENEFICIARY=677000008
VICTIM_IMSI=624010000000010
ATTACKER_IMSI=624010000000666
BALANCE=500000
fail=0

adm() { docker exec isc-postgres psql -U isc_admin -d identishield -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }

# Porte de disponibilité console (premier appel serveur->cœur à froid : cf. observation cold-start).
for _ in $(seq 1 15); do curl -sS "$CONSOLE/api/mode" 2>/dev/null | grep -q '"layerless"' && break; sleep 2; done

# 1) Le propriétaire place la pile en MODE PROTÉGÉ (l'outil attaquant, lui, n'en sait rien).
curl -sS -X POST "$CONSOLE/api/mode" -H 'Content-Type: application/json' -d '{"layerless": false}' >/dev/null
echo "mode : $(curl -sS "$CONSOLE/api/mode")"

# 2) Mise en scène : victime approvisionnée, compte RECRÉÉ, SIM d'origine.
adm "DELETE FROM core.transaction WHERE account_id IN (SELECT id FROM core.account WHERE msisdn IN ('$VICTIM','$BENEFICIARY'));
     DELETE FROM core.notification WHERE msisdn='$VICTIM';
     DELETE FROM core.otp WHERE msisdn='$VICTIM';
     DELETE FROM core.hlr_reassignment WHERE msisdn='$VICTIM';
     DELETE FROM core.account WHERE msisdn='$VICTIM';
     INSERT INTO core.account(msisdn, balance_xaf, created_at) VALUES ('$VICTIM', $BALANCE, now());
     INSERT INTO core.account(msisdn, balance_xaf, created_at) VALUES ('$BENEFICIARY', 0, now())
       ON CONFLICT (msisdn) DO NOTHING;
     INSERT INTO core.hlr_subscriber(msisdn, current_imsi, updated_at) VALUES ('$VICTIM', '$VICTIM_IMSI', now())
       ON CONFLICT (msisdn) DO UPDATE SET current_imsi='$VICTIM_IMSI', updated_at=now();" >/dev/null
acc=$(adm "SELECT id FROM core.account WHERE msisdn='$VICTIM';")
echo "compte victime : $acc ; état PDP avant : $(adm "SELECT coalesce(string_agg(state,','),'AUCUN') FROM pdp.trust_state WHERE account_id='$acc';")"

# 3) L'outil attaquant : une commande, contre le cœur, sur isc-net.
echo "======== SORTIE BRUTE DE isc-attacker ac02 (mode PROTÉGÉ) ========"
docker run --rm --network isc-net -e CORE_URL=http://core:8080 -e SIM_URL=http://sim:8080 \
  -e PYTHONPATH=/src -v "$(pwd)/attacker/src:/src:ro" python:3.12-alpine \
  python -m isc_attacker ac02 --victim $VICTIM --victim-imsi $VICTIM_IMSI \
  --attacker-imsi $ATTACKER_IMSI --beneficiary $BENEFICIARY \
  --agent-id agent-kyc-07 --motif "remplacement SIM" 2>&1
echo "code de sortie de l'outil : $?"
echo "=================================================================="

# 4) Oracle de la phase 2, vérifié en base.
echo "--- oracle de la phase 2 (vérifié en base) ---"
bal=$(adm "SELECT balance_xaf FROM core.account WHERE msisdn='$VICTIM';")
[ "$bal" = "$BALANCE" ] && echo "[OK] solde de la victime intact : $bal FCFA" || { echo "[ECHEC] solde victime=$bal (attendu $BALANCE)"; fail=1; }
outs=$(adm "SELECT count(*) FROM core.transaction WHERE account_id='$acc' AND state='EXECUTED';")
[ "${outs:-x}" = "0" ] && echo "[OK] aucune sortie exécutée" || { echo "[ECHEC] $outs sortie(s) exécutée(s)"; fail=1; }
echo "transactions du cœur : $(adm "SELECT coalesce(string_agg(beneficiary_id||':'||state, ','),'aucune') FROM core.transaction WHERE account_id='$acc';")"
dec=$(adm "SELECT coalesce(string_agg((payload->>'decision')||'/'||(payload->>'vetoApplied'), ',' ORDER BY chain_index),'aucune') FROM pdp.audit_entry WHERE payload->>'accountId'='$acc' AND payload ? 'decision';")
echo "décisions du PDP pour ce compte : $dec"
case "$dec" in FREEZE/PROBATION_VIOLATION*) echo "[OK] première décision : FREEZE, veto PROBATION_VIOLATION";; *) echo "[ECHEC] première décision inattendue"; fail=1;; esac
nf=$(adm "SELECT count(*) FROM pdp.audit_entry WHERE payload->>'accountId'='$acc' AND payload->>'decision'='FREEZE';")
[ "$nf" = "1" ] && echo "[OK] exactement une décision FREEZE au journal" || { echo "[ECHEC] $nf décisions FREEZE"; fail=1; }
st=$(adm "SELECT state FROM pdp.trust_state WHERE account_id='$acc';")
[ "$st" = "FROZEN" ] && echo "[OK] compte FROZEN côté PDP" || { echo "[ECHEC] état PDP=$st (attendu FROZEN)"; fail=1; }
echo "événements de confiance : $(adm "SELECT coalesce(string_agg(event, ',' ORDER BY id),'aucun') FROM pdp.trust_transition WHERE account_id='$acc';")"
ver=$(adm "SELECT count(*) FROM pdp.audit_verify();")
[ "$ver" = "0" ] && echo "[OK] chaîne d'audit : 0 anomalie (pdp.audit_verify)" || { echo "[ECHEC] chaîne d'audit : $ver anomalie(s)"; fail=1; }

[ $fail -eq 0 ] && echo "verify-ac02-protected: VERT" || echo "verify-ac02-protected: ROUGE"
exit $fail
