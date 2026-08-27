#!/usr/bin/env bash
# SPIKE-3 étape 4 — le coût, DANS la VM. Durabilité PostgreSQL par défaut
# (fsync + synchronous_commit actifs) : chaque écriture d'audit est un commit
# durable. Latence, débit, concurrence (mécanisme + coût), vérification.
set -uo pipefail
cd "$(dirname "$0")/.."

export POSTGRES_PASSWORD=${POSTGRES_PASSWORD:?}
DC="docker compose"
admin() { $DC exec -T db psql -v ON_ERROR_STOP=1 -U postgres -d spike3 "$@"; }
q() { $DC exec -T db psql -tAq -U postgres -d spike3 -c "$1"; }
xin() { $DC exec -T -e PGPASSWORD=$POSTGRES_PASSWORD db bash -c "$1"; }

reset_empty() {
  admin -c "ALTER TABLE audit.entry ENABLE TRIGGER chain_before_insert;" >/dev/null
  admin -c "TRUNCATE audit.entry RESTART IDENTITY;" >/dev/null
  admin -c "UPDATE audit.chain_head SET last_seq=0,last_hash=repeat('0',64),n_records=0 WHERE id;" >/dev/null
}

# pgbench custom script: one durable chained insert (trigger fills prev_hash/hash)
xin "cat > /tmp/insert.sql <<'SQL'
INSERT INTO audit.entry(payload, prev_hash, hash, written_at)
VALUES ('{\"decision\":\"ALLOW\"}', '', '', now());
SQL"

pctl() {  # parse pgbench --log latencies (field 3 = us) -> p50/p95/p99 + count
  xin "cat /tmp/pgbench_log.* 2>/dev/null | awk '{print \$3}' | sort -n | awk '
    {a[NR]=\$1}
    END{ if(NR==0){print \"no samples\"; exit}
      printf \"n=%d  p50=%.3f ms  p95=%.3f ms  p99=%.3f ms  max=%.3f ms\n\",
        NR, a[int(NR*0.50)]/1000, a[int(NR*0.95)]/1000, a[int(NR*0.99)]/1000, a[NR]/1000 }'"
}

echo "===== 1) LATENCE + DÉBIT — 1 écrivain, 10 000 insertions chaînées durables ====="
reset_empty
xin "cd /tmp && rm -f pgbench_log.*; pgbench -n -f /tmp/insert.sql -c 1 -j 1 -t 10000 \
      --log -h 127.0.0.1 -U postgres -d spike3 2>&1 | grep -E 'tps|latency average|number of transactions'"
echo "-- percentiles (depuis --log) --"; pctl

echo
echo "===== 2) CONCURRENCE — mécanisme de sérialisation + coût 1 vs 10 ====="
echo "--- 2a) mécanisme : preuve déterministe du verrou ---"
reset_empty
admin -c "INSERT INTO audit.entry(payload,prev_hash,hash,written_at) VALUES ('{\"x\":1}','','',now());" >/dev/null
# session A holds the chain_head row lock; session B's insert must wait on it
$DC exec -T db psql -U postgres -d spike3 -c "BEGIN; SELECT last_hash FROM audit.chain_head WHERE id FOR UPDATE; SELECT pg_sleep(3);" >/dev/null 2>&1 &
sleep 1
echo "pendant que la session A tient chain_head FOR UPDATE, un INSERT concurrent attend :"
q "SELECT wait_event_type, wait_event, left(query,40) AS q FROM pg_stat_activity WHERE state='active' AND query LIKE 'INSERT%';" >/dev/null 2>&1
( $DC exec -T db psql -U postgres -d spike3 -c "INSERT INTO audit.entry(payload,prev_hash,hash,written_at) VALUES ('{\"y\":1}','','',now());" >/dev/null 2>&1 ) &
sleep 1
q "SELECT a.wait_event_type, a.wait_event, l.mode, l.relation::regclass AS locked_rel
   FROM pg_stat_activity a
   JOIN pg_locks l ON l.pid=a.pid
   WHERE a.query LIKE 'INSERT%' AND NOT l.granted;"
wait
echo "--- 2b) coût : débit à 1 écrivain vs 10 écrivains (durée fixe 15 s) ---"
reset_empty
echo -n "1 écrivain  : "; xin "pgbench -n -f /tmp/insert.sql -c 1  -j 1 -T 15 -h 127.0.0.1 -U postgres -d spike3 2>&1 | grep -E '^tps'"
reset_empty
echo -n "10 écrivains: "; xin "pgbench -n -f /tmp/insert.sql -c 10 -j 4 -T 15 -h 127.0.0.1 -U postgres -d spike3 2>&1 | grep -E '^tps'"
echo "--- 2c) la chaîne reste-t-elle cohérente après 10 écrivains ? ---"
admin < sql/verify.sql | sed -n '/LAYER A/,/LAYER B/p' | head -4
q "SELECT 'gaps=' || count(*) FROM (SELECT seq - lag(seq) OVER (ORDER BY seq) AS d FROM audit.entry) t WHERE d <> 1;"

echo
echo "===== 3) VÉRIFICATION — complète vs incrémentale, à trois paliers ====="
for SZ in 10000 100000 500000; do
  reset_empty
  admin -c "INSERT INTO audit.entry(payload,prev_hash,hash,written_at)
            SELECT jsonb_build_object('i',g),'','', timestamptz '2026-01-01T00:00:00Z' + make_interval(secs=>g)
            FROM generate_series(1,$SZ) g;" >/dev/null
  echo "--- $SZ enregistrements ---"
  echo -n "complète     : "
  admin -c "\\timing on" -c "SELECT count(*) FROM (WITH v AS (
      SELECT seq,payload,prev_hash,hash,written_at,
             encode(digest(audit.canonical(seq,payload,prev_hash,written_at),'sha256'),'hex') AS rc,
             lag(hash) OVER (ORDER BY seq) AS pr FROM audit.entry)
      SELECT 1 FROM v WHERE hash<>rc OR COALESCE(pr,repeat('0',64))<>prev_hash) x;" 2>&1 | grep -E 'Time:'
  # incremental: verify only the tail since an anchor 1000 records back
  A=$((SZ-1000))
  echo -n "incrémentale (depuis ancre seq=$A, ANCHOR_INTERVAL=1000) : "
  admin -c "\\timing on" -c "SELECT count(*) FROM (WITH v AS (
      SELECT seq,payload,prev_hash,hash,written_at,
             encode(digest(audit.canonical(seq,payload,prev_hash,written_at),'sha256'),'hex') AS rc,
             lag(hash) OVER (ORDER BY seq) AS pr FROM audit.entry WHERE seq >= $A)
      SELECT 1 FROM v WHERE seq > $A AND (hash<>rc OR pr<>prev_hash)) x;" 2>&1 | grep -E 'Time:'
done
