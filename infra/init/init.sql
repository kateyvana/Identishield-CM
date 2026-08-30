-- T2-01 — Schemas et roles de production (ISC-SAD v1.1 §9 vue donnees, §10.1
-- privileges, §1bis obligations). Applique par 01-apply.sh en tant que
-- isc_admin (POSTGRES_USER, proprietaire des objets). Mots de passe des roles
-- applicatifs via variables psql (:'core_pw', :'pdp_pw'), jamais versionnes.
--
-- Regles gravees ici : un schema par service, aucune table partagee, aucune
-- cle etrangere inter-schemas (ED-01). Aucun DEFAULT d'horloge : les
-- horodatages viennent de l'application (ED-04). Montants en entiers (ED-05).
-- Identifiants UUID v4, MSISDN dans la plage fictive (ED-09). Journal d'audit
-- en INSERT seul (ED-06). Obligations OB-2, OB-3, OB-4. Aucune donnee inseree.
\set ON_ERROR_STOP on

-- ---- Roles applicatifs (isc_admin = proprietaire, deja cree comme POSTGRES_USER) ----
CREATE ROLE isc_core_app LOGIN PASSWORD :'core_pw';
CREATE ROLE isc_pdp_app  LOGIN PASSWORD :'pdp_pw';

-- ---- Schemas : un par service ; aucune table partagee (ED-01, C-07) ----
CREATE SCHEMA core;
CREATE SCHEMA pdp;

-- ---- Domaine MSISDN fictif (ED-09, sandbox) : plage 699000000-699009999 ----
CREATE DOMAIN core.msisdn_fictif AS text CHECK (VALUE ~ '^69900[0-9]{4}$');

-- ======================= SCHEMA core (ISC-SAD §9.1) =======================
CREATE TABLE core.account (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),   -- UUID v4 (ED-09)
    msisdn      core.msisdn_fictif NOT NULL UNIQUE,
    balance_xaf bigint NOT NULL DEFAULT 0 CHECK (balance_xaf >= 0),  -- entier FCFA (ED-05)
    created_at  timestamptz NOT NULL                          -- fourni par l'app (ED-04)
);

CREATE TABLE core.hlr_subscriber (
    msisdn       core.msisdn_fictif PRIMARY KEY,
    current_imsi text NOT NULL,
    updated_at   timestamptz NOT NULL
);

-- Historique immuable des reassignations ; source de l'evenement de changement d'IMSI.
CREATE TABLE core.hlr_reassignment (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    msisdn      core.msisdn_fictif NOT NULL,
    old_imsi    text,
    new_imsi    text NOT NULL,
    agent_id    text NOT NULL,
    reason      text NOT NULL,
    occurred_at timestamptz NOT NULL
);

CREATE TABLE core.transaction (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id     uuid NOT NULL REFERENCES core.account(id),   -- FK intra-schema uniquement
    type           text NOT NULL,
    amount_xaf     bigint NOT NULL CHECK (amount_xaf > 0),       -- entier FCFA (ED-05)
    beneficiary_id text,
    channel        text NOT NULL,
    state          text NOT NULL,                               -- machine a etats (§7.4)
    decision_id    uuid,               -- refere la decision du PDP ; PAS de FK inter-schema
    created_at     timestamptz NOT NULL
);

CREATE TABLE core.ussd_session (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    msisdn     core.msisdn_fictif NOT NULL,
    step       text NOT NULL,
    payload    jsonb,
    expires_at timestamptz NOT NULL
);

CREATE TABLE core.otp (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    msisdn      core.msisdn_fictif NOT NULL,
    target_imsi text NOT NULL,          -- emis vers l'IMSI courant (EF-03)
    code_hash   text NOT NULL,          -- EMPREINTE, jamais l'OTP en clair (decision T2-12)
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz
);

CREATE TABLE core.notification (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_ref text NOT NULL,
    channel       text NOT NULL,
    type          text NOT NULL,
    body          text NOT NULL,        -- contenu contraint (ENF-17), verifie applicativement
    sent_at       timestamptz NOT NULL
);

-- ================= SCHEMA pdp : audit seul au mois 2 (T2-01) =================
-- Le reste des entites du PDP (device, trust_state, decision, freeze, nonce...)
-- vient au mois 4 : ne PAS les creer ici. Table d'audit reprise de SPIKE-3.
CREATE TABLE pdp.audit_entry (
    seq         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,   -- OB-4 : insertion « entre deux » impossible
    chain_index bigint      NOT NULL,   -- OB-2 : assigne DANS le verrou par T2-06 ; cree des maintenant (forme finale)
    payload     jsonb       NOT NULL,
    prev_hash   text        NOT NULL,
    hash        text        NOT NULL,
    written_at  timestamptz NOT NULL    -- fourni par l'app (ED-04) ; aucun DEFAULT d'horloge
);

-- Tete de chaine : maintenue UNIQUEMENT par le declencheur (T2-06). Le role
-- applicatif n'y a AUCUN acces : c'est elle qui detecte la suppression du
-- dernier enregistrement, que le chainage seul ne voit pas (SPIKE-3).
CREATE TABLE pdp.chain_head (
    id          boolean PRIMARY KEY DEFAULT true CHECK (id),   -- singleton
    last_seq    bigint NOT NULL,
    head_hash   text   NOT NULL,
    entry_count bigint NOT NULL
);
INSERT INTO pdp.chain_head (id, last_seq, head_hash, entry_count)
VALUES (true, 0, repeat('0', 64), 0);   -- genese

