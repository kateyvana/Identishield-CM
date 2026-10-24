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

- **L-B6-1 · `GET /account` non servie à ce stade.** Le contrat exige `state` (TRUSTED / PROBATION /
  FROZEN / LOCKED), que seul le PDP connaît ; aucune route de lecture de l'état de confiance
  n'existe dans `contracts/evaluate.yaml` (couloir A). Demande d'une route `GET /trust/{accountId}`
  portée par le couloir A à la propriétaire et au superviseur le 2026-10-24 au soir. Le couloir B
  n'écrit rien dans `evaluate.yaml` et n'invente pas d'état. Un virement vers un compte GELÉ est
  quand même refusé : `/evaluate` répond `REJECTED` avec le veto `ACCOUNT_FROZEN`
  (`EvaluateController`), que le cœur rendra 403 `ACCOUNT_FROZEN`.
- **L-B6-2 · `POST /transfers` non servie à ce stade.** `TransferService.buildRequest` (couloir A)
  envoie toujours `proofType: NONE`. Le couloir A livre la surcharge minimale sur sa branche
  `t4-transfer-proof` (record `Proof(deviceKeyId, signature, nonce)`, `operate(…, channel, Proof)`,
  `TransferResult.vetoApplied` tel que renvoyé par le PDP, jamais réinterprété). La route mobile
  codera contre cette forme ; **jamais `proofType: NONE` depuis la route mobile** (le PDP accepte
  une opération APP non signée, ES-02 n'est imposée que par la route signée). Signature absente →
  `Proof(deviceKeyId, null, nonce)` → le PDP rend `SIGNATURE_INVALID`, l'audit s'écrit.
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

- `POST /nonce` et `POST /transfers/{transferId}/cancel` déclarent `503` (`SERVICE_UNAVAILABLE`,
  `Retry-After`) : le PDP est leur dépendance.
- `contracts/device.yaml` : `VerifyRequest.nonce` facultatif (forme B6).
