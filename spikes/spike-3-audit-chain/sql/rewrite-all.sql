-- The sophisticated admin attacker: after modifying any record, recompute EVERY
-- hash from the genesis forward so the chain is valid end-to-end again, and
-- update chain_head to match. This defeats BOTH verification layers of step 2.
-- (The chain trigger is BEFORE INSERT only, so these UPDATEs do not fire it.)
\set ON_ERROR_STOP on
DO $$
DECLARE
    r  record;
    ph text := repeat('0', 64);
    h  text;
    c  bigint := 0;
BEGIN
    FOR r IN SELECT seq, payload, written_at FROM audit.entry ORDER BY seq LOOP
        h := encode(digest(audit.canonical(r.seq, r.payload, ph, r.written_at), 'sha256'), 'hex');
        UPDATE audit.entry SET prev_hash = ph, hash = h WHERE seq = r.seq;
        ph := h;
        c := c + 1;
    END LOOP;
    UPDATE audit.chain_head
       SET last_seq = (SELECT max(seq) FROM audit.entry), last_hash = ph, n_records = c
     WHERE id;
END $$;
