# Registre des décisions — IdentiShield-CM

- **Créé le** : 2026-10-11 (carte A0 du mois 4, ticket #34)
- **Rôle** : une seule page où retrouver chaque décision en vigueur, sa date et sa source. Le registre **ne remplace pas** les ADR : une décision d'architecture garde son ADR, et le registre y renvoie.
- **Règle de citation** : chaque ligne cite le fichier du dépôt qui porte la décision. Une décision prise en séance et absente du dépôt porte la mention « décision orale consignée ici » : ce registre en devient alors la source.
- **Statuts** : EN VIGUEUR · PROVISOIRE (appliquée, non fermée) · ABANDONNÉ · LIMITE (limite déclarée, assumée devant le jury).

## 1. Décisions du propriétaire

| # | Date | Décision | Statut | Source |
|---|---|---|---|---|
| P-01 | 2026-09-07 → 2026-10-11 | **Contact de confiance : ABANDONNÉ.** Différé le 07/09 (QO-19), il est abandonné ; QO-19 est fermée : ni enregistrement, ni demande, ni réponse, ni levée de gel par un contact. Les mentions des spécifications sont marquées « ABANDONNÉ (voir REGISTRE_DECISIONS) » dans leurs versions suivantes. | ABANDONNÉ | `docs/00-registre-questions.md` (QO-19, différé) ; abandon : décision orale consignée ici |
| P-02 | 2026-10-11 | **E-mail de récupération : ABANDONNÉ.** Aucune récupération de compte par e-mail. | ABANDONNÉ | décision orale consignée ici |
| P-03 | 2026-09-07 | **Récupération par code uniquement.** Le ré-enrôlement exige le PIN puis le code de récupération ; jamais d'OTP. | EN VIGUEUR | `docs/01-decisions/ADR-029.md` ; SRS v1.4 ES-18, ES-19 |
| P-04 | 2026-09-25 | « La biométrie n'est PAS un facteur d'authentification de l'application. Les deux facteurs sont le PIN de l'application et la signature de la clé de l'appareil. L'usage de la clé exige le déverrouillage local du téléphone, par la méthode que l'usager a configurée sur son téléphone (code de l'appareil, ou empreinte/visage s'il les a activés) ; l'application fonctionne sans aucun capteur biométrique (vérifié sur tablette sans capteur, 2026-10-08). ADR-037 (androidx.biometric) reste valable : c'est l'outil Android qui affiche ce déverrouillage local. » | EN VIGUEUR | ISC-MOB-SPEC v1.2, journal (tâche MOB-VOCAB, 25/09/2026) ; `docs/01-decisions/ADR-037.md` ; formulation : décision orale consignée ici (2026-10-11) ; sur les écrans, voir ISC-MOB-SPEC v1.3 |
| P-05 | 2026-10-06 | **USSD : un STEP_UP est annulé** (CANCELLED), jamais reporté. | PROVISOIRE | `docs/01-decisions/ADR-016.md` §3 ; `docs/03-revues/2026-10-06-amendements-proposes-T3-07.md` |
| P-06 | 2026-10-06 | **Réglages de la calibration T3-07 écrits à titre provisoire** (somme bornée, poids, seuils, CAP_FAILCLOSED, NIGHT_ZONE) ; ADR-016 non fermé. | PROVISOIRE | `docs/01-decisions/ADR-016.md` ; `config/ruleset.yaml` (rs-0.8.0) |
| P-07 | 2026-10-08 | **Charte qualité mobile** : dix règles, une étape mobile qui ne les respecte pas n'est pas terminée. | EN VIGUEUR | `CLAUDE.md` §10 |
| P-08 | 2026-10-10 | **Périmètre du mois 4 : AC-02 de bout en bout.** Les autres cas d'abus passent au mois 5, la console au mois 6. | EN VIGUEUR | décision orale consignée ici ; tableau « Mois 4 — Démo AC-02 » (`docs/03-revues/2026-10-11-levee-8e-interdiction-kanban-m4.md`) |
| P-09 | 2026-10-10 | **Session de l'application** : inactivité 5 min, arrière-plan 60 s, durée maximale 30 min, imposées par le serveur, en paramètres nommés. | EN VIGUEUR | `CLAUDE.md` §10 règle 7 (commit `bc69e44`) |
| P-10 | 2026-10-10 | **BouncyCastle** remplace le lecteur DER maison (`Der.java`) ; un **rejeu** est affiché « refusé » à l'usager. | EN VIGUEUR | décision orale consignée ici ; voir la note N-2 |
| P-11 | 2026-10-10 | **PIN : 5 échecs bloquent la source 15 min, jamais le compte.** Valeurs en paramètres nommés. Ferme QM-14 (ISC-MOB-SPEC v1.3, `docs/00-registre-questions.md`). | EN VIGUEUR | décision orale consignée ici |
| P-12 | 2026-10-10 | **Code de récupération : 6 caractères, lettres et chiffres.** Nombre d'essais selon la spécification mobile du 2026-09-25 (REENROL_ATTEMPTS = 2, puis délai croissant LOCK_BASE / LOCK_MAX, jamais sur le compte). | EN VIGUEUR | longueur : décision orale consignée ici ; essais : ISC-MOB-SPEC v1.2 §14, `config/ruleset.yaml` ; voir la note N-3 |
| P-13 | 2026-10-10 | **Écran E-09 : saisie complète du code**, affiché masqué sauf les 2 derniers caractères. | EN VIGUEUR | décision orale consignée ici ; ISC-MOB-SPEC v1.3 |
| P-14 | 2026-10-10 | **Code de récupération perdu : passage en agence**, avec pièce d'identité. Déclaré comme limite. | LIMITE | décision orale consignée ici ; ferme QM-05 (ISC-MOB-SPEC v1.3, `docs/00-registre-questions.md`) |
| P-15 | 2026-10-10 | **USSD** : canal sans capacité cryptographique ; sa protection repose sur le signal IMSI et les règles comportementales. Déclaré comme limite (TPR AC-02 sans IMSI ≈ 90 %). | LIMITE | décision orale consignée ici ; mesure : `docs/01-decisions/ADR-016.md` §4 |

## 2. Décisions du tech lead

| # | Date | Décision | Statut | Source |
|---|---|---|---|---|
| T-01 | 2026-10-09 | **Ordre des contrôles d'une décision** : signature, puis état du compte, puis règles. Un veto court-circuite tout ce qui suit et ne traverse jamais le moteur de règles. | EN VIGUEUR | `CLAUDE.md` §3 (vetos distincts du moteur) ; carte A4 (ticket #38) ; ordre : décision orale consignée ici |
| T-02 | 2026-09-07 | **Clés d'appareil : deux états seulement, ACTIVE et REVOKED.** Pas d'état PENDING ; une seule clé ACTIVE par compte, garantie par la base. | EN VIGUEUR | ISC-SAD v1.2 (ISC-AMD-01) ; `infra/init/init.sql` (`pdp.device`, index unique partiel) ; ABANDON de PENDING marqué dans les versions suivantes |
| T-03 | 2026-09-07 | **PROBATION déclenchée par un changement d'IMSI uniquement**, jamais par un enrôlement ou un ré-enrôlement. | EN VIGUEUR | SRS v1.4 ES-01b, ES-03c |
| T-04 | 2026-10-06 | **Pas de règle « montant seul »** : le montant ne contribue qu'avec un bénéficiaire nouveau (ES-05b). | EN VIGUEUR | `docs/01-decisions/ADR-016.md` (poids `newBeneficiaryHigh`) ; SRS v1.4 ES-05b |
| T-05 | 2026-10-10 | **État LOCKED hors du mois 4** : aucun déclencheur n'est défini depuis ISC-AMD-01 ; il n'est pas implémenté au mois 4. | EN VIGUEUR | décision orale consignée ici ; ISC-SAD v1.3 (« incident → LOCKED », non défini) |
| T-06 | 2026-10-10 | **Renumérotation des versions du ruleset à la fusion** : une branche fusionnée après une autre prend le numéro suivant libre, avec l'ancien numéro annoté. | EN VIGUEUR | `config/ruleset.yaml` (historique rs-0.8.0) ; commit d'intégration `bb7753e` |
| T-07 | 2026-10-11 | **Ordre des champs du canonical** : l'ordre d'ADR-018 (`accountId\|type\|amountXaf\|beneficiaryId\|nonce`) prime sur le texte d'EF-21. | EN VIGUEUR | `docs/01-decisions/ADR-018.md` ; décision du tech lead, consignée ici (2026-10-11) |
| T-08 | 2026-10-11 | **Aucune carte mobile n'est acceptée sur les seuls tests JVM** : preuve sur appareil réel obligatoire. | EN VIGUEUR | `CLAUDE.md` §10 règle 1 ; décision du tech lead, consignée ici (2026-10-11) |
| T-09 | 2026-10-11 | **Réseau de l'application** : réserve de connexion plus courte côté application que côté serveur ; rejeu automatique des lectures seulement, jamais d'une action ; si le résultat d'une action est incertain, l'application interroge le serveur. | EN VIGUEUR | son ADR sera fourni avec la carte B1a (ticket #43) ; décision du tech lead, consignée ici (2026-10-11) |
| T-10 | 2026-10-14 | **STEP_UP sur l'application = exécution différée (DEFER), annulable**, au lieu d'une confirmation par signature : une confirmation par un appareil potentiellement volé n'est pas une protection. La durée est le paramètre DEFER. USSD = CANCEL inchangé (P-05). ISC-MOB-SPEC (E-17) et le code mobile traitaient déjà le STEP_UP applicatif ainsi. Remplace la valeur `app = SIGNATURE` d'ADR-016 §3 (décision du propriétaire du 2026-10-06) ; ADR-016 amendé par la propriétaire (ADR-016-A, 2026-10-14). | EN VIGUEUR | `config/ruleset.yaml` (rs-0.9.0, `STEP_UP_POLICY.app`) ; `docs/01-decisions/ADR-016.md` (amendement ADR-016-A) ; ISC-MOB-SPEC v1.3 E-17 ; décision du tech lead, consignée ici (2026-10-14) |
| T-11 | 2026-10-14 | **`config/ruleset.yaml` appartient au couloir A seul** ; le couloir B demande ses paramètres au couloir A. | EN VIGUEUR | décision du tech lead, consignée ici (2026-10-14) |
| T-12 | 2026-10-15 | **Fenêtre de la médiane de THR_HIGH en paramètre nommé** : clé `THR_HIGH.medianWindow: P30D` ajoutée par la carte A2. La valeur est celle d'ISC-TM v1.4 §7 (« médiane des 30 derniers jours ») ; aucune valeur existante n'est modifiée, la ligne de version attend le bump de l'intégration. | EN VIGUEUR | ISC-TM v1.4 §7 ; `config/ruleset.yaml` ; décision du tech lead, consignée ici (2026-10-15) |
| T-13 | 2026-10-15 | **Veto PROBATION de la carte A2 = ES-04 complet** : bénéficiaire nouveau OU cumul des sorties + montant au-delà du plafond CAP_PROB, conformément à ADR-016 §1 (la carte ne citait que le plafond). | EN VIGUEUR | ISC-SRS-FR v1.5 ES-04 ; `docs/01-decisions/ADR-016.md` §1 et §4 ; décision du tech lead, consignée ici (2026-10-15) |
| T-14 | 2026-10-25 | **Observation « premier `/evaluate` à froid » (2026-09-15) TRANCHÉE : lecture 2 (budget EI-01 = 800 ms à chaud) ET pile chaude PAR CONSTRUCTION** — pool du PDP ouvert à l'initialisation des singletons, `/health` = aller-retour en base (200/503), `warmup()` du cœur en réessais bornés (paramètres nommés), outils avec le délai de production (`--entrypoint java`, #112) ; plus aucun contournement « relancer si à froid ». | PROPOSÉ (signature de la propriétaire) | décision du superviseur technique (2026-10-25) ; `docs/03-revues/2026-09-15-observation-recurrente-evaluate-a-froid.md` § Décision ; PR « prêt par construction » |

## 2 bis. Arbitrages de la machine à états, carte A1

Arbitrages du tech lead du 2026-10-14, rendus sur les contradictions relevées par la carte A1 (ticket #35) avant tout code.

| # | Date | Arbitrage | Statut | Source |
|---|---|---|---|---|
| A1-1 | 2026-10-14 | **Sortie de PROBATION** : CAR_IMSI expiré sans incident → TRUSTED, et l'IMSI courant devient l'IMSI de référence. Le constat « attaquant patient » d'ISC-AMD-01 (constat 3, repris par ADR-029) visait CAR_ENROL, retiré ; il ne s'applique pas à CAR_IMSI. | EN VIGUEUR | ISC-SAD v1.4 §7.5 ; glossaire d'ISC-SRS-FR v1.5 (« IMSI de référence ») ; ISC-TM v1.4, T-AC-04 ; arbitrage du tech lead, consigné ici (2026-10-14) |
| A1-2 | 2026-10-14 | **Sortie de FROZEN et probation en cours** : la sortie de FROZEN (levée ou expiration) rend l'état en vigueur avant le gel. Une période CAR_IMSI en cours n'est ni consommée ni remise à zéro par le gel : son échéance d'origine est conservée. Gelé pendant la probation → PROBATION jusqu'à la fin prévue ; TRUSTED seulement si cette fin est déjà passée. Motif : sinon un gel de 24 h (TTL_FREEZE) effacerait 47 h de probation (attaquant patient, T-05). Précise ISC-SAD v1.4 §7.5 (« expiration → TRUSTED »), qui contredisait ES-01b. | EN VIGUEUR | ISC-SAD v1.4 §7.5 ; ISC-SRS-FR v1.5 ES-01b ; arbitrage du tech lead, consigné ici (2026-10-14) |
| A1-3 | 2026-10-14 | **Gel issu de « ce n'est pas moi »** : il n'expire jamais tout seul ; levée explicite seulement (ES-08). TTL_FREEZE ne s'applique qu'au gel issu d'une décision FREEZE sur transaction. Les deux origines de gel sont distinguées en base (colonne de cause) et testées séparément. | EN VIGUEUR | ISC-SAD v1.4 §7.5 ; ISC-MOB-SPEC v1.3 §9.2 (« levée par l'opérateur ») ; arbitrage du tech lead, consigné ici (2026-10-14) |
| A1-4 | 2026-10-14 | **« Ce n'est pas moi » selon l'alerte** : alerte de changement de SIM → FROZEN complet et ré-enrôlements gelés (REENROL_FREEZE) ; alerte de tentative de ré-enrôlement → ré-enrôlements gelés uniquement, l'état du compte ne change pas. Motif : la tentative a échoué et le titulaire garde sa clé ACTIVE ; geler tout le compte punirait le titulaire qui se défend. ES-27 et ISC-SAD v1.4 §7.5 ne distinguaient pas les deux alertes ; cet arbitrage les précise. | EN VIGUEUR | ISC-SRS-FR v1.5 ES-27 ; ISC-SAD v1.4 §7.5 ; ISC-MOB-SPEC v1.3 §8.3, §9.2 ; arbitrage du tech lead, consigné ici (2026-10-14) |
| A1-5 | 2026-10-14 | **État initial** : TRUSTED ; IMSI de référence = IMSI observé au premier enrôlement. | EN VIGUEUR | arbitrage du tech lead, consigné ici (2026-10-14) |
| A1-6 | 2026-10-14 | **Changement d'IMSI pendant FROZEN** : IMSI de référence et échéance CAR_IMSI mis à jour, l'état reste FROZEN ; à la sortie, A1-2 s'applique. | EN VIGUEUR | arbitrage du tech lead, consigné ici (2026-10-14) |
| A1-7 | 2026-10-14 | **Événements d'audit des transitions** : TRUST_PROBATION_ENTERED, TRUST_PROBATION_EXPIRED, TRUST_FROZEN, TRUST_UNFROZEN, enregistrés dans `pdp.trust_transition` (fromState, toState, cause). | EN VIGUEUR | ISC-SAD v1.4 §9.2 (`trust_transition`) ; arbitrage du tech lead, consigné ici (2026-10-14) |

## 3. Règles permanentes

| # | Règle | Source |
|---|---|---|
| R-01 | `make check` VERT sur la branche, prouvé dans le rapport, avant toute fusion. | `CLAUDE.md` §4 (règle du 2026-09-25) |
| R-02 | `git add` des fichiers par leur nom **avant** `make check` (les portes lisent les fichiers suivis par git). | décision orale consignée ici (2026-10-08) |
| R-03 | Une session = un worktree ; deux couloirs, deux sessions. | `CLAUDE.md` §4 (2026-09-25, décision D-14) |
| R-04 | Aucun push, aucune action sur GitHub sans levée écrite du propriétaire, consignée dans `docs/03-revues/`. | `CLAUDE.md` §4 |
| R-05 | Les documents de `docs/02-specifications/` ne sont jamais modifiés en place ; une version suivante est créée à côté, sous levée écrite. | `CLAUDE.md` §2 ; `docs/03-revues/2026-10-11-levee-regle-2-carte-A0.md` |
| R-06 | Aucun paramètre nommé en dur dans le code : il vient de `config/ruleset.yaml`. | `CLAUDE.md` §4 |
| R-07 | Commande destructrice : jamais enchaînée, cible listée avant, erreurs non masquées. | `CLAUDE.md` §4 |
| R-08 | Une décision d'architecture et toute bibliothèque hors de la pile exigent un ADR écrit par le propriétaire. | `CLAUDE.md` §4 et §5 |

## 4. Notes

- **N-1 (biométrie).** Voir P-04. Les écrans ne présentent pas la biométrie comme nécessaire : « Déverrouillez votre téléphone », sans lister « empreinte, visage » (ISC-MOB-SPEC v1.3).
- **N-2 (BouncyCastle).** Bibliothèque hors de la pile : son ADR sera fourni avec la carte B2 (ticket #45), conformément à `CLAUDE.md` §4. Le lecteur DER maison qu'elle remplace est décrit dans l'amendement M-09 d'ADR-018.
- **N-3 (longueur du code).** `config/ruleset.yaml` fixe encore `CODE_LENGTH: 20`. Le passage à 6 relève de la carte B1a (ticket #43) ; la carte A0 ne modifie pas le ruleset.
