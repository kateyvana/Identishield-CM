-- SPIKE-5 schema (test only; infra/ not touched). pdp.decision + pdp.audit_entry
-- chained (SPIKE-3 design, ADR-020): order index assigned INSIDE the chain_head
-- lock (obligation 5). ES-09 write cost is what the throughput measures.
\set ON_ERROR_STOP on
CREATE EXTENSION IF NOT EXISTS pgcrypto;
DROP SCHEMA IF EXISTS pdp CASCADE;
CREATE SCHEMA pdp;

CREATE TABLE pdp.decision (
    id       uuid        PRIMARY KEY,
    account  text        NOT NULL,
    decision text        NOT NULL,
    reason   text        NOT NULL,
    at       timestamptz NOT NULL
);

CREATE TABLE pdp.audit_entry (
    seq         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    chain_index bigint      NOT NULL,          -- assigned in the lock (ADR-020 §5)
    payload     jsonb       NOT NULL,
    prev_hash   text        NOT NULL,
    hash        text        NOT NULL,
    written_at  timestamptz NOT NULL
);

CREATE TABLE pdp.chain_head (
    id         boolean PRIMARY KEY DEFAULT true CHECK (id),
    last_index bigint NOT NULL,
    last_hash  text   NOT NULL,
    n_records  bigint NOT NULL
);
INSERT INTO pdp.chain_head VALUES (true, 0, repeat('0', 64), 0);

CREATE OR REPLACE FUNCTION pdp.chain() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pdp, public, pg_temp AS $$
DECLARE h_prev text; idx bigint;
BEGIN
    SELECT last_hash, n_records INTO h_prev, idx FROM pdp.chain_head WHERE id FOR UPDATE;
    NEW.chain_index := idx + 1;                -- order index inside the lock
    NEW.prev_hash := h_prev;
    NEW.hash := encode(digest(
        NEW.chain_index::text || '|' || NEW.payload::text || '|' || NEW.prev_hash || '|' ||
        to_char(NEW.written_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), 'sha256'), 'hex');
    UPDATE pdp.chain_head SET last_index = NEW.chain_index, last_hash = NEW.hash, n_records = idx + 1 WHERE id;
    RETURN NEW;
END $$;

CREATE TRIGGER chain_bi BEFORE INSERT ON pdp.audit_entry
    FOR EACH ROW EXECUTE FUNCTION pdp.chain();
