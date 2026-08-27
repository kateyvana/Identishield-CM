-- Rebuild a fresh 20-record chain as ADMIN, keeping the trigger and chain_head
-- coherent. Used between alteration cases so each is isolated.
\set ON_ERROR_STOP on
ALTER TABLE audit.entry ENABLE TRIGGER chain_before_insert;
TRUNCATE audit.entry RESTART IDENTITY;
UPDATE audit.chain_head SET last_seq = 0, last_hash = repeat('0', 64), n_records = 0 WHERE id;
INSERT INTO audit.entry(payload, prev_hash, hash, written_at)
SELECT jsonb_build_object('i', g, 'decision', 'ALLOW', 'actor', 'pdp'),
       '', '',
       timestamptz '2026-01-01T08:00:00Z' + make_interval(secs => g)
FROM generate_series(1, 20) g;
