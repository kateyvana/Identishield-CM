-- TACHE-00 tache 3 - schemas, roles, table d'audit (ED-06, ISC-SAD §9)
-- Les mots de passe arrivent en variables psql (:'core_pw', :'pdp_pw') ; aucun secret ici.

CREATE ROLE isc_core_app LOGIN PASSWORD :'core_pw';
CREATE ROLE isc_pdp_app  LOGIN PASSWORD :'pdp_pw';

-- Un schema par service ; aucune table partagee (ISC-SAD §9.3, C-07)
CREATE SCHEMA core;
CREATE SCHEMA pdp;

GRANT USAGE, CREATE ON SCHEMA core TO isc_core_app;
GRANT USAGE ON SCHEMA pdp TO isc_pdp_app;

-- Table d'audit en ecriture seule (ED-06). Possedee par l'administrateur,
-- jamais par le role applicatif : un proprietaire contournerait les GRANT.
-- ED-04 : written_at est fourni par l'application via Clock ; aucun DEFAULT d'horloge base.
CREATE TABLE pdp.audit_entry (
    seq        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payload    jsonb       NOT NULL,
    prev_hash  text        NOT NULL,
    hash       text        NOT NULL,
    written_at timestamptz NOT NULL
);

-- ED-06 : SELECT et INSERT, rien d'autre. Aucun UPDATE, DELETE, TRUNCATE.
GRANT SELECT, INSERT ON pdp.audit_entry TO isc_pdp_app;
GRANT USAGE ON SEQUENCE pdp.audit_entry_seq_seq TO isc_pdp_app;
