# Passe B7 · Application branchée sur le vrai système — rapport de passe (en cours)

Carte #50. Couloir B. Ordre du superviseur technique du 2026-10-25 (étapes a → g). Règle du
rapport (charte §10.9) : « conforme » seulement pour ce qui a été observé ; sinon « non observé ».
Les captures sont nommées `E-xx-<étape>.png` dans ce dossier ; aucun écran protégé (FLAG_SECURE :
code de récupération, PIN) n'est capturé.

## Journal

### 2026-10-25 ~00:55 UTC — étape (a) autorité épinglée et nom attendu (poste, VM en lecture seule)

Sous témoin B. Certificats PUBLICS lus dans le volume `isc-certs` de la VM (conteneur jetable
`postgres:16`, montage lecture seule ; aucune clé lue).

- CA du bac à sable : `CN=IdentiShield-CM Local CA`, SHA-256
  `70:58:2E:19:59:A6:C3:47:5D:46:87:B6:94:ED:2B:44:32:74:0A:2F:06:07:FC:1D:01:B2:85:39:C3:1A:BA:2F`,
  valide du 2026-10-22 au 2036-10-19. **Conforme** à l'empreinte attendue (« 70:58:2E… »).
- Feuille `core-mobile` : `CN=isc-core`, émise par cette CA, valide du 2026-10-24 au 2027-10-24,
  SAN `DNS:isc-core, DNS:isc-vm, DNS:isc-vm.tail3824f1.ts.net, IP:100.93.166.19`. Le nom visé par
  l'APK (`100.93.166.19`) **est un SAN de la feuille** : conforme.
- L'APK épingle la CA fournie au build (`-Pisc.sandboxCa`, `SandboxTrustTask`, MOB-S-08) : c'est ce
  fichier `ca.crt` (même empreinte) qui est passé à l'étape (b) ; aucune autorité utilisateur.

### 2026-10-25 ~00:55 UTC — étape (b) APK debug contre la VM (poste)

- Construit : `./gradlew :app:assembleDebug -Pisc.apiBaseUrl=https://100.93.166.19:8443/mobile/v1/
  -Pisc.sandboxCa=<ca.crt de la VM>` ; `config/ruleset.yaml` **identique** à `origin/main` 0d7a952
  déployé (diff vide, `rs-0.10.0`) : mêmes paramètres nommés dans l'APK et sur la VM.
- APK : `app-debug.apk`, 37 224 104 octets, SHA-256
  `d71eb7ea07494c22c694b0ac09a9da3810d95aab4f4d9fde0c44b94ad05481c9` ; `cm.identishield.mobile`
  versionCode 1, versionName 0.1.0 ; signé par la clé **debug** du poste (`CN=Android Debug`,
  SHA-256 `0715eed5…2a175e`), vérifié par `apksigner verify`.
- Autorité épinglée DANS l'APK (`res/raw/sandbox_ca.pem` extrait de l'archive) : SHA-256
  `70:58:2E:19:59:A6:C3:47:5D:46:87:B6:94:ED:2B:44:32:74:0A:2F:06:07:FC:1D:01:B2:85:39:C3:1A:BA:2F`
  = CA de la VM ; `network_security_config` généré : `cleartextTrafficPermitted="false"`, seule
  ancre de confiance = cette CA (aucune autorité utilisateur). **Conforme.**
- Copie hors dépôt : `…/scratchpad/identishield-b7-debug-d71eb7ea.apk` (jamais versionnée).
- **2026-10-25 ~10:12 UTC — installation OBSERVÉE** sur l'appareil réel présenté par la propriétaire
  (« téléphone prêt ») : **tablette Samsung SM-T515**, Android 11 (correctif 2022-12-01), nœud
  Tailscale `galaxy-tab-a` = `100.110.208.13` sur le tailnet de la propriétaire (vu depuis le poste),
  verrou d'écran configuré (ancre d'E-07 ; pas de biométrie sur cet appareil, passe QA du
  2026-10-08). `adb install -r` de l'APK `d71eb7ea…` : `Success` ; `versionName=0.1.0`. Lancement
  observé (E-02 Bienvenue affiché, capture `E-02-bienvenue.png`) ; aucun appel réseau avant la
  saisie du numéro.

