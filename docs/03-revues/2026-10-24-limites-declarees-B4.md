# Carte B4 · Session de l'application — décisions appliquées et limites déclarées

Date : 2026-10-24. Couloir B (Mobile). Carte #47. Branche `mobile-15-b4-session`.

Référence : décision P-09 de la propriétaire du 2026-10-10 (inactivité 5 min, arrière-plan 60 s,
durée maximale 30 min, **imposées par le serveur**, charte qualité mobile §10 règle 7) et P-11 (5
échecs de PIN bloquent la source 15 min, jamais le compte). Paramètres nommés livrés par le couloir A
dans `config/ruleset.yaml` rs-0.10.0 : `APP_SESSION_TTL`, `APP_SESSION_BACKGROUND_TTL`,
`APP_SESSION_MAX_TTL`, `APP_PIN.max_attempts`, `APP_PIN.origin_lock`. Aucune valeur n'est écrite dans
le code.

## Les quatre décisions de la propriétaire du 2026-10-24 (questions posées avant d'écrire)

1. **Arrière-plan : le serveur mesure l'écart entre deux requêtes.** Il ne voit pas l'arrière-plan,
   il ne voit que des requêtes ; plus rien pendant plus d'`APP_SESSION_BACKGROUND_TTL`, le jeton
   meurt. Un serveur qui croirait une annonce du client n'imposerait rien (ES-26 : le serveur
   décide, l'application est un terminal). Implémenté dans `SessionTokens.touch` : une seule
   instruction SQL, conditionnelle sur les trois durées, qui prolonge ou ne renvoie rien.
2. **Renouvellement du jeton : le PIN est retapé.** `POST /session` exige le PIN (contrat) et le PIN
   n'est jamais conservé (charte §10.5). Un jeton mort n'est jamais ranimé.
3. **Pas d'interrogation des alertes en arrière-plan avec un jeton** (limite L-B4-1 ci-dessous).
4. **Le défi de session est le nonce du PDP** (ES-10 : 128 bits, usage unique, `NONCE_TTL`). Aucune
   pièce nouvelle côté défi.

## Ce que le serveur applique (carte B4)

| Règle | Où | Preuve |
|---|---|---|
| Inactivité `APP_SESSION_TTL` : seule une requête de l'**usager** compte ; les interrogations automatiques (`GET /alerts`) ne comptent pas | `SessionTokens.touch(token, userActivity)` | `SessionLivenessDbTest` (vraie base, horloge virtuelle) |
| Arrière-plan `APP_SESSION_BACKGROUND_TTL` : toute requête compte | idem | idem |
| Durée maximale `APP_SESSION_MAX_TTL` : rien ne la prolonge | `expires_at` à l'émission | idem |
| PIN : `APP_PIN.max_attempts` échecs bloquent l'**origine** pendant `APP_PIN.origin_lock`, jamais le compte ; 429 `THROTTLED` + `Retry-After` = secondes restantes, avant tout calcul | `PinAttempts`, `/enrolment/start`, `/session` | `PinAttemptsTest`, `MobileSessionTest`, `MobileEnrolmentTest` |
| Défi : nonce du PDP lié au compte de la clé ACTIVE ; appareil inconnu → leurre de même forme (ES-22) | `MobileSession.challenge`, PDP `GET /device/{id}/nonce` | `MobileSessionTest`, `DeviceControllerTest`, `DeviceEnrolmentDbTest` |
| Signature du défi vérifiée par le PDP, qui consomme le nonce ; `VALID` / `INVALID` / `REVOKED` | PDP `POST /device/verify`, `SignatureVerifier.verifyChallenge` | `SignatureVerifierTest` |
| Branche SESSION de `/enrolment/start` : compte lié + défi signé → jeton ; signature qui ne vaut rien → RECOVERY | `MobileEnrolment.start` | `MobileEnrolmentTest` |

