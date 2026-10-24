# Migrations du schéma

`infra/init/init.sql` crée le schéma d'une base **neuve**, et ne s'exécute qu'une seule fois, à la
création du volume. Un changement de schéma ne peut donc pas y être ajouté seul : la base qui tourne
déjà, celle de la VM, ne le verrait jamais. Elle ne peut pas non plus être recréée par `make reset`,
qui détruit l'autorité TLS épinglée par l'APK publié (L7-2).

Tout changement de schéma postérieur à `init.sql` est donc une **migration** de ce dossier.

## Règles

1. **Un fichier par changement**, nommé `NNN-description.sql`, où `NNN` est un numéro à trois chiffres.
   Les fichiers s'appliquent dans l'ordre de leur nom.
2. **Chaque migration est rejouable** : l'appliquer deux fois laisse la base dans le même état. On
   utilise `ADD COLUMN IF NOT EXISTS`, `CREATE TABLE IF NOT EXISTS`, `DROP CONSTRAINT IF EXISTS`
   avant un `ADD CONSTRAINT`, ou un bloc `DO $$ … $$` qui teste le catalogue.
3. **Une migration fusionnée dans `main` n'est jamais modifiée.** Pour corriger, on écrit la suivante.
4. **Aucune variable psql** (`:core_pw`, …) ni aucun secret : seul `init.sql` reçoit les mots de passe
   des rôles.
5. Les droits accordés sur une table par `init.sql` couvrent ses nouvelles colonnes. Une nouvelle
   table doit recevoir ses `GRANT` dans sa propre migration.

## Application

- **Base neuve** : `01-apply.sh` applique `init.sql`, puis chaque migration, à la création du volume
  (`make up` sur une base vide).
- **Base existante** : `make migrate`, pile démarrée (`infra/init/migrate.sh`), à lancer après chaque
  mise à jour du dépôt dans la VM, **avant** `make up` du nouveau code.
- **Preuve** : `bash tools/verify_migrations.sh`. Elle vérifie, sur deux bases jetables dans la VM, que
  la base neuve et la base migrée ont le même schéma, et qu'une seconde application ne change rien.
