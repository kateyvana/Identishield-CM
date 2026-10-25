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

En attente du téléphone (Tailscale connecté par la propriétaire, ACL), du solde de 677000200 et du
créneau AC-02 avec le couloir A.
