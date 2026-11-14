# Procédure de l'APK de publication (I-6) — préparée, NON exécutée

Date : 2026-11-14. Couloir B (Mobile). Ordre du superviseur technique du 2026-11-14 : préparer sans exécuter.
**La signature et le mot de passe du magasin de clés sont tapés par la propriétaire, jamais écrits nulle
part** : ni dans le dépôt, ni dans `gradle.properties`, ni dans une variable d'environnement, ni dans un
script, ni dans un rapport. `mobile/app/build.gradle.kts` ne contient **aucun** `signingConfig` : le build
`release` produit un APK **non signé** (`app-release-unsigned.apk`), signé ensuite à la main. Cette
procédure ne change pas cela.

## 0. Préconditions (le jour de la publication)

| # | Quoi | Qui | Preuve |
|---|---|---|---|
| 0.1 | `main` figé (gel), SHA noté ; `make check` VERT sur ce SHA | B | journal |
| 0.2 | La pile servie tourne sur ce même `main` ; **aucun `make reset` prévu** après la publication (L13) | A | `docker inspect` |
| 0.3 | La CA du bac à sable **de la pile servie**, exportée depuis le volume `isc-certs` en lecture seule (certificat public seulement, jamais la clé) : `docker run --rm -v isc-certs:/certs:ro alpine cat /certs/ca.crt > ca.crt` dans la VM, puis copie sur le poste **hors du dépôt** ; empreinte SHA-256 relevée (`openssl x509 -in ca.crt -noout -fingerprint -sha256`) et comparée à celle que sert le 8443 | B, témoin VM court | les deux lignes brutes |
| 0.4 | Témoin `~/VM-OCCUPEE` lu avant toute construction sur le poste (la VM tourne sur le même portable) | B | contenu |
| 0.5 | Magasin de clés de la propriétaire **existant** (§1) ou à créer | propriétaire | — |

## 1. Magasin de clés (une seule fois, par la propriétaire)

```text
keytool -genkeypair -v \
  -keystore <dossier personnel, HORS du dépôt>/identishield-release.jks \
  -alias identishield -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA \
  -validity 10000 -dname "CN=IdentiShield-CM, O=ICT University"
```

