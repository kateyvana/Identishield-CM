#!/usr/bin/env bash
# Exporte la chaîne d'audit (pdp.audit_entry) de la base de la pile vers un fichier daté HORS du
# volume isc-pgdata, puis la vérifie : aucune rupture (pdp.audit_verify), ancre conforme
# (pdp.audit_anchor_check), et consigne l'empreinte SHA-256 de l'export.
#
# C'est la PORTE écrite dans infra/init/migrations/README.md (précondition sur la vraie base) et la
# règle du 2026-10-17 (export avant tout make reset) : la chaîne d'audit est une contribution
# centrale du mémoire (preuves AC-02, ENF-01). On l'exporte AVANT la première migration sur la base
# de la VM et avant toute réinitialisation. Lecture seule sur la base : rien n'est modifié.
#
# S'exécute DANS la VM (docker exec sur isc-postgres), PostgreSQL démarré et le SEUL rédacteur du
# journal (isc-pdp, rôle isc_pdp_app) ARRÊTÉ, pour que l'export soit un instantané stable :
#   docker stop isc-core isc-pdp
#   bash tools/export_audit_chain.sh [dossier de destination, défaut ~/audit-exports]
#   docker start isc-pdp isc-core
# Même format que l'export du 2026-10-17 : seq,chain_index,payload,prev_hash,hash,written_at (CSV).
set -euo pipefail
DEST="${1:-$HOME/audit-exports}"
PG=isc-postgres
WRITER=isc-pdp

running() { docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null | grep -q true; }

if ! running "$PG"; then
  echo "[export-audit] ECHEC : $PG ne tourne pas ; PostgreSQL doit être démarré" >&2
  exit 2
fi
if running "$WRITER"; then
  echo "[export-audit] ECHEC : $WRITER tourne encore ; arrêter le rédacteur du journal d'abord (docker stop isc-core isc-pdp)" >&2
  exit 2
fi

mkdir -p "$DEST"
stamp=$(date -u +%Y-%m-%dT%H%M%SZ)
out="$DEST/audit-export-$stamp.csv"

psql_adm() { docker exec -i "$PG" psql -v ON_ERROR_STOP=1 -U isc_admin -d identishield -tA "$@"; }

echo "[export-audit] export de pdp.audit_entry -> $out"
psql_adm -c "COPY (SELECT seq, chain_index, payload, prev_hash, hash, written_at FROM pdp.audit_entry ORDER BY chain_index) TO STDOUT WITH (FORMAT csv, HEADER true)" > "$out"
n=$(($(wc -l < "$out") - 1))
sha=$(sha256sum "$out" | cut -d' ' -f1)

echo "[export-audit] vérification de la chaîne (pdp.audit_verify) et de l'ancre (pdp.audit_anchor_check)"
broken=$(psql_adm -c "SELECT count(*) FROM pdp.audit_verify() WHERE hash_mismatch OR chain_break;")
anchor=$(psql_adm -c "SELECT anchored_count || ',' || actual_count || ',' || anchored_last_seq || ',' || actual_last_seq || ',' || head_matches FROM pdp.audit_anchor_check();")
IFS=, read -r a_count r_count a_seq r_seq head_ok <<<"$anchor"

echo "=============================================================="
echo "export            : $out"
echo "entrées exportées : $n"
echo "sha256            : $sha"
echo "ruptures (verify) : $broken"
echo "ancre             : entry_count=$a_count (réel $r_count), last_seq=$a_seq (réel $r_seq), head_matches=$head_ok"
echo "=============================================================="
if [ "$broken" = "0" ] && [ "$head_ok" = "true" ] && [ "$a_count" = "$r_count" ] && [ "$a_count" = "$n" ]; then
  echo "export-audit: VERT (chaîne intacte, ancre conforme, export complet)"
  exit 0
fi
echo "export-audit: ROUGE — NE PAS migrer ni réinitialiser avant d'avoir compris" >&2
exit 1