### étapes (c) à (g) — non observées

Ordre imposé par le superviseur (2026-10-25) : **(c) et (d)** dès le mot de la propriétaire
« téléphone prêt, ACL posée » (solde doté : 20 000 FCFA, référence 2b0f90e2, audit seq 120281) ;
**(e) et (f) seulement APRÈS la fenêtre 4 du couloir A** (« prêt par construction » : PDP avec pool
ouvert avant le port et `/health` = vrai aller-retour en base, cœur avec warmup qui réessaie jusqu'à
200), parce que le premier `/evaluate` après un redémarrage du PDP sort aujourd'hui
`EXECUTED_DEGRADED` (pool ouvert au premier appel ≈ 1 s > 800 ms, 6 occurrences consignées par A).
Si malgré tout un virement sortait DEGRADED, il serait écrit tel quel.

En attente du téléphone (Tailscale connecté par la propriétaire, ACL posée), du solde de 677000200
(virement P2P de 20 000 FCFA depuis 677000123 par le couloir A, sous témoin — décision du
superviseur du 2026-10-25) et du créneau AC-02 avec le couloir A. Aucune reprise de la VM avant ces
trois conditions.

### 2026-10-25 10:45–10:55 UTC — étape (c) flux A sur la tablette (pile servie fbdaf3b, témoin B)

Mot de la propriétaire « téléphone prêt, ACL posée » reçu à 10:40Z. Déroulé observé :

