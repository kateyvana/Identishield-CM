# Note de qualification de L12 — ancrage aux racines Google et lecture du RootOfTrust (carte M5-B2)

Date : 2026-11-14. Couloir B (Mobile). Ordre du superviseur technique du 2026-11-14 : note en **lecture seule,
sans code**. Objet : la limite L12 de B3a (`2026-10-22-limites-declarees-B3a.md`, « l'attestation prouve qu'une
chaîne est bien formée, pas qu'elle vient d'un vrai téléphone »), déclarée dans ADR-018 (amendement M-09,
FERMÉ le 2026-10-11 : « Racine simulée », « État de démarrage non contrôlé », « Validité temporelle non
contrôlée »). Sources lues : `AttestationVerifier.java` (main 963b6c3), `RealisticAttestationTest.java`,
ADR-018, ADR-040, SPIKE-1 `RESULT.md`, ISC-MOB-SPEC §12.1 tel que cité par ADR-018.

## 1. Ce que le vérificateur fait aujourd'hui, et ce qu'il ne fait pas

| Contrôle | Aujourd'hui (`AttestationVerifier`, 8 issues) | Ce que L12 ajoute |
|---|---|---|
| Chaîne lisible, chaque certificat signé par le suivant, dernier auto-signé | fait (`MALFORMED_CHAIN`, `BROKEN_CHAIN`) | la **racine** doit être l'une des racines matérielles publiées par Google, sinon refus |
| Clé attestée = clé présentée, EC P-256 | fait (`NOT_EC_P256`, `KEY_MISMATCH`) | — |
| Extension `KeyDescription` (OID 1.3.6.1.4.1.11129.2.1.17), 8 champs, DER strict | fait, BouncyCastle (ADR-040) | lecture **à l'intérieur** de `teeEnforced` (champ 8) : l'étiquette **704 `rootOfTrust`** |
| Niveaux de sécurité TEE (1) ou StrongBox (2), logiciel refusé | fait (`SOFTWARE_KEY`) | — |
| Défi identique, comparé à temps constant | fait (`CHALLENGE_MISMATCH`) | — |
| Racine comparée à une liste de confiance | **non** (la racine est supposée de confiance) | point 1 |
| `RootOfTrust` : démarrage vérifié, chargeur verrouillé | **non** | point 2 |
| Révocation (liste d'état Google) | **non**, et impossible hors ligne (QM-06) | reste déclaré |
| Validité temporelle des certificats | **non** (horloge injectée à décider) | reste déclaré |

## 2. Ce qu'exige l'ancrage aux racines Google (point 1)

1. **Les racines.** Google publie, dans la documentation « Key Attestation », le ou les certificats racines
   auto-signés de l'attestation matérielle (racine historique RSA 4096 bits, racines plus récentes ajoutées
   depuis). Ils sont publics, copiables **hors ligne** dans la configuration versionnée du PDP (fichier de
   racines à côté de `config/ruleset.yaml`, ce ne sont pas des secrets). La liste doit être **tenue à jour à la
   main** à chaque publication de Google : sans Internet, le bac à sable ne l'apprendra pas seul. Une racine
   nouvelle non copiée = faux rejet des appareils récents.
2. **Le contrôle.** Après `linked`, comparer le dernier certificat (ou sa clé publique) à la liste ; toute autre
   racine → nouvelle issue, par exemple `UNTRUSTED_ROOT`, refus `DEVICE_UNSUPPORTED` côté cœur comme pour
   une clé logicielle. Les tests gardent une racine de test **injectée** (paramètre de configuration), jamais
   codée en dur : les chaînes synthétiques de `TestAttestation`, `RealisticAttestationTest` et de l'outil
   `tools/device_register_cold` (racine auto-signée) seraient toutes refusées sinon.
3. **Ce que cela ferme.** Une chaîne fabriquée de bout en bout par un attaquant avec sa propre racine ne passe
   plus. **Ce que cela ne ferme pas :** une clé d'attestation de lot **compromise et révoquée** par Google (cas
   réel de 2022 sur des clés de plusieurs fabricants) reste acceptée tant que la révocation (point 3) n'est pas
   contrôlée. À dire dans ADR-018.

