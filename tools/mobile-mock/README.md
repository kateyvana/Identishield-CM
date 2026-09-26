# isc-mobile-mock — faux serveur de l'API mobile (M-03)

Faux serveur de **développement** de l'application mobile. Il sert les 18 routes de `contracts/mobile-api.yaml` (préfixe `/mobile/v1`) pour que l'application puisse être construite (cartes M-06 à M-08) avant que le cœur n'expose ces routes (M-09). Python, bibliothèque standard uniquement (ADR-033).

## Limites — à lire avant tout usage

- **Ce n'est pas le cœur.** Rien de ce qu'il accepte ne prouve quoi que ce soit sur la sécurité du système.
- **Il ne vérifie NI la signature cryptographique NI l'attestation.** Il ne contrôle que leur **forme** : base64 non vide, défi et nonce émis par lui et non réutilisés, appareil attendu. Une signature « acceptée » par le mock ne vaut rien.
- **Ses règles de décision sont des règles de MOCK, pas celles du PDP :**
  - `REJECTED` : signature absente, ou nonce inconnu ou déjà utilisé ;
  - `FREEZE` : envoi vers le numéro de test `freezeBeneficiaryMsisdn` des fixtures ;
  - `STEP_UP` (exécution différée de `DEFER`, annulable) : montant ≥ `CAP_PROB.dailyXaf`, ou nouveau bénéficiaire ;
  - `ALLOW` : dans tous les autres cas.
- **Attestation de mock :** un certificat dont le contenu commence par `SOFTWARE` est refusé (`422 DEVICE_UNSUPPORTED`), pour simuler l'écran E-27b.
- **État en mémoire, volatile,** rechargé depuis `fixtures.json` à chaque démarrage. Toutes les données sont **fictives** (plage 677000000–677009999, ADR-035).
- **HTTP en clair,** sur `127.0.0.1` par défaut. Pas de TLS épinglé (MOB-S-08) : c'est une limite du mock, à traiter avant de brancher un téléphone physique (M-05 ou M-06).
- **ES-22 :** un compte inexistant et un PIN faux reçoivent le même code et le même corps, mais **pas forcément le même temps de réponse**.
- **Non simulés :** le freinage de la recherche du nom du titulaire (MT-06), l'anti-force-brute du PIN (QM-14, décision ouverte) et l'expiration du nonce.

## Lancer

```bash
make mobile-mock                               # port : MOBILE_MOCK_PORT (défaut du Makefile)
make mobile-mock MOBILE_MOCK_PORT=9000         # autre port
make mobile-mock MOBILE_MOCK_DEMO=1            # mode démo : l'OTP de test est renvoyé par /enrolment/start
```

Les paramètres nommés (REENROL_ATTEMPTS, LOCK_BASE, LOCK_MAX, ALERT_REENROL, CAP_PROB, OTP, OTP_ATTEMPTS, CODE_LENGTH, ENROL_TTL, APP_SESSION_TTL, DEFER, REENROL_FREEZE) sont lus dans `config/ruleset.yaml`, jamais écrits en dur. Chaque requête est validée par le contrat lui-même : un corps hors contrat reçoit `400 REQUEST_INVALID`.

## Fixtures (`fixtures.json`, fictives)

| Compte | Numéro | PIN | Détail |
|---|---|---|---|
| NEUF | 677000200 | 1234 | Jamais lié : le premier enrôlement passe par l'OTP |
| DÉJÀ LIÉ | 677000123 « Amina NDONGO ÉLOMO » | 1234 | Solde 152 300 FCFA ; appareil lié `boundDeviceId` ; clé de récupération de test dans le fichier (le mock n'en garde qu'un haché salé) ; bénéficiaires connus 677000456 et 677000789 |
| Bénéficiaire | 677000456 « Jean-Paul NKOA » | — | Pas d'accès à l'application |
| Bénéficiaire | 677000789 « Marie ABENA » | — | Pas d'accès à l'application |
| Test FREEZE | 677009999 | — | Tout envoi vers ce numéro donne FREEZE (règle de mock) |

L'OTP de test est `demoOtp`. En dehors du mode démo, il n'apparaît dans aucune réponse ; les tests le lisent dans les fixtures.

**Alerte de changement de SIM (flux F).** Le changement de SIM est un événement du HLR, pas une route de l'API mobile. Le mock le déclenche par `MockState.raise_sim_change(msisdn)`, utilisé par les tests. Il n'existe pas de route HTTP pour cela.

## Codes de raison

Liste fermée d'ISC-MOB-SPEC v1.2 §12.4, plus **`REQUEST_INVALID`** : un code technique pour une requête hors contrat ou une ressource inconnue. Il n'existe pas dans la SPEC et doit y être ajouté (SPEC v1.3). Un jeton de session absent ou expiré reçoit `401 CREDENTIALS_INVALID` ; l'application refait alors une signature de défi (SPEC §6).

## Tests

```bash
cd tools/mobile-mock && uv run pytest -q
```

- **Tests de contrat** (`tests/test_contract.py`) : un parcours touche les 18 routes, et chaque réponse est validée contre le contrat (code HTTP déclaré, corps conforme au schéma).
- **Tests de scénario** (`tests/test_scenarios.py`) : flux A, B, C (les quatre décisions), D (deux échecs, `Retry-After` croissant, succès), F (SIM_CHANGED → « ce n'est pas moi » → FROZEN), réponse uniforme ES-22.
- **Lecteur YAML restreint** (`tests/test_miniyaml.py`).
