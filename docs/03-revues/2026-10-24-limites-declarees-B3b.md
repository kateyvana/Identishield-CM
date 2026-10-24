# Carte B3b · Réenrôlement (flux D) — décisions appliquées et limites déclarées

Date : 2026-10-24. Couloir B (Mobile). Carte #62. Branche `mobile-16-b3b-reenrolement`.

Références : ADR-041 (amendement du 2026-10-17, VERROUILLÉ), ADR-030 (FERME), ES-19, ES-21, ES-22,
ES-24, ES-25, EF-46 ; décisions de la propriétaire du 2026-10-24 (trois points), appliquées telles
quelles. Paramètres nommés : `REENROL_ATTEMPTS`, `LOCK_BASE`, `LOCK_MAX`, `ENROL_TTL` (aucun nouveau).

## Les trois décisions et où elles vivent

| Décision | Où | Preuve |
|---|---|---|
| **1. ADR-041 gagne : `/enrolment/recover` ne vérifie RIEN et répond toujours BIND ; le code est vérifié par le PDP dans `/device/register`, AVEC l'attestation, en une seule fois ; le cœur ne garde pas le code : l'application le RENVOIE à `/enrolment/complete` (`recoveryCode` facultatif)** | cœur `MobileEnrolment.recover/complete`, PDP `DeviceEnrolment.register`, contrat mobile et `device.yaml`, faux serveur, application (`pendingRecoveryCode`, mémoire vive) | `MobileEnrolmentTest`, `DeviceEnrolmentTest`, tests du faux serveur, `RecoveryFlowTest` |
| Le 429 THROTTLED et le compteur passent de `/recover` à `/complete` ; le compteur vit au PDP (`pdp.enrolment_attempt`, migration 004), par ORIGINE et INSTALLATION, jamais le compte (compté seulement, ES-24) | `EnrolmentThrottle` | `EnrolmentThrottleTest`, `DeviceEnrolmentDbTest` (vraie base) |
| Réponse UNIFORME au réenrôlement : code faux, code absent, attestation refusée, défi invalide → même 401 RECOVERY_INVALID, même corps ; **toutes les vérifications sont faites quoi qu'il arrive** (le PBKDF2 du code comme l'attestation) pour que la durée ne désigne pas la faute | `DeviceEnrolment.register` | `DeviceEnrolmentTest.boundAccount_wrongCode_noCode_badAttestation_badSignature_areTheSameRefusal_eachCounted` (cinq causes, un seul refus, PBKDF2 compté cinq fois) |
| **2. Identifiant d'installation** : ajouté au contrat PDP (`installationId`, facultatif) et au compteur ; **sa forme côté application (en-tête ou champ) est proposée à la propriétaire, pas écrite** | `RegisterRequest.installationId`, `EnrolmentThrottle.Key.installation` | — (voir L-B3b-1) |
| **3. Révocation à l'ACTIVATION, dans la même transaction** : `/device/activate` verrouille le compte, révoque l'ACTIVE, active la PENDING ; une activation refusée annule la révocation ; jamais deux ACTIVE, jamais zéro entre les deux (ES-20) | `DeviceEnrolment.activate`, `DeviceRegistry.revokeActive` | `DeviceEnrolmentDbTest.activation_revokesTheOldActiveKey_inTheSameTransaction_andNeverLeavesZeroOrTwoActive` |

Autres pièces : `/enrolment/start` sur un compte lié sans signature valide ouvre un enrôlement à
l'étape **RECOVERY** (migration 004) et renvoie `enrolmentId` ; aucun OTP (EF-46). `DEVICE_ALREADY_BOUND`
(409) disparaît du contrat PDP : la course du premier enrôlement (compte lié entre-temps) est désormais
un 401 RECOVERY_INVALID « sans code » que le cœur rend 410, indiscernable d'un enrôlement expiré
(motif ES-22 inchangé) ; un 409 distinct aurait nommé le fait que le compte vient d'être lié.

## Limites déclarées

**L-B3b-1 — Identifiant d'installation : non lu par le cœur tant que sa forme n'est pas décidée.** Le
comptage des échecs porte sur l'origine réseau seule ; ADR-030 exige aussi l'installation (sinon une
réinstallation ou un changement d'adresse remet le compteur à zéro). Proposition faite à la propriétaire
(en-tête `X-ISC-Installation`, UUID v4) ; le contrat PDP et le compteur sont prêts à la recevoir.

**L-B3b-2 — Amorçage PDP du compte lié des fixtures : NON FAIT, volontairement.** Le PDP ne connaît pas
l'UUID du compte créé par le cœur, et aucune route ne pose une clé ACTIVE sans attestation ; en créer une
ou forger une chaîne serait un changement de conception. Recommandation : le vrai téléphone fait un
premier enrôlement (flux A), puis un second enrôlement du même compte prouve le flux D. Décision de la
propriétaire attendue.

**L-B3b-3 — Un essai de clé coûte une clé matérielle.** Conséquence voulue d'ADR-041 (« le coût d'un
essai devient matériel, pas réseau ») : l'application crée la clé, déverrouille et signe AVANT de savoir
si le code était juste ; un refus détruit la clé créée (aucune clé orpheline) et renvoie à E-20/E-21.
Après le premier défi, l'enrôlement reste à BIND côté serveur : un seul `/recover`, puis autant de
`/complete` que d'essais autorisés par le délai croissant.

**L-B3b-4 — `DEVICE_UNSUPPORTED` reste nommé au réenrôlement.** Il parle de l'appareil de l'appelant
(clé logicielle), pas du code ; l'application vérifie d'ailleurs le matériel localement avant tout appel
(E-27b). Il compte comme un échec pour l'origine.

**L-B3b-5 — ES-24 : le compte est COMPTÉ (`kind = ACCOUNT`), l'alerte console au-delà d'`ALERT_REENROL`
n'est pas émise** ; l'alerte au téléphone lié (ES-27, « ce n'est pas moi ») est la carte B5 (#48), de même
que le gel des réenrôlements (`REENROL_FREEZE`, 403 ACCOUNT_FROZEN à `/recover`, prévu au contrat, non
servi par le cœur).

**L-B3b-6 — Compteur en base, entrées jamais purgées.** Un succès efface les clés de l'appelant ; un
attaquant qui n'aboutit jamais laisse une ligne par origine (quelques octets). Purge à nommer si besoin.

**L-B3b-7 — Le faux serveur mime le contrat, pas l'uniformité en temps.** Il vérifie le code à
`/complete` et renvoie les mêmes codes ; la mesure d'uniformité en durée n'a de sens que sur le PDP.

**L-B3b-8 — Non prouvé sans téléphone : le succès de `/complete` au réenrôlement** (vraie attestation) et
donc l'activation qui révoque l'ancienne clé de bout en bout. Prouvé sur vraie base au niveau du PDP
(`DeviceEnrolmentDbTest`), à rejouer à l'étape du vrai téléphone (second enrôlement du même compte).
