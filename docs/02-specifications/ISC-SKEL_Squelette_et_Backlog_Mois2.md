# ISC-SKEL — Squelette marchant et backlog du mois 2

| Champ | Valeur |
|---|---|
| Identifiant | ISC-SKEL |
| Version | 1.0 |
| Statut | REVIEW |
| Date | 28 août 2026 |
| Documents amont | ISC-SRS v1.2 · ISC-SAD v1.1 (§1bis obligations, §1ter enveloppe) · ISC-TEST v1.0 · ISC-PROC v1.0 · ISC-DEPLOY v1.0 |
| Objet | Définir le premier code de production du projet, puis découper le mois 2 en tâches tracées vers les exigences. |

---

## PARTIE I — LE SQUELETTE MARCHANT

### 1. Pourquoi un squelette, et pas un composant

Un composant est trop gros pour être un point de départ. Construire le PDP en entier prendrait trois semaines pendant lesquelles rien ne prouverait qu'il s'assemble avec le reste. Le jour de l'assemblage, on découvrirait que le contrat diverge, que le mTLS ne se négocie pas comme dans le spike, que la console ne reçoit rien. Trois semaines bâties sur des suppositions d'intégration.

Le squelette marchant est le fil le plus mince qui traverse le système de bout en bout. Il touche cinq conteneurs et n'implémente aucune logique métier.

Deux propriétés le rendent possible maintenant :

- il ne dépend d'aucune décision encore ouverte — ADR-016, la fonction d'agrégation du score, ne le concerne pas puisqu'il n'y a pas de score ;
- les cinq spikes ont validé chacun des mécanismes qu'il traverse.

### 2. Le fil, étape par étape

1. Une session USSD simulée soumet un transfert P2P au cœur.
2. Le cœur crée la transaction à l'état `PENDING_DECISION` et génère un `decisionId` (UUID v4).
3. Le cœur appelle `POST /evaluate` sur le PDP, en mTLS, sur une connexion persistante.
4. Le PDP reçoit le contexte, **n'applique aucune règle**, et répond `ALLOW` avec un score de 0 et une liste de règles déclenchées vide.
5. Le PDP écrit une entrée d'audit chaînée, index d'ordre assigné dans le verrou.
6. Le cœur applique la décision : commit, transaction à `EXECUTED`, solde débité.
7. La console affiche la transaction, sa décision et son `decisionId`, en temps réel.

### 3. Ce que le squelette ne fait pas

Aucune règle comportementale. Aucun score. Aucune machine à états de confiance. Aucune signature de dispositif. Aucun nonce. Aucun gel. Aucune notification. Aucun contact de confiance. Aucune application Android. Aucun outil attaquant. Aucun changement d'IMSI.

Un seul type d'opération : le transfert P2P. Un seul canal : USSD. Une seule décision possible : `ALLOW`.

Cette liste est aussi importante que la précédente. Tout ce qui y figure et serait implanté malgré tout est du travail hors périmètre, à refuser en revue.

### 4. Exigences couvertes, et à quel degré

| Exigence | Degré | Ce que le squelette en fait |
|---|---|---|
| EF-01 | partiel | Comptes avec MSISDN fictif, solde, sans état de confiance |
| EF-04 | partiel | Transfert P2P uniquement ; les cinq autres types viendront |
| EF-05 | **complet** | Le cœur appelle `/evaluate` avant tout commit |
| EF-06 | partiel | Une seule branche de la machine à états USSD |
| EF-10 | **complet en forme** | Le contrat de requête est respecté intégralement |
| EF-11 | **complet en forme** | Décision, score, règles, action, `decisionId`, version du jeu de règles |
| EF-17 | **complet** | Version du jeu de règles chargée depuis la configuration et portée par la décision |
| EF-18 | **complet** | Horloge injectable, `SystemClock` et `VirtualClock` |
| EF-30 | partiel | Flux temps réel avec décision et raisons ; sans état HLR ni chronologie |
| ES-09 | partiel | Chaînage, écriture seule, index dans le verrou ; sans ancre signée |
| ES-11 | **complet** | mTLS mutuel, rejet au handshake, connexions persistantes, fail-closed au-dessus de `CAP_PROB` |
| ENF-03 | **complet** | Idempotence par `decisionId`, exactement une entrée d'audit |
| ENF-06 | **complet** | Journaux JSON corrélés par `decisionId` |
| ENF-07 | **complet** | Plage MSISDN fictive, réseau isolé, TLS entre services |
| ENF-11 | **complet** | Aucune lecture d'horloge statique, vérifié par TA-CLOCK-01 durcie |
| ED-01 | **complet** | Un schéma par service, aucune table partagée |
| ED-04 | **complet** | Horodatages UTC serveur via l'horloge injectée |
| ED-05 | **complet** | Montants entiers en FCFA |
| ED-06 | **complet** | Rôle applicatif en INSERT seul sur la table d'audit |
| ED-09 | **complet** | UUID v4, plage MSISDN fictive |
| EI-01 | **complet** | Contrat OpenAPI, mTLS, délai 800 ms, codes d'erreur |

