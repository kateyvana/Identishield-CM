# SPIKE-1 — mode d'emploi du dispositif

Code jetable. Rien d'ici n'est importé par `core/`, `pdp/` ni `android/`.

## Partie B — serveur (prête, validée par auto-test)

```sh
cd spikes/spike-1-keystore/server
"$JAVA_HOME/bin/java" CanonicalFingerprint.java   # empreintes de référence des 3 cas
"$JAVA_HOME/bin/java" SelfTest.java               # (option) génère un jeu d'essai JVM
"$JAVA_HOME/bin/java" Verifier.java entree.txt [cle-autre-appareil.txt]
```

Format de `entree.txt` (UTF-8, 7 lignes) : clé publique base64 X.509 /
accountId / type / amountXaf / beneficiaryId / nonce / signature base64.

## Partie A — Android (action manuelle du propriétaire)

1. Android Studio → nouveau projet **Empty Views Activity**, Kotlin, minSdk 28,
   dans `spikes/spike-1-keystore/android/`.
2. Remplacer le contenu de `MainActivity.kt` par
   `../android-a-coller/MainActivity.kt` (adapter le nom de paquet à celui du
   projet). Aucune dépendance à ajouter, aucune permission.
3. Installer sur chaque appareil, dérouler dans l'ordre les boutons 1 → 6.
4. Étape 1 du protocole : préremplir CAS-1, CAS-2, CAS-3 ; pour chacun, bouton
   4 ; comparer l'empreinte affichée avec celle du serveur. **Tant qu'elles
   diffèrent, ne rien signer d'autre.**
5. Transmettre au poste (copier-coller ou `adb shell input` /
   `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`) : la clé publique
   base64, et pour le cas nominal la signature base64.

## Empreintes de référence (serveur, constatées le 26/08/2026)

| Cas | Propriétés couvertes | SHA-256 attendu |
|---|---|---|
| CAS-1 | UUID v4 minuscules ; montant 1 | `d91f4522a644a427d9607a0197180ce5689b883fcdd98f2abfbe8d35ebe1e5d9` |
| CAS-2 | accents non-ASCII ; montant 999999999 | `62b0d9521e3c6f07bc3da1d7450cc6b24557981f4440afe98c0bd7c6850395b7` |
| CAS-3 | nominal | `6a179dc176152a7d37e3508b054168f5913885f6a1a69091f93d0b1e6b4be86f` |
