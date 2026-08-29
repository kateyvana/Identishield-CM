-- SPIKE-3 schema. Mirrors infra/init/init.sql (pdp.audit_entry, role isc_pdp_app
-- restricted to SELECT+INSERT, table owned by admin) but in its OWN test schema.
-- The product schema is NOT touched. ADR-025: everything runs in Docker.

\set ON_ERROR_STOP on

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Adversary role: the analyst (T-04). Same shape as the product's isc_pdp_app.
DROP ROLE IF EXISTS isc_pdp_app;
CREATE ROLE isc_pdp_app LOGIN PASSWORD :'app_pw';

DROP SCHEMA IF EXISTS audit CASCADE;
CREATE SCHEMA audit;                       -- owned by postgres (admin), never by the app role
GRANT USAGE ON SCHEMA audit TO isc_pdp_app;

-- Write-only audit log (ED-06). Admin-owned; the app role never owns it.
-- Q2: the hash covers seq, payload, prev_hash and written_at (canonical UTC).
CREATE TABLE audit.entry (
    seq        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payload    jsonb       NOT NULL,
    prev_hash  text        NOT NULL,
    hash       text        NOT NULL,
    written_at timestamptz NOT NULL
);

-- Singleton head: the chaining serialisation point (Q6). Admin-owned, no grant
-- to the app role: only the SECURITY DEFINER trigger touches it.
CREATE TABLE audit.chain_head (
    id        boolean PRIMARY KEY DEFAULT true CHECK (id),
    last_seq  bigint NOT NULL,
    last_hash text   NOT NULL,
    n_records bigint NOT NULL
);
INSERT INTO audit.chain_head(id, last_seq, last_hash, n_records)
VALUES (true, 0, repeat('0', 64), 0);   -- genesis prev_hash = 64 zeros

-- Canonical string hashed for one record. jsonb::text normalises key order.
CREATE OR REPLACE FUNCTION audit.canonical(
    p_seq bigint, p_payload jsonb, p_prev text, p_at timestamptz)
RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT p_seq::text || '|' || p_payload::text || '|' || p_prev || '|' ||
           to_char(p_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
$$;

-- BEFORE INSERT chainer. SECURITY DEFINER so it can lock+update chain_head,
-- which the app role cannot reach directly. Locks the head row FOR UPDATE:
-- concurrent inserts serialise here (Q6). Overwrites any client-supplied
-- prev_hash/hash so the app cannot forge them.
CREATE OR REPLACE FUNCTION audit.chain() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = audit, public, pg_temp AS $$
DECLARE
    h_prev text;
    cnt    bigint;
BEGIN
    SELECT last_hash, n_records INTO h_prev, cnt FROM audit.chain_head WHERE id FOR UPDATE;
    NEW.prev_hash := h_prev;
    NEW.hash := encode(
        digest(audit.canonical(NEW.seq, NEW.payload, NEW.prev_hash, NEW.written_at), 'sha256'),
        'hex');
    UPDATE audit.chain_head SET last_seq = NEW.seq, last_hash = NEW.hash, n_records = cnt + 1 WHERE id;
    RETURN NEW;
END $$;

CREATE TRIGGER chain_before_insert BEFORE INSERT ON audit.entry
    FOR EACH ROW EXECUTE FUNCTION audit.chain();

-- ED-06 grant: SELECT + INSERT only. Nothing else.
GRANT SELECT, INSERT ON audit.entry TO isc_pdp_app;
GRANT USAGE ON SEQUENCE audit.entry_seq_seq TO isc_pdp_app;

-- Tentative 8 fixture: an admin-owned SECURITY DEFINER function that writes.
-- The TACHE-02 lesson: PostgreSQL grants EXECUTE to PUBLIC by default, so an
-- unguarded SECURITY DEFINER function is a privilege-elevation hole. We close
-- it explicitly; the effective-privilege query proves it is closed.
CREATE OR REPLACE FUNCTION audit.admin_tamper(p_seq bigint, p_payload jsonb)
RETURNS void LANGUAGE sql SECURITY DEFINER AS $$
    UPDATE audit.entry SET payload = p_payload WHERE seq = p_seq;
$$;
REVOKE EXECUTE ON FUNCTION audit.admin_tamper(bigint, jsonb) FROM PUBLIC;

-- Seed a few legitimate rows as admin so step 1 has targets to attack.
INSERT INTO audit.entry(payload, prev_hash, hash, written_at) VALUES
    ('{"decision":"ALLOW","actor":"pdp"}',       '', '', '2026-01-01T08:00:00Z'),
    ('{"decision":"STEP_UP","actor":"pdp"}',      '', '', '2026-01-01T08:00:01Z'),
    ('{"decision":"FREEZE","actor":"analyst-7"}', '', '', '2026-01-01T08:00:02Z');
