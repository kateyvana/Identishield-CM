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
| MOB-F-01 : vérification locale de la clé | `AndroidKeystoreKeyChecker` : la présence de l'alias dans l'Android Keystore est la seule chose que l'application sait |

**Détail de `SandboxTrustTask`.** Le trafic en clair est interdit dans tous les cas, et aucune autorité système ni utilisateur n'est acceptée.

- **Pourquoi la CA n'est pas dans le dépôt.** La CA du bac à sable est créée au premier démarrage de chaque pile (SPIKE-4, ADR-021), donc elle change d'une pile à l'autre. De plus, un `.pem` ne peut pas entrer dans le dépôt (porte `secrets`).
- **Comment la fournir :** `-Pisc.sandboxCa=<chemin du certificat de la CA>` ou la variable `ISC_SANDBOX_CA`.
- **Avec une CA fournie :** seule cette CA est de confiance (épinglage).
- **Sans CA :** le build debug ne fait confiance à **aucune** autorité, ce qui est un échec fermé ; le build release **refuse** de se construire.

Limite connue : le faux serveur `tools/mobile-mock/` parle en HTTP en clair. L'application ne peut donc pas l'atteindre telle quelle. Le raccordement (TLS du mock, ou exception réservée au debug) est à trancher dans M-06.

## Maquettes de référence

`docs/02-specifications/mobile/maquettes/` (voir son README) : 28 écrans et l'identité visuelle. Palette : marine `#10375C`, vert protégé `#1B998B`, ambre `#F2A93B`, rouge `#D7263D`, fond `#F4F7FB`, texte `#14213D`. Police système (Roboto).

**Règle : tout écart avec les maquettes est documenté, jamais silencieux.**

Écarts en vigueur :

| Écran | Écart | Motif |
|---|---|---|
| E-01 | Le gris des textes secondaires (sous-titre, « Vérification… ») est le texte `#14213D` à 65 % d'opacité, pas une septième couleur | La palette ne définit pas de gris secondaire |
| E-01 | Le logo est `logo_mark.png` (PNG des maquettes), pas un dessin vectoriel | Échafaudage ; la vectorisation n'est pas nécessaire tant que l'écran est fidèle |

## Architecture

- **MVVM :** `ViewModel` + `StateFlow`, avec un état unidirectionnel.
- **Injection manuelle par constructeurs,** sans cadre d'injection (ADR-037).
- **Paquets :** `launch/` (écran E-01) et `ui/theme/` (palette).
- **Tests** (`app/src/test`) :
  - tests unitaires JVM (`LaunchViewModelTest`) ;
  - test d'interface Compose d'E-01 (`LaunchScreenTest`), exécuté sur la JVM grâce à Robolectric, sans téléphone.
