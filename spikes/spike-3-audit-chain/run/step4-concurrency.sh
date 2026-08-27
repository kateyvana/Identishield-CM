#!/usr/bin/env bash
# SPIKE-3 étape 4 — caractérisation de Q6 : sous 10 écrivains, l'ordre des seq
# (identité, assignée au début de l'INSERT) et l'ordre de chaînage (acquisition
# du verrou FOR UPDATE sur chain_head) peuvent diverger. La chaîne reste-t-elle
# UN chaînon unique et intact quand on la suit par les LIENS, pas par seq ?
set -uo pipefail
cd "$(dirname "$0")/.."
export POSTGRES_PASSWORD=${POSTGRES_PASSWORD:?}
DC="docker compose"
admin() { $DC exec -T db psql -v ON_ERROR_STOP=1 -U postgres -d spike3 "$@"; }

admin -c "ALTER TABLE audit.entry ENABLE TRIGGER chain_before_insert;" >/dev/null
admin -c "TRUNCATE audit.entry RESTART IDENTITY;" >/dev/null
admin -c "UPDATE audit.chain_head SET last_seq=0,last_hash=repeat('0',64),n_records=0 WHERE id;" >/dev/null

echo 'INSERT INTO audit.entry(payload,prev_hash,hash,written_at) VALUES ('"'"'{"d":1}'"'"','"'"''"'"','"'"''"'"',now());' \
  | $DC exec -T db tee /tmp/ins.sql >/dev/null
$DC exec -T -e PGPASSWORD=$POSTGRES_PASSWORD db pgbench -n -f /tmp/ins.sql -c 10 -j 4 -t 500 -h 127.0.0.1 -U postgres -d spike3 2>&1 | grep -E 'processed|tps'

echo "--- (a) ruptures vues en ordre SEQ (prev_hash != hash de la ligne seq précédente) ---"
admin -c "WITH v AS (SELECT seq, prev_hash, lag(hash) OVER (ORDER BY seq) AS pr FROM audit.entry)
          SELECT count(*) AS ruptures_par_seq FROM v WHERE COALESCE(pr,repeat('0',64)) <> prev_hash;"

echo "--- (b) la chaîne suivie par les LIENS est-elle UN chaînon unique et intact ? ---"
admin -c "SELECT
  (SELECT count(*) FROM audit.entry) AS total,
  (SELECT count(*) FROM audit.entry WHERE prev_hash = repeat('0',64)) AS racines_genesis,
  (SELECT count(*) FROM audit.entry e WHERE e.prev_hash <> repeat('0',64)
       AND NOT EXISTS (SELECT 1 FROM audit.entry p WHERE p.hash = e.prev_hash)) AS liens_orphelins,
  (SELECT count(DISTINCT prev_hash) FROM audit.entry) AS prev_distincts,
  (SELECT count(*) FROM audit.entry) - (SELECT count(DISTINCT prev_hash) FROM audit.entry) AS prev_dupliques;"

echo "--- (c) recompute par LIENS : toutes les empreintes sont-elles authentiques ? ---"
admin -c "SELECT count(*) AS empreintes_fausses FROM audit.entry
          WHERE hash <> encode(digest(audit.canonical(seq,payload,prev_hash,written_at),'sha256'),'hex');"
