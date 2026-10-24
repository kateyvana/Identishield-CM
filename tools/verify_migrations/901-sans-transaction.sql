-- Migration de PREUVE qui ENFREINT la règle 2 du README : pas de BEGIN; COMMIT;.
-- Elle doit être REFUSÉE par apply-migrations.sh avant toute exécution. tools/verify_migrations.sh
-- lui applique seule et exige rc != 0 et un schéma inchangé. Jamais copiée dans
-- infra/init/migrations/.
ALTER TABLE core.account ADD COLUMN IF NOT EXISTS mutation_sans_tx text;