Schéma : migration `005-b4-session-liveness.sql` (`last_seen_at`, `last_activity_at` sur
`core.session`), indépendante de la 004 réservée à B3b ; numéro attribué par le couloir A.

## Routes internes PDP ajoutées — à ratifier (amendement d'ADR-041 ou ADR propre)

`GET /device/{deviceId}/nonce` et `POST /device/verify` (`contracts/device.yaml`). La spécification
dit « le PDP reconnaît la clé » (ISC-MOB-SPEC §6) et l'application ne parle jamais au PDP (SAD) : le
cœur a besoin d'une route pour déléguer la vérification. Le nonce du PDP exige un compte à
l'émission, or `/session/challenge` ne porte que `deviceId` : c'est donc le PDP qui retrouve le
compte par la clé ACTIVE. **Aucun changement de conception** : SignatureVerifier et NonceStore sont
réutilisés, le PDP énonce, le cœur arbitre. L'acte formel (ADR) est celui de la propriétaire.

## Limites déclarées

**L-B4-1 — Pas d'alertes en arrière-plan avec un jeton** (décision 3). ISC-MOB-SPEC §9.4 déclare
déjà qu'une application fermée reçoit l'alerte en retard (pas de FCM). S'y ajoute : une application
en arrière-plan plus de 60 s n'a plus de jeton ; l'alerte n'est vue qu'au retour au premier plan,
après ressaisie du PIN. MOB-F-12 (« au mieux toutes les quinze minutes en arrière-plan ») n'est pas
tenue par un jeton ; une autre voie serait un changement de conception (gelée).

**L-B4-2 — L'application doit parler au serveur au moins toutes les 60 s au premier plan, sur TOUS
ses écrans.** Aujourd'hui elle n'interroge les alertes que sur l'accueil
(`MainActivity.kt` : `if (step is DailyStep.Home) … pollWhileForeground()`). Un usager qui reste
plus de 60 s sur l'écran du montant ou de l'historique perdrait son jeton et verrait « Code PIN
incorrect » à l'envoi. Correction côté application (sondage ou requête de maintien sur tout écran au
premier plan, verrouillage local à l'arrière-plan) : carte B6/B7, **avant** le test sur vrai
téléphone.

**L-B4-3 — Compteur de PIN en mémoire, par origine réseau.** (a) Un redémarrage du cœur remet les
compteurs à zéro ; l'attaquant ne redémarre pas le cœur. (b) L'origine est l'adresse distante vue
par le cœur : derrière un NAT commun, un évaluateur maladroit bloque les autres 15 min ; à travers
Tailscale chaque téléphone a sa propre adresse `100.x`, **à vérifier au montage de L7-b** (que le
cœur voie bien l'adresse du téléphone et non la passerelle Docker). ADR-030 nomme aussi un
« identifiant d'installation » : absent du contrat, question ouverte posée pour B3b.

**L-B4-4 — Demande de correction documentaire (le document n'est pas modifié).** ISC-MOB-SPEC v1.3
§6 (« Jeton de session expiré → nouvelle signature de défi, transparente pour l'usager ») et §12.4
bis contredisent le contrat (`POST /session` exige le PIN) et la charte (PIN jamais conservé). Le
renouvellement exige le PIN (décision 2). À corriger dans une version future de la SPEC.

**L-B4-5 — `expiresInSeconds` annonce `APP_SESSION_TTL`** (contrat : « Jeton de session émis
(APP_SESSION_TTL) »), pas la plus courte des trois durées. L'application ne lit pas ce champ
aujourd'hui.

**L-B4-6 — Aucune route protégée n'existe encore dans le cœur** (`/account`, `/transactions`,
`/nonce`, `/transfers` : carte B6). `SessionTokens.touch` est le gardien que B6 appellera ; il est
prouvé sur vraie base, pas encore traversé par une route HTTP.

**L-B4-7 — Nettoyage des sessions mortes** : les lignes de `core.session` expirées ne sont pas
supprimées (aucune exigence ; volume négligeable au mois 4). À nommer si la table grossit.
