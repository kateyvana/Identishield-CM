-- 005 — B4, couloir B (P-09, decision de la proprietaire du 2026-10-24) : les trois durees de la
-- session de l'application sont IMPOSEES PAR LE SERVEUR, qui ne voit que des requetes :
--   - inactivite (APP_SESSION_TTL)              : derniere requete de l'USAGER  -> last_activity_at ;
--   - arriere-plan (APP_SESSION_BACKGROUND_TTL) : derniere requete, meme automatique -> last_seen_at ;
--   - duree maximale (APP_SESSION_MAX_TTL)      : created_at, deja presente ; expires_at la porte.
-- Independante de 004 (B3b) : aucun objet commun. Une seule transaction ; chaque instruction est
-- rejouable (infra/init/migrations/README.md). Aucune contrainte redefinie.
BEGIN;

ALTER TABLE core.session
    ADD COLUMN IF NOT EXISTS last_seen_at     timestamptz,
    ADD COLUMN IF NOT EXISTS last_activity_at timestamptz;

-- Sessions emises avant cette migration : leur derniere requete connue est leur creation. Sans
-- effet a la seconde application (plus aucune ligne NULL).
UPDATE core.session SET last_seen_at     = created_at WHERE last_seen_at     IS NULL;
UPDATE core.session SET last_activity_at = created_at WHERE last_activity_at IS NULL;

ALTER TABLE core.session
    ALTER COLUMN last_seen_at     SET NOT NULL,
    ALTER COLUMN last_activity_at SET NOT NULL;

COMMIT;
