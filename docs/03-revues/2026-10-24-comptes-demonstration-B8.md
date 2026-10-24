# Carte #76 · Comptes de démonstration (20) et noms des titulaires — procédure et limites

Date : 2026-10-24. Couloir B (Mobile). Branche `mobile-19-b8-comptes-demo`. Décision de la
propriétaire du 2026-10-24 : les 20 comptes sortent de B3a, deviennent leur propre carte, après la
migration 003 (`holder_name`, couloir A, dans `main`).

## Ce qui est dans le dépôt

- `core/src/main/java/cm/identishield/core/mobile/DemoSeedCsv.java` : lecture et validation du
  fichier, en entier, avant toute écriture ; les trois pièges du tableur (BOM UTF-8 ou encodage
  Windows-1252, séparateur « ; », zéro de tête d'un PIN supprimé) sont **refusés avec la consigne**,
  jamais devinés ; un nom entre guillemets avec une virgule est lu ; **aucun PIN dans aucun message**.
- `DemoAccountSeeder` (profil `demo` seulement) : comptes techniques de `tools/mobile-mock/fixtures.json`
  + comptes nominatifs du fichier, **une seule transaction** ; compte créé s'il manque (solde posé à
  la création seulement), **nom du titulaire réaligné à chaque démarrage**, PIN posé seulement s'il
  est absent, abonné HLR assuré (A5-00 : un compte sans IMSI ne peut pas transiger). Vérifie que la
  colonne `holder_name` existe, sinon échoue en nommant la migration 003. Journal : des nombres.
- `tools/mobile-mock/fixtures.json` : « Compte de test NEUF » → **Hervé ESSOMBA**, « Compte de test
  GEL » → **Clarisse TCHOUAKE** (arbitrage retenu par la propriétaire) ; le rôle de test est dans un
  champ séparé `testRole` (`compte-neuf-sans-appareil`, `beneficiaire-scenario-gel`). Numéros et PIN
  inchangés : le faux serveur et ses tests en dépendent.
- `infra/compose.b3a.yml` : montage en lecture seule du fichier depuis `ISC_DEMO_SEED_CSV`
  (`infra/.env`, jamais versionné) ; **sans la variable, Compose refuse de démarrer** en nommant la
  cause. `core.demo.seed-csv-path` = `/fixtures/seed-comptes-demo.csv`.
- `.gitignore` : `seed-comptes-demo*.csv`. La porte `secrets` interdit ce nom de fichier (commit
  séparé, relu par le couloir A).

## Ce que la propriétaire fait, une fois (le fichier ne passe JAMAIS par le dépôt ni par une session)

Format attendu du fichier : UTF-8 **sans** BOM, séparateur **virgule**, en-tête exact
`msisdn,holderName,pin,balanceXaf`, numéros `677001101` à `677001120`, PIN de **4 chiffres** (la
colonne formatée en texte dans le tableur, sinon `0042` devient `42`), solde entier en FCFA.

```
# depuis le poste, vers la VM (le fichier reste hors de tout dépôt)
ssh isc@isc-vm 'mkdir -p -m 700 ~/.isc'
scp seed-comptes-demo.csv isc@isc-vm:~/.isc/seed-comptes-demo.csv
ssh isc@isc-vm 'chmod 600 ~/.isc/seed-comptes-demo.csv'
# dans la VM, une ligne dans infra/.env (jamais versionné)
echo 'ISC_DEMO_SEED_CSV=/home/isc/.isc/seed-comptes-demo.csv' >> ~/identishield-cm/infra/.env
```

## Commandes EXACTES de déploiement (couloir A, dans la VM, à copier telles quelles)

Pour que les 20 comptes existent, le cœur doit tourner sous le **profil Spring `demo`**, avec la
**surcouche `compose.b3a.yml`** et la **variable `ISC_DEMO_SEED_CSV`** dans `infra/.env`. Sans la
surcouche, le profil n'est pas actif et rien n'est amorcé ; sans la variable, Compose refuse de
démarrer en nommant la cause.

