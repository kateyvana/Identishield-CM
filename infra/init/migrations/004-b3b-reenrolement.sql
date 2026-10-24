-- 004 — B3b, couloir B (ADR-041 amendement du 2026-10-17, ADR-030 ; decisions de la proprietaire du
-- 2026-10-24) : reenrolement (flux D). Numero attribue par le couloir A ; independante de 005 (B4).
-- Une seule transaction ; chaque instruction est rejouable (infra/init/migrations/README.md).
BEGIN;

-- core : l'enrolement d'un compte deja lie commence a l'etape RECOVERY (code de recuperation a
-- fournir), puis BIND -> CONFIRM -> DONE comme le premier enrolement. La contrainte d'origine est
-- ecrite en ligne dans la 002, donc nommee par PostgreSQL (enrolment_stage_check) : si une base la
-- porte sous un autre nom, DROP ... IF EXISTS ne fait rien et l'ancienne refuserait RECOVERY ; la
-- verification qui suit l'interdit (README, regle 4).
ALTER TABLE core.enrolment DROP CONSTRAINT IF EXISTS enrolment_stage_check;
ALTER TABLE core.enrolment ADD CONSTRAINT enrolment_stage_check
    CHECK (stage IN ('OTP', 'RECOVERY', 'BIND', 'CONFIRM', 'DONE'));

DO $$
DECLARE
    others text;
BEGIN
    SELECT string_agg(conname || ' : ' || pg_get_constraintdef(oid), ' ; ')
      INTO others
      FROM pg_constraint
     WHERE conrelid = 'core.enrolment'::regclass
       AND contype = 'c'
       AND conname <> 'enrolment_stage_check'
       AND pg_get_constraintdef(oid) ~ '\mstage\M';
    IF others IS NOT NULL THEN
        RAISE EXCEPTION 'migration 004 : contrainte CHECK sur core.enrolment.stage hors du nom attendu (enrolment_stage_check) : %', others;
    END IF;
END
$$;

-- pdp : comptage des echecs de reenrolement (ADR-030, ES-21, ES-24), la ou le code est verifie
-- (ADR-041 : le compteur est AUTORITAIRE cote PDP). Une ligne par cle comptee :
--   ORIGIN       : adresse reseau de l'appelant      -> delai croissant (LOCK_BASE double, LOCK_MAX) ;
--   INSTALLATION : identifiant d'installation de l'app -> idem (etiquette, pas un secret) ;
--   ACCOUNT      : compte vise                        -> COMPTE SEULEMENT, jamais bloque (ES-21),
--                  pour l'alerte au-dela d'ALERT_REENROL (ES-24).
CREATE TABLE IF NOT EXISTS pdp.enrolment_attempt (
    kind         text        NOT NULL CHECK (kind IN ('ORIGIN', 'INSTALLATION', 'ACCOUNT')),
    key          text        NOT NULL,
    failures     integer     NOT NULL DEFAULT 0 CHECK (failures >= 0),
    locked_until timestamptz,            -- NULL : pas de blocage en cours
    updated_at   timestamptz NOT NULL,   -- fourni par l'app (ED-04) ; aucun DEFAULT d'horloge
    PRIMARY KEY (kind, key)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON pdp.enrolment_attempt TO isc_pdp_app;

COMMIT;