- E-02 → « Commencer » → **E-03** (`E-03-numero.png`, `E-03-numero-saisi.png`) : champ numérique,
  pavé numérique du système, 9 chiffres acceptés (un « 0 » surnuméraire est venu d'un tapotement
  d'automate sur le clavier, pas du champ) ; **E-06 PIN** : pavé numérique de l'application,
  protégé, PIN des fixtures tapé par automate sans affichage.
- **Première requête réelle tablette → VM** : `POST /enrolment/start` sur `https://100.93.166.19:8443`
  → **200** (ACL Tailscale, TLS, CA épinglée et SAN : **conformes**, observés) ; **E-04 / E-05**
  (`E-04-attente.png`, `E-05-otp.png`) : « Code reçu par SMS … 677 ••• 200 », compte à rebours de renvoi.
- OTP lu dans la boîte du réseau simulé (`GET /inbox?imsi=624010677000200` dans le conteneur
  `isc-sim`, lecture seule ; jamais affiché), 6 chiffres tapés → **E-07** (`E-07-securisation.png`) :
  « Clé créée dans la puce ✓ », « Déverrouillage local ✓ » (verrou de la tablette : mot de passe ;
  aucune saisie demandée à cet instant), « Enregistrement auprès du serveur … » puis **« Une erreur
  est survenue. Réessayez. »** au premier essai. Côté serveur, ce premier essai avait pourtant
  **enregistré une clé au PDP** (`pdp.device` `e6ecb8f0…` à 10:50:12Z, EN ATTENTE puis RÉVOQUÉE par
  la seconde inscription — règle « au plus une clé EN ATTENTE par compte », ADR-041) : l'échec est
  donc côté application, **après** la réponse du cœur, cause **non journalisée** (l'application
  n'écrit rien dans logcat). **Constat D-B7-1** : message générique pour une cause non connue —
  conforme à la charte §10.3 au sens strict, mais la cause doit être trouvée (journal de diagnostic
  sans secret ?) ; pas de correctif pendant la passe. Au second essai, logcat système :
  `E KeyStore: generateKeyInternal failed on request -68` à 10:52:20Z (StrongBox refusé, repli
  TEE, comme SPIKE-1) puis réussite.
- « Réessayer » → `POST /enrolment/complete` avec l'**attestation matérielle réelle** → **acceptée
  par le PDP** (clé `d4d95427…` EN ATTENTE à 10:52:23Z) → **E-08 « Votre clé de récupération »**
  (écran protégé : pas de capture). **La propriétaire a écrit la clé sur papier et l'a retapée sur
  E-09 elle-même** (« clé écrite ») : aucune lecture de la clé par l'automate, rien dans aucun
  fichier.
- **E-11 Accueil atteint** (`E-11-accueil-trusted.png`) : « Hervé ESSOMBA », « 677 000 200 · MTN
  MoMo », solde 20 000 FCFA, badge « 🛡 Appareil protégé · SM-T515 », Envoyer / Historique / Alertes
  / Sécurité. Côté serveur (`serveur-c-pdp-device-active.txt`) : `pdp.device` **ACTIVE**
  `d4d95427…`, `core.enrolment` **DONE**, session émise, `pdp.trust_state` sans ligne (jamais vu →
  TRUSTED, A1-5), solde 20 000. **Étape (c) : conforme, observée.**

### 2026-10-25 10:57–11:01 UTC — étape (d) flux D sur la même tablette (témoin B)

- `adb uninstall` (clé détruite dans la puce, SPIKE-1) puis `adb install -r` du même APK ; E-02 →
  E-03 (`E-03-numero-reenrolement.png`) → E-06 PIN (automate) → `POST /enrolment/start` → **branche
  RECOVERY** : **E-20 « Nouveau téléphone — Ce compte est déjà protégé par un autre téléphone »**,
  « Aucun code ne vous sera envoyé par SMS pour cette étape » (ES-17b, ES-19 : aucun OTP ; écran
  protégé, pas de capture). **La propriétaire a tapé sa clé papier**, puis a écrit et confirmé la
  **nouvelle clé** (E-08/E-09) elle-même (« fait »).
- Résultat observé : accueil E-11 de nouveau (`E-22-reenrolement-reussi-accueil.png`, badge
  « Appareil protégé · SM-T515 ») ; écran Alertes (`E-23-alertes-device-rebound.png`).
- Côté serveur (`serveur-d-reenrolement.txt`) : nouvelle clé **ACTIVE** `0a4d704b…` (10:59:16Z),
  ancienne `d4d95427…` **RÉVOQUÉE à l'activation** (11:00:02Z, même transaction, décision B3b), la
  première `e6ecb8f0…` toujours RÉVOQUÉE ; `core.enrolment` DONE ×2 ; **alerte `DEVICE_REBOUND`
  INFO levée à 11:00:02Z** (« Un nouveau téléphone a été lié à votre compte. », B5 en production).
  Un seul appareil : l'ancien téléphone « informé » n'est pas observable (L-B5-4). **Étape (d) :
  conforme, observée.**

### 2026-10-25 11:27–11:31 UTC — étape (e) virement ALLOW (pile servie 9ea36dc, « prêt par construction » déployé par A à la fenêtre 4 ; témoin B)

- Au retour sur l'application après ~25 min : **E-10 « Votre session a expiré. Déverrouillez à
  nouveau. »** (`E-10-deverrouillage-session-expiree.png`) — expiration imposée par le serveur
  (charte §10.7). PIN (automate) puis **invite système « Ouvrir votre compte — Entrez votre mot de
  passe actuel »** : déverrouillage local exigé avant la signature du défi (S-14), **tapé par la
  propriétaire** ; accueil.
- « Envoyer » → **E-12** (`E-12-beneficiaire.png`, `E-12-beneficiaire-saisi.png`) : 677001101 →
  **E-13** (`E-13-montant.png`, `E-13-montant-saisi.png`) : « À Estelle MBARGA NGONO · 677 001 101 »
  (titulaire réel, ED-11), « Solde disponible : 20 000 FCFA », montants rapides 5 000 / 10 000 /
  25 000 / 50 000 ; **5 000 FCFA** choisi (< 50 000, de jour : 12:30 Douala).
- **E-14** (protégé) : « Vérifiez avant d'envoyer », bénéficiaire, « ● Nouveau bénéficiaire »,
  5 000 FCFA → Confirmer → **E-15** (protégé) : « 1. Votre code PIN », « 🔐 2. Déverrouillez votre
  téléphone pour confirmer » → PIN (automate) → invite système → mot de passe de la tablette tapé par
  la propriétaire → signature par la clé de l'appareil.
- **E-16 « Envoyé »** (`E-16-succes.png`) : 5 000 FCFA à Estelle MBARGA NGONO · 677 001 101,
  Référence `50762db8-df8e-4808-9067-ca9d70151020`, « Signé par ce téléphone (SM-T515) » ; **E-19
  Historique** (`E-19-historique.png`) : « Estelle MBARGA NGONO · 25 oct. 12:30 · Envoi · − 5 000 FCFA ».
- Côté serveur (`serveur-e-audit-signature.txt`) : `core.transaction` P2P 5000 → 677001101, canal
  **APP**, **EXECUTED**, `decision_id = 50762db8…` ; soldes 677000200 = **15 000**, 677001101 =
  225 000 ; entrée `pdp.audit_entry` seq 120282 : `decision=ALLOW`, `vetoApplied=NONE`, score 15.
  **Constat F-B7-2** : le payload d'audit **ne porte pas de clé `proofType`** (clés : type, score,
  msisdn, decision, accountId, amountXaf, decisionId, evaluatedAt, vetoApplied, beneficiaryId,
  requiredAction, ruleSetVersion, triggeredRules) ; la preuve « opération signée » est apportée par
  `pdp.nonce` : le nonce émis à 11:29:54Z pour le compte a été **consommé à 11:30:02.79Z**, à
  l'instant du `/evaluate` — un nonce n'est consommé que par une signature **valide**
  (`SignatureVerifier` : signature d'abord, nonce ensuite). À remonter au couloir A / superviseur
  (proofType dans le payload d'audit ?), pas de correctif pendant la passe. Pas de mode dégradé
  observé (premier `/evaluate` après le redémarrage de la fenêtre 4 : nominal — prêt par
  construction). **Étape (e) : conforme, observée.**

### 2026-10-25 11:33–11:40 UTC — étape (f) AC-02 réel avec le couloir A (un seul témoin, le B)

- **Réattribution HLR par A** à **11:33:12.916Z** (`hlr-agent reassign`, motif « AC-02 (f) demo jury :
  remplacement SIM ») : 677000200 passe de `624010677000200` à l'IMSI attaquant
  **`624010000000667`** (distinct des portes automatiques).
- **22 ms plus tard**, `core.alert` **`SIM_CHANGED` OPEN** (11:33:12.939Z : écouteur
  `SecurityAlerts.onImsiChanged` dans la transaction de la réattribution). Sur la tablette, au
  sondage suivant : bandeau « Alertes 1 » sur l'accueil (`E-11-accueil-alerte-sim.png`), **E-23**
  (`E-23-alertes-sim-changed.png`) « Carte SIM changée · à traiter · 25 oct. à 12:33 », **E-24**
  (`E-24-detail-sim-changed.png`) : « Numéro 677 000 200 », « Ce téléphone : toujours lié », « C'est
  vous ? », « Oui, c'est moi » / « Non, ce n'est pas moi », « Nous ne vous demanderons jamais de code
  ni de PIN dans une alerte » (ES-28).
- « **Non, ce n'est pas moi** » → invite « Répondre à l'alerte — Déverrouillez votre téléphone pour
  confirmer » → mot de passe de la tablette tapé par la propriétaire → signature
  `accountId|ALERT_NOT_ME|alertId|nonce` vérifiée par le PDP → `POST /trust/{accountId}/not-me`.
- Côté serveur (`serveur-f-trust-frozen.txt`) : `core.alert` **NOT_ME** à 11:36:13.690Z ;
  `pdp.trust_state` **FROZEN**, `freeze_cause = NOT_ME`, `freeze_until = NULL` (sans expiration,
  A1-3), **`reenrol_frozen_until = 2026-10-26T11:36:13Z`** (REENROL_FREEZE 24 h) ; transition 60134
  TRUSTED → FROZEN ; audit seq 120283 `TRUST_FROZEN`/`NOT_ME` et 120284 `REENROL_FROZEN`/`SIM_CHANGE`
  avec l'`alertId` `46715ad0…` ; **solde 677000200 = 15 000, inchangé**. **ES-27 observé de bout en
  bout.** E-24 après réponse (`E-24-apres-not-me-frozen.png`) : les boutons ont disparu ; l'état du
  compte n'est pas affiché sur cet écran (**constat D-B7-3**, présentation : afficher « compte gelé »
  après « ce n'est pas moi »).
- Tentative de l'attaquant (A, SIM `…667`) : voir sorties du couloir A (`serveur-f-attaquant-refuse.txt`).

## Captures attendues (ISC-MOB-SPEC v1.3, tableau des écrans ; écrans FLAG_SECURE exclus : E-06, E-08, E-09, E-14, E-15, E-20)

Chaque ligne reste « non observée » tant que le fichier n'existe pas dans ce dossier.

| Étape | Fichier attendu | Écran / preuve | État |
|---|---|---|---|
| (b) | `E-01-lancement.png`, `E-02-bienvenue.png` | E-01 Lancement et E-02 Bienvenue sur la tablette SM-T515, APK d71eb7ea installé | **observée** (2026-10-25 10:13Z) |
| (c) | `E-02-bienvenue.png` | E-02 Bienvenue (déjà saisie à l'étape (b)) | **observée** |
| (c) | `E-03-numero.png`, `E-03-numero-saisi.png` | E-03 Numéro (677000200 saisi) | **observée** |
| (c) | — | E-06 PIN : **protégé**, pas de capture | — |
| (c) | `E-04-attente.png` | E-04 Attente (OTP émis côté serveur) | **observée** |
| (c) | `E-05-otp.png` | E-05 OTP (champ saisi, code masqué) | **observée** |
| (c) | `E-07-securisation.png` | E-07 Sécurisation (attestation réelle → PDP ; si refus : écran d'erreur capturé et **ARRÊT**) | **observée** (1er essai : erreur générique ; 2e : OK) |
| (c) | — | E-08 / E-09 Clé de récupération et confirmation : **protégés**, pas de capture | — |
| (c) | `E-11-accueil-trusted.png` | E-11 Accueil : badge « Appareil protégé », état TRUSTED, solde | **observée** |
| (c) | `serveur-c-pdp-device-active.txt` | Côté serveur : `pdp.device` ACTIVE pour le compte (lecture seule, console ou SQL en lecture) | **observée** |
| (d) | `E-03-numero-reenrolement.png` | E-03 Numéro après désinstallation/réinstallation | **observée** |
| (d) | — | E-06 PIN, E-20 Clé de récupération, E-08/E-09 nouvelle clé : **protégés** | — |
| (d) | — | E-07 Sécurisation (nouvelle clé) — non capturé (sécurisation menée par la propriétaire, écran passé) | non observée |
| (d) | `E-22-reenrolement-reussi-accueil.png`, `serveur-d-reenrolement.txt` | E-22 Réenrôlement réussi (ancienne clé révoquée) — accueil après ré-enrôlement, ancienne clé RÉVOQUÉE, nouvelle ACTIVE | **observée** |
| (d) | `E-23-alertes-device-rebound.png` | E-23 Alertes : `DEVICE_REBOUND` informative (si un second appareil est disponible, sinon « non observée : un seul appareil ») — sur le même appareil (lecture par la session du nouveau téléphone) | **observée** |
| (e) | `E-12-beneficiaire.png`, `E-12-beneficiaire-saisi.png` | E-12 Bénéficiaire 677001101 (titulaire affiché, badge « nouveau bénéficiaire ») — **étape (e) seulement APRÈS la fenêtre 4 de A (« prêt par construction » déployé : plus de premier /evaluate DEGRADED à froid)** | **observée** |
| (e) | `E-13-montant.png`, `E-13-montant-saisi.png` | E-13 Montant (< 50 000 FCFA, de jour) | **observée** |
| (e) | — | E-14 Confirmation, E-15 Autorisation (PIN) : **protégés** | — |
| (e) | `E-16-succes.png` | E-16 Succès (ALLOW, EXECUTED) ; si DEGRADED malgré la fenêtre 4 : écrit tel quel | **observée** (ALLOW, EXECUTED, 50762db8) |
| (e) | `E-19-historique.png` | E-19 Historique (ligne OUT EXECUTED) | **observée** |
| (e) | `serveur-e-audit-signature.txt` | Côté serveur : entrée d'audit de la décision avec `proofType=SIGNATURE` (console intégrité EF-34 ou lecture seule du journal) — preuve par le nonce consommé (F-B7-2 : pas de proofType dans le payload) | **observée** |
| (f) | `serveur-f-hlr-reassign.txt` | Côté serveur (A) : réattribution HLR 677000200 → IMSI attaquant **distinct** des portes automatiques (ex. `624010000000667`, décision du 2026-10-25) — **étape (f) seulement APRÈS la fenêtre 4 de A et après (e)** | **observée** (11:33:12.916Z, par A) |
| (f) | `E-11-accueil-alerte-sim.png`, `E-23-alertes-sim-changed.png` | E-23 Alertes : `SIM_CHANGED` OPEN avec actions | **observée** |
| (f) | `E-24-detail-sim-changed.png` | E-24 Détail : « C'est moi » / « Ce n'est pas moi » | **observée** |
| (f) | `E-24-apres-not-me-frozen.png` | E-24 après « Ce n'est pas moi » signée : état FROZEN affiché — boutons disparus ; état du compte NON affiché (D-B7-3) | **observée** (sans état affiché) |
| (f) | `serveur-f-trust-frozen.txt` | Côté serveur : `GET /trust/{accountId}` → `FROZEN`, `reenrolFrozenUntil` posé | **observée** (FROZEN/NOT_ME, reenrol gelé 24 h) |
| (f) | `serveur-f-attaquant-refuse.txt` | Côté serveur (A) : tentative de l'attaquant refusée (USSD STEP_UP bloqué ; application : `RECOVERY_INVALID` uniforme pendant le gel) ; **oracle : aucun débit** | non observée |
| (f) | `E-18-bloquee.png` | E-18 Bloquée si un virement est tenté depuis le téléphone lié après le gel (`ACCOUNT_FROZEN`) — « Opération bloquée · Pour votre sécurité, cet envoi n'a pas été exécuté. Votre argent est toujours sur votre compte. Que faire ? … Aucun code ne vous sera demandé par téléphone. » ; serveur : REJECTED, veto ACCOUNT_FROZEN (audit 120286), solde 15 000 | **observée** |
| (g) | `E-10-deverrouillage-session-expiree.png` | E-10 après 61 s en arrière-plan : verrou (charte §10.7) — observé après ~25 min : « Votre session a expiré » | **observée** |
| (g) | `E-11-accueil-tablette.png`, `E-11-accueil-police-200.png` | Mise en page tablette et police 200 % (charte §10.8) | non observée |