- `keytool` **demande** les mots de passe au clavier : la propriétaire les tape ; personne ne les voit, rien ne
  les enregistre. Aucune option `-storepass` ni `-keypass` sur la ligne de commande (elles resteraient dans
  l'historique du shell).
- Le fichier `.jks` vit **hors du dépôt**, sauvegardé par la propriétaire (deux copies hors ligne). **Le perdre
  interdit toute mise à jour** des APK déjà installés (rupture n°8 de
  `2026-11-14-sans-cable-impossibilites-et-ruptures.md`).
- La porte `secrets` du dépôt refuse de toute façon un `.jks`/`.pem` ; on ne l'approche pas.

## 2. Construction du build `release` (B, sur le poste, hors ligne)

```text
cd mobile
ANDROID_HOME=C:/Users/uiooi/AppData/Local/Android/Sdk ./gradlew --offline :app:assembleRelease \
  -Pisc.apiBaseUrl=https://100.93.166.19:8443/mobile/v1/ \
  -Pisc.sandboxCa=<chemin hors dépôt>/ca.crt
```

- `SandboxTrustTask` **refuse** un build `release` sans CA, ou avec la CA de développement du faux serveur
  (`OU=ISC-DEV-MOBILE-MOCK`, ADR-038) ; `isc.apiBaseUrl` doit être en `https://` (MOB-S-08).
- Résultat : `mobile/app/build/outputs/apk/release/app-release-unsigned.apk`, `isDebuggable = false`, R8
  activé (MOB-S-14), attendu autour de 3,3 Mio (MOB-NF-02 : < 15 Mo ; mesuré 3 332 800 octets le 30/09).
- Vérifications : `unzip -p app-release-unsigned.apk res/raw/sandbox_ca.pem | openssl x509 -noout
  -fingerprint -sha256` = empreinte de 0.3 ; taille ; `aapt dump badging` → `versionName`, `minSdkVersion 30`,
  `debuggable` absent.
- **Version.** `versionCode = 1`, `versionName = "0.1.0"` dans `build.gradle.kts`. Les changer est une
  modification de code sous gel : **décision de la propriétaire** (publier en 0.1.0, ou lever le gel pour une
  ligne).

## 3. Alignement et signature (propriétaire au clavier)

Outils : `build-tools/36.1.0/{zipalign,apksigner}` du SDK.

```text
zipalign -v -p 4 app-release-unsigned.apk identishield-0.1.0-aligned.apk
apksigner sign --ks <HORS dépôt>/identishield-release.jks --ks-key-alias identishield \
  --v2-signing-enabled true --v3-signing-enabled true \
  --out identishield-0.1.0.apk identishield-0.1.0-aligned.apk
```

- `apksigner` **demande** le mot de passe du magasin puis celui de la clé : la propriétaire les tape. **Jamais**
  `--ks-pass pass:…`, `--ks-pass env:…` ni `--ks-pass file:…`.
- B ne touche pas au clavier pendant cette étape et ne relève pas l'écran.

## 4. Vérification et empreintes (B, lecture seule)

```text
apksigner verify --verbose --print-certs identishield-0.1.0.apk
sha256sum identishield-0.1.0.apk
```

À consigner dans le rapport et sur la page de publication : `Verified using v2 scheme: true`, l'empreinte
SHA-256 du **certificat du signataire** (publique) et l'empreinte SHA-256 du **fichier APK**. Rien d'autre.

## 5. Sentinelle de la pile (propriétaire, dans la VM, témoin court)

```text
make apk-publie APK_VERSION=0.1.0
```

Pose `/certs/APK-PUBLIE` (date, version, empreinte de la CA épinglée) dans le volume `isc-certs`
(`tools/apk_publie.sh`, #68) : dès lors `make reset` et `gen-ca.sh FORCE=1` **refusent**. Le retrait
(`make apk-depublie`) est un acte de la propriétaire.

## 6. Page de publication (release GitHub, propriétaire)

Contenu, sans fichier de clé ni mot de passe :

1. le fichier `identishield-0.1.0.apk` et son SHA-256 ; l'empreinte du certificat du signataire ;
2. le SHA de `main` construit ;
3. **le texte de L14, tel quel** : « Le réseau SMS est simulé dans le bac à sable. L'installation et
   l'application fonctionnent de façon autonome ; l'enrôlement d'un nouveau téléphone demande la présence de
   l'auteur, qui lit le code dans le réseau SMS simulé. » ;
4. les conditions du téléphone (Android 11+, verrouillage d'écran, puce de sécurité, Tailscale) et le lien vers
   `2026-11-14-guide-du-testeur.md` ;
5. la mention « serveur allumé sur rendez-vous » (L7 : le serveur est allumé le jour de la soutenance).

## 7. Après la publication

- **Tablette du projet** : l'APK de débogage installé (signature de débogage) **ne peut pas** être mis à jour
  par l'APK signé ; il faut désinstaller (perte de la clé locale, compte lié à reprendre par le flux D) puis
  installer l'APK signé. Sans câble, l'acheminement du fichier vers la tablette est à décider (impossibilité
  n°1 de `2026-11-14-sans-cable-impossibilites-et-ruptures.md`).
- **Chaque testeur** : fichier + SHA-256 + guide ; invitation Tailscale et règle d'accès (#66) **avant** l'envoi
  du fichier.
- **Aucun changement** de CA, d'adresse, de contrat ni des paramètres copiés tant que l'APK circule (ruptures
  n°1, 4, 5, 6).

## 8. Ce que cette procédure ne fait pas

- Elle n'a **pas été exécutée** : aucune construction `release`, aucun magasin de clés créé, aucune sentinelle
  posée.
- Elle ne décide ni de la version (0.1.0 ou plus), ni de l'acheminement sans câble vers la tablette, ni de la
  date : décisions de la propriétaire.
- Elle ne couvre pas la signature Play Store (`.aab`) : hors périmètre, l'APK est remis directement.
