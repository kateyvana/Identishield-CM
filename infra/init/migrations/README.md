# Migrations du schéma

`infra/init/init.sql` crée le schéma d'une base **neuve**, et ne s'exécute qu'une seule fois, à la
création du volume. Un changement de schéma ne peut donc pas y être ajouté seul : la base qui tourne
déjà, celle de la VM, ne le verrait jamais. Elle ne peut pas non plus être recréée par `make reset`,
qui détruit l'autorité TLS épinglée par l'APK publié (L7-2).

Tout changement de schéma postérieur à `init.sql` est donc une **migration** de ce dossier.

## Règles

1. **Un fichier par changement**, nommé `NNN-description.sql`, où `NNN` est un numéro à trois chiffres.
   Les fichiers s'appliquent dans l'ordre de leur nom.
2. **Chaque migration est enveloppée dans une transaction** : `BEGIN;` en première instruction,
   `COMMIT;` en dernière. Un `DROP CONSTRAINT` suivi d'un `ADD CONSTRAINT` qui échoue laisserait
   autrement la table **sans** contrainte, et `psql` valide chaque instruction séparément. La preuve
   `tools/verify_migrations.sh` rougit sur une migration non enveloppée.
3. **Chaque migration est rejouable** : l'appliquer deux fois laisse la base dans le même état. On
   utilise `ADD COLUMN IF NOT EXISTS`, `CREATE TABLE IF NOT EXISTS`, `DROP CONSTRAINT IF EXISTS`
   avant un `ADD CONSTRAINT`, ou un bloc `DO $$ … $$` qui teste le catalogue.
4. **Contrainte redéfinie : vérifier qu'aucun autre doublon ne survit.** Une contrainte créée sans
   nom (par exemple un `CHECK` inline dans `CREATE TABLE`) reçoit un nom généré par PostgreSQL.
   `DROP CONSTRAINT IF EXISTS` sur un nom supposé peut alors ne **rien faire en silence**, puis
   `ADD CONSTRAINT` crée une seconde contrainte pendant que l'ancienne survit. Toute migration qui
   redéfinit une contrainte doit, **dans la même transaction**, vérifier le catalogue
   (`pg_catalog.pg_constraint`) et refuser (`RAISE`) s'il reste une contrainte résiduelle sur la
   colonne visée.
5. **Une migration fusionnée dans `main` n'est jamais modifiée.** Pour corriger, on écrit la suivante.
6. **Aucune variable psql** (`:core_pw`, …) ni aucun secret : seul `init.sql` reçoit les mots de passe
   des rôles.
7. Les droits accordés sur une table par `init.sql` couvrent ses nouvelles colonnes. Une nouvelle
   table doit recevoir ses `GRANT` dans sa propre migration.

## Application

- **Base neuve** : `01-apply.sh` applique `init.sql`, puis `apply-migrations.sh`, à la création du
  volume (`make up` sur une base vide). Les deux chemins passent par le **même** `apply-migrations.sh`.
- **Base existante** : à **chaque `make up`**, le conteneur `isc-migrate` (`docker-compose.yml`) exécute
  `apply-migrations.sh`. `isc-core` et `isc-pdp` en dépendent en `service_completed_successfully` :
  **une migration qui échoue arrête le démarrage du cœur et du PDP**. L'application est automatique,
  sans ordre à retenir ; une commande à lancer dans une procédure a été condamnée (R-S).
- **Preuve** : `bash tools/verify_migrations.sh`. Sur deux bases jetables dans la VM, elle vérifie que
  la base neuve et la base migrée ont le même schéma, qu'une seconde application ne change rien, et
  qu'une migration **non enveloppée** dans `BEGIN; … COMMIT;` est refusée.

## Précondition sur la vraie base, à cocher avant la première migration

Avant la **première** migration appliquée à la base de la VM (après la fusion dans `main`) :
**exporter la chaîne d'audit et confirmer qu'elle est intacte**, comme la règle du 2026-10-17
l'exige avant tout `make reset`. Motif identique : la chaîne d'audit est une contribution centrale
du mémoire, et une migration transactionnelle qui échoue sur un ADR encore inconnu ne doit pas
détruire la preuve. Le passage est à consigner dans `docs/03-revues/`.

## Manque déclaré, mois 5

Le cœur ne vérifie pas, à son démarrage **hors** de la pile standard (banc, isolement, débug), que le
schéma attendu est présent. Dans la pile standard, `isc-migrate` garantit l'application avant que le
cœur démarre. Hors de la pile, une colonne manquante donne une erreur SQL au premier appel. Un
contrôle au démarrage (requêtes `SELECT col FROM table LIMIT 0`) sera ajouté au mois 5.
