# Migrations du schéma

`infra/init/init.sql` crée le schéma d'une base **neuve**, et ne s'exécute qu'une seule fois, à la
création du volume. Un changement de schéma ne peut donc pas y être ajouté seul : la base qui tourne
déjà, celle de la VM, ne le verrait jamais. Elle ne peut pas non plus être recréée par `make reset`,
qui détruit l'autorité TLS épinglée par l'APK publié (L7-2).

Tout changement de schéma postérieur à `init.sql` est donc une **migration** de ce dossier.

## Règles

1. **Un fichier par changement**, nommé `NNN-description.sql`, où `NNN` est un numéro à trois chiffres.
   Les fichiers s'appliquent dans l'ordre de leur nom. **Le numéro est attribué par le couloir A**
   (décision du superviseur technique du 2026-10-24, après que les deux couloirs ont failli prendre
   le même) : un couloir qui veut écrire une migration demande son numéro au couloir A avant
   d'ouvrir le fichier, pour éviter qu'un numéro soit choisi deux fois en parallèle.
   Un numéro attribué **n'est jamais réassigné**, même si la carte qui l'a reçu est abandonnée :
   les trous dans la numérotation sont admis. Deux migrations de numéros voisins doivent être
   **indépendantes** (aucune ne suppose que l'autre est passée), puisqu'elles peuvent être écrites
   et fusionnées dans l'ordre inverse de leur attribution. Attribués : 001–003 (A), 004 = B3b,
   005 = B4, 006 = B5 (alertes ; fusionnée le 24/10, appliquée à la fenêtre 2).
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
- **Aucune mémoire de ce qui a été appliqué.** `apply-migrations.sh` ne tient ni table de versions ni
  « dernier numéro » : à chaque exécution, la boucle `for f in "$DIR"/[0-9][0-9][0-9]-*.sql`
  (`infra/init/apply-migrations.sh`, ligne 16) **rejoue tous les fichiers** dans l'ordre de leur nom,
  et c'est la règle 3 (rejouable) qui rend ce rejeu sans effet. Conséquence : une migration de numéro
  **inférieur** arrivant **après** (la 004 de B3b fusionnée après la 005 de B4) est appliquée au
  `make up` suivant, jamais sautée ; c'est ce qui autorise les trous et l'ordre d'arrivée libre de
  la règle 1.
- **Preuve** : `bash tools/verify_migrations.sh`. Sur deux bases jetables dans la VM, elle vérifie que
  la base neuve et la base migrée ont le même schéma, qu'une seconde application ne change rien, et
  qu'une migration **non enveloppée** dans `BEGIN; … COMMIT;` est refusée.

## Précondition sur la vraie base, à cocher avant la première migration

Avant la **première** migration appliquée à la base de la VM (après la fusion dans `main`), les
étapes suivantes sont cochées, dans cet ordre, et le passage est consigné dans `docs/03-revues/` :

- [ ] la pile est à jour sur `main` dans la VM, mais pas encore relancée ;
- [ ] la pile est démarrée **sans** `isc-core` ni `isc-pdp` (le `make up` qui déclencherait les
      migrations), pour que PostgreSQL soit joignable sans que personne écrive dans le journal ;
- [ ] la chaîne d'audit est **exportée** vers un fichier daté, hors du volume `isc-pgdata` ;
- [ ] la vérification intégrale de la chaîne (`pdp.audit_verify()`) sort VERTE sur l'export ;
- [ ] l'empreinte SHA-256 de l'export est consignée dans `docs/03-revues/`.

Outil : `tools/export_audit_chain.sh` (dans la VM, `isc-pdp` arrêté ; export CSV daté, `audit_verify`,
`audit_anchor_check`, SHA-256). Répétition générale consignée dans
`docs/03-revues/2026-10-24-export-chaine-audit.md`.

Motif : la chaîne d'audit est une contribution centrale du mémoire. Une migration
transactionnelle qui échoue est annulée, mais le passage de 002 et des suivantes doit laisser une
trace externe de l'état antérieur, pour pouvoir reconstruire au besoin. C'est la règle du
2026-10-17 (export avant `make reset`), appliquée ici au premier `make up` du dépôt migré.

## Manque déclaré, mois 5

Le cœur ne vérifie pas, à son démarrage **hors** de la pile standard (banc, isolement, débug), que le
schéma attendu est présent. Dans la pile standard, `isc-migrate` garantit l'application avant que le
cœur démarre. Hors de la pile, une colonne manquante donne une erreur SQL au premier appel. Un
contrôle au démarrage (requêtes `SELECT col FROM table LIMIT 0`) sera ajouté au mois 5.
