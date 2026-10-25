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
- **Sur la pile isolée** (`infra/compose.b3a-isolement.yml`, images `isc-*-b3a`) : à faire à la
  prochaine fenêtre VM libre, sous témoin : redémarrer `isc-b3a-pdp`, mesurer la durée du **premier**
  `POST /device/register` (en mTLS depuis le réseau isolé) puis d'un second ; attendu : premier
  appel > 1 000 ms, second ≪ 1 000 ms. Résultat : **non observé à ce jour** (la VM est au couloir A :
  témoin, `check-stack` à 15:48Z).

## 4. Verdict et correctifs proposés (PR à part, après accord)

1. **Application (couloir B, certain)** : `SERVICE_UNAVAILABLE` à `/enrolment/complete` → erreur
   **transitoire dédiée** (« Le service est momentanément indisponible. Réessayez dans N s. », N =
   `Retry-After`), **sans détruire la clé locale** ni quitter l'étape ; le bouton « Réessayer »
   rejoue la même requête avec la même clé (la règle « une seule clé EN ATTENTE » du PDP absorbe la
   réinscription si la première était passée). Test JVM : 503 → état `Securing(REGISTERING,
   SERVICE_UNAVAILABLE)` avec `retryAfter`, clé conservée.
2. **Cœur (couloir B, `core/mobile`, probable)** : chauffer le chemin `/device/*` au démarrage comme
   `/health` (un `GET /device/state` sur un compte inexistant, lecture seule, dans le `warmup`) OU
   relever `core.device.timeout-ms` d'après une mesure à froid — **décision après la mesure du §3**,
   pas avant.
3. **Observabilité (les deux couloirs, décision superviseur)** : ni le cœur ni le PDP ne
   journalisent un délai dépassé vers le PDP ni la durée d'une requête interne ; une ligne WARN côté
   cœur (« /device/register : délai dépassé après 1000 ms ») aurait donné la cause en une lecture.
   À proposer, sans secret dans la ligne.
