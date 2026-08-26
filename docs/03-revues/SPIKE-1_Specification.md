# SPIKE-1 — Signature Keystore vérifiable côté serveur

**Boîte de temps : 2 jours.** Code jetable, dans `spikes/spike-1-keystore/`, jamais importé par le produit.

---

## 1. Pourquoi ce spike existe

Toute la thèse du projet repose sur une phrase écrite dans douze documents : *le téléphone de la victime détient une clé privée que l'attaquant ne peut pas voler, même en contrôlant le numéro.*

Cette phrase suppose trois choses jamais vérifiées :

- que les téléphones du projet disposent d'un Keystore **matériel** (StrongBox ou TEE) ;
- qu'une clé EC P-256 y est générée **non exportable** ;
- que la signature produite est **vérifiable par un service Java** sans manipulation exotique.

Si l'une de ces trois est fausse, ES-02 tombe, le device binding tombe, et l'architecture est à revoir. C'est l'hypothèse HP-2 du registre.

**Ce spike ne construit rien. Il répond par oui ou non.**

---

## 2. Question principale

> Une clé EC P-256 générée dans le Keystore matériel d'un appareil Android, non exportable, produit-elle sur un canonical à ordre fixe une signature ECDSA/SHA-256 qu'un service Java 21 vérifie avec la seule clé publique transmise à l'enrôlement ?

**Réussite** : oui sur les deux appareils, avec la sortie brute de la vérification.
**Échec** : non, ou oui seulement avec un contournement — et alors le contournement est documenté et ES-02 est remise en question.

---

## 3. Questions secondaires (à répondre dans le même spike)

| # | Question | Pourquoi elle compte |
|---|---|---|
| Q1 | StrongBox est-il disponible sur chaque appareil ? Sinon, le TEE l'est-il ? | Détermine le niveau de garantie réel ; ISC-DEV §2.4 |
| Q2 | L'attestation de clé est-elle disponible et lisible ? | Permettrait de prouver au serveur que la clé est matérielle. Souhaitée, non bloquante |
| Q3 | Quelle est la latence de signature sur l'appareil ? p50 et p95 sur 100 signatures | Entre dans le budget de ENF-01 (500 ms p95 de bout en bout) |
| Q4 | Quelle est la taille de la signature encodée en base64 ? | Dimensionne le champ du contrat |
| Q5 | La clé survit-elle au redémarrage de l'appareil et à la mise à jour de l'app ? | Une clé perdue à chaque redémarrage rendrait le système inutilisable |
| Q6 | Que se passe-t-il si l'app est désinstallée puis réinstallée ? | C'est le cas de ré-enrôlement UC-05 ; il faut savoir si la clé est détruite |
| Q7 | Le canonical proposé produit-il exactement les mêmes octets côté Android et côté Java ? | C'est le piège numéro un : un accent, un séparateur, un encodage, et la vérification échoue |

---

## 4. Le canonical à tester

Proposition d'ADR-018, à valider ou à amender par ce spike :

```
accountId | type | amountXaf | beneficiaryId | nonce
```

Règles :

- séparateur : le caractère `|` (U+007C), sans espace autour ;
- encodage : UTF-8, sans BOM ;
- `amountXaf` : représentation décimale de l'entier, sans séparateur de milliers, sans signe ;
- `nonce` : la chaîne hexadécimale de 32 caractères telle que reçue du serveur, en minuscules ;
- aucun retour à la ligne, aucun espace de tête ou de queue ;
- **jamais de JSON sérialisé** : la sérialisation JSON n'est pas déterministe et ne doit jamais servir de base à une signature.

Le spike doit prouver que la chaîne d'octets produite côté Android est **identique** à celle produite côté Java, en comparant les empreintes SHA-256 des deux, avant toute signature. Si elles diffèrent, tout le reste est inutile.

Cas à inclure dans le jeu de test du canonical :
- `beneficiaryId` contenant un caractère non-ASCII (accent) ;
- `amountXaf` valant 1, puis 999999999 ;
- `accountId` en UUID v4 minuscules.

---

## 5. Dispositif à construire

### Partie A — Android (Android Studio)

Une application minimale, **une seule activité**, sans dépendance hors SDK :

