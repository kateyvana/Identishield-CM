# Carte B6 · Virements et quotidien de l'application — décisions appliquées et limites déclarées

Date : 2026-10-24. Couloir B (Mobile). Carte #49. Branche `mobile-18-b6-virements`.

Référence : ISC-MOB-SPEC §7 (flux B et C), ADR-018 (chaîne canonique, amendement M-07 pour
`CANCEL`), ADR-016-A (STEP_UP applicatif = exécution différée de `DEFER`, annulable), obligation
L-B4-2 du superviseur du 2026-10-24 (requête automatique sur TOUS les écrans au premier plan,
intervalle `POLL_FOREGROUND`, arrêt dès l'arrière-plan, trois preuves sur vraie base).

## Ce que le serveur applique (carte B6)

| Règle | Où | Preuve |
|---|---|---|
| Toute route du quotidien exige un jeton porteur vivant ; la session est touchée AVANT tout contrôle de forme : sans session, la seule réponse est 401 `CREDENTIALS_INVALID` (ES-22) | `MobileSessions`, `MobileDailyController.guarded` | `MobileDailyControllerTest`, `tools/verify_mobile_contract.py` (cas « sans jeton ») |
| `GET /alerts` est la requête AUTOMATIQUE (`userActivity` faux) : elle maintient la session (arrière-plan) sans compter comme activité (inactivité) | `MobileDailyController.alerts` | `MobileDailyControllerTest`, `SessionLivenessDbTest` |
| Le nonce d'une opération signée vient du PDP, lié au compte de la session (ES-10) | `MobileDaily.nonce` → PDP `GET /nonce?accountId=` | `MobileDailyTest` |
| Le bénéficiaire est désigné par son MSISDN : `Holder.beneficiaryId` = MSISDN = `EvaluateRequest.beneficiaryId`, la chaîne que le PDP recalcule (ADR-018) | `MobileDaily.holder`, simulateur aligné | `MobileDailyTest`, `tools/mobile-mock` (43 tests) |
| Annulation d'une opération différée : signature de `accountId\|CANCEL\|transferId\|nonce` vérifiée par le PDP avec la clé ACTIVE de l'appareil de la session, nonce consommé à part ; `VALID` seul annule, et seulement une opération DIFFÉRÉE du compte avant son échéance ; `REVOKED` → 403 `KEY_REVOKED` ; toute autre faute → 403 `DECISION_REJECTED` | `MobileDaily.cancel`, PDP `POST /device/verify` avec `nonce` (`SignatureVerifier.verifyChallenge` à cinq arguments) | `MobileDailyTest`, `SignatureVerifierTest` (le nonce consommé DOIT être le dernier champ de la chaîne, sinon `INVALID` avant toute lecture de clé) |
| Les opérations différées échues s'exécutent à la lecture de l'historique (ADR-016-A, « par tout appelant qui les consulte ») | `MobileDaily.transactions` → `TransferService.settleDeferred` | `MobileDailyTest` |

## Ce que l'application applique (L-B4-2)

| Règle | Où | Preuve |
|---|---|---|
| Requête automatique `GET /alerts` à l'ouverture puis toutes les `POLL_FOREGROUND`, sur TOUS les écrans derrière une session (accueil, historique, bénéficiaire, montant, confirmation, autorisation, envoyé, différé, arrêté, ouverture) ; sur aucun écran d'avant (démarrage, non lié, verrou) | `DailyStep.isSignedIn`, `MainActivity.DailyRoot` | `DailyStepTest` (exhaustif : une variante ajoutée sans décision ne compile pas) |
| Arrêt dès l'arrière-plan, reprise au retour : la boucle est liée à l'état `RESUMED` du cycle de vie (`repeatOnLifecycle`) | `MainActivity.DailyRoot` | **non observé sur appareil** à cette étape (observation prévue à l'étape téléphone, flux B : mise en arrière-plan 61 s → verrou E-10 au retour) |
| 401 sur la requête automatique → retour au verrou E-10 depuis n'importe quel écran ; la position du flux C est restituée après le PIN (D4) | `AlertsViewModel.refresh`, `DailyViewModel.sessionLost` | `AlertsViewModelTest`, `DailyViewModelTest` (existants) |

## Les trois preuves exigées (vraie base, horloge virtuelle) — `SessionLivenessDbTest`

| N° | Énoncé du superviseur | Test | Résultat |
|---|---|---|---|
| 1 | Premier plan, aucune action de l'usager, 4 min → session VIVANTE | `foregroundWithoutUserAction_pollsOnly_fourMinutes_isStillAlive` | **non observé** (à rejouer dans la VM après la fenêtre du couloir A, 2026-10-25 15:48Z) |
| 2 | Arrière-plan 61 s → session MORTE | `noRequestAtAll_theTokenDiesAfterTheBackgroundTtl_andIsNeverRevived` (B4) | observé VERT le 2026-10-24 20:25Z (B3b, PDP 12/12, cœur 4/4, liveness 3/3) |
| 3 | Premier plan avec sondages, 5 min sans action → MORTE malgré les sondages | `automaticPollsKeepTheTokenAlive_butNotTheUser_idleKillsIt` (B4) | observé VERT le 2026-10-24 20:25Z |

## Limites déclarées

- **L-B6-1 · `GET /account.state` : route PDP `GET /trust/{accountId}` — décision consignée.**
  Décision du superviseur technique du 2026-10-24 (transmise par le couloir A, qui la rédige en
  amendement PROPOSÉ d'ADR-041 dans `contracts/device.yaml`, branche `t4-trust-route`) : route
  INTERNE du PDP, lecture seule, mTLS, aucune écriture d'audit, réponse `{ state ∈
  TRUSTED|PROBATION|FROZEN, since, probationUntil, freezeUntil }` (ISO-8601 UTC, `null` si sans
  objet), 404 `ACCOUNT_UNKNOWN` si le PDP n'a jamais vu le compte. Le cœur recopie `state` sans
  l'interpréter (`MobileDaily.account`). **404 → `TRUSTED`** : c'est l'état que le PDP donne
  lui-même à un compte sans histoire (`TrustStateMachine`, « un compte jamais vu est TRUSTED »,
  A1-5). Réserve : la première opération d'un compte jamais vu dont l'IMSI a changé dans la fenêtre
  d'amorçage (AC-13, ISC-TM v1.3) l'enverra en PROBATION ; l'écran aura montré TRUSTED jusque-là,
  faute d'IMSI dans une lecture. `LOCKED` (contrat) n'est jamais rendu par le PDP. Un virement
  vers un compte GELÉ est refusé par `/evaluate` (`REJECTED`, veto `ACCOUNT_FROZEN`), que le cœur
  rendra 403 `ACCOUNT_FROZEN`.
- **L-B6-2 · `POST /transfers` : servie sur la surcharge du couloir A (PR #100, e9cbc3c, fusionnée
  dans la branche B6).** `Proof(deviceKeyId, signature, nonce)`, `operate(…, channel, Proof)`,
  `TransferResult.vetoApplied` et `TransferResult.decision` (chaînes du PDP posées telles quelles) ;
  la route mobile ne lit jamais `detail`. **Jamais `proofType: NONE` depuis la route mobile** (le
  PDP accepte une opération APP non signée, ES-02 n'est imposée que par la route signée) : signature
  absente → `Proof(deviceId, null, nonce)` → le PDP rend `SIGNATURE_INVALID`, l'audit s'écrit, la
  réponse est `REJECTED` / `DECISION_REJECTED`. Ordre : PIN (compté par origine, 429 avant tout
  calcul), bénéficiaire (404), solde (409, sans PDP), puis `/evaluate`. Un `REJECTED` dont la
  décision est `ALLOW` ne peut venir que du débit refusé au commit (course avec une autre opération)
  → 409 `INSUFFICIENT_FUNDS`. Veto `ACCOUNT_FROZEN` → 403 `ACCOUNT_FROZEN`.
- **L-B6-10 · Réponses de `POST /transfers` sans décision du PDP.** `EXECUTED_DEGRADED` (PDP
  injoignable, montant ≤ `CAP_FAILCLOSED`, ES-11) est rendu `ALLOW` / `EXECUTED` : l'argent a bougé,
  le contrat n'a pas de décision « dégradée » ; le cœur journalise. Un refus sans décision
  (fail-closed au-dessus de `CAP_FAILCLOSED`, refus A5-00 « numéro sans SIM ») est rendu `REJECTED` /
  `DECISION_REJECTED`. `CANCELLED` (STEP_UP résolu par CANCEL) n'est atteignable que par l'USSD
  (`STEP_UP_POLICY.app = DEFER`) ; rendu `REJECTED` par sûreté. À reporter en SPEC v1.3 §12.4 si la
  propriétaire veut des codes distincts.
- **L-B6-3 · MT-06 (recherche du titulaire freinée) non appliquée.** Aucun paramètre nommé ne
  définit la limite (ni dans ISC-TM §7 ni dans `config/ruleset.yaml`). Un littéral est interdit ;
  la route n'est servie qu'à une session vivante, ce qui borne déjà l'énumération au titulaire d'un
  compte lié. Paramètre à nommer par la propriétaire (proposition : `LOOKUP_RATE`, compte par
  session et par fenêtre) ; carte à créer.
- **L-B6-4 · Historique : P2P seulement, 50 lignes au plus.** Le contrat exige
  `counterpartyMsisdn` au format MSISDN ; les autres types (cash-in/out, marchand, facture, crédit)
  portent une contrepartie externe simulée qui n'en est pas un. Les lignes `PENDING_DECISION` ne
  sont jamais montrées. Un gel (`FREEZE`) est enregistré `REJECTED` dans `core.transaction`
  (`TransferService`), l'historique le montre `REJECTED`, pas `FROZEN`.
- **L-B6-5 · Nom du titulaire absent → chaîne vide.** `core.account.holder_name` est nullable
  (migration 003) ; le contrat exige une chaîne : un compte sans nom se présente avec `""`, jamais en
  404 (le numéro existe).
- **L-B6-6 · Annulation : le nonce est consommé même si rien n'est annulable** (opération déjà
  échue, exécutée ou annulée → 404). Voulu : une preuve ne sert qu'une fois, l'application redemande
  un nonce.
- **L-B6-7 · `POST /device/verify` avec `nonce` : amendement d'ADR-041 à ratifier** (même procédure
  que la ratification des routes `nonce`/`verify` de B4, amendement PROPOSÉ du 2026-10-24). Le
  couloir A n'a pas d'objection de principe et relit `SignatureVerifier`.
- **L-B6-8 · Arrêt du sondage en arrière-plan : vérifié par le cycle de vie, non observé sur
  appareil.** Observation à l'étape téléphone (après L7-b), rapportée alors comme « conforme » ou
  « non observé ».

## Contrat (`contracts/mobile-api.yaml`)

- `POST /nonce`, `GET /account` et `POST /transfers/{transferId}/cancel` déclarent `503`
  (`SERVICE_UNAVAILABLE`, `Retry-After`) : le PDP est leur dépendance.
- Lecture stricte des corps renforcée : plus aucune coercition de type (`"5000"` n'est pas l'entier
  `5000`), pour toutes les routes `/mobile/v1`.
- `contracts/device.yaml` : `VerifyRequest.nonce` facultatif (forme B6).
