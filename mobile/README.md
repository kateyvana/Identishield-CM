# mobile/ — application Android IdentiShield-CM

Application mobile du projet (ISC-MOB-CDC v1.2, ISC-MOB-SPEC v1.2), en Kotlin, Android natif. Carte M-05 : échafaudage. Les parcours arrivent avec M-06 à M-08.

**Pile fermée par ADR-037 :** les versions exactes vivent dans `gradle/libs.versions.toml`. **Aucune bibliothèque nouvelle sans amendement d'ADR-037.**

## Construire

Il faut le SDK Android (Android Studio ou les command-line tools) et un JDK 21.

```bash
# depuis la racine du dépôt : la porte de make check
make mobile

# ou directement
cd mobile
./gradlew lint testDebugUnitTest assembleDebug     # APK : app/build/outputs/apk/debug/app-debug.apk
```

Le SDK est trouvé par `ANDROID_HOME`, `ANDROID_SDK_ROOT` ou `local.properties` (`sdk.dir`, jamais versionné). La porte `make mobile` cherche aussi `%LOCALAPPDATA%/Android/Sdk` (Windows) et `~/Android/Sdk`. Si le SDK est absent, elle l'annonce (« SAUTÉE — rien n'a été vérifié ») sans faire échouer `make check`. Avec `ISC_REQUIRE_MOBILE=1` (VM ou CI équipée du SDK), cette absence devient un échec.

## Sécurité posée dès l'échafaudage

