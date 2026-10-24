-- 002 — B3a, couloir B (ADR-041) : cycle de vie de la cle d'appareil, code de recuperation (pdp) ;
-- enrolement, PIN et session (core). Sections sorties d'init.sql : une base neuve et une base
-- existante recoivent le meme schema (infra/init/migrations/README.md).
-- Une seule transaction : une erreur, y compris la verification des contraintes ci-dessous, annule
-- TOUTE la migration. Chaque instruction est rejouable (README, regle 2). Aucun index CONCURRENTLY :
-- il est interdit dans une transaction.
BEGIN;

-- ADR-041 §1 : trois etats. EN ATTENTE (PENDING) a /device/register ; ACTIVE seulement a
-- /device/activate, apres confirmation du code (invariant D4). L'index unique partiel
-- device_one_active_per_account (M-09) continue de garantir une seule cle ACTIVE par compte (ES-20) ;
-- une cle EN ATTENTE n'est jamais lue par DeviceRegistry.findActive : elle ne signe rien.
-- La contrainte d'origine est ecrite en ligne dans init.sql, donc nommee par PostgreSQL
-- (device_state_check). Si une base la porte sous un autre nom, DROP ... IF EXISTS ne fait rien et
-- l'ancienne contrainte refuserait PENDING : la verification qui suit l'interdit.
ALTER TABLE pdp.device DROP CONSTRAINT IF EXISTS device_state_check;
ALTER TABLE pdp.device ADD CONSTRAINT device_state_check
    CHECK (state IN ('PENDING', 'ACTIVE', 'REVOKED'));

-- Verification, dans la meme transaction, sur les noms REELS du catalogue : aucune autre contrainte
-- CHECK de pdp.device ne porte sur state. Sinon la migration echoue en nommant la contrainte, et
-- rien n'est applique : elle se retire a la main, apres examen, jamais devinee ici.
DO $$
DECLARE
    others text;
BEGIN
    SELECT string_agg(conname || ' : ' || pg_get_constraintdef(oid), ' ; ')
      INTO others
      FROM pg_constraint
     WHERE conrelid = 'pdp.device'::regclass
       AND contype = 'c'
       AND conname <> 'device_state_check'
       AND pg_get_constraintdef(oid) ~ '\mstate\M';
    IF others IS NOT NULL THEN
        RAISE EXCEPTION 'migration 002 : contrainte CHECK sur pdp.device.state hors du nom attendu (device_state_check) : %', others;
    END IF;
END
$$;

-- ADR-041 §5, ES-18 : code de recuperation produit par le PDP ; seule une empreinte PBKDF2 a sel
-- unique est conservee, jamais le clair. Les iterations sont stockees par ligne : elles pourront
-- changer sans invalider les codes deja emis (ADR-042).
CREATE TABLE IF NOT EXISTS pdp.recovery_code (
    device_id   text PRIMARY KEY REFERENCES pdp.device (id),
    code_hash   text        NOT NULL,   -- PBKDF2-HMAC-SHA256, base64
    salt        text        NOT NULL,   -- aleatoire, propre a ce code, base64
    iterations  integer     NOT NULL CHECK (iterations > 0),
    issued_at   timestamptz NOT NULL    -- fourni par l'app (ED-04) ; aucun DEFAULT d'horloge
);
GRANT SELECT, INSERT ON pdp.recovery_code TO isc_pdp_app;

-- Au plus UNE cle EN ATTENTE par compte, garantie PAR LA BASE (decision de la proprietaire du
-- 2026-10-21) : index partiel jumeau de device_one_active_per_account. Sans lui, chaque reponse de
-- /device/register perdue laisserait une cle EN ATTENTE de plus, et /device/activate devrait choisir
-- celle qu'active le code de recuperation de l'usager. /device/register REVOQUE d'abord toute cle
-- EN ATTENTE du compte (DeviceRegistry.registerPending).
CREATE UNIQUE INDEX IF NOT EXISTS device_one_pending_per_account
    ON pdp.device (account_id) WHERE state = 'PENDING';

-- Schema core (ADR-041 §6, ED-01) : enrolement, PIN, sessions. Aucune reference au schema pdp.
-- PIN applicatif : empreinte PBKDF2-HMAC-SHA256 a sel aleatoire, jamais le PIN (carte B3a, decision 2).
ALTER TABLE core.account
    ADD COLUMN IF NOT EXISTS pin_hash       text,       -- base64 ; NULL = aucun PIN pose
    ADD COLUMN IF NOT EXISTS pin_salt       text,       -- base64, propre au compte
    ADD COLUMN IF NOT EXISTS pin_iterations integer CHECK (pin_iterations > 0);

-- Enrolement en cours : OTP (code envoye) -> BIND (OTP valide, defi d'attestation emis) -> CONFIRM
-- (cle EN ATTENTE au PDP, code de recuperation emis) -> DONE (CONSOMME : confirmation reussie, ou
-- devance par une autre liaison ; toute reprise recoit 410). Expire a created_at + ENROL_TTL.
CREATE TABLE IF NOT EXISTS core.enrolment (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),   -- UUID v4 (ED-09)
    account_id            uuid        NOT NULL REFERENCES core.account (id),
    stage                 text        NOT NULL CHECK (stage IN ('OTP', 'BIND', 'CONFIRM', 'DONE')),
    otp_failures          integer     NOT NULL DEFAULT 0 CHECK (otp_failures >= 0),
    attestation_challenge text,       -- base64, emis a la validation de l'OTP (ES-25)
    device_id             text,       -- cle EN ATTENTE enregistree au PDP (identifiant seul)
    expires_at            timestamptz NOT NULL,
    created_at            timestamptz NOT NULL    -- fourni par l'app (ED-04)
);

-- Session minimale (carte B3a, decision 4) : SEULE l'empreinte SHA-256 du jeton est conservee,
-- jamais le jeton ; echeance fixe APP_SESSION_TTL, aucun autre minuteur (carte B4).
CREATE TABLE IF NOT EXISTS core.session (
    token_hash  text PRIMARY KEY,   -- SHA-256 hexadecimal du jeton
    account_id  uuid        NOT NULL REFERENCES core.account (id),
    device_id   text        NOT NULL,
    expires_at  timestamptz NOT NULL,
    created_at  timestamptz NOT NULL
);
GRANT SELECT, INSERT, UPDATE, DELETE ON core.enrolment, core.session TO isc_core_app;

COMMIT;
