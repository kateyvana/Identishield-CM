# D-B7-1 · Premier `POST /enrolment/complete` en erreur générique — qualification AVANT correctif

Date : 2026-10-25. Couloir B. Passe B7 (`docs/02-specifications/mobile/captures/2026-11-B7/RAPPORT-PASSE-B7.md`,
étape (c)). Ordre du superviseur technique : retrouver la cause dans les journaux, reproduire si
possible sur la pile isolée, écrire la cause avec les lignes brutes, PUIS correctif + test dans une PR
à part.

## 1. Faits observés (2026-10-25, pile servie `main fbdaf3b`, tablette SM-T515)

| Instant (UTC) | Fait | Source |
|---|---|---|
| ~10:50:0x | E-07 : « Clé créée dans la puce ✓ », « Déverrouillage local ✓ », « Enregistrement auprès du serveur … » | capture `E-07-securisation.png`, dump de l'écran |
| **10:50:12.547** | `pdp.device` : clé `e6ecb8f0…` **enregistrée EN ATTENTE** pour le compte 677000200 | `select state, enrolled_at, left(id,8) from pdp.device …` → `REVOKED \| 2026-10-25 10:50:12.547881+00 \| e6ecb8f0` (révoquée ensuite par la seconde inscription) |
| 10:50:1x | Écran : **« Une erreur est survenue. Réessayez. »**, bouton « Réessayer » | dump de l'écran |
| 10:50:12 → 10:52:20 | `core.enrolment` de 677000200 **reste en `BIND`** (constaté à 10:51 : `BIND \| f \| …`) : le cœur n'a PAS passé l'enrôlement à `CONFIRM` | SQL en lecture seule |
| 10:52:20.464 | logcat système : `E KeyStore: generateKeyInternal failed on request -68` (seconde génération de clé : StrongBox refusé, repli TEE) | `adb logcat` |
| 10:52:23.483 | `pdp.device` : clé `d4d95427…` enregistrée EN ATTENTE ; `core.enrolment` → `CONFIRM` ; E-08 affiché | SQL, écran |

**Journaux serveur** : `docker logs --since 2026-10-25T10:49:55Z --until 2026-10-25T10:50:40Z` sur
`isc-core` et `isc-pdp` : **aucune ligne** (ni le cœur ni le PDP ne journalisent les requêtes ; le
chemin nominal et le délai dépassé côté client sont silencieux). Il n'existe donc **pas de ligne
brute serveur** à coller : c'est en soi un constat (voir §4, point 3).

## 2. Lecture du code (ce que les faits imposent)

1. Le PDP a **réussi** `/device/register` (ligne `e6ecb8f0` écrite à 10:50:12.547) : attestation
   acceptée, code de récupération émis.