**Une seule façon de démarrer (décision du superviseur technique, 2026-10-24, avec le couloir A,
PR #91) : `make up`.** Dès que #91 est dans `main`, `make up` inclut lui-même
`-f infra/compose.b3a.yml` quand `ISC_DEMO_SEED_CSV` est définie dans `infra/.env`, et l'annonce
(« profil demo : compose.b3a.yml inclus » / « profil demo : non »). L'étape 3 ci-dessous est la
commande explicite équivalente, valable tant que #91 n'est pas fusionnée ; après, c'est `make up`.

```bash
cd ~/identishield-cm/infra
# 1. La variable, une seule fois (infra/.env n'est jamais versionné) :
grep -q '^ISC_DEMO_SEED_CSV=' .env || echo 'ISC_DEMO_SEED_CSV=/home/isc/.isc/seed-comptes-demo.csv' >> .env
# 2. Le fichier doit exister, dossier 700, fichier 600 (déposé par la propriétaire) :
ls -ld ~/.isc && ls -l ~/.isc/seed-comptes-demo.csv
# 3. Démarrage de la pile AVEC la surcouche (profil demo + montage du fichier) :
docker compose -f docker-compose.yml -f compose.b3a.yml up -d --build
# 4. Vérification : une ligne « amorçage démo : 5 comptes techniques, 20 comptes de démonstration »
docker logs isc-core 2>&1 | grep 'amorçage démo'
```

Si le fichier est invalide, `isc-core` s'arrête et `docker logs isc-core` nomme le fichier, la
ligne, le champ et la cause (jamais le PIN). Un `up -d` **sans** `-f compose.b3a.yml` relance la
pile sans profil `demo` : les comptes déjà créés restent en base, mais aucun amorçage n'a lieu.

## Preuve de rejouabilité (à faire dans la VM, par la session, une fois le fichier déposé)

Deux démarrages successifs du cœur sous le profil `demo`, puis comparaison à l'identique des 25
lignes : numéro, nom, solde, empreinte du PIN (`SELECT msisdn, holder_name, balance_xaf, pin_hash
FROM core.account WHERE msisdn >= '677001101' OR msisdn IN (…) ORDER BY msisdn`, sous `isc_admin`).
Le rapport donne le résultat de la comparaison, **pas** les empreintes. Témoin posé avec heure de fin.

Commandes de la preuve (pile ISOLÉE `isc-b3a`, `infra/compose.b3a-isolement.yml` : conteneurs
`isc-b3a-*`, réseaux `isc-b3a-*`, aucun port publié, volumes neufs — la pile en service n'est pas
touchée et peut tourner en même temps), sous témoin avec heure de fin :

```bash
cd ~/identishield-cm/infra
P="docker compose -p isc-b3a -f docker-compose.yml -f compose.b3a.yml -f compose.b3a-isolement.yml"
$P up -d --build                      # 1er démarrage : amorçage
$P exec postgres psql -U isc_admin -d identishield -Atc \
  "SELECT msisdn, holder_name, balance_xaf, md5(coalesce(pin_hash,'')) FROM core.account ORDER BY msisdn" > /tmp/seed-1.txt
$P restart core                        # 2e démarrage : rejoué
$P exec postgres psql -U isc_admin -d identishield -Atc \
  "SELECT msisdn, holder_name, balance_xaf, md5(coalesce(pin_hash,'')) FROM core.account ORDER BY msisdn" > /tmp/seed-2.txt
cmp /tmp/seed-1.txt /tmp/seed-2.txt && wc -l /tmp/seed-1.txt    # identiques, 25 lignes
```

Le rapport donne « identiques, 25 lignes » ; les fichiers `/tmp/seed-*.txt` ne portent que des
empreintes d'empreintes (md5 du hash PBKDF2), jamais un PIN ; ils sont retirés après.

**État au 2026-10-24, 16 h 40 UTC :** le fichier EST déposé dans la VM (`~/.isc/seed-comptes-demo.csv`,
909 octets, 600, dossier 700) ; la variable `ISC_DEMO_SEED_CSV` n'est pas encore dans `infra/.env`
de la VM. Preuve de rejouabilité : à faire dès le témoin libre. Le code est prouvé par les tests (CSV factice `core/src/test/resources/demo-seed/`,
PIN manifestement factices, et un test par refus).

## Limites déclarées

- **L-B8-1 — Un seul validateur.** Le plan prévoyait une vérification préalable autonome dans la VM ;
  elle n'est pas écrite : deux validateurs des mêmes règles divergeraient (même règle que pour le
  contrat mobile : un seul validateur, jamais deux). La vérification préalable **est** le démarrage
  du cœur, dont le message d'échec est explicite.
- **L-B8-2 — Le nombre de comptes n'est pas figé** (décision du 2026-10-24) : le journal annonce le
  nombre lu ; la propriétaire peut en ajouter.
- **L-B8-3 — Le faux serveur ne connaît pas les 20 comptes** : il ne lit que `fixtures.json`. Ils
  n'existent que sur le vrai cœur.
- **L-B8-4 — Le solde n'est posé qu'à la création.** Changer un solde dans le fichier après un
  premier démarrage ne change rien en base : volontaire, pour ne pas effacer l'activité d'une
  démonstration ; pour repartir, supprimer les comptes (acte de la propriétaire, hors dépôt).
