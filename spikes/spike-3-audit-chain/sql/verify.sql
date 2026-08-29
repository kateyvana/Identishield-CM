-- Integrity verification, two independent layers.
-- Layer A — chaining only: recompute each row's hash and check continuity.
--   Detects content change, hash change, mid deletion, reorder, forged links.
--   By construction it CANNOT detect deletion of the last record (a shorter
--   chain is still internally consistent) nor a full rewrite (step 3).
-- Layer B — anchor (audit.chain_head): a running head the app role cannot reach
--   (step 1 proved zero privileges on it). Covers last-record deletion by
--   comparing the anchored (count, last_seq, last_hash) to the table's reality.

\echo '--- LAYER A: chain-only anomalies (empty = chain internally valid) ---'
WITH v AS (
    SELECT seq, payload, prev_hash, hash, written_at,
           encode(digest(audit.canonical(seq, payload, prev_hash, written_at), 'sha256'), 'hex')
               AS recomputed,
           lag(hash) OVER (ORDER BY seq) AS prev_row_hash
    FROM audit.entry
)
SELECT seq,
       (hash <> recomputed) AS hash_mismatch,
       (COALESCE(prev_row_hash, repeat('0', 64)) <> prev_hash) AS chain_break
FROM v
WHERE hash <> recomputed
   OR COALESCE(prev_row_hash, repeat('0', 64)) <> prev_hash
ORDER BY seq;

\echo '--- LAYER B: anchor comparison (chain_head vs table) ---'
SELECT h.n_records AS anchored_count,
       (SELECT count(*) FROM audit.entry) AS actual_count,
       h.last_seq AS anchored_last_seq,
       (SELECT COALESCE(max(seq), 0) FROM audit.entry) AS actual_last_seq,
       left(h.last_hash, 12) AS anchored_head12,
       left((SELECT hash FROM audit.entry ORDER BY seq DESC LIMIT 1), 12) AS actual_head12,
       CASE
         WHEN h.n_records = (SELECT count(*) FROM audit.entry)
          AND h.last_hash = COALESCE((SELECT hash FROM audit.entry ORDER BY seq DESC LIMIT 1), repeat('0',64))
         THEN 'MATCH' ELSE 'MISMATCH (anchor detects: count or head differ)'
       END AS anchor_verdict
FROM audit.chain_head h;
