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

-- ---- Domaine MSISDN fictif (ED-09, sandbox) : plage 677000000-677009999 ----
CREATE DOMAIN core.msisdn_fictif AS text CHECK (VALUE ~ '^67700[0-9]{4}$');

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
    created_at     timestamptz NOT NULL,
    execute_at     timestamptz,        -- A3 : echeance d'une operation DEFERRED (now + DEFER), fournie par l'app
    CHECK ((state = 'DEFERRED') <= (execute_at IS NOT NULL))
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
    msisdn        core.msisdn_fictif NOT NULL,   -- numero destinataire : consultation par numero (EF-09)
    recipient_ref text NOT NULL,                 -- cible de livraison : IMSI courant (SMS) ou ref appareil (PUSH)
    channel       text NOT NULL,
    type          text NOT NULL,
    body          text NOT NULL,        -- contenu contraint (ENF-17), verifie applicativement a l'emission
    sent_at       timestamptz NOT NULL
);

-- ================= SCHEMA pdp : audit (T2-01), device et nonce (M-09) =================
-- Les autres entites du PDP (trust_state, decision, freeze...) viennent au mois 4 : ne PAS
-- les creer ici. Table d'audit reprise de SPIKE-3.
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

-- M-09 : cles d'appareil (ISC-SAD §9.2 device). Une seule cle ACTIVE par compte (ES-20),
-- garantie PAR LA BASE ; la precedente est revoquee a l'enregistrement d'une nouvelle (ES-14b).
CREATE TABLE pdp.device (
    id          text PRIMARY KEY,
    account_id  text        NOT NULL,
    public_key  text        NOT NULL,   -- SubjectPublicKeyInfo DER en base64, EC P-256 (ADR-018)
    state       text        NOT NULL CHECK (state IN ('ACTIVE', 'REVOKED')),
    enrolled_at timestamptz NOT NULL,   -- fourni par l'app (ED-04) ; aucun DEFAULT d'horloge
    revoked_at  timestamptz
);
CREATE UNIQUE INDEX device_one_active_per_account ON pdp.device (account_id) WHERE state = 'ACTIVE';

-- M-09 : nonces (ISC-SAD §9.2 nonce ; ES-10). Usage unique garanti par un UPDATE conditionnel
-- atomique (consumed_at IS NULL) : de deux presentations concurrentes, une seule consomme.
CREATE TABLE pdp.nonce (
    value       text PRIMARY KEY CHECK (value ~ '^[0-9a-f]{32}$'),
    account_id  text        NOT NULL,
    issued_at   timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,   -- issued_at + NONCE_TTL (config/ruleset.yaml)
    consumed_at timestamptz
);

-- A1 : etat de confiance du compte (ISC-SAD v1.4 §7.5, §9.2 trust_state et trust_transition ;
-- arbitrages A1-1 a A1-7 de REGISTRE_DECISIONS). Une ligne par compte qui s'est enrole ou a
-- connu un evenement ; sans ligne, le compte est TRUSTED (A1-5). LOCKED est hors du mois 4 (T-05) :
-- la contrainte le refuse. Champs ajoutes au §9.2 : current_imsi (A1-1), probation_until conserve
-- pendant un gel (A1-2), freeze_cause et freeze_until (A1-3), reenrol_frozen_until (A1-4).
CREATE TABLE pdp.trust_state (
    account_id           text        PRIMARY KEY,
    state                text        NOT NULL CHECK (state IN ('TRUSTED', 'PROBATION', 'FROZEN')),
    since                timestamptz NOT NULL,   -- fourni par l'app (ED-04) ; aucun DEFAULT d'horloge
    reference_imsi       text,                   -- glossaire SRS v1.5 ; A1-1, A1-5, A1-6
    current_imsi         text,                   -- dernier IMSI notifie
    probation_until      timestamptz,            -- echeance CAR_IMSI d'origine (A1-2)
    freeze_cause         text CHECK (freeze_cause IN ('FREEZE_DECISION', 'NOT_ME')),
    freeze_until         timestamptz,            -- TTL_FREEZE ; jamais pour NOT_ME (A1-3)
    reenrol_frozen_until timestamptz,            -- REENROL_FREEZE (A1-4)
    probation_entered_at timestamptz,            -- A4 : instant d'entree en PROBATION (fenetre CAP_PROB)
    probation_entry_balance_xaf bigint CHECK (probation_entry_balance_xaf >= 0),  -- A4 : solde fige a l'entree (CAP_PROB, ED-05)
    CHECK ((state = 'FROZEN') = (freeze_cause IS NOT NULL)),
    CHECK ((freeze_cause IS NOT DISTINCT FROM 'FREEZE_DECISION') = (freeze_until IS NOT NULL)),
    CHECK ((state = 'PROBATION') <= (probation_until IS NOT NULL)),
    CHECK (state <> 'PROBATION' OR probation_entered_at IS NOT NULL),
    CHECK (state <> 'PROBATION' OR probation_entry_balance_xaf IS NOT NULL)
);

