-- Migration de PREUVE, utilisée seulement par tools/verify_migrations.sh, et jamais copiée dans
-- infra/init/migrations/. Elle vérifie que le mécanisme applique un vrai changement de schéma, à
-- l'identique sur une base neuve et sur une base existante, et qu'on peut la rejouer.
-- Enveloppée dans BEGIN/COMMIT comme toute migration réelle (règle 2 du README).
BEGIN;
ALTER TABLE core.account ADD COLUMN IF NOT EXISTS preuve_migration text;
COMMIT;
