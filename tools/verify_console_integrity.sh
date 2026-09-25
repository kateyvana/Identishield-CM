#!/bin/bash
# T2-19 — Verifie les DEUX vues de lecture de la console : integrite du journal (EF-34) et
# notifications emises (EF-37). Contre la pile debout (postgres, coeur, PDP, isc-sim, console).
#
# Le verdict d'integrite affiche est la verification REELLE de T2-06 (pdp.audit_verify /
# pdp.audit_anchor_check), appelee par le coeur (GET /audit/integrity), JAMAIS reecrite dans la
# console. L'alteration de demonstration est faite par le role ADMINISTRATEUR (psql), pas par la
# console : la console lit, verifie, affiche -- elle n'expose aucune route d'ecriture vers le journal.
set -uo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.." || exit 2
CONSOLE=${CONSOLE_URL:-http://localhost:3000}
V=677000007; B=677000008; VIMSI=624010000000010; AIMSI=624010000000666
fail=0
adm(){ docker exec -i isc-postgres psql -U isc_admin -d identishield -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }
jq_field(){ python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }

# Porte de disponibilite (premier appel serveur->coeur a froid).
for _ in $(seq 1 15); do curl -sS "$CONSOLE/api/integrity" 2>/dev/null | grep -q '"intact"' && break; sleep 2; done

# --- ASSERTION NEGATIVE : la console n'expose AUCUNE route d'ecriture/alteration du journal --------
echo "--- assertion negative : aucune route d'ecriture/alteration du journal cote console ---"
wr=$(grep -rnE "export async function (POST|PUT|PATCH|DELETE)" console/app/api/integrity console/app/api/notifications 2>/dev/null)
if [ -z "$wr" ]; then echo "[OK] /api/integrity et /api/notifications n'exposent QUE GET (aucun POST/PUT/PATCH/DELETE)"; else echo "[ECHEC] route d'ecriture exposee : $wr"; fail=1; fi
alt=$(grep -rniE "audit.*(alter|tamper|/write|delete from|update .*audit|insert into .*audit)" console/app 2>/dev/null | grep -viE "//|inalter|no way to modify|aucun|aucune|n'expose")
if [ -z "$alt" ]; then echo "[OK] aucune reference a une alteration/ecriture du journal d'audit dans console/app"; else echo "[ECHEC] reference a une ecriture du journal : $alt"; fail=1; fi

# --- 1) CHAINE INTEGRE : verdict vert -------------------------------------------------------------
# Assurer >= 4 entrees (via isc_pdp_app -> le trigger les chaine), comme verify_audit.sh.
n=$(adm "SELECT count(*) FROM pdp.audit_entry;")
if [ "${n:-0}" -lt 4 ]; then
  docker exec -i isc-postgres psql -U isc_pdp_app -d identishield -c \
    "INSERT INTO pdp.audit_entry(payload, written_at) SELECT jsonb_build_object('decisionId', gen_random_uuid()::text, 'seed', true), timestamptz '2026-01-01T00:00:00Z' FROM generate_series(1,4);" >/dev/null 2>&1
fi
intact=$(curl -s "$CONSOLE/api/integrity" | jq_field "d.get('intact')")
if [ "$intact" = "True" ]; then echo "[OK] chaine integre : la vue affiche le verdict vert (intact=true)"; else echo "[ECHEC] chaine non integre a l'etat initial (intact=$intact)"; fail=1; fi

# --- 2) ALTERATION PAR L'ADMIN (pas la console) : rupture detectee ET localisee -------------------
mid=$(adm "SELECT chain_index FROM pdp.audit_entry ORDER BY chain_index LIMIT 1 OFFSET 1;")
# Sauvegarde ROBUSTE de l'original en base64 (aucun souci de quotes JSON), pour restaurer a l'octet pres.
orig_b64=$(adm "SELECT encode(convert_to(payload::text,'UTF8'),'base64') FROM pdp.audit_entry WHERE chain_index=$mid;")
adm "UPDATE pdp.audit_entry SET payload='{\"tampered\":true}' WHERE chain_index=$mid;" >/dev/null
sleep 1
resp=$(curl -s "$CONSOLE/api/integrity")
ai=$(printf '%s' "$resp" | jq_field "str(d.get('intact'))+' '+str(d.get('firstBrokenIndex'))")
if [ "$ai" = "False $mid" ]; then echo "[OK] apres alteration (ADMIN) : la vue affiche la rupture (intact=false) ET sa position (entree $mid)"; else echo "[ECHEC] rupture non detectee/localisee : '$ai' (attendu 'False $mid')"; fail=1; fi
# Restaurer l'entree a l'identique. La base64 (alphanumerique + /+=, sans apostrophe) s'embarque
# directement comme litteral SQL -> aucune interpolation psql fragile.
adm "UPDATE pdp.audit_entry SET payload=convert_from(decode('$orig_b64','base64'),'UTF8')::jsonb WHERE chain_index=$mid;" >/dev/null
sleep 1
intact2=$(curl -s "$CONSOLE/api/integrity" | jq_field "d.get('intact')")
if [ "$intact2" = "True" ]; then echo "[OK] journal restaure par l'admin : verdict de nouveau vert (intact=true)"; else echo "[ECHEC] restauration : intact=$intact2"; fail=1; fi

# --- 3) NOTIFICATIONS emises lors d'un transfert : canal + horodatage -----------------------------
curl -sS -X POST "$CONSOLE/api/mode" -H 'Content-Type: application/json' -d '{"layerless": true}' >/dev/null 2>&1
docker exec -i isc-postgres psql -U isc_admin -d identishield -qtA >/dev/null 2>&1 <<SQL
DELETE FROM core.transaction WHERE account_id IN (SELECT id FROM core.account WHERE msisdn IN ('$V','$B'));
DELETE FROM core.notification WHERE msisdn='$V';
UPDATE core.account SET balance_xaf=500000 WHERE msisdn='$V';
INSERT INTO core.account(msisdn,balance_xaf,created_at) VALUES('$V',500000,now()) ON CONFLICT(msisdn) DO UPDATE SET balance_xaf=500000;
INSERT INTO core.account(msisdn,balance_xaf,created_at) VALUES('$B',0,now()) ON CONFLICT(msisdn) DO NOTHING;
INSERT INTO core.hlr_subscriber(msisdn,current_imsi,updated_at) VALUES('$V','$VIMSI',now()) ON CONFLICT(msisdn) DO UPDATE SET current_imsi='$VIMSI',updated_at=now();
SQL
docker run --rm --network isc-net -e CORE_URL=http://core:8080 -e SIM_URL=http://sim:8080 -e PYTHONPATH=/src \
  -v "$(pwd)/attacker/src:/src:ro" python:3.12-alpine python -m isc_attacker ac02 \
  --victim $V --victim-imsi $VIMSI --attacker-imsi $AIMSI --beneficiary $B --agent-id agent-kyc-07 --motif swap >/dev/null 2>&1
sleep 1
notif=$(curl -s "$CONSOLE/api/notifications?limit=50")
count=$(printf '%s' "$notif" | jq_field "len(d)")
if [ "${count:-0}" -ge 1 ]; then echo "[OK] la vue notifications affiche $count notification(s) emise(s) lors du transfert"; else echo "[ECHEC] aucune notification affichee"; fail=1; fi
meta=$(printf '%s' "$notif" | jq_field "(d[0].get('channel','')+' @ '+d[0].get('sentAt','')) if d else 'AUCUNE'")
if printf '%s' "$meta" | grep -qE "^(SMS|PUSH) @ .+T.+"; then echo "[OK] canal et horodatage presents : $meta"; else echo "[ECHEC] canal/horodatage manquant : $meta"; fail=1; fi

# --- 4) CONTENU affiche : ni lien, ni numero de rappel (garde-fou T2-14 jusqu'a l'affichage) ------
body=$(printf '%s' "$notif" | jq_field "d[0].get('body','') if d else ''")
# On retire l'horodatage ISO (ses nanosecondes ne sont pas un numero de rappel) avant le controle.
body_check=$(printf '%s' "$body" | sed -E 's/[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z?//g')
if printf '%s' "$body_check" | grep -qiE "https?://|www\.|[0-9]{6,}"; then
  echo "[ECHEC] le contenu affiche contient un lien ou un numero de rappel : « $body »"; fail=1
else echo "[OK] contenu affiche sans lien ni numero de rappel : « $body »"; fi

curl -sS -X POST "$CONSOLE/api/mode" -H 'Content-Type: application/json' -d '{"layerless": false}' >/dev/null 2>&1
echo "[OK] mode restaure a PROTEGE"

[ $fail -eq 0 ] && echo "verify-console-integrity: VERT" || echo "verify-console-integrity: ROUGE"
exit $fail