-- Historique des transitions (EF-13 : chaque transition avec sa cause), en ecriture seule pour le
-- role applicatif ; chaque ligne a sa jumelle dans le journal d'audit chaine (A1-7).
CREATE TABLE pdp.trust_transition (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  text        NOT NULL,
    from_state  text        NOT NULL,
    to_state    text        NOT NULL,
    event       text        NOT NULL CHECK (event IN ('TRUST_PROBATION_ENTERED',
                    'TRUST_PROBATION_EXPIRED', 'TRUST_FROZEN', 'TRUST_UNFROZEN')),
    cause       text        NOT NULL CHECK (cause IN ('IMSI_CHANGE', 'CAR_IMSI_EXPIRED',
                    'FREEZE_DECISION', 'NOT_ME', 'TTL_FREEZE_EXPIRED', 'RELEASE')),
    occurred_at timestamptz NOT NULL    -- instant de l'evenement ou de l'echeance (ADR-019)
);
CREATE INDEX trust_transition_account ON pdp.trust_transition (account_id, id);

-- ======================= Privileges (ISC-SAD §10.1) =======================
-- core : le service coeur gere ses propres entites.
GRANT USAGE ON SCHEMA core TO isc_core_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA core TO isc_core_app;

-- ADR-032 : historique HLR en ecriture seule AU NIVEAU BASE (constat T2-10). Le role
-- applicatif ne peut qu'INSERT et SELECT sur hlr_reassignment -- ni UPDATE ni DELETE :
-- l'historique des reassignations (preuve a posteriori d'un SIM swap) ne depend plus de la
-- seule discipline applicative. account/transaction/hlr_subscriber gardent leurs droits.
REVOKE UPDATE, DELETE ON core.hlr_reassignment FROM isc_core_app;

-- pdp : l'audit en SELECT + INSERT seulement (ED-06) ; device et nonce en SELECT, INSERT,
-- UPDATE (revocation, consommation), jamais DELETE (M-09).
GRANT USAGE ON SCHEMA pdp TO isc_pdp_app;
GRANT SELECT, INSERT ON pdp.audit_entry TO isc_pdp_app;
GRANT SELECT, INSERT, UPDATE ON pdp.device, pdp.nonce TO isc_pdp_app;
GRANT USAGE ON SEQUENCE pdp.audit_entry_seq_seq TO isc_pdp_app;  -- USAGE sans UPDATE => setval refuse
-- Aucun GRANT sur pdp.chain_head : le role applicatif n'y accede pas.
-- A1 : etat de confiance en SELECT, INSERT, UPDATE (jamais DELETE) ; historique des transitions
-- en SELECT + INSERT seulement : le role applicatif ne peut ni le reecrire ni l'effacer.
GRANT SELECT, INSERT, UPDATE ON pdp.trust_state TO isc_pdp_app;
GRANT SELECT, INSERT ON pdp.trust_transition TO isc_pdp_app;
GRANT USAGE ON SEQUENCE pdp.trust_transition_id_seq TO isc_pdp_app;

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

-- A4 : historique d'un compte relu depuis le journal du PDP (decisions ALLOW du compte, dans
-- l'ordre de la chaine). Index d'EXPRESSION sur accountId : sans lui, chaque /evaluate parcourt
-- toute la table.
CREATE INDEX audit_entry_account ON pdp.audit_entry ((payload ->> 'accountId'), chain_index);

-- ================= T2-06 : verification du journal d'audit =================
-- Reprend SPIKE-3 (verify.sql) / ADR-020 en production. DEUX couches independantes.
-- CORRECTION de production (Q6) : la couche A ordonne par chain_index (l'ordre
-- assigne DANS le verrou), PAS par seq -- sous concurrence seq != ordre de chaine
-- (4589 fausses ruptures / 5000 sinon). Fonctions possedees par l'admin, EXECUTE
-- revoque a PUBLIC (OB-3) : la verification est operationnelle, pas exposee au
-- role applicatif. L'ancre SIGNEE (Ed25519) est au mois 4 (ES-09 partiel).