Vingt et une exigences touchées, dont quinze complètes. Aucune exigence de sécurité comportementale n'est concernée : elles arrivent au mois 4.

### 5. Les sept obligations, dès la première ligne

Elles ne sont pas des recommandations pour plus tard. Le squelette est précisément le moment où les respecter coûte le moins.

| Obligation | Application dans le squelette |
|---|---|
| OB-1 — règles pures | Aucune règle n'existe encore, mais l'interface du moteur DOIT recevoir l'instant en paramètre, jamais l'horloge. La signature est figée ici et ne changera plus. |
| OB-2 — index d'ordre dans le verrou | Le `chain_index` est assigné à l'intérieur du verrou sur `chain_head` dès la première entrée d'audit. |
| OB-3 — `REVOKE EXECUTE FROM PUBLIC` | Appliqué dans le script d'initialisation du schéma d'audit. |
| OB-4 — `GENERATED ALWAYS` | La colonne d'ordre est une colonne d'identité `GENERATED ALWAYS`. |
| OB-5 — rejet au handshake | Le PDP Spring Boot en `client-auth=need`. **Les quatre tentatives de SPIKE-4 sont rejouées sur le vrai PDP** — c'est le risque résiduel RA-11. |
| OB-6 — connexions persistantes | Le client PDP du cœur réutilise ses connexions, et vérifie la chaîne ET le nom du serveur. |
| OB-7 — révocation différée | Sans objet ici : aucun enrôlement. À respecter au mois 4. |

### 6. Critères d'acceptation

| # | Critère | Vérification |
|---|---|---|
| 1 | `make up` démarre la pile complète ; `make check-stack` vert | D |
| 2 | Une transaction USSD traverse le fil et est exécutée | T |
| 3 | Exactement une entrée d'audit par décision ; deux appels avec le même `decisionId` produisent la même réponse et une seule entrée | T |
| 4 | La chaîne d'audit est intègre après mille transactions ; l'altération d'une ligne est détectée et localisée | T |
| 5 | Un appel au PDP sans certificat client est refusé **au niveau de la poignée de main**, pas par un code applicatif | T |
| 6 | Le cœur refuse un PDP dont le certificat est auto-signé, et un PDP dont le nom ne correspond pas | T |
| 7 | Le PDP arrêté : une sortie supérieure à `CAP_PROB` est refusée, une sortie inférieure est exécutée et journalisée en mode dégradé | T |
| 8 | La suite ArchUnit est verte, TA-CLOCK-01 durcie comprise | T |
| 9 | Un test place l'horloge à `t+47 h` et obtient le comportement attendu, en moins d'une seconde réelle | T |
| 10 | La console affiche la transaction et sa décision en moins de deux secondes | D |
| 11 | Aucun paramètre nommé en littéral dans le code | T |
| 12 | La latence de `/evaluate` est mesurée et rapportée, à titre de référence pour ENF-01 | A |

### 7. Ce qui serait un échec

Un squelette qui fonctionne mais dont le PDP refuse au niveau applicatif au lieu du handshake : OB-5 violée, RA-11 non levé.

Un squelette dont l'entrée d'audit est écrite hors du verrou : OB-2 violée, le défaut réapparaîtra sous concurrence au mois 4 et sera très coûteux à diagnostiquer.

Un squelette qui implémente une règle « pour tester » : périmètre violé, à refuser en revue.

---

## PARTIE II — BACKLOG DU MOIS 2

### 8. Objectif du mois

Jalon ISC-PROC §5 : **l'attaque AC-02 réussit, scriptée, visible en console.**

Ce n'est pas « le cœur est terminé ». C'est la moitié gauche de la démonstration au jury, acquise dès le deuxième mois.

### 9. Les tâches, dans l'ordre de dépendance

