#!/bin/bash
# T2-06 — Verifie l'integrite du journal d'audit (ES-09, porte ISC-DEV #4 :
# "la chaine est integre apres N transactions ; l'alteration d'une ligne est
# detectee ET localisee"). Reprend SPIKE-3 : couche A (chainage) + couche B
# (ancre chain_head). Les alterations de test sont jouees dans des transactions
# ANNULEES (ROLLBACK) -> aucune modification permanente du journal. Fonctions de
# verification executees en admin (elles ne sont pas exposees au role applicatif).
# Necessite la pile debout (postgres).
set -uo pipefail
export MSYS_NO_PATHCONV=1
fail=0
adm() { docker exec -i isc-postgres psql -U isc_admin -d identishield -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }
# Pour les blocs BEGIN;...;ROLLBACK; : psql imprime aussi les etiquettes de
# commande. On prefixe le SELECT utile par 'R=' et on n'extrait que cette ligne.
admtx() { docker exec -i isc-postgres psql -U isc_admin -d identishield -tAc "$1" 2>/dev/null | grep '^R=' | head -1 | sed 's/^R=//' | tr -d '[:space:]'; }

# Amorce quelques entrees si la chaine est trop courte (via isc_pdp_app -> le
# trigger les chaine). decisionId aleatoire pour l'unicite ; entrees append-only.
n=$(adm "SELECT count(*) FROM pdp.audit_entry;")
if [ "${n:-0}" -lt 4 ]; then
  docker exec -i isc-postgres psql -U isc_pdp_app -d identishield -c \
    "INSERT INTO pdp.audit_entry(payload, written_at) SELECT jsonb_build_object('decisionId', gen_random_uuid()::text, 'seed', true), timestamptz '2026-01-01T00:00:00Z' FROM generate_series(1,4);" >/dev/null 2>&1
fi

mid=$(adm "SELECT chain_index FROM pdp.audit_entry ORDER BY chain_index LIMIT 1 OFFSET 1;")
last=$(adm "SELECT max(chain_index) FROM pdp.audit_entry;")

# A. Verification COMPLETE : chaine interne intacte (0 anomalie).
a=$(adm "SELECT count(*) FROM pdp.audit_verify(0);")
if [ "$a" = "0" ]; then echo "[OK] verification complete : chaine intacte (0 anomalie)"; else echo "[ECHEC] chaine : $a anomalie(s)"; fail=1; fi

# B. Alteration de contenu (transaction annulee) : detectee ET LOCALISEE.
det=$(admtx "BEGIN; UPDATE pdp.audit_entry SET payload='{\"tampered\":true}' WHERE chain_index=$mid; SELECT 'R='||COALESCE(string_agg(chain_index::text, ',' ORDER BY chain_index),'') FROM pdp.audit_verify(0) WHERE hash_mismatch; ROLLBACK;")
if [ "$det" = "$mid" ]; then echo "[OK] alteration de contenu detectee ET localisee au chain_index $mid"; else echo "[ECHEC] alteration : localisation=$det (attendu $mid)"; fail=1; fi

# C. Suppression du DERNIER (transaction annulee) : la couche A est aveugle,
# l'ancre chain_head la detecte.
anc=$(admtx "BEGIN; DELETE FROM pdp.audit_entry WHERE chain_index=$last; SELECT 'R='||head_matches::text FROM pdp.audit_anchor_check(); ROLLBACK;")
if [ "$anc" = "false" ]; then echo "[OK] suppression du dernier enregistrement detectee par l'ancre chain_head"; else echo "[ECHEC] suppression du dernier non detectee (head_matches=$anc)"; fail=1; fi

# D. Verification INCREMENTALE depuis un index : chaine intacte (0 anomalie).
inc=$(adm "SELECT count(*) FROM pdp.audit_verify($mid);")
if [ "$inc" = "0" ]; then echo "[OK] verification incrementale depuis chain_index $mid : 0 anomalie"; else echo "[ECHEC] incrementale : $inc anomalie(s)"; fail=1; fi

[ $fail -eq 0 ] && echo "verify-audit: VERT" || echo "verify-audit: ROUGE"
exit $fail
