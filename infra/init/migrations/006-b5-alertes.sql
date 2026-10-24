-- 006 — B5, couloir B (flux F, ISC-MOB-SPEC §9 ; ES-27, ES-28, EF-47) : alertes de securite du
-- compte, ecrites par le coeur et lues par le telephone lie a travers sa session. Le coeur est la
-- source des evenements (HLR, enrolement, virement) ; l'EFFET de « ce n'est pas moi » (FROZEN,
-- ré-enrolements geles) est un acte du PDP (machine A1), jamais de cette table.
--   - type     : SIM_CHANGED | REENROL_ATTEMPT | DEVICE_REBOUND | TRANSFER_DEFERRED | TRANSFER_FROZEN
--   - actions  : vrai = reponse binaire signee attendue (ACK / NOT_ME) ; faux = informative (EF-47)
--   - status   : OPEN (attend une reponse) | INFO (informative, jamais repondue) | ACKNOWLEDGED | NOT_ME
--   - summary  : texte d'ecran, jamais de lien, de code ni de demande de saisie (ES-28)
-- Numero 006 : propose au couloir A le 2026-10-24 (005 = B4). Une seule transaction ; chaque
-- instruction est rejouable (infra/init/migrations/README.md). Aucune contrainte redefinie.
BEGIN;

CREATE TABLE IF NOT EXISTS core.alert (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  uuid NOT NULL REFERENCES core.account(id),   -- FK intra-schema uniquement
    type        text NOT NULL,
    summary     text NOT NULL,
    actionable  boolean NOT NULL,
    status      text NOT NULL,
    created_at  timestamptz NOT NULL,                         -- fourni par l'app (ED-04)
    answered_at timestamptz,
    CHECK (type IN ('SIM_CHANGED', 'REENROL_ATTEMPT', 'DEVICE_REBOUND', 'TRANSFER_DEFERRED', 'TRANSFER_FROZEN')),
    CHECK (status IN ('OPEN', 'INFO', 'ACKNOWLEDGED', 'NOT_ME')),
    CHECK ((status = 'OPEN') <= actionable),
    CHECK ((status IN ('ACKNOWLEDGED', 'NOT_ME')) <= (answered_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS alert_account_created_idx ON core.alert (account_id, created_at DESC);

GRANT SELECT, INSERT, UPDATE ON core.alert TO isc_core_app;

COMMIT;
