# Carte B5 · Alertes de sécurité et « Ce n'est pas moi » — décisions appliquées et limites déclarées

Date : 2026-10-25. Couloir B (Mobile). Carte #48. Branche `mobile-21-b5-alertes` (basée sur B6,
PR #101, fusionnée).

Référence : ISC-MOB-SPEC §9 (flux F : catalogue des signaux §9.2, règle d'or §9.3, livraison §9.4) ;
SRS ES-24, ES-27, ES-28, EF-47, ES-13b ; ISC-TM AC-14, AC-15 ; ADR-018 amendement M-08 (chaînes
`ALERT_ACK` / `ALERT_NOT_ME`). Garde-fous du superviseur technique du 2026-10-24 : aucune ligne dans
`pdp/` ; le gel est une route interne du PDP écrite par le couloir A sous amendement PROPOSÉ
d'ADR-041 ; migration 006 relue par le couloir A AVANT push.

## Ce que le cœur applique (carte B5)

| Règle | Où | Preuve |
|---|---|---|
| Changement de SIM sur le numéro (HLR) → alerte **à réponse** `SIM_CHANGED` au compte ; levée dans la transaction de la réattribution, sans jamais la faire échouer | `SecurityAlerts.onImsiChanged` (`@EventListener` sur `ImsiChangeEvent`) | `SecurityAlertsTest` |
| Échecs de ré-enrôlement au code (PDP `RECOVERY_INVALID`) comptés par compte dans `ALERT_REENROL.window` ; au `ALERT_REENROL.count`-ième, UNE alerte à réponse `REENROL_ATTEMPT`, jusqu'au prochain succès ; aucune restriction du compte (ES-24) | `MobileEnrolment.complete` → `SecurityAlerts.reenrolFailed` ; `RuleSet.alertReenrolCount/Window` | `SecurityAlertsTest` (seuil, fenêtre, une seule alerte, remise à zéro) |
| Ré-enrôlement réussi qui remplace une clé ACTIVE → alerte **informative** `DEVICE_REBOUND` (EF-47) ; le fait « une clé ACTIVE existait » est lu au PDP AVANT l'activation, qui l'efface | `MobileEnrolment.confirm` → `SecurityAlerts.reenrolSucceeded` | `SecurityAlertsTest` ; `MobileEnrolmentTest` (6 tests adaptés : stub `device.state`, assertions intactes) |
| Opération différée / gel décidé → alertes informatives `TRANSFER_DEFERRED` / `TRANSFER_FROZEN` | `MobileDaily.outcome` | `MobileDailyTest` |
| Textes fixes, sans lien, code ni demande de saisie (ES-28) ; l'heure n'est pas dans le texte, l'alerte porte `at` | `SecurityAlerts.TEXT_*` | `SecurityAlertsTest` (motif négatif) |
| `GET /alerts` : les alertes du compte de la session, `actions` = `[ACK, NOT_ME]` seulement tant que l'alerte est `OPEN` ; requête automatique au premier plan seulement (L-B4-1, L-B4-2) | `MobileDaily.alerts` | `MobileDailyTest`, `MobileDailyControllerTest` |
| « C'est moi » : l'alerte doit être au compte et `OPEN` (404) ; signature de `accountId\|ALERT_ACK\|alertId\|nonce` vérifiée par le PDP (clé ACTIVE de l'appareil de la session, nonce consommé à part) ; `REVOKED` → 403 `KEY_REVOKED` (un appareil révoqué ne répond jamais, EF-47) ; l'état du compte (`GET /trust`) est lu AVANT de clore ; 503 sans rien clore s'il manque | `MobileDaily.acknowledge` | `MobileDailyTest` |
| « Ce n'est pas moi » : même vérification, puis `POST /trust/{accountId}/not-me` du PDP ; 200 ou 409 (déjà gelé NOT_ME) ferment l'alerte `NOT_ME` ; PDP injoignable ou route absente → 503, l'alerte reste `OPEN` | `MobileDaily.deny`, `DeviceClient.notMe` | `MobileDailyTest` (faux PDP) |

Schéma : migration `006-b5-alertes.sql` (`core.alert`, numéro attribué par le couloir A le
2026-10-24 ; `CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`, `GRANT` rejouable, `created_at`
fourni par l'application, `CHECK` sur `type` et `status`).

## Limites déclarées

- **L-B5-1 · L'effet de « ce n'est pas moi » attend la route du PDP.** `TrustStateMachine.notMe`
  existe (A1-4) mais aucune route ne l'expose ; le besoin exact (requête, réponse, erreurs,
  idempotence) est écrit sur la carte #48 (2026-10-24) et le couloir A l'écrit sous amendement
  PROPOSÉ d'ADR-041 après sa fenêtre VM du 25/10. Jusque-là `POST /alerts/{id}/not-me` répond 503
  après avoir vérifié la signature (le nonce est consommé ; un nouveau nonce suffit) et l'alerte
  reste `OPEN`. Le critère d'acceptation de la carte (« gèle le compte côté serveur, vérifié sur la
  tablette ») n'est **pas atteint** tant que la route n'existe pas.
- **L-B5-2 · Le gel des ré-enrôlements pendant `reenrolFrozenUntil` n'est pas appliqué par
  `/device/register`** (constat du couloir A, 2026-10-24 : `DeviceEnrolment` ne consulte pas la
  machine à états). Décision A / superviseur à rendre (refus distinct ou uniforme). Hors couloir B.
- **L-B5-3 · Compteur `REENROL_ATTEMPT` en mémoire.** Comme `PinAttempts` : un redémarrage du cœur
  remet à zéro le comptage de l'alerte téléphone. Le comptage durable (console, ES-24) est celui du
  PDP (`pdp.enrolment_attempt`, kind `ACCOUNT`).
- **L-B5-4 · L'ancien téléphone « informé » (EF-47) ne l'est que s'il a encore une session.**
  Sans FCM (§9.4), il ne lit `DEVICE_REBOUND` que par une interrogation au premier plan avec un jeton
  encore vivant ; sa clé étant révoquée, il ne peut ni rouvrir une session (`KEY_REVOKED`) ni
  répondre à une alerte (le PDP répond `REVOKED`). Limite de la livraison sans push, déjà déclarée
  en L-B4-1.
- **L-B5-5 · `SIM_CHANGED` est levée pour tout compte dont le numéro est réattribué**, lié ou non :
  seul un téléphone lié peut la lire (session) ; un compte sans appareil garde une alerte `OPEN` que
  personne ne verra. Voulu : le cœur ne consulte pas le PDP à chaque réattribution de l'opérateur.
- **L-B5-6 · Texte de `SIM_CHANGED` sans l'heure** (la SPEC §9.2 écrit « à 14 h 02 ») : le serveur
  ne calcule aucune heure locale ; l'application affiche `at` dans le fuseau du téléphone. À
  confirmer par la propriétaire, ou à reporter en SPEC v1.3.
- **L-B5-7 · Un compte sans nom ou jamais vu du PDP** : `accountState` rendu `TRUSTED` sur « c'est
  moi » (A1-5), même règle que `GET /account` (L-B6-1).
