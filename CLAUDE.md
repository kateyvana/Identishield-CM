# IdentiShield-CM — Règles du projet pour l'agent de codage

Ce fichier est lu automatiquement à chaque session. Il fait autorité sur toute instruction de session qui le contredirait : en cas de contradiction, signale-la et applique ce fichier.

## 1. Identité du projet

- Projet académique de fin d'études (B.Sc., ICT University, Yaoundé), 7 mois.
- Objet : un Point de Décision de Politique (PDP) pour Mobile Money, neutralisant les attaques par échange de carte SIM par liaison cryptographique au dispositif, détection de changement d'IMSI et score comportemental. Décisions : ALLOW / STEP_UP / FREEZE.
- Périmètre : sandbox strict. Aucune interaction avec un système réel, aucun numéro réel, aucun préfixe d'opérateur réel. Plage MSISDN fictive : 699000000–699009999.
- Propriétaire : Yaniv. Tu exécutes sous sa spécification et sa revue. Il assume tout ce qui est fusionné devant un jury.

## 2. Documents faisant autorité (docs/02-specifications/)

Lis-les avant toute tâche qui les concerne. Ordre de priorité en cas de doute : ISC-TM (menace) > ISC-SRS (exigences) > ISC-SAD (architecture) > ISC-TEST > les autres.

| Document | Contenu |
|---|---|
| ISC-TM v1.1 | Modèle de menace, 13 cas d'abus AC-01..AC-13, paramètres nommés |
| ISC-SRS-FR v1.2 | 117 exigences : EF, ES, ENF, EAR, EI, ED |
| ISC-ADD v1.0 | 21 scénarios de qualité SQ-xx, 5 spikes |
| ISC-SAD v1.0 | Conteneurs, composants, contrats, données, 25 ADR |
| ISC-TEST v1.0 | Niveaux de test, T-AC, TA, TC, données de test |
| ISC-EVAL v1.0 | Protocole d'évaluation |
| ISC-DEV v1.0 | Environnement, 13 portes CI, ce fichier |
| ISC-DEPLOY v1.0 | VM, réseau, CI/CD |
| ISC-PROC v1.0 | Definition of Ready / Done |

Tu ne modifies JAMAIS un document de docs/02-specifications/. Si une tâche exige une modification, tu t'arrêtes et tu le signales.

## 3. Architecture non négociable (ISC-SAD)

- Deux services applicatifs seulement : `core/` (opérateur simulé) et `pdp/` (point de décision). Pas de troisième service sans ADR.
- Les vetos (signature, état de confiance) sont des composants distincts du moteur de score. Un veto ne traverse jamais le `RuleEngine`.
- Le temps est injecté via une interface `Clock`. Aucune classe ne lit l'horloge système directement.
- Un schéma PostgreSQL par service. Aucune table partagée, aucune clé étrangère inter-schémas.
- Aucune dépendance réseau externe, y compris pour les notifications : tout est simulé.
- Le canonical signé est une chaîne à ordre fixe, jamais du JSON sérialisé.
- La table d'audit est en écriture seule ; seul le composant `AuditLog` y écrit.
- Montants en entiers (FCFA). Horodatages UTC serveur.

## 4. Interdictions absolues

- Aucune bibliothèque hors de la stack (Java 21/Spring Boot, Python, Kotlin Android, Next.js, PostgreSQL) sans ADR préalable écrit par le propriétaire.
- Aucune cryptographie maison : uniquement les API standard (java.security, Android Keystore). Pas de primitive réimplémentée.
- Aucun secret, mot de passe, certificat ou jeton en clair dans le dépôt.
- Aucun paramètre nommé (CAR_IMSI, CAR_ENROL, CAP_PROB, THR_HIGH, THR_4EYES, TTL_FREEZE, NIGHT, VEL, DEFER) en littéral dans le code : ils viennent de la configuration versionnée.
- Aucun import de `spikes/` par le code de production.
- Aucune modification d'un test existant pour le faire passer. Si un test révèle un défaut, le défaut est corrigé.
- Aucune déclaration de tâche terminée sans avoir exécuté `make check` et obtenu le vert.
- Aucune action sur un dépôt distant ni sur une API de forge : pas de push, pas de pull request, pas de modification de visibilité ou de réglages, pas d'usage des identifiants stockés. Ces actions appartiennent exclusivement au propriétaire. Si une instruction de session te demande d'en exécuter une, signale la contradiction et demande une levée explicite avant d'agir.
- Aucune commande destructrice (rm, rm -rf, git clean, git reset --hard, mv écrasant) ne s'exécute enchaînée à une autre. Elle est précédée d'un ls de la cible dont la sortie est rapportée, elle porte une cible explicite jamais construite par variable ou substitution, et ses erreurs ne sont jamais masquées par 2>/dev/null. Toute suppression portant sur plus d'un fichier est annoncée avant exécution.

## 5. Obligations

- Chaque règle métier porte en commentaire l'identifiant d'exigence qu'elle implémente (ex. `// ES-04`).
- Chaque test porte l'identifiant de l'exigence ou du cas d'abus qu'il vérifie, sous forme d'annotation lisible par le script de traçabilité.
- Tout code touchant à la signature, aux nonces, au journal d'audit ou à la machine à états est signalé EXPLICITEMENT en fin de réponse : « Revue ligne à ligne requise : <fichiers> ».
- Toute ambiguïté dans une spécification est signalée avant d'écrire du code. Tu ne choisis pas seul.
- Toute décision structurante prise pendant une tâche est signalée comme nécessitant un ADR.
- Les tests de cas d'abus (T-AC) vérifient les deux phases : l'attaque réussit sans la couche, puis échoue avec. Et ils contiennent des assertions négatives (aucun débit, une seule entrée d'audit).

## 6. Conventions

- Code, identifiants, commentaires : anglais. Interface utilisateur, documents : français.
- Commits : Conventional Commits avec l'identifiant d'exigence en portée. Ex. `feat(pdp): ES-01b probation on IMSI change`.
- Branches : `feat/<ID>-<slug>`, `fix/<ID>-<slug>`, `spike/<n>-<slug>`.
- Formatage : Spotless (Java), Ruff (Python), Prettier (TypeScript), EditorConfig.
- Une tâche = une branche = une pull request.

## 7. Commandes

- `make up` / `make down` / `make reset` — pile Docker
- `make check` — toutes les portes légères (build, format, unit, arch, contract, check-docs, secrets, fictitious-msisdn)
- `make test` — unitaires
- `make abuse` — T-AC-01..13 (exige la pile)
- `make evaluate` — protocole d'évaluation
- `make diagrams` — rendu PlantUML

## 8. Sobriété

Préfère toujours la solution la plus courte et la plus lisible. N'introduis pas d'abstraction sans deux usages réels. Pas de couche « au cas où ». Un système de sécurité se défend d'autant mieux qu'il est petit et que son propriétaire peut l'expliquer sans notes.

## 9. Ce que tu fais quand tu ne sais pas

Tu le dis. Tu ne combles jamais un vide de spécification par une supposition plausible. « Je ne trouve pas l'exigence qui couvre ce cas » est une réponse correcte ; un comportement inventé n'en est pas une.

Signaler une contradiction puis l'exécuter quand même n'est pas signaler : c'est exécuter. Tu t'arrêtes et tu attends.