-- ======================= Privileges (ISC-SAD §10.1) =======================
-- core : le service coeur gere ses propres entites.
GRANT USAGE ON SCHEMA core TO isc_core_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA core TO isc_core_app;

-- pdp : le role applicatif ne touche QUE l'audit, en SELECT + INSERT (ED-06).
GRANT USAGE ON SCHEMA pdp TO isc_pdp_app;
GRANT SELECT, INSERT ON pdp.audit_entry TO isc_pdp_app;
GRANT USAGE ON SEQUENCE pdp.audit_entry_seq_seq TO isc_pdp_app;  -- USAGE sans UPDATE => setval refuse
-- Aucun GRANT sur pdp.chain_head : le role applicatif n'y accede pas.

-- OB-3 : aucune fonction du schema pdp executable par PUBLIC. Defense contre une
-- fonction SECURITY DEFINER exposee par le defaut PUBLIC de PostgreSQL —
-- troisieme occurrence d'un privilege PUBLIC invisible dans les GRANT.
-- (1) Fonctions EXISTANTES (aucune au mois 2 ; la premiere vient avec T2-06) :
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA pdp FROM PUBLIC;
-- (2) Fonctions FUTURES. MESURE T2-01 : en PostgreSQL 16, l'ALTER DEFAULT
-- PRIVILEGES ci-dessous NE suffit PAS — une fonction creee ensuite garde
-- proacl=NULL (le defaut PUBLIC, donc executable par isc_pdp_app). La garantie
-- REELLE est un REVOKE EXECUTE ... FROM PUBLIC EXPLICITE sur CHAQUE fonction
-- (des T2-06 pour le declencheur de chainage), que la porte check-stack fait
-- respecter (elle echoue si une fonction de pdp est executable par le role
-- applicatif). On conserve l'ALTER (correct sur d'autres versions, inoffensif),
-- mais il ne doit pas etre notre seule ligne de defense.
ALTER DEFAULT PRIVILEGES FOR ROLE isc_admin IN SCHEMA pdp REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- ================= T2-05 : chainage in-lock du journal d'audit =================
-- Reprend le dispositif MESURE en SPIKE-3 / ADR-020 (FERME) en code de production.
-- Le role applicatif ne fait qu'INSERT (payload, written_at) ; un trigger BEFORE
-- INSERT SECURITY DEFINER -- la SEULE chose qui touche chain_head -- assigne
-- l'index d'ordre DANS LE VERROU (OB-2), pose prev_hash et calcule hash, et
-- ecrase tout chain_index/prev_hash/hash fourni par le client (l'app ne peut pas
-- forger la chaine). La VERIFICATION (complete + incrementale) est T2-06.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Empreinte d'un enregistrement. Couvre l'ordre de chaine (chain_index) ET
-- l'identite (seq), le contenu, le lien et l'horodatage UTC (ADR-020 ob4 + Q6 :
-- sous concurrence seq != ordre, donc l'ordre authoritatif est chain_index).
CREATE FUNCTION pdp.audit_canonical(
    p_chain_index bigint, p_seq bigint, p_payload jsonb, p_prev text, p_at timestamptz)
RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT p_chain_index::text || '|' || p_seq::text || '|' || p_payload::text || '|' ||
           p_prev || '|' ||
           to_char(p_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
$$;

-- Declencheur de chainage. SECURITY DEFINER : il verrouille et met a jour
-- chain_head, hors de portee du role applicatif. Le verrou de ligne FOR UPDATE
-- serialise les insertions concurrentes -- c'est LE point de serialisation.
CREATE FUNCTION pdp.audit_chain() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pdp, public, pg_temp AS $$
DECLARE
    h_prev text;
    cnt    bigint;
BEGIN
    SELECT head_hash, entry_count INTO h_prev, cnt FROM pdp.chain_head WHERE id FOR UPDATE;
    NEW.chain_index := cnt;               -- index d'ordre assigne DANS le verrou (OB-2)
    NEW.prev_hash   := h_prev;            -- ecrase toute valeur fournie par le client
    NEW.hash        := encode(
        digest(pdp.audit_canonical(NEW.chain_index, NEW.seq, NEW.payload, NEW.prev_hash, NEW.written_at), 'sha256'),
        'hex');
    UPDATE pdp.chain_head SET last_seq = NEW.seq, head_hash = NEW.hash, entry_count = cnt + 1 WHERE id;
    RETURN NEW;
END $$;

CREATE TRIGGER audit_chain_before_insert BEFORE INSERT ON pdp.audit_entry
    FOR EACH ROW EXECUTE FUNCTION pdp.audit_chain();

-- OB-3 : les deux fonctions ne sont executables par PERSONNE via PUBLIC (le
-- trigger s'execute hors ACL EXECUTE ; le REVOKE ci-dessus sur ALL FUNCTIONS
-- s'appliquait AVANT leur creation, d'ou ce REVOKE explicite -- MESURE T2-01).
REVOKE EXECUTE ON FUNCTION pdp.audit_canonical(bigint, bigint, jsonb, text, timestamptz) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION pdp.audit_chain() FROM PUBLIC;

-- ENF-03 : idempotence par decisionId. Au PLUS une entree d'audit par decisionId
-- (le payload porte decisionId). L'index unique rend le doublon atomiquement
-- impossible ; l'app rattrape la violation et renvoie la decision deja ecrite.
CREATE UNIQUE INDEX audit_entry_decision_id_uk ON pdp.audit_entry ((payload ->> 'decisionId'));