| ID | Tâche | Exigences | Durée | Dépend de |
|---|---|---|---|---|
| **T2-01** | Schémas et rôles : `core` et `pdp`, table d'audit avec `GENERATED ALWAYS`, `chain_head`, rôle en INSERT seul, `REVOKE EXECUTE FROM PUBLIC` | ED-01, ED-06, OB-3, OB-4 | 1 j | — |
| **T2-02** | Abstraction d'horloge : interface, `SystemClock`, `VirtualClock`, TA-CLOCK-01 durcie dans les deux services | EF-18, ENF-11, OB-1 | 1 j | — |
| **T2-03** | Configuration versionnée : paramètres nommés, version portée par chaque décision, porte interdisant les littéraux | EF-17, ES-15 | 1 j | T2-02 |
| **T2-04** | mTLS entre cœur et PDP : autorité au démarrage, `client-auth=need`, connexions persistantes, vérification chaîne et nom. **Rejouer les quatre tentatives de SPIKE-4 sur le vrai PDP** | ES-11, EI-01, OB-5, OB-6, RA-11 | 2 j | T2-01 |
| **T2-05** | `POST /evaluate` : contrat complet, réponse `ALLOW` sans règle, idempotence par `decisionId` | EF-10, EF-11, ENF-03 | 2 j | T2-03, T2-04 |
| **T2-06** | Journal d'audit : chaînage, index dans le verrou, vérification complète et incrémentale | ES-09, OB-2 | 2 j | T2-01, T2-05 |
| **T2-07** | Cœur minimal : comptes, transferts P2P, appel à `/evaluate` avant commit, fail-closed | EF-01, EF-04, EF-05 | 2 j | T2-05 |
| **T2-08** | Canal USSD : machine à états de session, une branche de transfert | EF-06 | 2 j | T2-07 |
| **T2-09** | Console v1 minimale : flux temps réel, décision, `decisionId` | EF-30 | 2 j | T2-05 |
| — | **SQUELETTE MARCHANT COMPLET** — critères §6 | — | — | T2-01..09 |
| **T2-10** | HLR : association MSISDN vers IMSI, historique, événement de changement | EF-02, EF-08, EI-02 | 2 j | T2-07 |
| **T2-11** | Outil agent HLR : remplacement de SIM avec motif et identifiant d'agent | EF-42 | 1 j | T2-10 |
| **T2-12** | OTP : émission vers l'IMSI courant, usage unique, expiration | EF-03 | 1 j | T2-10 |
| **T2-13** | Types d'opération restants : cash-in, cash-out, marchand, facture, crédit | EF-04 | 1 j | T2-07 |
| **T2-14** | Notifications simulées : boîte SMS et push, contenu contraint sans lien cliquable | EF-09, ENF-17 | 1 j | T2-07 |
| **T2-15** | Mode sans couche : bascule, bandeau non ambigu en console | EF-07, EF-36 | 1 j | T2-09 |
| **T2-16** | Outil attaquant : ossature et scénario AC-02 | EF-40 | 2 j | T2-11, T2-12 |
| **T2-17** | Console : état HLR, changement d'IMSI signalé, chronologie d'attaque | EF-31, EF-32, EF-41 | 2 j | T2-10, T2-16 |
| **T2-18** | Test T-AC-02 phase 1 : l'attaque réussit en mode sans couche, avec ses assertions | ENF-09, CR-01 | 1 j | T2-16, T2-15 |
| — | **JALON MOIS 2** — AC-02 réussit, scriptée, visible | — | — | T2-01..18 |

Total : 27 jours de travail effectif. Le mois compte environ vingt jours ouvrés ; l'écart est absorbé par le fait que plusieurs tâches sont parallélisables entre elles, et par la marge du mois 7.

### 10. Ce qui n'est pas dans le mois 2

Aucune règle comportementale, aucune machine à états de confiance, aucune signature, aucun gel, aucune application Android, aucun contact de confiance. Tout cela est au mois 4.

Le générateur et le jeu de données sont au mois 3.

### 11. Definition of Done applicable à chaque tâche

Reprise d'ISC-PROC §4.4, sans allègement :

- le comportement attendu est constaté, cas limites compris ;
- les tests prévus par ISC-TEST existent et passent, avec au moins une assertion négative pour toute tâche touchant la sécurité ;
- les treize portes sont vertes ;
- le candidat a lu l'intégralité du changement et peut l'expliquer sans notes ;
- le code cryptographique, s'il y en a, a fait l'objet d'une revue ligne à ligne consignée ;
- la matrice de traçabilité est à jour : le marqueur `@implements` dans le code, le marqueur `@covers` dans le test ;
- toute décision structurante a donné lieu à un ADR ;
- le chapitre du mémoire concerné a été complété ou annoté.

### 12. Ce qui reste à faire avant T2-01

| # | Élément | Qui |
|---|---|---|
| 1 | Fusionner la branche de traçabilité et l'index des exigences | Propriétaire |
| 2 | Pousser `main` | Propriétaire |
| 3 | Créer les dix-huit tâches dans le tableau, chacune au gabarit ISC-PROC §4.2 | Propriétaire |
| 4 | Vérifier que la VM est en accès par pont si les téléphones doivent être joignables — sans objet au mois 2 | Propriétaire |

Aucun de ces points ne relève de la conception. La conception est close.
