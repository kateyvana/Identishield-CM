# Backlog du mois 2 — instrument de travail

**Date :** 2026-08-29 · **Statut :** vivant (mis à jour à chaque changement d'état)
**Sources :** ISC-SKEL §9 (titres, exigences, durées, dépendances) · amendement du 2026-08-29 (cinq rattachements + T2-19) · `docs/requirements-index.csv` (libellés exacts) · ISC-SRS v1.2 (comportement attendu).
**Gabarit :** ISC-PROC §4.2 — sept champs par carte.

> Règle de lecture : le **critère de vérification** nomme une commande ou un test, pas une intention. Le **comportement attendu** des tâches de sécurité porte au moins une **assertion négative** (ce qui NE DOIT PAS se produire), car un test qui vérifie « la décision est FREEZE » sans vérifier « le solde n'a pas bougé » passe au vert sur un système qui gèle **et** débite (ISC-TEST §4.1).

## Tableau récapitulatif

| ID | Titre court | Durée | Dépend de | Sécurité | État |
|---|---|---|---|---|---|
| T2-01 | Schémas et rôles base | 1 j | — | **oui** | Backlog |
| T2-02 | Abstraction d'horloge | 1 j | — | non | Backlog |
| T2-03 | Configuration versionnée | 1 j | T2-02 | non | Backlog |
| T2-04 | mTLS cœur↔PDP | 2 j | T2-01 | **oui** | Backlog |
| T2-05 | POST /evaluate | 2 j | T2-03, T2-04 | non | Backlog |
| T2-06 | Journal d'audit chaîné | 2 j | T2-01, T2-05 | **oui** | Backlog |
| T2-07 | Cœur minimal | 2 j | T2-05 | non | Backlog |
| T2-08 | Canal USSD | 2 j | T2-07 | non | Backlog |
| T2-09 | Console v1 | 2 j | T2-05 | non | Backlog |
| T2-10 | HLR simulé | 2 j | T2-07 | non | Backlog |
| T2-11 | Outil agent HLR | 1 j | T2-10 | non | Backlog |
| T2-12 | OTP | 1 j | T2-10 | **oui** | Backlog |
| T2-13 | Types d'opération restants | 1 j | T2-07 | non | Backlog |
| T2-14 | Notifications simulées | 1 j | T2-07 | non | Backlog |
| T2-15 | Mode sans couche | 1 j | T2-09 | non | Backlog |
| T2-16 | Outil attaquant (AC-02) | 2 j | T2-11, T2-12 | non | Backlog |
| T2-17 | Console avancée (attaque) | 2 j | T2-10, T2-16 | non | Backlog |
| T2-18 | Test T-AC-02 phase 1 | 1 j | T2-16, T2-15 | non | Backlog |
| T2-19 | Console : intégrité + notifications | 2 j | T2-06, T2-14 | non | Backlog |

**Total : 29 jours de travail effectif** (18 tâches ISC-SKEL + T2-19). Le mois compte ~20 jours ouvrés ; l'écart est absorbé par la parallélisation (voir chemin critique) et la marge du mois 7 — **compteur à surveiller** : si le mois 3 déborde de la même façon, replanifier plutôt qu'absorber.

## Chemin critique vers le jalon AC-02

Le jalon (« AC-02 réussit, scriptée, **visible** en console ») dépend de T2-01..18. La **chaîne de dépendance la plus longue** est :

> **T2-01 → T2-04 → T2-05 → T2-07 → T2-10 → (T2-11 ∥ T2-12) → T2-16 → T2-17 → T2-18** (≈ **15 jours**).

**T2-12 (OTP) est sur le chemin critique**, en parallèle de T2-11 : AC-02 est le vidage post-SIM swap sur USSD, et le scénario suppose que l'attaquant **reçoit l'OTP** sur son appareil après la réassignation d'IMSI. Sans OTP fonctionnel, T2-16 ne peut pas exécuter le scénario et T2-18 ne peut rien prouver. T2-11 (réassignation) et T2-12 (OTP) doivent donc **tous deux** être finis avant T2-16.

Ces tâches **bloquent le jalon** : tout retard sur l'une le décale. Les autres sont **hors chemin critique** et parallélisables — leur retard n'affecte pas la date du jalon tant qu'elles finissent avant leur dépendante : **T2-02, T2-03, T2-06, T2-08, T2-09, T2-13, T2-14, T2-15, T2-19**. (T2-06 et T2-08 appartiennent au squelette mais pas au fil de l'attaque AC-02 ; T2-19 est postérieure au jalon dans l'esprit — vues de lecture.)

---

## Cartes de tâches

### T2-01 — Schémas et rôles base

- **Exigences couvertes** : **ED-01** (un schéma par service, aucune table partagée) · **ED-06** (table d'audit en INSERT seul, aucun UPDATE/DELETE) · **ED-02** (le cœur persiste Compte, AbonnéHLR, Transaction, Session, OTP, Notification — *rattachement, amendement du 29/08*) · obligations **OB-3**, **OB-4**.
- **Comportement attendu** : deux schémas `core` et `pdp`, aucune table partagée ni clé étrangère inter-schémas. Table d'audit possédée par l'administrateur ; colonne d'ordre `bigint GENERATED ALWAYS AS IDENTITY` (OB-4) ; table `chain_head`. Rôle applicatif restreint à `SELECT, INSERT` sur l'audit ; `REVOKE EXECUTE ON ALL FUNCTIONS FROM PUBLIC` dans le schéma d'audit (OB-3). **Assertions négatives** : le rôle applicatif NE DOIT PAS pouvoir `UPDATE`, `DELETE`, `TRUNCATE` la table d'audit, NE DOIT PAS pouvoir exécuter une fonction héritée de PUBLIC, et l'insertion « entre deux » NE DOIT PAS être possible (identité `GENERATED ALWAYS`).
- **Critère de vérification** : `make check-stack` affiche le rôle d'audit restreint avec `UPDATE=false`, `DELETE=false`, `TRUNCATE=false` ; un test SQL exécuté avec le rôle applicatif obtient `permission denied` sur UPDATE/DELETE/TRUNCATE et sur l'appel d'une fonction du schéma d'audit ; un `INSERT ... OVERRIDING SYSTEM VALUE` échoue.
- **Composant** : `isc-core` et `isc-pdp` (schémas de persistance).
- **Jalon** : mois 2.
- **Risque de sécurité** : **oui** — journal d'audit et rôles de base. Revue ligne à ligne consignée (ENF-08c).

### T2-02 — Abstraction d'horloge

- **Exigences couvertes** : **EF-18** (PDP accepte une horloge injectable) · **ENF-11** (le temps est injectable ; aucune règle ne lit l'horloge système) · obligation **OB-1**.
- **Comportement attendu** : interface `Clock`, implémentations `SystemClock` (production, confinée à un paquet `time`) et `VirtualClock` (tests/simulation). L'instant est reçu en paramètre par tout consommateur ; TA-CLOCK-01 **durcie** appliquée aux deux services (core, pdp). L'interface du futur moteur de règles reçoit l'instant en paramètre, jamais l'horloge (signature figée maintenant, OB-1). **Assertion négative** : aucun chemin de code hors paquet `time` NE DOIT lire l'horloge système (ni `Instant.now()`, ni `new Random()`, ni les chemins indirects fermés par SPIKE-4/tâche de durcissement).
- **Critère de vérification** : la suite ArchUnit `*ArchitectureTest` (TA-CLOCK-01 durcie) est verte sur core et pdp ; un test place `VirtualClock` à `t+47 h` et obtient le comportement attendu en moins d'une seconde réelle (critère ISC-SKEL §6.9).
- **Composant** : `isc-pdp` et `isc-core`.
- **Jalon** : mois 2.
- **Risque de sécurité** : non (infrastructure) — mais OB-1 s'applique et sa violation ne produit aucun symptôme visible.

### T2-03 — Configuration versionnée

- **Exigences couvertes** : **EF-17** (charger règles et paramètres depuis une configuration versionnée ; version portée par chaque décision) · **ES-15** (paramètres nommés configurables sans modification de code et versionnés).
- **Comportement attendu** : les paramètres nommés vivent dans une configuration versionnée ; la version du jeu de règles est chargée et **portée par chaque décision** (`/evaluate` la renvoie). Une porte refuse tout paramètre nommé en littéral dans le code. **Assertion négative** : aucun paramètre nommé NE DOIT apparaître en littéral dans le code de production.
- **Critère de vérification** : la porte de littéraux échoue si un paramètre nommé est écrit en dur (test à valeur négative : injecter un littéral, la porte passe au rouge, le retirer, elle repasse au vert) ; la réponse `/evaluate` contient un champ `version du jeu de règles` non vide.
- **Composant** : `isc-pdp`.
- **Jalon** : mois 2.
- **Risque de sécurité** : non.
- **Paramètres et mécanisme (décision, source ISC-TM v1.1 §7)** : la liste fait autorité et compte **onze** paramètres nommés — CAR_IMSI, CAR_ENROL, CAP_PROB, THR_HIGH, THR_4EYES, TTL_FREEZE, NIGHT, VEL, DEFER, plus **ANCHOR_INTERVAL** et **ANCHOR_MAX_DELAY** (ajoutés par SPIKE-3). Le mécanisme de détection des littéraux est une **règle d'architecture**, comme TA-CLOCK-01 : elle interdit toute constante numérique nue dans les classes de règles et de politique, hors du composant de configuration. Comme toute liste noire, elle n'est pas exhaustive (même limite que TA-CLOCK-01) ; la vraie défense est que **les paramètres n'existent qu'à un seul endroit**.

### T2-04 — mTLS cœur↔PDP

- **Exigences couvertes** : **ES-11** (canal cœur↔PDP mutuellement authentifié ; fail-closed au-dessus de `CAP_PROB` si PDP indisponible ; `/evaluate` limité en débit) · **EI-01** (interface Cœur→PDP : POST /evaluate, GET /nonce, POST /freeze/{id}/release) · obligations **OB-5**, **OB-6** · risque **RA-11**.
- **Comportement attendu** : autorité locale générée au démarrage ; PDP Spring Boot en `server.ssl.client-auth=need` (rejet au **handshake**, OB-5) ; client PDP du cœur en **connexions persistantes**, vérifiant la **chaîne ET le nom** du serveur (OB-6). **Les quatre tentatives de SPIKE-4 sont rejouées sur le vrai PDP** (RA-11) : sans certificat, autre autorité, certificat expiré (refus au handshake), et faux PDP (refus par signature *et* par nom). **Assertions négatives** : un appel sans certificat client NE DOIT PAS atteindre la couche applicative (refus au handshake, pas un code 400) ; le cœur NE DOIT PAS accepter un PDP auto-signé ni un PDP dont le nom ne correspond pas.
- **Critère de vérification** : un test rejoue les quatre tentatives et vérifie `http_code=000` (échec handshake) pour les trois refus serveur et l'échec TLS côté client pour le faux PDP (critères ISC-SKEL §6.5 et §6.6) ; la latence de `/evaluate` en connexion persistante est mesurée et rapportée (§6.12).
- **Composant** : `isc-core` et `isc-pdp`.
- **Jalon** : mois 2.
- **Risque de sécurité** : **oui** — cryptographie (mTLS, certificats). Revue ligne à ligne consignée (ENF-08c).

### T2-05 — POST /evaluate

- **Exigences couvertes** : **EF-10** (le PDP expose POST /evaluate recevant compte, MSISDN, IMSI, canal, type, montant, bénéficiaire, horodatage serveur, localité, empreinte/signature optionnelles, nonce) · **EF-11** (la réponse contient décision, score, règles avec contribution, action, identifiant de décision, version du jeu de règles) · **ENF-03** (aucune décision perdue ; exactement une entrée d'audit par /evaluate, idempotence par identifiant).
- **Comportement attendu** : `/evaluate` respecte le contrat OpenAPI en entrée comme en sortie ; le PDP **n'applique aucune règle** et répond `ALLOW`, score 0, règles déclenchées vide, action `NONE`, `decisionId` et version du jeu de règles. **Idempotence** : deux appels avec le même `decisionId` produisent la **même réponse** et **une seule** entrée d'audit. **Assertion négative** : deux appels au même `decisionId` NE DOIVENT PAS produire deux entrées d'audit ni deux débits.
- **Critère de vérification** : un test envoie deux fois le même `decisionId` et vérifie réponses identiques + `SELECT count(*) FROM pdp.audit_entry WHERE ...` = 1 (critère ISC-SKEL §6.3) ; la validation de contrat (`redocly lint`) passe sur requête et réponse.
- **Composant** : `isc-pdp`.
- **Jalon** : mois 2.
- **Risque de sécurité** : non (couche API) — mais l'invariant « exactement une entrée d'audit » est une propriété de sécurité et porte son assertion négative ci-dessus.

### T2-06 — Journal d'audit chaîné

- **Exigences couvertes** : **ES-09** (journalisation en écriture seule, chaque enregistrement portant l'empreinte SHA-256 du précédent ; empreinte de tête signée et exportée périodiquement) · obligation **OB-2**.
- **Comportement attendu** : chaque décision écrit une entrée chaînée ; l'`index d'ordre` est assigné **à l'intérieur du verrou** sur `chain_head` (OB-2). Vérification complète et incrémentale disponibles. **Assertions négatives** : sous concurrence, la vérification NE DOIT PAS produire de fausses ruptures (l'ordre de chaîne ne doit pas diverger de l'ordre d'insertion — le défaut des 4 589/5 000 de SPIKE-3) ; une ligne altérée NE DOIT PAS passer inaperçue.
- **Critère de vérification** : après mille transactions, la vérification complète est verte ; l'altération d'une ligne est détectée **et localisée** (critère ISC-SKEL §6.4) ; un test à dix écrivains concurrents suivi d'une vérification par les liens montre zéro fausse rupture.
- **Composant** : `isc-pdp`.
- **Jalon** : mois 2.
- **Risque de sécurité** : **oui** — journal d'audit. Revue ligne à ligne consignée (ENF-08c).

### T2-07 — Cœur minimal

- **Exigences couvertes** : **EF-01** (comptes MSISDN fictifs, solde, historique, état de confiance) · **EF-04** (transfert P2P — les autres types en T2-13) · **EF-05** (avant tout commit d'une sortie sensible, appeler /evaluate et appliquer la décision) · **ED-02** (persistance des entités — *rattachement, amendement du 29/08*).
- **Comportement attendu** : comptes dans la plage MSISDN fictive (699000000–699009999), avec solde entier FCFA. Le cœur appelle `/evaluate` **avant** tout commit et applique la décision : `ALLOW` → commit, transaction `EXECUTED`, solde débité. **Fail-closed** : si le PDP est indisponible, une sortie `> CAP_PROB` est **refusée**, une sortie `≤ CAP_PROB` est exécutée et journalisée en **mode dégradé**. **Assertion négative** : aucune sortie sensible NE DOIT être commitée sans passage par `/evaluate` ; en mode dégradé, une sortie `> CAP_PROB` NE DOIT PAS être exécutée.
- **Critère de vérification** : un test PDP arrêté vérifie qu'une sortie `> CAP_PROB` est refusée et qu'une sortie `≤ CAP_PROB` est exécutée et journalisée en mode dégradé (critère ISC-SKEL §6.7) ; une transaction USSD traverse le fil et est exécutée (§6.2).
- **Composant** : `isc-core`.
- **Jalon** : mois 2.
- **Risque de sécurité** : non (logique métier) — mais le fail-closed est un contrôle de sécurité et porte son assertion négative.

### T2-08 — Canal USSD

- **Exigences couvertes** : **EF-06** (canal USSD simulé sous forme de machine à états de session, sans capacité cryptographique côté client).
- **Comportement attendu** : une machine à états de session USSD, une seule branche : le transfert P2P (menu → saisie bénéficiaire → montant → confirmation → soumission au cœur). Aucun secret cryptographique côté client. **Assertion négative** : la session USSD NE DOIT porter aucune capacité cryptographique côté client (pas de clé, pas de signature).
- **Critère de vérification** : un test déroule la branche de transfert de bout en bout et vérifie qu'une soumission atteint le cœur à l'état attendu ; revue confirme l'absence de primitive cryptographique côté session.
- **Composant** : `isc-core`.
- **Jalon** : mois 2.
- **Risque de sécurité** : non.

### T2-09 — Console v1

- **Exigences couvertes** : **EF-30** (flux temps réel des transactions avec décision, score, raisons) · **EF-26** (interface en français — *rattachement, amendement du 29/08*) · **EF-38** (console en français, anglais recommandé — *rattachement, amendement du 29/08*).
- **Comportement attendu** : la console affiche en temps réel le flux des transactions avec décision (ALLOW/STEP_UP/FREEZE), score et raisons, et le `decisionId`. **Française dès le premier écran** (EF-26, EF-38 ; traduire après coup coûte plus cher). **Assertion négative** : aucun texte d'interface NE DOIT être livré dans une autre langue que le français (l'anglais reste optionnel).
- **Critère de vérification** : la console affiche la transaction et sa décision en moins de deux secondes (critère ISC-SKEL §6.10) ; une revue des chaînes d'interface confirme le français.
- **Composant** : `isc-console`.
- **Jalon** : mois 2.
- **Risque de sécurité** : non.

### T2-10 — HLR simulé

- **Exigences couvertes** : **EF-02** (HLR associant chaque MSISDN à un IMSI courant, historique des réassignations : horodatage serveur, agent, motif) · **EF-08** (émettre un événement « changement d'IMSI », équivalent CAMARA Sim Swap) · **EI-02** (interface HLR→Cœur : événement de changement d'IMSI).
- **Comportement attendu** : le HLR associe MSISDN→IMSI courant et conserve l'historique complet des réassignations (horodatage serveur via l'horloge injectée, agent, motif). Un changement d'IMSI émet un événement interne portant l'horodatage du dernier changement. **Assertion négative** : un changement d'IMSI NE DOIT PAS effacer l'historique précédent (traçabilité de la réassignation).
- **Critère de vérification** : un test provoque une réassignation et vérifie que l'historique conserve l'ancien IMSI + horodatage + agent + motif, et qu'un événement de changement est émis avec le bon horodatage.
- **Composant** : `isc-core`.
- **Jalon** : mois 2.
- **Risque de sécurité** : non — mais le changement d'IMSI est la surface d'attaque du SIM swap (AC-02).

### T2-11 — Outil agent HLR

- **Exigences couvertes** : **EF-42** (remplacement de SIM avec motif et identifiant d'agent, UC-11, mode légitime ou complice).
- **Comportement attendu** : un outil permettant un remplacement de SIM (réassignation d'IMSI) portant un motif et un identifiant d'agent, utilisable en mode légitime ou complice. Chaque action est attribuée à son agent dans l'historique HLR. **Assertion négative** : aucune réassignation NE DOIT être possible sans identifiant d'agent ni motif.
- **Critère de vérification** : un test exécute un remplacement via l'outil et vérifie que l'historique HLR porte l'identifiant d'agent et le motif ; un appel sans agent/motif est refusé.
- **Composant** : `isc-hlr-agent`.
- **Jalon** : mois 2.
- **Risque de sécurité** : non.

### T2-12 — OTP

- **Exigences couvertes** : **EF-03** (OTP à usage unique émis vers l'IMSI courant du MSISDN, pour reproduire la faiblesse réelle).
- **Comportement attendu** : le cœur émet un OTP à **usage unique** vers l'**IMSI courant** du MSISDN, avec **expiration**. C'est délibérément la faiblesse réelle : l'OTP suit l'IMSI courant, donc un SIM swap le détourne. **Assertions négatives** : un OTP NE DOIT PAS être réutilisable (usage unique), NE DOIT PAS être accepté après expiration, et NE DOIT PAS être émis vers un ancien IMSI.
- **Critère de vérification** : un test vérifie qu'un OTP consommé est refusé au second usage, qu'un OTP expiré est refusé, et qu'après un changement d'IMSI l'OTP part vers le nouvel IMSI.
- **Composant** : `isc-core`.
- **Jalon** : mois 2.
- **Risque de sécurité** : **oui** — credential à usage unique (proche d'un nonce), au cœur de la faiblesse SIM swap. Revue ligne à ligne consignée (ENF-08c).
- **Forme et stockage (décision)** : EF-03 ne fixe ni durée ni forme **volontairement** — ce sont des paramètres, pas des constantes. **Six chiffres décimaux, expiration cinq minutes, usage unique**, valeurs **versionnées dans la configuration** comme les onze autres paramètres nommés (T2-03). Le **stockage est une empreinte, jamais l'OTP en clair** dans la base — même en sandbox, un OTP en clair est une mauvaise habitude qui se remarque en soutenance.

### T2-13 — Types d'opération restants

- **Exigences couvertes** : **EF-04** (cash-in, cash-out, paiement marchand, facture, achat de crédit — le transfert P2P est fait en T2-07).
- **Comportement attendu** : les cinq types d'opération restants sont supportés par le cœur, chacun passant par `/evaluate` avant commit comme le transfert P2P. **Assertion négative** : aucun de ces types NE DOIT commiter une sortie sensible sans passage par `/evaluate`.
- **Critère de vérification** : un test par type vérifie l'appel à `/evaluate` avant commit et l'application de la décision.
- **Composant** : `isc-core`.
- **Jalon** : mois 2.
- **Risque de sécurité** : non.

### T2-14 — Notifications simulées

- **Exigences couvertes** : **EF-09** (sous-système de notification simulé : SMS vers IMSI courant, push vers dispositif enrôlé, consultable console et app) · **ENF-17** (toute notification de sécurité contient type d'événement, horodatage serveur, action possible, et NE contient NI lien cliquable NI demande de secret — anti-hameçonnage).
- **Comportement attendu** : boîte SMS (vers IMSI courant) et push (vers dispositif enrôlé), consultables. Le contenu est **contraint** : type d'événement + horodatage serveur + action possible. **Assertion négative (ENF-17)** : une notification de sécurité NE DOIT PAS contenir de lien cliquable ni demander de secret.
- **Critère de vérification** : un test vérifie qu'une notification émise porte type + horodatage + action, et qu'un test à valeur négative rejette toute notification contenant un lien cliquable ou une demande de secret.
- **Composant** : `isc-core`.
- **Jalon** : mois 2.
- **Risque de sécurité** : non (au sens crypto/rôles/audit) — mais ENF-17 est une exigence de sécurité et son assertion négative est obligatoire ci-dessus.

### T2-15 — Mode sans couche

- **Exigences couvertes** : **EF-07** (mode sans couche pour la démonstration avant/après, affiché sans ambiguïté) · **EF-36** (la console affiche l'état du PDP — disponible/dégradé/indisponible — et le mode sans couche).
- **Comportement attendu** : une bascule active/désactive la couche de sécurité ; la console affiche un **bandeau non ambigu** de l'état (mode sans couche, état du PDP). **Assertion négative** : en mode sans couche, la console NE DOIT PAS laisser croire que la protection est active (bandeau non ambigu).
- **Critère de vérification** : un test bascule le mode et vérifie que la console affiche l'état correct (disponible/dégradé/indisponible + mode sans couche) sans ambiguïté.
- **Composant** : `isc-core` et `isc-console`.
- **Jalon** : mois 2.
- **Risque de sécurité** : non.

### T2-16 — Outil attaquant (AC-02)

- **Exigences couvertes** : **EF-40** (l'outil attaquant exécute chacun des scénarios AC-01 à AC-13 par une commande unique paramétrable).
- **Comportement attendu** : l'ossature de l'outil attaquant, et le **scénario AC-02** exécutable par une commande unique paramétrable. AC-02 enchaîne la réassignation d'IMSI (SIM swap via l'agent HLR complice, T2-11) puis l'interception de l'OTP (T2-12) détourné vers le nouvel IMSI. **Assertion négative** : l'outil NE DOIT agir que sur des comptes de la plage fictive et via les interfaces simulées (aucune interaction avec un système réel).
- **Critère de vérification** : la commande `make demo-attack` (ou équivalent) exécute AC-02 en mode sans couche et l'attaque réussit ; l'outil publie chaque étape (préparation de T2-17).
- **Composant** : `isc-attacker`.
- **Jalon** : mois 2.
- **Risque de sécurité** : non (outil de test) — mais tout scénario doit rester dans le sandbox (assertion négative ci-dessus).
- **Scénario (décision, source ISC-TM v1.1 §8)** : AC-02 fait autorité dans **ISC-TM v1.1 §8**, pas dans ISC-SKEL — **à lire avant T2-16**. AC-02 est le vidage post-SIM swap sur USSD : réassignation d'IMSI (agent complice, T2-11) puis interception de l'OTP détourné vers le nouvel appareil (T2-12), puis vidage du solde.

### T2-17 — Console avancée (attaque)

- **Exigences couvertes** : **EF-31** (état HLR d'un compte, changement d'IMSI signalé) · **EF-32** (chronologie d'attaque alimentée par l'outil attaquant) · **EF-41** (l'outil publie chaque étape dans la chronologie de la console).
- **Comportement attendu** : la console affiche l'état HLR d'un compte et **signale tout changement d'IMSI** ; elle affiche une **chronologie d'attaque** alimentée en temps réel par l'outil attaquant (chaque étape publiée). C'est la moitié « visible » du jalon. **Assertion négative** : un changement d'IMSI NE DOIT PAS passer silencieux dans la console (signalé).
- **Critère de vérification** : un test exécute AC-02 et vérifie que la console signale le changement d'IMSI et affiche la chronologie complète des étapes publiées par l'outil.
- **Composant** : `isc-console`.
- **Jalon** : mois 2.
- **Risque de sécurité** : non.

### T2-18 — Test T-AC-02 phase 1

- **Exigences couvertes** : **ENF-09** (chaque AC correspond à un test d'intégration T-AC-xx exécuté en CI) · critère **CR-01**.
- **Comportement attendu** : le test d'intégration **T-AC-02 phase 1** : en **mode sans couche**, l'attaque AC-02 **réussit**, avec ses assertions. Conformément à la règle des tests de cas d'abus (CLAUDE.md §5), le test porte des **assertions négatives**. **Assertions négatives** : en phase 1 (sans couche), le test vérifie que l'attaque aboutit *et* que rien d'autre n'est perturbé au-delà du scénario (pas d'effet de bord non prévu) ; la phase 2 (avec couche, l'attaque échoue) viendra au mois 4.
- **Critère de vérification** : `make abuse` (ou la cible ciblée `T-AC-02`) exécute le test en CI et il passe (l'attaque réussit en mode sans couche), avec les assertions négatives.
- **Composant** : `isc-attacker` et la suite de tests d'intégration.
- **Jalon** : mois 2 (jalon lui-même).
- **Risque de sécurité** : non (test) — mais c'est un test de cas d'abus (T-AC) qui DOIT porter des assertions négatives.
- **Oracle de succès (décision, source ISC-TM v1.1 §8)** : la phase 1 est réussie quand, en mode sans couche, **le solde est vidé, le transfert est exécuté vers un bénéficiaire inconnu, aucune alerte n'est émise et le téléphone de la victime reste silencieux**. Ce sont les assertions positives et négatives du test T-AC-02 phase 1.

### T2-19 — Console : intégrité du journal + boîte de notifications

*(Tâche ajoutée par l'amendement du 2026-08-29.)*

- **Exigences couvertes** : **EF-34** (la console affiche l'intégrité du journal d'audit et localise une rupture) · **EF-37** (la console affiche les notifications émises — boîte SMS/push — par compte).
- **Comportement attendu** : deux **vues de lecture seule** dans la console : (1) l'état d'intégrité du journal d'audit, avec **localisation d'une rupture** le cas échéant — c'est l'acte 4 de la démonstration au jury ; (2) la boîte des notifications émises par compte. Aucune écriture, aucune logique métier. **Assertion négative** : ces vues NE DOIVENT rien écrire ni modifier (lecture seule stricte) ; une rupture de chaîne NE DOIT PAS être affichée comme « intègre ».
- **Critère de vérification** : un test altère une entrée d'audit et vérifie que la vue d'intégrité affiche la rupture **et sa position** ; un test vérifie que la boîte affiche les notifications d'un compte donné ; une revue confirme l'absence d'écriture.
- **Composant** : `isc-console`.
- **Jalon** : mois 2 (renforce la partie console, hors chemin critique de l'attaque).
- **Risque de sécurité** : non (vues de lecture) — mais s'appuie sur le journal d'audit produit par T2-06.

---

## Annexe — les sept obligations d'ISC-SAD §1bis et où elles s'appliquent

Une obligation qu'il faut aller chercher dans un autre document ne sera pas respectée. Elles s'appliquent **dès la première ligne** des tâches ci-dessous.

| Obligation | Énoncé (résumé) | Tâches où elle s'applique |
|---|---|---|
| **OB-1** | Toute règle temporelle est une fonction pure de (historique, instant), fenêtre recalculée à chaque évaluation ; aucun état mutable élagué au fil du temps. | **T2-02** (signature du moteur : instant en paramètre, figée maintenant). S'imposera à toute règle au mois 4. |
| **OB-2** | L'index d'ordre de la chaîne d'audit est assigné à l'intérieur du verrou. | **T2-06** (chaînage) ; le schéma de **T2-01** prépare `chain_head`. |
| **OB-3** | `REVOKE EXECUTE ON ALL FUNCTIONS FROM PUBLIC` par défaut dans le schéma d'audit. | **T2-01** (script d'initialisation du schéma). |
| **OB-4** | La colonne d'ordre du journal est `GENERATED ALWAYS AS IDENTITY`. | **T2-01** (définition de la table d'audit). |
| **OB-5** | Le terminateur TLS du PDP rejette au niveau de la poignée de main ; un refus applicatif est un défaut. | **T2-04** (mTLS, `client-auth=need`). |
| **OB-6** | Le client PDP du cœur utilise des connexions persistantes et vérifie la chaîne ET le nom du serveur. | **T2-04** (client PDP du cœur). |
| **OB-7** | L'ancienne clé de dispositif n'est révoquée qu'à la fin de la probation de ré-enrôlement. | **Sans objet au mois 2** (aucun enrôlement) ; à respecter au mois 4. |
