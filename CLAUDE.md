# IdentiShield-CM — Règles du projet pour l'agent de codage

Ce fichier est lu automatiquement à chaque session. Il fait autorité sur toute instruction de session qui le contredirait : en cas de contradiction, signale-la et applique ce fichier.

## 1. Identité du projet

- Projet académique de fin d'études (B.Sc., ICT University, Yaoundé), 7 mois.
- Objet : un Point de Décision de Politique (PDP) pour Mobile Money, neutralisant les attaques par échange de carte SIM par liaison cryptographique au dispositif, détection de changement d'IMSI et score comportemental. Décisions : ALLOW / STEP_UP / FREEZE.
- Périmètre : sandbox strict. Aucune interaction avec un système réel. ~~Aucun numéro réel, aucun préfixe d'opérateur réel. Plage MSISDN fictive : 699 000 000–699 009 999.~~ (remplacé par ADR-035 : 69x est un préfixe Orange, incohérent avec l'IMSI MTN). Opérateur simulé : **MTN Cameroun**, identifiants à structure réelle : MSISDN **677000000–677009999**, IMSI **624-01** + MSIN (15 chiffres). Aucune plage réservée à la fiction n'existe dans le plan camerounais : la sûreté repose sur l'**isolement** (réseau isc-net sans aucune sortie), pas sur l'inexistence des numéros.
- ~~Propriétaire : Yaniv.~~ Propriétaire : YENGUE MESSAH KATE YVANA (en-têtes harmonisés par ISC-AMD-02 v1.1). Tu exécutes sous sa spécification et sa revue. ~~Il assume tout ce qui est fusionné devant un jury.~~ Tout ce qui est fusionné est assumé par le propriétaire devant un jury.

## 2. Documents faisant autorité (docs/02-specifications/)

Lis-les avant toute tâche qui les concerne. Ordre de priorité en cas de doute : ISC-TM (menace) > ISC-SRS (exigences) > ISC-SAD (architecture) > ISC-TEST > les autres.

