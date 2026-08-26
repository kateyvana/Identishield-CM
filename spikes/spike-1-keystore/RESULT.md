# Résultat du spike

## Question

Une clé EC P-256 générée dans le Keystore matériel d'un appareil Android, non
exportable, produit-elle sur un canonical à ordre fixe une signature
ECDSA/SHA-256 qu'un service Java 21 vérifie avec la seule clé publique
transmise à l'enrôlement ? (spec §2)

## Dispositif

| | Appareil 1 | Appareil 2 |
|---|---|---|
| Modèle | itel A70 (modèle A665L) | Redmi Pad SE (Xiaomi 23073RPBFG) |
| Android / API | 13 / 33 | 15 / 35 |
| Voie Keystore | **TEE** (StrongBox absent : `StrongBoxUnavailableException`, `FEATURE_STRONGBOX_KEYSTORE=false`) | **TEE** (idem) |
| Attestation | chaîne de **5** certificats | chaîne de **4** certificats |

Outillage : JDK 21.0.5 Temurin (vérificateur) ; app Kotlin minSdk 28, AGP 9.3.2,
Gradle 9.5 ; pilotage par adb (USB puis Wi-Fi), preuves lues sur l'écran par
`uiautomator dump` (le logcat de l'itel supprime des lignes).
Construit : une activité unique (génération, infos, export public, signature,
mesure, tentative d'export privé) et un vérificateur Java 21 autonome sans
dépendance hors JDK (`server/`). Code jetable.

## Résultat

**Étape 1 — canonical (Q7)** : empreintes SHA-256 identiques appareil/serveur
sur les trois cas, sur les deux appareils, contre-vérifiées par une
implémentation Python indépendante :

| Cas | Octets | SHA-256 (identique sur les 4 mesures) |
|---|---|---|
| CAS-1 (UUID v4 min., montant 1) | 86 | `d91f4522a644a427d9607a0197180ce5689b883fcdd98f2abfbe8d35ebe1e5d9` |
| CAS-2 (accents é/ô, montant 999999999) | 113 | `62b0d9521e3c6f07bc3da1d7450cc6b24557981f4440afe98c0bd7c6850395b7` |
| CAS-3 (nominal) | 95 | `6a179dc176152a7d37e3508b054168f5913885f6a1a69091f93d0b1e6b4be86f` |

**Signature nominale** : `NOMINAL : VERIFIEE` sur les deux appareils
(entrées : `server/device-input.txt`, `server/device2-input.txt`).

**Cas négatifs (les quatre, chacun REFUSÉE)** :
N1 caractère modifié → REFUSEE · N2 montant +1 → REFUSEE ·
N3 signature de l'appareil 2 contre la clé publique de l'appareil 1 → REFUSEE ·
N4 signature tronquée d'un octet → REFUSEE
(`SignatureException: Invalid encoding for signature` — refus par exception).

**Questions Q1–Q7** (toutes constatées) :

| Q | Appareil 1 | Appareil 2 |
|---|---|---|
| Q1 StrongBox / TEE | StrongBox non, **TEE oui** | idem |
| Q2 Attestation lisible | oui, 5 certificats | oui, 4 certificats |
| Q3 Latence (n=100) | p50=14 ms, p95=18 ms | p50=10 ms, p95=11 ms |
| Q4 Taille signature | 96 car. base64 (70–71 octets DER, taille variable ECDSA) | idem |
| Q5 Survie redémarrage / mise à jour | **oui / oui** (VERIFIEE contre la clé publique antérieure) | **oui / oui** |
| Q6 Désinstallation | **clé détruite** (`getKey → null`) → ré-enrôlement UC-05 | idem |
| Q7 Canonical identique | oui (3/3) | oui (3/3) |

**Non-exportabilité de la clé privée** — message exact, identique sur les deux
appareils (classe `android.security.keystore2.AndroidKeyStoreECPrivateKey`) :
`PrivateKey.getEncoded() → null` ;
`KeyFactory.getKeySpec(PKCS8) → java.security.spec.InvalidKeySpecException:
Encoding type must be PKCS#8; was null`.

## Décision (ADR)

Proposition : **ADR-018 passe de PROVISOIRE à FERME** avec :
canonical `accountId|type|amountXaf|beneficiaryId|nonce`, séparateur U+007C
sans espaces, UTF-8 sans BOM, montant décimal sans signe, nonce hex 32
minuscules ; **ECDSA P-256 (secp256r1), SHA-256** (`SHA256withECDSA`),
signature DER encodée base64 (70–72 octets, taille variable) ; clé publique
transmise en X.509 SubjectPublicKeyInfo base64 ; attestation demandée à la
génération (`setAttestationChallenge`), chaîne disponible sur les deux
appareils. Politique d'enrôlement : `setIsStrongBoxBacked(true)` avec repli
TEE journalisé — StrongBox est absent des deux appareils du projet.
La fermeture de l'ADR est l'acte du propriétaire.

## Statut

**RÉUSSI** — avec une réserve à porter au mémoire : la garantie matérielle
constatée est de niveau **TEE, pas StrongBox** (aucun des deux appareils n'en
dispose ; scénario §9-1 partiellement matérialisé — la clé reste matérielle et
non exportable). Conséquences : **ES-02 tient** (le dispositif signe, le
serveur vérifie, la clé ne sort pas, un autre appareil est refusé) ;
**HP-2 tient au niveau TEE** ; Q6 confirme le besoin du ré-enrôlement
protégé (ES-17 / AC-13 / UC-05) puisque la désinstallation détruit la clé.