-- Couche A -- chainage seul : recalcule chaque empreinte et verifie la continuite.
-- Detecte ET LOCALISE (au chain_index) : alteration de contenu, d'empreinte,
-- suppression du milieu, reordonnancement, lien forge. p_from > 0 => verification
-- INCREMENTALE depuis un index (l'entree limite est reliee a p_from-1). Ne voit ni
-- la suppression du dernier enregistrement ni la reecriture complete (couche B / ancre).
CREATE FUNCTION pdp.audit_verify(p_from bigint DEFAULT 0)
RETURNS TABLE(chain_index bigint, hash_mismatch boolean, chain_break boolean)
LANGUAGE sql STABLE AS $$
    WITH boundary AS (
        SELECT CASE WHEN p_from <= 0 THEN repeat('0', 64)
                    ELSE COALESCE(
                        (SELECT e.hash FROM pdp.audit_entry e WHERE e.chain_index = p_from - 1),
                        repeat('0', 64))
               END AS prev_hash
    ), v AS (
        SELECT e.chain_index, e.prev_hash, e.hash,
               encode(digest(
                   pdp.audit_canonical(e.chain_index, e.seq, e.payload, e.prev_hash, e.written_at),
                   'sha256'), 'hex') AS recomputed,
               lag(e.hash) OVER (ORDER BY e.chain_index) AS prev_row_hash
        FROM pdp.audit_entry e WHERE e.chain_index >= p_from
    )
    SELECT v.chain_index,
           (v.hash <> v.recomputed) AS hash_mismatch,
           (COALESCE(v.prev_row_hash, (SELECT prev_hash FROM boundary)) <> v.prev_hash) AS chain_break
    FROM v
    WHERE (v.hash <> v.recomputed)
       OR (COALESCE(v.prev_row_hash, (SELECT prev_hash FROM boundary)) <> v.prev_hash)
    ORDER BY v.chain_index;
$$;

-- Couche B -- ancre chain_head : la tete (que le role applicatif ne peut atteindre)
-- comparee a la realite de la table. Couvre la suppression du DERNIER enregistrement,
-- que la couche A ne voit pas (une chaine plus courte reste coherente).
CREATE FUNCTION pdp.audit_anchor_check()
RETURNS TABLE(anchored_count bigint, actual_count bigint,
              anchored_last_seq bigint, actual_last_seq bigint, head_matches boolean)
LANGUAGE sql STABLE AS $$
    SELECT h.entry_count,
           (SELECT count(*) FROM pdp.audit_entry),
           h.last_seq,
           (SELECT COALESCE(max(e.seq), 0) FROM pdp.audit_entry e),
           (h.entry_count = (SELECT count(*) FROM pdp.audit_entry)
            AND h.head_hash = COALESCE(
                (SELECT e.hash FROM pdp.audit_entry e ORDER BY e.chain_index DESC LIMIT 1),
                repeat('0', 64)))
    FROM pdp.chain_head h;
$$;

-- OB-3 : verification non exposee au role applicatif (admin seul l'execute).
REVOKE EXECUTE ON FUNCTION pdp.audit_verify(bigint) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION pdp.audit_anchor_check() FROM PUBLIC;

-- ===== T2-19 (EF-34) : affichage de l'integrite du journal par la console =====
-- La console (via le coeur) AFFICHE la verification d'integrite REELLE de T2-06 ; elle ne la
-- reecrit pas. Le coeur est un verificateur INDEPENDANT : il n'ECRIT PAS le journal (isc_pdp_app
-- en est le SEUL redacteur). Son role isc_core_app recoit un acces STRICTEMENT EN LECTURE pour
-- executer les fonctions existantes -- SELECT + EXECUTE seulement, AUCUN droit d'ecriture :
-- l'inalterabilite du journal est preservee. OB-3 (aucune fonction pdp executable par le REDACTEUR
-- isc_pdp_app) reste intacte -- la porte check-stack ne vise QUE isc_pdp_app.
GRANT USAGE ON SCHEMA pdp TO isc_core_app;
GRANT SELECT ON pdp.audit_entry TO isc_core_app;
GRANT SELECT ON pdp.chain_head TO isc_core_app;
GRANT EXECUTE ON FUNCTION pdp.audit_verify(bigint) TO isc_core_app;
GRANT EXECUTE ON FUNCTION pdp.audit_anchor_check() TO isc_core_app;
-- audit_verify (fonction SQL, pas SECURITY DEFINER) s'execute avec les privileges de l'APPELANT ;
-- elle appelle audit_canonical (recalcul de l'empreinte), aussi revoquee de PUBLIC. Le lecteur doit
-- donc l'executer aussi. Lecture seule (pur calcul de hachage), aucune ecriture.
GRANT EXECUTE ON FUNCTION pdp.audit_canonical(bigint, bigint, jsonb, text, timestamptz) TO isc_core_app;
