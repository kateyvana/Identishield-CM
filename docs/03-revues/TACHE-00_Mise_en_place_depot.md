# Spécification de tâche — Mise en place du dépôt (ISC-DEV §6, étapes 2 à 9)

Exigences couvertes : ENF-04, ENF-05, ENF-07, ED-06, CT-02 ; ISC-DEV §3, §4, §5 ; ISC-DEPLOY §4.
Critère de réussite global : un dépôt vide de tout code applicatif, dont l'intégration continue est verte, dont les portes existent (même si certaines n'ont rien à vérifier), et dans lequel `make up` démarre PostgreSQL avec les deux schémas et les rôles attendus.

Règle : ne pas produire de code applicatif. Aucune classe dans core/ ni pdp/ hormis un point de santé minimal si le build l'exige. Ce n'est pas le squelette marchant ; c'est l'environnement.

## Tâche 1 — Structure

Créer l'arborescence d'ISC-DEV §3 :
core/ pdp/ console/ android/ simulator/ attacker/ hlr-agent/ evaluation/ contracts/ infra/ spikes/ docs/ tests/ datasets/
Dans docs/ : 00-registre-questions.md, 01-decisions/, 02-specifications/, 03-revues/, 04-sources/, diagrams/, memoire/, traceability.csv (en-tête seul).
Dans spikes/ : spike-1-keystore/ spike-2-clock/ spike-3-audit-chain/ spike-4-mtls/ spike-5-injection/, chacun avec un RESULT.md vide portant le gabarit : Question / Dispositif / Résultat / Décision (ADR) / Statut.
Placer CLAUDE.md à la racine (contenu fourni). Placer .editorconfig, .gitignore (Java, Python, Node, Android, IDE, secrets), .vscode/extensions.json (liste d'ISC-DEV §2.3).
Vérification : `tree -L 2` correspond à ISC-DEV §3.

## Tâche 2 — Registre de questions et ADR initiaux

docs/00-registre-questions.md : tableau ID / Question / Bloque / Propriétaire / Échéance / Statut, avec QO-02, 03, 04, 11, 13, 17 en OUVERT et QO-01, 15 en FERMÉ.
docs/01-decisions/ : un fichier ADR-001.md à ADR-025.md au gabarit (Contexte / Décision / Alternatives rejetées / Conséquences / Statut), contenu repris de l'index d'ISC-SAD §15 et d'ISC-DEPLOY §9. ADR-016 à 021 en statut PROVISOIRE.
Vérification : 25 fichiers, chacun avec les cinq sections.

## Tâche 3 — Infrastructure minimale

infra/docker-compose.yml : service postgres:16 uniquement, réseau `isc-net` interne sans accès sortant, volume nommé, variables d'environnement lues depuis un fichier .env généré (jamais versionné).
infra/init/ : script SQL créant les schémas `core` et `pdp`, les rôles `isc_core_app` et `isc_pdp_app`, et la restriction du rôle PDP à SELECT + INSERT sur `pdp.audit_entry` (table créée vide avec les colonnes seq, payload, prev_hash, hash, written_at). Aucun UPDATE ni DELETE accordé.
infra/gen-secrets.sh : génère .env avec des mots de passe aléatoires s'il n'existe pas.
Vérification : `make up` puis une requête qui tente un UPDATE sur pdp.audit_entry avec le rôle isc_pdp_app échoue avec une erreur de permission.

## Tâche 4 — Makefile

Cibles : up, down, reset, check, test, abuse, evaluate, diagrams, check-docs, check-stack.
Les cibles sans contenu encore (abuse, evaluate) affichent « non implémenté — jalon Mx » et renvoient 0.
`check-stack` vérifie : postgres en bonne santé, schémas présents, restriction du rôle d'audit effective, absence d'accès sortant depuis isc-net (une tentative de résolution DNS externe doit échouer).
Vérification : `make check-stack` vert.

## Tâche 5 — Squelettes de build

core/ et pdp/ : projets Gradle Java 21 Spring Boot minimaux avec Spotless configuré, un test unitaire trivial, ArchUnit en dépendance de test avec une première règle TA-CLOCK-01 (aucun appel à Instant.now(), LocalDateTime.now(), System.currentTimeMillis() hors d'un package `time`).
simulator/, attacker/, hlr-agent/, evaluation/ : projets Python avec pyproject.toml, Ruff configuré, un test trivial.
console/ : projet Next.js minimal avec ESLint et Prettier.
android/ : non créé à cette étape (Android Studio, tâche manuelle du propriétaire).
Vérification : chaque build passe ; `make check` vert.

## Tâche 6 — Contrat OpenAPI

contracts/evaluate.yaml : OpenAPI 3.1 décrivant POST /evaluate, GET /nonce, POST /freeze/{id}/release selon ISC-SAD §8.1 et §8.3. Schémas complets, codes 200/400/401/409/429/503, exemples.
Porte `contract` : validation du fichier par un linter OpenAPI.
Vérification : le linter passe sans avertissement bloquant.

## Tâche 7 — Porte check-docs

Script (Python) dans tools/check_docs.py qui :
- extrait tous les identifiants ADR-xxx cités dans docs/ et vérifie qu'un fichier docs/01-decisions/ADR-xxx.md existe ;
- vérifie qu'aucun identifiant d'exigence marqué RETIRÉE dans ISC-SRS (liste fournie : ES-01, ES-16) n'est cité ailleurs que dans sa ligne de retrait ;
- vérifie que les ensembles d'identifiants d'exigences des versions FR et EN du SRS sont identiques (mapping EF→FR, ES→SR, ENF→NFR, EAR→RAR, ED→DR, EI→IR).
Les documents .docx sont convertis en texte pour l'analyse.
Vérification : le script passe au vert sur les documents actuels ; il échoue si on introduit volontairement une citation de ES-01.

## Tâche 8 — Intégration continue

.github/workflows/ci.yml : sur chaque poussée et pull request, exécute les portes légères (build, format, unit, arch, contract, check-docs, secrets, fictitious-msisdn, diagrams) sur exécuteur hébergé.
.github/workflows/heavy.yml : sur pull request vers main et chaque nuit, exécute integration, abuse, audit-role sur l'exécuteur étiqueté `isc-vm` (self-hosted). Ce workflow peut rester en attente tant que l'exécuteur n'est pas installé.
Protection de main : documentée dans docs/03-revues/setup.md pour application manuelle par le propriétaire (fusion interdite sans CI verte et sans revue).
Vérification : le workflow léger est vert sur le dépôt.

## Tâche 9 — README

README.md : en moins d'une page, les prérequis (renvoi à ISC-DEV §2), les trois commandes (`make up`, `make check-stack`, `make check`), et la mention que le code applicatif n'existe pas encore.

## Rendu attendu

Une pull request `chore/setup-repository` contenant les neuf tâches, CI verte, avec en description la liste des vérifications exécutées et leurs résultats. Signaler explicitement toute décision prise qui n'était pas dans cette spécification.