## 3. Ce qu'exige la lecture du RootOfTrust (point 2)

1. **Où il est.** Dans `KeyDescription`, champ `teeEnforced` (le huitième), étiquette **704**, structure
   `RootOfTrust ::= SEQUENCE { verifiedBootKey OCTET STRING, deviceLocked BOOLEAN, verifiedBootState
   ENUMERATED { Verified(0), SelfSigned(1), Unverified(2), Failed(3) }, verifiedBootHash OCTET STRING }`
   (le dernier champ existe depuis la version 3 de l'attestation). Il doit être lu dans `teeEnforced`, jamais
   dans `softwareEnforced` : seul le premier est garanti par la puce.
2. **Le contrôle demandé par ISC-MOB-SPEC §12.1 :** `deviceLocked = TRUE` **et** `verifiedBootState =
   Verified`. Tout autre couple → refus, nouvelle issue (`BOOT_NOT_VERIFIED`), même traitement côté cœur.
3. **Le lecteur.** Le vérificateur actuel ne lit que le premier niveau de `KeyDescription` ; il faut descendre
   dans la `AuthorizationList` de `teeEnforced` (SEQUENCE d'éléments étiquetés, numéros élevés, ordre
   croissant), ce que BouncyCastle sait faire (ADR-040) et que `RealisticAttestationTest` fabrique déjà en
   test (RootOfTrust compris). L'absence de l'étiquette 704 dans `teeEnforced` doit être un **refus**, pas une
   tolérance.

## 4. Effort, en jours

| Lot | Contenu | Jours |
|---|---|---|
| A | Fichier de racines dans la configuration, paramètre nommé, comparaison, issue `UNTRUSTED_ROOT`, tests (racine de test injectée, racine inconnue refusée, deux racines publiées acceptées) | 1 |
| B | Lecture de `teeEnforced` → étiquette 704 → `RootOfTrust`, issue `BOOT_NOT_VERIFIED`, tests (quatre états × deux valeurs de `deviceLocked`, absence de 704, 704 dans `softwareEnforced` seulement) | 1 |
| C | **Vecteur réel** : chaîne complète de la SM-T515 et d'au moins un téléphone (voir §5) rejouée dans un test, racine et RootOfTrust lus et consignés | 0,5 à 1 |
| D | Preuve sur appareil : un enrôlement complet (R5 du script de répétition) après déploiement, résultat VALID et non `UNTRUSTED_ROOT`/`BOOT_NOT_VERIFIED` ; fenêtre VM du couloir A | 0,5 + une fenêtre A |
| E | Documents : amendement d'ADR-018 (deux limites levées, deux maintenues), Javadoc d'`AttestationVerifier` (cite encore M-09 « PROPOSÉ »), limites B3a, SRS si une exigence est touchée (à vérifier par l'index) | 0,5 |
| **Total** | | **3,5 à 4 jours** de couloir B, plus une fenêtre de déploiement du couloir A |

Les deux à trois jours annoncés dans L12 pour les points 1 et 2 tiennent ; le reste est le vecteur réel, la
preuve sur appareil et les documents, qui n'étaient pas comptés.

## 5. Risque de faux rejet sur la SM-T515

La question qui décide de tout : **à quoi ressemble la vraie chaîne de la SM-T515 ?** Personne ne l'a
encore lue : SPIKE-1 a lu les chaînes d'un itel A70 (5 certificats) et d'un Redmi Pad SE (4), pas de la
tablette ; le test de contrat de B3a (étape 4) a utilisé la chaîne du téléphone de la propriétaire, non
conservée dans le dépôt ; `pdp.device` ne garde que la clé publique. Tant que cette lecture n'est pas faite,
les risques ci-dessous sont des hypothèses.

| Risque | Mécanisme | Vraisemblance (hypothèse) | Comment le lever avant d'écrire du code |
|---|---|---|---|
| Racine non Google | La chaîne d'attestation Keystore d'un appareil Samsung certifié GMS de 2019 remonte normalement à la racine Google ; une chaîne Samsung (Knox) ou propre à un lot ancien ne le ferait pas | faible, mais **non vérifiée** | lire la chaîne réelle (§6) et comparer sa racine |
| `verifiedBootState ≠ Verified` ou `deviceLocked = FALSE` | tablette dont le chargeur a été déverrouillé un jour, ou firmware non officiel ; Samsung l'affiche comme « Statut de l'appareil : Officiel / Personnalisé » dans Paramètres → À propos → Informations sur le logiciel | faible si la tablette n'a jamais été modifiée ; **non vérifiée** | la propriétaire lit « Statut de l'appareil » (sans câble) ; puis la chaîne réelle |
| Clé de lot révoquée par Google | certaines clés d'attestation Samsung ont été révoquées en 2022 ; sans révocation (point 3), **aucun faux rejet** ; si un jour la liste d'état est copiée hors ligne, une SM-T515 de lot révoqué serait refusée | inconnue | ne pas faire le point 3 ; le dire |
| Dates des certificats | certains certificats d'attestation ont des dates atypiques (`notBefore` 1970, intermédiaires expirés) ; **aucun faux rejet tant que le point 4 n'est pas fait** | — | ne pas faire le point 4 sans vecteur réel |
| Racine Google nouvelle non copiée | appareils récents (pas la SM-T515) | nulle pour la tablette | tenir la liste |
| `KeyDescription` v3 (Android 11, Keymaster 4) | champs connus du vérificateur ; `RealisticAttestationTest` couvre KeyMint plus récent ; v3 est plus simple | très faible | vecteur réel |

Conséquence pour la démonstration : un faux rejet se manifesterait à **R5** (E-07, « Clé créée dans la puce »
puis refus serveur), c'est-à-dire **au cœur de la thèse, devant le jury**. C'est la raison de la
recommandation ci-dessous.

## 6. Recommandation

1. **Ne rien déployer avant la démonstration.** Le gel du code tient, et toute nouvelle issue de refus est
   un risque non répété au moment le plus visible du parcours.
2. **Faire d'abord la lecture, sans code de décision** : au prochain enrôlement sur la SM-T515 (R5 de la
   répétition), obtenir la chaîne réelle telle que le PDP la reçoit et la consigner (racine, longueur, niveau,
   RootOfTrust). Cette lecture exige un relevé côté serveur qui n'existe pas aujourd'hui (le PDP ne journalise
   pas la chaîne) : c'est une petite carte de **lecture seule** (journal au niveau DEBUG ou vue d'un outil
   d'administration), à décider par la propriétaire ; sans câble, c'est la seule voie. En même temps, la
   propriétaire lit « Statut de l'appareil » dans les paramètres de la tablette.
3. **Puis, après la démonstration, faire les points 1 et 2** (lots A à E, 3,5 à 4 jours) **derrière un
   paramètre nommé** de la configuration versionnée (par exemple `ATTEST_ROOT_PINNING` : `off` / `on`), `off`
   dans la pile servie jusqu'à la preuve D, `on` ensuite ; la racine de test injectée pour les portes et les
   outils. Le paramètre est une **décision de conception** : il relève d'un amendement d'ADR-018 par la
   propriétaire, pas du couloir B.
4. **Laisser déclarés les points 3 (révocation) et 4 (validité temporelle)**, et l'écrire dans ADR-018 avec
   la phrase manquante : une clé de lot révoquée reste acceptée hors ligne.
5. **Ordre par rapport aux autres cartes du mois 5** : après la démonstration sans câble (M5-B1) et après la
   décision sur `/recovery-code/rotate` et `DELETE /device` (#64), qui touchent la même table `pdp.device` et la
   même route d'enrôlement.

## 7. Ce que cette note ne vérifie pas

- La chaîne réelle de la SM-T515 (racine, RootOfTrust) : **non lue** ; tout §5 en dépend.
- Le texte exact d'ISC-MOB-SPEC §12.1 (fichier `.docx` v1.3) : cité d'après ADR-018 et L12.
- La liste exacte des racines publiées par Google à ce jour : à copier depuis la documentation officielle au
   moment du lot A, pas de mémoire.
- Rien n'a été exécuté : aucun appareil, aucune VM, aucun code touché.