| Document | Contenu |
|---|---|
| ISC-TM ~~v1.1~~ ~~v1.2.1~~ v1.3 (`02-modele-de-menace-v1.3.md`) | Modèle de menace, ~~13 cas d'abus AC-01..AC-13~~ 16 cas d'abus AC-01..AC-16, scénarios S-01..S-21 (dont menaces mobiles S-14..S-21), paramètres nommés (valeurs dans `config/ruleset.yaml`, SRS v1.4) |
| ISC-SRS-FR ~~v1.2~~ v1.4 | ~~117 exigences~~ 136 identifiants dont 128 actifs (index `docs/requirements-index.csv`) : EF, ES, ENF, EAR, EI, ED |
| ISC-ADD v1.0 | 21 scénarios de qualité SQ-xx, 5 spikes |
| ISC-SAD ~~v1.0~~ v1.3 | Conteneurs, composants, contrats, données, ~~25 ADR~~ index des ADR (jusqu'à ADR-037) |
| ISC-MOB-CDC v1.1, ISC-MOB-SPEC v1.1 (`mobile/`) | Application mobile : cahier des charges, spécification, 6 diagrammes de séquence |
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
- Aucun paramètre nommé (CAR_IMSI, ~~CAR_ENROL~~, CAP_PROB, THR_HIGH, THR_4EYES, TTL_FREEZE, NIGHT, VEL, DEFER) en littéral dans le code : ils viennent de la configuration versionnée.
- Aucun import de `spikes/` par le code de production.
- Aucune modification d'un test existant pour le faire passer. Si un test révèle un défaut, le défaut est corrigé.
- Aucune déclaration de tâche terminée sans avoir exécuté `make check` et obtenu le vert.
- `make check` VERT sur la branche, prouvé dans le rapport, avant toute fusion. Rien ne se fusionne sans cette preuve. (Règle du propriétaire du 2026-09-25, après un rouge découvert seulement après fusion.)
- Une session = un worktree ; jamais deux sessions dans le même dossier de travail. (Règle du propriétaire du 2026-09-25, après qu'une session a changé de branche sous une autre.)
- **La VM est le SERVEUR du projet : on n'y mesure pas pendant qu'elle sert.** Avant TOUTE utilisation de la VM, une session vérifie `~/VM-OCCUPEE` par ssh. S'il existe, elle attend et le signale. Sinon, elle l'écrit avec son couloir et l'heure, travaille, puis l'EFFACE en partant. (Règle de coordination du 2026-10-23.)
- Deux couloirs, deux sessions : **Données** (branches `t3-*`) et **Mobile** (branches `mobile-*`, `amd-02-*`). Chaque session reste sur ses branches ; aucune ne touche `main`, ni les branches ou les dossiers réservés de l'autre couloir. Limite Kanban « In Progress » = 2, une carte par couloir. (Décision D-14 du propriétaire du 2026-09-25, ISC-AMD-02 v1.1.)
- ~~Aucune action sur un dépôt distant ni sur une API de forge : pas de push, pas de pull request, pas de modification de visibilité ou de réglages, pas d'usage des identifiants stockés. Ces actions appartiennent exclusivement au propriétaire. Si une instruction de session te demande d'en exécuter une, signale la contradiction et demande une levée explicite avant d'agir.~~
- 8e interdiction RÉVISÉE (décision de la propriétaire du 2026-10-16, inscrite ici le 2026-10-17). **Autorisé aux sessions après porte pré-poussée verte** : `git push` d'une branche de carte, `gh pr create`, lecture (PR, CI, issues), réponse aux commentaires. **Réservé à la propriétaire** : fusion dans `main` ; `push --force` et toute réécriture d'historique ; suppression de branche ou d'étiquette sur `origin` ; réglages du dépôt ; jetons. Toute autre action distante : signale-la et demande une levée explicite avant d'agir.
- R-X. JAMAIS de script Python passé par heredoc (`python - <<'EOF'`) : dans cet environnement il se bloque, ou le shell interprète les séquences d'échappement (` `, `\n`) avant qu'elles n'atteignent le fichier. Écris toujours le script dans un fichier, puis exécute-le. (Constaté trois fois, 2026-10-16 et 2026-10-17 : deux corruptions silencieuses de chaînes, un blocage de tâche.)
- R-Y. JAMAIS d'enchaînement « édition + `git add` + `make check` » dans une seule commande d'arrière-plan : si elle se bloque, on ne sait pas jusqu'où elle est allée, et une reprise tardive peut réappliquer une modification périmée sur un état déjà commité. Sépare les étapes et vérifie entre chacune. Si une tâche se bloque : l'ARRÊTER, puis VÉRIFIER l'absence de modification en double avant de reprendre.
- Aucune commande destructrice (`rm`, `rm -rf`, `git clean`, `git reset --hard`, `mv` écrasant) ne s'exécute enchaînée à une autre. Elle est précédée d'un `ls` de la cible dont la sortie est rapportée, elle porte une cible explicite jamais construite par variable ou substitution, et ses erreurs ne sont jamais masquées par `2>/dev/null`. Toute suppression portant sur plus d'un fichier est annoncée avant exécution.
- R-S. Règle STRUCTURELLE de suppression (décision de la propriétaire du 2026-10-23, après deux `rm -f` enchaînés en une journée : la règle précédente demandait de se souvenir au bon moment, et ça ne marche pas). Aucune commande de suppression (`rm`, `rmdir`, `unlink`, `docker rm`) ne figure JAMAIS dans une commande composée : ni après `&&`, `;` ou `|`, ni dans une boucle, ni dans un script lancé en une fois. Une suppression est une commande SEULE, précédée d'une commande SEULE qui liste la cible. Deux appels, toujours. Cette règle ne dépend d'aucune vigilance.

## 5. Obligations

- **Convention de traçabilité (une seule, lisible par `tools/gen_traceability.py`)** : le code de production qui implémente une exigence porte, dans un commentaire, le marqueur `@implements <ID>` ; un test qui vérifie une exigence ou un cas d'abus porte le marqueur `@covers <ID>`. Un marqueur par ligne ; plusieurs marqueurs autorisés sur des lignes distinctes. `<ID>` est un identifiant d'exigence du SRS (familles EF, ES, ENF, EAR, EI, ED, suffixe de variante optionnel, ex. `ES-05b`) ou un cas d'abus (~~`AC-01` à `AC-13`~~ `AC-01` à `AC-16` : AC-14..16 existent depuis ISC-TM v1.2 ; la fenêtre d'amorçage étend AC-13 en v1.3, pas d'AC-17). Exemples : `// @implements ES-04` (Java/Kotlin/TS), `# @implements ED-06` (Python), `// @covers AC-09` (test). La matrice `docs/traceability.csv` est **générée** à partir de ces marqueurs (`make traceability`), jamais tenue à la main : une matrice manuelle diverge du code. Un marqueur référençant une exigence inexistante dans le SRS fait échouer la génération.
- Tout code touchant à la signature, aux nonces, au journal d'audit ou à la machine à états est signalé EXPLICITEMENT en fin de réponse : « Revue ligne à ligne requise : <fichiers> ».
- Toute ambiguïté dans une spécification est signalée avant d'écrire du code. Tu ne choisis pas seul.
- Toute décision structurante prise pendant une tâche est signalée comme nécessitant un ADR.
- Les tests de cas d'abus (T-AC) vérifient les deux phases : l'attaque réussit sans la couche, puis échoue avec. Et ils contiennent des assertions négatives (aucun débit, une seule entrée d'audit).
- **Tableau Kanban « Mois 4 — Démo AC-02 »** (levée permanente de la propriétaire du 2026-10-22, `docs/03-revues/2026-10-22-levee-8e-interdiction-tableau-kanban.md`) : à CHAQUE changement d'état d'une carte — commencée, poussée, PR ouverte, fusionnée — sa carte est déplacée sur le tableau dans le même mouvement. Autorisé : déplacer les cartes, modifier « Dépend de » et « Jours », créer des cartes et leurs étiquettes. Non couvert : fusion, `main`, réglages du dépôt, visibilité. Rendu à la propriétaire : UN lien (PR à fusionner ou tableau) et une ligne ; le détail technique sur demande.

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

## 10. Charte qualité mobile — standard produit réel

Décision du propriétaire du 2026-10-08, après la première passe sur appareil réel. Elle s'applique à **toute étape mobile**. Une étape qui ne la respecte pas n'est **pas terminée**, quel que soit l'état des tests JVM et de `make check`.

1. **Appareil réel obligatoire.** Chaque étape est vérifiée sur un appareil physique (téléphone, et tablette si disponible) avant d'être déclarée terminée. Les tests JVM ne suffisent pas. Les parcours critiques ont des tests d'interface instrumentés.
2. **Champs de saisie.** Chaque champ est touchable, ouvre le clavier, du bon type, impose sa longueur maximale, et se trouve à côté de son libellé. Le bouton de validation reste désactivé tant que la saisie n'est pas valide.
3. **Messages d'erreur.** Jamais de message générique quand la cause est connue. Chaque erreur dit ce qui s'est passé et quoi faire. Le message générique est réservé aux causes réellement inconnues.
4. **Préconditions vérifiées AVANT d'agir** : verrouillage d'écran configuré, version d'Android, réseau. Chacune a son écran explicatif, avec un lien vers les réglages.
5. **État conservé** à la rotation ET à la mort du processus (retour depuis les Réglages). Les secrets (PIN, OTP, code de secours, jeton) ne sont JAMAIS conservés.
6. **Secrets à l'écran** : protection contre les captures ET masquage pour les services d'accessibilité ; champs masqués ; jamais dans les journaux ; effacés après usage. Le compromis avec les lecteurs d'écran pour malvoyants est documenté.
7. **Session** : expiration après inactivité, verrouillage après passage en arrière-plan, durée maximale, imposées par le serveur (inactivité 5 min, arrière-plan 60 s, durée maximale 30 min — décision du propriétaire du 2026-10-10 ; valeurs en paramètres nommés dans la configuration, jamais en dur).
8. **Mise en page** vérifiée sur téléphone ET tablette, et avec une taille de police à 200 %. Aucun texte coupé, aucun espace manquant.
9. **Rapports** : « conforme » seulement pour ce qui a été observé. Sinon : « non observé ».
10. **Tout secret** apparaissant dans un journal, un fichier temporaire ou un rapport est un incident : il est supprimé, et le secret est considéré comme compromis.
