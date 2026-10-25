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
- Installation sur le téléphone de la propriétaire (`adb install`) : **non observé** — aucun
  appareil branché au poste ; téléphone et Tailscale côté propriétaire.

### étapes (c) à (g) — non observées

En attente du téléphone (Tailscale connecté par la propriétaire, ACL posée), du solde de 677000200
(virement P2P de 20 000 FCFA depuis 677000123 par le couloir A, sous témoin — décision du
superviseur du 2026-10-25) et du créneau AC-02 avec le couloir A. Aucune reprise de la VM avant ces
trois conditions.

## Captures attendues (ISC-MOB-SPEC v1.3, tableau des écrans ; écrans FLAG_SECURE exclus : E-06, E-08, E-09, E-14, E-15, E-20)

Chaque ligne reste « non observée » tant que le fichier n'existe pas dans ce dossier.

| Étape | Fichier attendu | Écran / preuve | État |
|---|---|---|---|
| (b) | `E-01-lancement.png` | E-01 Lancement sur le téléphone, APK d71eb7ea installé | non observée |
| (c) | `E-02-bienvenue.png` | E-02 Bienvenue | non observée |
| (c) | `E-03-numero.png` | E-03 Numéro (677000200 saisi) | non observée |
| (c) | — | E-06 PIN : **protégé**, pas de capture | — |
| (c) | `E-04-attente.png` | E-04 Attente (OTP émis côté serveur) | non observée |
| (c) | `E-05-otp.png` | E-05 OTP (champ saisi, code masqué) | non observée |
| (c) | `E-07-securisation.png` | E-07 Sécurisation (attestation réelle → PDP ; si refus : écran d'erreur capturé et **ARRÊT**) | non observée |
| (c) | — | E-08 / E-09 Clé de récupération et confirmation : **protégés**, pas de capture | — |
| (c) | `E-11-accueil-trusted.png` | E-11 Accueil : badge « Appareil protégé », état TRUSTED, solde | non observée |
| (c) | `serveur-c-pdp-device-active.txt` | Côté serveur : `pdp.device` ACTIVE pour le compte (lecture seule, console ou SQL en lecture) | non observée |
| (d) | `E-03-numero-reenrolement.png` | E-03 Numéro après désinstallation/réinstallation | non observée |
| (d) | — | E-06 PIN, E-20 Clé de récupération, E-08/E-09 nouvelle clé : **protégés** | — |
| (d) | `E-07-securisation-reenrolement.png` | E-07 Sécurisation (nouvelle clé) | non observée |
| (d) | `E-22-reenrolement-reussi.png` | E-22 Réenrôlement réussi (ancienne clé révoquée) | non observée |
| (d) | `E-23-alertes-device-rebound.png` | E-23 Alertes : `DEVICE_REBOUND` informative (si un second appareil est disponible, sinon « non observée : un seul appareil ») | non observée |
| (e) | `E-12-beneficiaire.png` | E-12 Bénéficiaire 677001101 (titulaire affiché, badge « nouveau bénéficiaire ») | non observée |
| (e) | `E-13-montant.png` | E-13 Montant (< 50 000 FCFA, de jour) | non observée |
| (e) | — | E-14 Confirmation, E-15 Autorisation (PIN) : **protégés** | — |
| (e) | `E-16-succes.png` | E-16 Succès (ALLOW, EXECUTED) | non observée |
| (e) | `E-19-historique.png` | E-19 Historique (ligne OUT EXECUTED) | non observée |
| (e) | `serveur-e-audit-signature.txt` | Côté serveur : entrée d'audit de la décision avec `proofType=SIGNATURE` (console intégrité EF-34 ou lecture seule du journal) | non observée |
| (f) | `serveur-f-hlr-reassign.txt` | Côté serveur (A) : réattribution HLR 677000200 → IMSI attaquant **distinct** des portes automatiques (ex. `624010000000667`, décision du 2026-10-25) | non observée |
| (f) | `E-23-alertes-sim-changed.png` | E-23 Alertes : `SIM_CHANGED` OPEN avec actions | non observée |
| (f) | `E-24-detail-sim-changed.png` | E-24 Détail : « C'est moi » / « Ce n'est pas moi » | non observée |
| (f) | `E-24-apres-not-me-frozen.png` | E-24 après « Ce n'est pas moi » signée : état FROZEN affiché | non observée |
| (f) | `serveur-f-trust-frozen.txt` | Côté serveur : `GET /trust/{accountId}` → `FROZEN`, `reenrolFrozenUntil` posé | non observée |
| (f) | `serveur-f-attaquant-refuse.txt` | Côté serveur (A) : tentative de l'attaquant refusée (USSD STEP_UP bloqué ; application : `RECOVERY_INVALID` uniforme pendant le gel) ; **oracle : aucun débit** | non observée |
| (f) | `E-18-bloquee.png` | E-18 Bloquée si un virement est tenté depuis le téléphone lié après le gel (`ACCOUNT_FROZEN`) | non observée |
| (g) | `E-10-deverrouillage-arriere-plan.png` | E-10 après 61 s en arrière-plan : verrou (charte §10.7) | non observée |
| (g) | `E-11-accueil-tablette.png`, `E-11-accueil-police-200.png` | Mise en page tablette et police 200 % (charte §10.8) | non observée |