1. Bouton « Générer la clé » : génère une paire EC P-256 dans le Keystore, avec `setIsStrongBoxBacked(true)` ; en cas d'exception, retente sans StrongBox et **journalise laquelle des deux voies a réussi**.
2. Affiche : alias de la clé, disponibilité StrongBox, présence d'une chaîne d'attestation et son nombre de certificats.
3. Bouton « Exporter la clé publique » : affiche la clé publique en base64 (format X.509 SubjectPublicKeyInfo), copiable.
4. Champ de saisie des cinq composants du canonical, et bouton « Signer » : construit le canonical, affiche son empreinte SHA-256 en hexadécimal, signe, affiche la signature en base64.
5. Bouton « Mesurer » : 100 signatures consécutives, affiche p50 et p95 en millisecondes.
6. Bouton « Tenter l'export de la clé privée » : essaie explicitement d'extraire la clé privée et affiche l'exception obtenue. **C'est une preuve, pas une erreur** : elle démontre la non-exportabilité.

L'application ne communique avec aucun serveur. Les valeurs se transmettent par copier-coller ou par `adb`.

### Partie B — Serveur (VS Code ou IntelliJ)

Un programme Java 21 autonome, **sans Spring**, sans dépendance hors JDK :

1. Lit la clé publique en base64 et la reconstruit.
2. Construit le même canonical à partir des cinq composants.
3. Affiche l'empreinte SHA-256 du canonical, pour comparaison avec celle de l'appareil.
4. Vérifie la signature avec `Signature.getInstance("SHA256withECDSA")`.
5. Affiche : VÉRIFIÉE ou REFUSÉE.
6. Cas négatifs obligatoires, chacun devant produire REFUSÉE :
   - un caractère modifié dans le canonical ;
   - le montant modifié de 1 ;
   - la signature du second appareil vérifiée contre la clé publique du premier ;
   - une signature tronquée d'un octet.

---

## 6. Ce que `RESULT.md` doit contenir

Le gabarit existe déjà. Il est rempli ainsi :

**Question** — la question principale, telle qu'énoncée en §2.

**Dispositif** — modèle exact de chaque appareil, version Android, niveau d'API, version d'Android Studio, version du JDK. Description en trois lignes de ce qui a été construit.

**Résultat** — pour chaque appareil et chaque question Q1 à Q7 : la réponse et la sortie brute qui l'établit. Les empreintes SHA-256 des deux canonicals côte à côte. Les quatre cas négatifs avec leur verdict.

**Décision (ADR)** — ADR-018 passe en FERME avec l'algorithme, le canonical, l'encodage et l'attestation constatée ; ou reste PROVISOIRE avec la raison précise.

**Statut** — RÉUSSI, ÉCHOUÉ, ou RÉUSSI AVEC RÉSERVES, suivi de ce que le résultat implique pour ES-02 et HP-2.

---

## 7. Critères d'acceptation du spike

| # | Critère |
|---|---|
| 1 | Les empreintes du canonical sont identiques côté Android et côté Java, sur les trois cas de test |
| 2 | Une signature légitime est VÉRIFIÉE sur les deux appareils |
| 3 | Les quatre cas négatifs produisent tous REFUSÉE |
| 4 | La tentative d'export de la clé privée échoue, avec l'exception citée |
| 5 | Q1 à Q7 ont toutes une réponse constatée, pas supposée |
| 6 | Aucun fichier de `spikes/` n'est importé par `core/`, `pdp/` ou `android/` |

---

## 8. Ce que ce spike ne fait pas

Pas d'enrôlement réel, pas de base de données, pas de PDP, pas d'API, pas de Spring, pas de mTLS, pas d'interface soignée. Aucune de ces choses n'est nécessaire pour répondre à la question, et chacune allongerait la boîte de temps.

Le code produit est **jeté** après consignation du résultat. Ce qui survit, c'est `RESULT.md` et l'ADR.

---

## 9. Si le spike échoue

Trois scénarios, à traiter dans l'ordre :

**Pas de Keystore matériel sur les appareils.** La clé existe mais en logiciel. ES-02 tient encore contre l'attaquant du modèle de menace — qui n'a pas l'appareil — mais la garantie est plus faible. À déclarer comme limite dans le mémoire et à mesurer : combien d'appareils du marché camerounais ont un TEE ?

**Signature non vérifiable côté Java.** Presque toujours un problème d'encodage ou de format de clé. Le spike doit alors isoler à quelle étape la chaîne diverge, grâce aux empreintes du canonical.

**Clé détruite au redémarrage ou à la mise à jour.** Le device binding deviendrait inutilisable en pratique. Ce serait le seul échec réellement structurel, et il imposerait de revoir ADR-002.

Dans les trois cas : on ne contourne pas, on consigne et on rouvre la conception.