| Exigence | Réalisation |
|---|---|
| MOB-S-09 : pas de sauvegarde | `allowBackup="false"`, `fullBackupContent="false"`, `data_extraction_rules.xml` qui exclut tout (sauvegarde en ligne et transfert d'appareil à appareil) |
| MOB-S-08 : TLS, CA du bac à sable épinglée, pas de trafic en clair | `network_security_config.xml` **généré à la construction** (tâche `SandboxTrustTask`, voir le détail ci-dessous) |
| MOB-S-14 : build de démonstration non débogable, minifié | `release` : `isDebuggable = false`, optimisation R8 activée |
| MOB-NF-02 : APK de démonstration de moins de 15 Mo | Mesuré le 30/09/2026 (correctif M-06) : `app-release-unsigned.apk` = 3 332 800 octets (3,2 Mio), construit avec une CA de test ordinaire. L'APK debug (non minifié, environ 36 Mo) n'est pas l'APK de démonstration. |
| MOB-F-01 : vérification locale de la clé | `AndroidKeystoreKeyChecker` : la présence de l'alias dans l'Android Keystore est la seule chose que l'application sait |

**Détail de `SandboxTrustTask`.** Le trafic en clair est interdit dans tous les cas, et aucune autorité système ni utilisateur n'est acceptée.

- **Pourquoi la CA n'est pas dans le dépôt.** La CA du bac à sable est créée au premier démarrage de chaque pile (SPIKE-4, ADR-021), donc elle change d'une pile à l'autre. De plus, un `.pem` ne peut pas entrer dans le dépôt (porte `secrets`).
- **Comment la fournir :** `-Pisc.sandboxCa=<chemin du certificat de la CA>` ou la variable `ISC_SANDBOX_CA`.
- **Avec une CA fournie :** seule cette CA est de confiance (épinglage).
- **Sans CA :** le build debug ne fait confiance à **aucune** autorité, ce qui est un échec fermé ; le build release **refuse** de se construire.

~~Limite connue : le faux serveur `tools/mobile-mock/` parle en HTTP en clair. L'application ne peut donc pas l'atteindre telle quelle. Le raccordement (TLS du mock, ou exception réservée au debug) est à trancher dans M-06.~~ Tranché en M-06 (ADR-038) : le faux serveur parle **HTTPS**, avec une CA de **développement** distincte. L'application garde sa posture TLS-only.

**Garde de `SandboxTrustTask` (ADR-038).** La CA fournie est lue comme un certificat X.509 ; un fichier illisible fait échouer le build. Une CA portant `OU=ISC-DEV-MOBILE-MOCK`, c'est-à-dire la CA de développement du faux serveur, est **refusée pour un build release**.

## Travailler contre le faux serveur (M-06)

```bash
make mobile-mock-tls                                   # CA de dev + certificat (SAN : 10.0.2.2, 127.0.0.1, localhost)
make mobile-mock                                       # faux serveur HTTPS sur MOBILE_MOCK_PORT
cd mobile && ./gradlew assembleDebug -Pisc.sandboxCa=../tools/mobile-mock/.tls/dev-ca.crt
```

- **Émulateur :** l'URL par défaut est `https://10.0.2.2:8089/mobile/v1/` (port `MOBILE_MOCK_PORT` du Makefile).
- **Téléphone physique :** régénérer le certificat avec l'adresse du poste (`make mobile-mock-tls MOBILE_MOCK_SAN="IP:192.168.1.20"`), lancer `make mobile-mock MOBILE_MOCK_HOST=0.0.0.0`, puis construire avec `-Pisc.apiBaseUrl=https://192.168.1.20:8089/mobile/v1/`. L'URL doit être en `https://`, sinon le build refuse.
- **Compte de test du flux A :** 677 000 200, PIN 1234. L'OTP de test est `demoOtp` dans `tools/mobile-mock/fixtures.json`, ou il s'affiche dans la réponse avec `MOBILE_MOCK_DEMO=1`.

## Parcours M-06 : premier enrôlement (flux A)

E-01 (aucune clé) → E-02 → E-03 → E-06 → E-04 → E-05 → E-07 → E-08 → E-09 → E-11.

**Garde-fous de sécurité, couverts par des tests :**
- **ES-25 :** la clé de l'appareil n'est créée dans l'Android Keystore qu'**après** que le serveur a validé l'OTP. Avant, et en cas d'OTP faux, aucune clé n'est créée.
- **MOB-S-01 à MOB-S-04 :**
  - clé EC P-256, StrongBox si disponible, sinon TEE ;
  - une clé **logicielle est refusée** ;
  - le défi d'attestation vient du serveur ;
  - chaque signature exige le déverrouillage **local** (biométrie ou code), et la clé est invalidée si une nouvelle biométrie est enregistrée.
- **P-11 :** le serveur ne reçoit que la clé publique, la chaîne d'attestation et la signature du défi, **jamais** le déverrouillage.
- **P-8, P-9, MOB-S-10 :**
  - le PIN et la clé de récupération ne vivent qu'en mémoire, et sont effacés à l'arrivée sur l'accueil ;
  - la clé de récupération n'est jamais persistée ;
  - une clé locale est détruite si le déverrouillage est annulé, si l'enregistrement échoue, ou si le Keystore lève une erreur (panne, clé invalidée). Il n'y a jamais de plantage ni de clé orpheline qu'E-01 prendrait pour un appareil lié.
- **Un seul appel serveur à la fois :** un double appui n'envoie qu'une requête. Un OTP à usage unique ne peut donc pas être rejoué pour ramener E-07 à E-05.
- **MOB-S-05 :** capture d'écran bloquée (`FLAG_SECURE`) sur E-06, E-08 et E-09, avec la pastille « 🔒 capture bloquée » des maquettes. `Page(secure = true)` pose les deux ensemble, jamais l'un sans l'autre.
- **MOB-S-06 :** ni copier ni coller. Le PIN et l'OTP passent par un pavé numérique (aucun champ texte) ; la clé est affichée en texte non sélectionnable ; sur E-09, la barre de texte est neutralisée.
- **MOB-S-07 :** les touches sont filtrées quand une autre fenêtre recouvre l'application.
- **Paramètres nommés :** `OTP_RESEND_DELAY` et `APP_NETWORK_TIMEOUT` (MOB-NF-03) sont lus dans `config/ruleset.yaml` **à la construction** (BuildConfig). Aucune de ces valeurs n'est écrite dans le code.

## Maquettes de référence

`docs/02-specifications/mobile/maquettes/` (voir son README) : 28 écrans et l'identité visuelle. Palette : marine `#10375C`, vert protégé `#1B998B`, ambre `#F2A93B`, rouge `#D7263D`, fond `#F4F7FB`, texte `#14213D`. Police système (Roboto).

**Règle : tout écart avec les maquettes est documenté, jamais silencieux.**

Écarts en vigueur :

| Écran | Écart | Motif |
|---|---|---|
| E-01 | Le gris des textes secondaires (sous-titre, « Vérification… ») est le texte `#14213D` à 65 % d'opacité, pas une septième couleur | La palette ne définit pas de gris secondaire |
| E-01 | Le logo est `logo_mark.png` (PNG des maquettes), pas un dessin vectoriel | Échafaudage ; la vectorisation n'est pas nécessaire tant que l'écran est fidèle |
| E-01 | Clé présente : l'écran E-01 reste affiché | Le déverrouillage E-10 relève de la carte M-07 |
| E-02 | « Continuer par USSD (*126#) » est un texte, sans action | Bac à sable : aucun code USSD réel n'est composé |
| E-03 | Contrôle local de la **forme** seulement (9 chiffres) ; la plage 677 est contrôlée par le serveur (`REQUEST_INVALID` → « Numéro invalide ») | Décision du propriétaire (M-06, Q2) : aucune plage en dur dans l'application |
| E-06 | Autant de points que de chiffres tapés, et un bouton « Continuer » (la maquette montre 5 points, validés automatiquement) | La longueur du PIN n'est fixée par aucune spécification ; le PIN de test du faux serveur a 4 chiffres |
| E-05 | Autant de cases que de chiffres tapés, et un bouton « Continuer » (la maquette montre 6 cases) | Décision du propriétaire (M-06, Q2) : la longueur de l'OTP est validée par le serveur |
| E-05 | Pas de compte à rebours « Il expire dans 4:52 » | Le contrat n'expose pas l'expiration de l'OTP ; le serveur fait autorité (`OTP_EXPIRED`) |
| E-04, E-07 | Pas de bande « MODE DÉMO » | Le mode démonstration s'active dans E-25 (carte M-08) |
| E-07 | Une erreur d'appareil (puce logicielle, attestation refusée) s'affiche sur E-07 | E-27 est hors périmètre (carte M-08) |
| E-08 | La clé est groupée par 4 sur autant de lignes que nécessaire : 20 caractères (`CODE_LENGTH`), là où la maquette en montre 12 | `CODE_LENGTH` vient du jeu de règles |
| E-09 | Après une seconde erreur, l'application reste sur E-09 avec « Caractères incorrects » | La maquette dit « affichée une dernière fois » ; la suite n'est pas spécifiée (rotation : E-25, M-08) |
| E-11 | Tuiles affichées sans action ; pas de bandeau d'alerte ni de dernières opérations | Envoyer et Historique : M-07 ; Alertes et Sécurité : M-08 |
| E-11 | Pas de barre de navigation du bas (Accueil, Envoyer, Historique, Alertes, Sécurité) | Ses destinations arrivent avec M-07 et M-08 ; écart omis du rapport M-06, documenté par le correctif du 30/09 |
| E-06, E-08, E-09 | La pastille « 🔒 capture bloquée » est affichée en haut de la page, sous la barre d'état ; la maquette la dessine DANS la barre d'état | L'application ne dessine pas dans la barre d'état du système ; pastille omise en M-06, restaurée par le correctif du 30/09 |
| Tous | Symboles Unicode (‹, ⟳, 🛡, ↗, ≡, 🔔) à la place d'icônes | Aucune bibliothèque d'icônes dans ADR-037 |
| Tous | Mise en page vérifiée sur la taille des maquettes (360 × 760 dp) | Sur un écran nettement plus petit, le pavé numérique peut repousser le bouton hors de l'écran : limite déclarée |

## Architecture

- **MVVM :** `ViewModel` + `StateFlow`, avec un état unidirectionnel.
- **Injection manuelle par constructeurs,** sans cadre d'injection (ADR-037).
- **Paquets :**
  - `launch/` : E-01 ;
  - `enrolment/` : flux A, avec le ViewModel, les écrans et les composants ;
  - `data/` : Retrofit, DTO du contrat, dépôt ;
  - `security/` : clé Keystore, déverrouillage local, protections de fenêtre ;
  - `ui/theme/` : palette.
- **Navigation par état** (un état par écran, porté par le ViewModel) : aucune bibliothèque de navigation, elle n'est pas dans ADR-037.
- **Tests** (`app/src/test`), tous sur la JVM, sans téléphone :
  - ViewModels (`LaunchViewModelTest`, `EnrolmentViewModelTest`) ;
  - couche réseau contre MockWebServer (`EnrolmentRepositoryTest`) ;
  - tests d'interface Compose d'E-01 et des écrans du flux A (`LaunchScreenTest`, `EnrolmentScreensTest`), grâce à Robolectric.
