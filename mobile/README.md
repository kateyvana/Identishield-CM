# mobile/ — application Android IdentiShield-CM

Application mobile du projet (ISC-MOB-CDC v1.2, ISC-MOB-SPEC v1.2), en Kotlin, Android natif. Carte M-05 : échafaudage ; M-06 : premier enrôlement (flux A) ; M-07 : ouverture de session et envoi signé (flux B et C) ; M-08 : ré-enrôlement par clé de récupération (flux D), alertes de sécurité (flux F) et écrans de fin de parcours (E-27). E-25 (Sécurité : rotation, déliaison, mode démonstration) reste à faire.

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

## Parcours M-07 : ouverture de session (flux B) et envoi signé (flux C)

- **Flux B :** E-01 (clé présente) → E-10 → E-04 → E-11 ; E-19 Historique.
- **Flux C :** E-11 → E-12 → E-13 → E-14 → E-15 → E-16, E-17 ou E-18. E-17 est aussi accessible depuis l'accueil (envois programmés), pour annuler.
- **Fin du flux A :** la session ouverte en E-09 est reprise directement par l'accueil du flux B, sans nouveau déverrouillage.

**Garde-fous, couverts par des tests :**
- **Signatures (amendement M-07 d'ADR-018, PROPOSÉ).** Chaque signature exige le déverrouillage local, et la clé créée en M-06 est réutilisée. Le serveur ne voit que la signature et, sous TLS épinglé, le PIN.
  - E-10 signe les **octets UTF-8 du défi tels que reçus**, sans aucun décodage.
  - E-15 signe `accountId|type|amountXaf|beneficiaryId|nonce`.
  - E-17 signe `accountId|CANCEL|transferId|nonce`.
  - Un champ vide, bordé d'espaces, contenant `|` ou un retour à la ligne, ou un nonce mal formé : l'application **refuse de signer** et n'envoie rien.
- **Nonce neuf à chaque tentative** (ES-10). Une tentative annulée ne réutilise jamais son nonce.
- **Jamais de renvoi automatique** (SPEC §7.4, amendement M-07 d'ADR-037, PROPOSÉ) :
  - `retryOnConnectionFailure(false)` sur tout le client ;
  - une réponse perdue après l'envoi donne l'issue « inconnue » (E-18, « Vérifiez votre historique »), sans aucun renvoi.
- **Un seul appel serveur à la fois :** un double appui n'envoie qu'un seul envoi, et le retour arrière est bloqué pendant l'appel.
- **Données locales :** seuls le numéro et le `deviceId` sont conservés (préférences privées, sauvegarde désactivée), jamais le solde, le nom, le PIN ni le jeton. Le jeton de session ne vit qu'en mémoire.
- **Erreurs :**
  - ES-22 : réponse uniforme sur E-10 ;
  - THROTTLED : le délai de `Retry-After` est affiché, jamais « compte bloqué » ;
  - KEY_REVOKED, clé invalidée par une nouvelle biométrie, ACCOUNT_FROZEN : message simple ; le flux D relève de M-08.
- **MOB-S-05 :** `FLAG_SECURE` et la pastille « 🔒 capture bloquée » sur E-14 et E-15.
- **Paramètre nommé :** `QUICK_AMOUNTS` (montants rapides d'E-13), lu dans `config/ruleset.yaml` à la construction.

## Parcours M-08 : ré-enrôlement (flux D), alertes (flux F), fin de parcours (E-27)

- **Flux D :** E-03 → E-06 → E-04 → **E-20** (clé de récupération) → E-07 → **E-22** → E-08 (nouvelle clé) → E-09 → E-11. Échecs : « Clé incorrecte », puis **E-21** (attente sur cet appareil). **E-26** : clé perdue (orientation, QM-05).
- **E-27a :** clé révoquée par le serveur, ou invalidée par une nouvelle biométrie : « Sécuriser à nouveau ce téléphone » reprend le flux D au PIN, avec le numéro mémorisé. **E-27b :** appareil incompatible (puce logicielle, attestation refusée) : « Quitter ».
- **Flux F :** bandeau et compteur sur E-11, **E-23** (liste), **E-24** (détail, « C'est moi » / « Ce n'est pas moi »).

**Garde-fous, couverts par des tests (amendement M-08 d'ADR-018, PROPOSÉ) :**
- **Le serveur décide.** L'application envoie numéro + PIN ; c'est la réponse `RECOVERY` qui ouvre le flux D. L'application n'en connaît pas la raison, et le message d'échec est le même pour tout le monde (ES-22).
- **Clé de récupération :** saisie sur un écran à capture bloquée et sans copier-coller, envoyée pour cette tentative seulement, **jamais conservée** (ni en mémoire après l'appel, ni en préférences, ni dans les journaux). Normalisée (majuscules, sans tirets) avant l'envoi.
- **Délai croissant sur cet appareil (ES-21) :** `REENROL_ATTEMPTS` essais libres, puis `LOCK_BASE` doublé à chaque échec, plafonné par `LOCK_MAX`, **la même règle que le serveur**, qui reste l'autorité (`Retry-After`). Le compteur survit à un redémarrage (deux nombres en préférences, jamais la clé). Pendant l'attente, **aucune requête** ne part. Le compte n'est jamais bloqué.
- **Supprimer avant de créer :** après validation par le serveur, l'ancienne clé locale est détruite, puis la nouvelle est créée dans la puce, avec la même exigence matérielle qu'en M-06.
- **Alertes :** interrogées à l'ouverture puis toutes les `POLL_FOREGROUND`, **au premier plan seulement** (décision du propriétaire : le jeton ne vit qu'en mémoire). Réponse binaire **signée** (`accountId|ALERT_ACK|alertId|nonce`, `accountId|ALERT_NOT_ME|alertId|nonce`), nonce neuf, sur le téléphone lié seulement ; une alerte informative n'a aucun bouton. Le résumé vient du serveur et reste un texte : un champ hors contrat (un lien, par exemple) fait refuser la réponse entière.
- **Temps injecté :** délais, compte à rebours et interrogation périodique sont testés avec une horloge et une attente injectables ; aucun test n'attend en temps réel.
- **Paramètres nommés :** `REENROL_ATTEMPTS`, `LOCK_BASE`, `LOCK_MAX`, `CODE_LENGTH` et `POLL_FOREGROUND` sont lus dans `config/ruleset.yaml` à la construction. Aucun paramètre nouveau : le ruleset reste en rs-0.7.0.

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
| E-11 | ~~Tuiles affichées sans action ; pas de bandeau d'alerte ni de dernières opérations~~ M-07 : Envoyer et Historique actifs, dernières opérations (deux lignes, depuis `/transactions`) ; Alertes et Sécurité grisées ; pas de bandeau d'alerte | Alertes et Sécurité : M-08 |
| E-11 | ~~Pas de barre de navigation du bas~~ M-07 : barre présente ; Alertes et Sécurité inactives | M-08 |
| E-11 | « Alertes » sans compteur de non-lus | Les alertes relèvent du flux F (M-08) |
| E-11 | Bloc « Envois programmés » (opérations différées, à annuler), absent de la maquette | SPEC §7.3 et §11 : les opérations différées sont visibles sur l'accueil, avec « Annuler » |
| E-10 | Numéro masqué seulement : ni nom du titulaire, ni badge « Appareil protégé » | Décision du propriétaire (M-07) : jamais le nom localement ; le vert signifie « vérifié par le serveur », or la session n'est pas encore ouverte |
| E-10 | Autant de points que de chiffres tapés, et un bouton « Continuer » | Comme E-06 (longueur du PIN non spécifiée) |
| Toutes (lectures) | Un 401 sur une lecture (session expirée) ramène à E-10 avec « Votre session a expiré » | Décision du propriétaire (M-07) : SPEC §6 prévoit une signature « transparente », impossible car chaque signature exige le PIN et le déverrouillage local |
| E-12 | « Récents » : les bénéficiaires déjà payés (envois exécutés), relus dans `/transactions` à chaque ouverture, sans cache ; deux au plus | Décision du propriétaire (M-07) : aucune route ne fournit de « récents » |
| E-13 | Montants rapides lus dans `QUICK_AMOUNTS` ; « Solde après envoi » affiché seulement si le montant ne dépasse pas le solde | Le solde n'est qu'indicatif : le serveur refuse un solde insuffisant (SPEC §7.4) |
| E-13 | Saisie limitée à 12 chiffres | Garde technique contre le débordement d'un entier long ; le serveur fait foi |
| E-14 | Aucune ligne « Frais / Total » | Décision du propriétaire (M-07) : aucun frais dans le contrat ni dans le cœur |
| E-15 | Bouton « Continuer » après le PIN (la maquette ne montre que le pavé) | Comme E-06 : la longueur du PIN n'est pas fixée |
| E-16 | Pas de ligne « Date » | Décision du propriétaire (M-07) : `TransferResponse` ne porte pas d'horodatage ; le téléphone n'en fabrique pas |
| E-16 | Référence = `transferId` du serveur tel quel (la maquette montre « TX-8F3A-21C7 ») | Le format de la référence appartient au serveur |
| E-17 | Heure d'échéance (« le 1 oct. à 17:00 »), pas de décompte « 09:41 » ; pas de motif « (nouveau bénéficiaire, montant élevé) » | Décision du propriétaire (M-07) : pas de décompte calculé sur l'horloge du téléphone ; le contrat ne donne pas le motif |
| E-18 | REJECTED : « Opération rejetée. Réessayez. » ; « depuis votre téléphone lié » seulement si la clé est révoquée | Décision du propriétaire (M-07) : message par code de raison |
| E-18 | Variante « Envoi non confirmé » (réponse perdue après l'envoi), avec « Voir l'historique » | SPEC §7.4 : jamais de renvoi, l'historique fait foi |
| E-19 | Seulement les champs du contrat : nom (sinon numéro), date du serveur, Envoi ou Réception, montant, statut ; pas de « signé », de canal ni de lieu | Décision du propriétaire (M-07) |
| E-11 | ~~Pas de bandeau d'alerte ; « Alertes » sans compteur~~ M-08 : bandeau de la première alerte à traiter, compteur sur la tuile | Flux F livré |
| E-20 | La clé est saisie dans un champ texte (clavier « mot de passe », sans correction ni suggestion), affichée en clair, tirets tous les 4 caractères à l'affichage seulement ; bouton actif à `CODE_LENGTH` caractères | La maquette montre la saisie en cours ; la longueur vient du jeu de règles |
| E-21 | Compte à rebours réel, calculé sur l'horloge **du téléphone** (c'est un délai local) | Contrairement à E-17, l'échéance est locale : le téléphone fait foi |
| E-21 | Pas de bouton retour pendant l'attente vers un nouvel essai ; le retour arrière ramène au PIN | L'attente tient quel que soit l'écran : elle est persistée |
| E-22 | « Ancien téléphone » sans modèle (la maquette montre « Tecno Spark 10 ») ; pas de nom du titulaire | L'application ne connaît ni l'ancien appareil ni le nom avant la session |
| E-24 | Pas de ligne « Compte » (nom du titulaire), ni de lieu (« agent-kyc-07 ») : seuls le résumé du serveur, l'heure, le numéro et l'état de ce téléphone | Le contrat ne porte que `summary` ; le nom n'est pas conservé localement |
| E-23 | Date et résumé du serveur sur chaque ligne ; pas de texte « vous avez répondu… » | Le contrat porte le statut, pas l'historique de la réponse |
| E-27a | « Continuer par USSD » est un texte, sans action | Bac à sable : aucun code USSD réel n'est composé (comme E-02) |
| E-27a | Pas de date « le 25 sept. à 15:40 » du nouveau téléphone | Le refus `KEY_REVOKED` ne porte pas de date |
| E-25 | Écran absent ; tuile et onglet « Sécurité » grisés | Hors M-08 (décision du propriétaire) : rotation et déliaison exigent des chaînes signées non définies |
| Alertes | Aucune interrogation en arrière-plan (SPEC §9.4) | Décision du propriétaire (M-08) : le jeton n'est qu'en mémoire (SPEC §3) ; carte SPEC v1.3 |
| E-06, E-08, E-09 | La pastille « 🔒 capture bloquée » est affichée en haut de la page, sous la barre d'état ; la maquette la dessine DANS la barre d'état | L'application ne dessine pas dans la barre d'état du système ; pastille omise en M-06, restaurée par le correctif du 30/09 |
| Tous | Symboles Unicode (‹, ⟳, 🛡, ↗, ≡, 🔔) à la place d'icônes | Aucune bibliothèque d'icônes dans ADR-037 |
| Tous | Mise en page vérifiée sur la taille des maquettes (360 × 760 dp) | Sur un écran nettement plus petit, le pavé numérique peut repousser le bouton hors de l'écran : limite déclarée |

## Architecture

- **MVVM :** `ViewModel` + `StateFlow`, avec un état unidirectionnel.
- **Injection manuelle par constructeurs,** sans cadre d'injection (ADR-037).
- **Paquets :**
  - `launch/` : E-01 ;
  - `enrolment/` : flux A et D (E-02 à E-09, E-20 à E-22, E-26, E-27), avec le ViewModel, les écrans et les composants ;
  - `daily/` : flux B, C et F (E-10 à E-19, E-23, E-24), avec les ViewModels, les écrans et la signature locale partagée ;
  - `data/` : Retrofit, DTO du contrat, dépôts, jeton de session en mémoire ;
  - `security/` : clé Keystore, déverrouillage local, protections de fenêtre, chaînes signées (`SignedPayload`), numéro et `deviceId` conservés (`BoundDeviceStore`), délai croissant local (`RecoveryThrottle`) ;
  - `ui/theme/` : palette.
- **Navigation par état** (un état par écran, porté par le ViewModel) : aucune bibliothèque de navigation, elle n'est pas dans ADR-037.
- **Tests** (`app/src/test`), tous sur la JVM, sans téléphone :
  - ViewModels (`LaunchViewModelTest`, `EnrolmentViewModelTest`, `RecoveryFlowTest`, `DailyViewModelTest`, `AlertsViewModelTest`). Chaque signature y est vérifiée avec la clé publique sur les octets attendus ; délais et interrogations sur horloge et attente injectées ;
  - octets signés (`SignedPayloadTest`), données conservées (`BoundDeviceStoreTest`, `PrefsThrottleStoreTest`) et délai croissant (`RecoveryThrottleTest`) ;
  - couche réseau contre MockWebServer (`EnrolmentRepositoryTest`, `DailyRepositoryTest`, `RecoveryAndAlertRepositoryTest`, dont la preuve qu'une requête coupée n'est jamais rejouée) ;
  - tests d'interface Compose d'E-01 et des écrans des flux A, B, C, D et F (`LaunchScreenTest`, `EnrolmentScreensTest`, `DailyScreensTest`, `RecoveryScreensTest`, `AlertScreensTest`), grâce à Robolectric.