2. Le cœur **n'a pas traité une réponse 200** : `MobileEnrolment.complete`, cas `200`, appelle
   `enrolments.toConfirm(enrolmentId, deviceId)` (UPDATE `stage='CONFIRM'` WHERE `stage='BIND'`) ;
   l'enrôlement est resté en `BIND`. Deux chemins y mènent dans ce code : (a) `toConfirm` rend
   faux → `Answer.unavailable()` ; (b) la réponse du PDP n'est **jamais arrivée au cœur dans le délai**
   : `DeviceClient.send` → `Reply.unreachable()` (statut 0) → `switch (r.status())` → `default` →
   `Answer.unavailable()`. (a) est exclu : la ligne était en `BIND` et aucun autre appel ne l'a
   touchée (le second `/complete` à 10:52 l'a trouvée en `BIND`). Reste (b).
3. Le délai client des routes `/device/*` est **`core.device.timeout-ms = 1000`**
   (`core/src/main/resources/application.yml`, `DeviceClientProperties`), fixé en B3a d'après la
   mesure de la dérivation PBKDF2 du code de récupération. C'était le **premier `/device/register`
   depuis le redémarrage du PDP** de la fenêtre 3 (09:1xZ) : chargement de classes et compilation à
   la volée du chemin attestation (X.509/DER, ECDSA) + PBKDF2 44 000 itérations + insertion ; le
   « prêt par construction » livré ensuite (#116) ne chauffe que `/health` (base) — pas ce chemin.
   **Cause probable du 503 : dépassement du délai de 1 000 ms au premier appel à froid.** Le PDP,
   lui, a terminé et écrit la clé : la réponse est partie dans le vide.
4. Côté application, `EnrolmentViewModel` (bloc `repository.complete(request)`) :
   `is ApiResult.Refused` → `keys.delete()` puis, pour toute raison autre que `RECOVERY_INVALID`,
   `THROTTLED`, `ATTESTATION_REJECTED`/`DEVICE_UNSUPPORTED`, `ENROLMENT_EXPIRED` → **`EnrolError.UNEXPECTED`**
   = « Une erreur est survenue. Réessayez. ». Le **503 `SERVICE_UNAVAILABLE`** tombe dans ce `else` :
   l'application **détruit sa clé locale** (d'où la seconde génération et le `-68`) et affiche le
   message générique, alors que le contrat (`responses.Unavailable`) le définit comme **transitoire** :
   « l'application peut réessayer la même requête après Retry-After ; l'enrôlement en cours est GARDÉ
   à son étape ». C'est un **défaut certain** de l'application, indépendant de la cause du 503.

## 3. Reproduction

- **Sur la pile servie** : non rejouée volontairement (chaque `/device/register` écrit une clé et
  révoque la précédente ; le compte de démonstration est en état de passe).
- **Sur la pile isolée** (`infra/compose.b3a-isolement.yml`, projet `isc-b3a`, « VM à toi » de la
  propriétaire à 16:52Z, témoin B 16:53→17:23Z) : **REPRODUIT** le 2026-10-25, 17:01–17:02Z.

  Méthode. PDP isolé = **l'image de la pile servie** (`isc-pdp`, construite 11:19Z, `main`), base
  isolée neuve (init + migrations 001–006), appels en mTLS depuis `isc-b3a-net` (`isc-tools-b3a`,
  `curl` avec `core.crt`/`core.key`, horloges de `curl`). Chaque appel est un **premier enrôlement
  complet** : chaîne d'attestation TEE fabriquée à l'exécution (même construction que la fixture
  `pdp/src/test/…/TestAttestation`, KeyDescription réaliste, racine jetable), défi aléatoire signé
  par la feuille, pas de code → le PDP parcourt tout le chemin : DER strict, signature du défi,
  **PBKDF2 44 000** du code émis, **insertion**. Preuve du chemin complet : 6 lignes `pdp.device`
  **PENDING** et `count(pdp.recovery_code) = 6` sur la base isolée. Aucun corps de réponse
  conservé (il porte le code de récupération). Deux séries, chacune après `docker restart` du PDP
  isolé et attente de `healthy` (22 s) :

  | Série | Appel | Statut | Total (`time_total`) | dont TLS (`time_appconnect`) | Côté serveur ≈ total − TLS |
  |---|---|---|---|---|---|
  | 1 | 1er (froid) | 200 | **2,246 s** | 0,453 s | **≈ 1,79 s** |
  | 1 | 2e | 200 | 0,180 s | 0,055 s | ≈ 0,13 s |
  | 1 | 3e | 200 | 0,153 s | 0,038 s | ≈ 0,12 s |
  | 2 | 1er (froid) | 200 | **2,582 s** | 0,471 s | **≈ 2,11 s** |
  | 2 | 2e | 200 | 0,330 s | 0,087 s | ≈ 0,24 s |
  | 2 | 3e | 200 | 0,199 s | 0,053 s | ≈ 0,15 s |

  Lignes brutes :

  ```text
  === serie 1 : redemarrage a froid de isc-b3a-pdp 17:01:32.509Z
  pdp healthy=healthy apres 22.0 s, 1er appel a 17:01:55.929Z
  body-1 http=200 total=2.245920s tls_done=0.452513s first_byte=2.245268s
  body-2 http=200 total=0.180157s tls_done=0.054904s first_byte=0.179018s
  body-3 http=200 total=0.153314s tls_done=0.037590s first_byte=0.149655s
  === serie 2 : redemarrage a froid de isc-b3a-pdp 17:02:01.693Z
  pdp healthy=healthy apres 22.0 s, 1er appel a 17:02:25.331Z
  body-4 http=200 total=2.581800s tls_done=0.471327s first_byte=2.578224s
  body-5 http=200 total=0.330411s tls_done=0.086964s first_byte=0.328641s
  body-6 http=200 total=0.198660s tls_done=0.052732s first_byte=0.197680s
  ```

  Lecture. Le **premier** `/device/register` après un démarrage du PDP dure **2,2 à 2,6 s**, soit
  **plus du double** de `core.device.timeout-ms = 1000` ; le PDP, lui, termine et écrit la clé
  (ligne PENDING), exactement comme à 10:50:12 sur la pile servie. Dès le deuxième appel : 0,15 à
  0,33 s. Le « prêt par construction » (#116) n'y change rien : la base est jointe et le servlet
  initialisé **avant** l'ouverture du port (journal du PDP isolé), le coût restant est le chargement
  et la compilation à la volée du chemin attestation + PBKDF2, que seul un premier passage réchauffe.
  Hypothèse (b) du §2 **confirmée** par la mesure ; la seconde hypothèse envisagée dans la passe
  (réponse 4xx du PDP mal classée par le cœur) est exclue : le PDP répond 200.

  Constats annexes de la reproduction (à corriger à part) : (1) `compose.b3a-isolement.yml` ne
  renomme pas le **pont** `br-isc-mobile` fixé par le fichier partagé depuis L7-b → collision au
  démarrage (contourné par une surcouche hors dépôt qui nomme le pont `br-isc-b3a-mob`) ; (2) l'image
  `isc-pdp-b3a` de la VM (22/10) est antérieure aux routes `/device/*` (404) : l'essai a tourné sur
  l'image servie `isc-pdp`. Même pour ce 404, le premier appel à froid coûtait 0,77–1,24 s (TLS à
  froid 0,37–0,62 s). Pile isolée retirée (`down -v`) à la fin ; pile servie non touchée.

## 4. Verdict et correctifs proposés (PR à part, après accord)

1. **Application (couloir B, certain)** : `SERVICE_UNAVAILABLE` à `/enrolment/complete` → erreur
   **transitoire dédiée** (« Le service est momentanément indisponible. Réessayez dans N s. », N =
   `Retry-After`), **sans détruire la clé locale** ni quitter l'étape ; le bouton « Réessayer »
   rejoue la même requête avec la même clé (la règle « une seule clé EN ATTENTE » du PDP absorbe la
   réinscription si la première était passée). Test JVM : 503 → état `Securing(REGISTERING,
   SERVICE_UNAVAILABLE)` avec `retryAfter`, clé conservée.
2. **Cœur / PDP (probable → mesuré, §3)** : un `GET /device/state` de chauffe **ne suffit pas** (il
   ne passe ni par la chaîne DER, ni par la signature, ni par PBKDF2 : ce sont eux qui coûtent
   ~2 s). Deux options mesurables, à trancher par la propriétaire :
   (a) **chauffe réelle côté PDP au démarrage** : exécuter une fois, avant l'ouverture du port, la
   vérification d'une chaîne d'attestation fabriquée en mémoire + une dérivation PBKDF2 (aucune
   écriture, aucune clé conservée) — supprime la cause ; preuve attendue : premier appel réel
   < 1 000 ms sur pile isolée ;
   (b) **délai dédié** `core.device.register-timeout-ms` ≥ 3 000 (le 1 000 ms reste pour
   `/device/verify`, `/nonce`, `/state`) — absorbe la cause sans la supprimer, et allonge d'autant
   l'attente de l'usager au premier enrôlement après un redémarrage.
   Dans les deux cas, le correctif applicatif du point 1 reste nécessaire (un 503 ne doit jamais
   détruire la clé).
3. **Observabilité (les deux couloirs, décision superviseur)** : ni le cœur ni le PDP ne
   journalisent un délai dépassé vers le PDP ni la durée d'une requête interne ; une ligne WARN côté
   cœur (« /device/register : délai dépassé après 1000 ms ») aurait donné la cause en une lecture.
   À proposer, sans secret dans la ligne.
