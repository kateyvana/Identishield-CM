# Résultat du spike

## Question

Un journal en écriture seule au niveau du rôle base de données, chaîné par
empreintes SHA-256 et ancré périodiquement, résiste-t-il à un adversaire
disposant du rôle applicatif, et détecte-t-il toute altération, y compris une
réécriture complète de la chaîne ? → **OUI, avec réserves** (le chaînage seul
ne voit pas la suppression du dernier enregistrement ni la réécriture complète ;
l'ancre `chain_head` et l'ancre **signée externe** les couvrent, dans les
limites du modèle d'adversaire §4).

## Dispositif

- **VM (mesure publiable, ISC-DEPLOY §5.2)** : Intel i7-1185G7 @ 3,00 GHz,
  4 vCPU (1 thread/cœur), 7,8 Gio, Ubuntu 24.04.4 LTS (noyau 6.8.0-138),
  VirtualBox. PostgreSQL **16.15**, durabilité par DÉFAUT (fsync +
  synchronous_commit actifs) : chaque écriture d'audit est un commit durable.
- **Schéma de test** (`audit`, séparé du produit ; `infra/init/` non touché),
  copie de la forme produit : table `audit.entry` possédée par l'admin, rôle
  `isc_pdp_app` restreint à SELECT+INSERT. Colonne d'ordre `seq bigint
  GENERATED ALWAYS AS IDENTITY`. Chaînage par un trigger `BEFORE INSERT`
  `SECURITY DEFINER` qui verrouille le singleton `audit.chain_head`
  (`FOR UPDATE`), calcule `hash = SHA256(canonical)` et met la tête à jour.
- **Empreinte (Q2)** : `canonical = seq | payload::jsonb::text | prev_hash |
  written_at(UTC ISO)` — couvre l'ordre, le contenu, le lien et l'horodatage.
- **Ancre** : `chain_head` (tête en base : last_seq, last_hash, n_records) +
  ancre **signée Ed25519** exportée hors base (clé privée hors base, mode 600).
- Reproduction : `run/step1.sh` … `run/step4.sh`, `run/step4-concurrency.sh`.

## Résultat

### Étape 1 — imperméabilité du rôle (8 tentatives, toutes échouent)

| # | Tentative (`isc_pdp_app`) | Message PostgreSQL |
|---|---|---|
| 1 | UPDATE | `permission denied for table entry` |
| 2 | DELETE | `permission denied for table entry` |
| 3 | TRUNCATE | `permission denied for table entry` |
| 4 | DROP TABLE | `must be owner of table entry` |
| 5 | ALTER TABLE DROP CONSTRAINT | `must be owner of table entry` |
| 6 | setval() | `permission denied for sequence entry_seq_seq` |
| 7 | COPY FROM fichier | `permission denied to COPY from a file` (pg_read_server_files) |
| 8 | fonction SECURITY DEFINER | `permission denied for function admin_tamper` |

Privilèges EFFECTIFS mesurés sur catalogues (`has_*_privilege`, PUBLIC inclus) :
`entry` = {SELECT, INSERT} ; `chain_head` = {} ; séquence = {USAGE} (pas UPDATE,
donc `setval` refusé) ; schéma = {USAGE} ; `admin_tamper` EXECUTE=**f**.
CONSTATÉ décisif : `admin_tamper` est SECURITY DEFINER et écrit ; PostgreSQL
accorde EXECUTE à PUBLIC par défaut ; sans le `REVOKE EXECUTE … FROM PUBLIC`,
l'adversaire écrivait avec les droits admin — un privilège présent dans AUCUN
GRANT écrit. Preuve COPY FROM STDIN (étape 2) : le trigger écrase les
`prev_hash`/`hash` fournis faux → COPY n'est pas un chemin non chaîné.

### Étape 2 — six altérations (rôle admin), détectées ET localisées

| # | Altération | Détection / position |
|---|---|---|
| 1 | contenu du milieu (seq 10) | chaînage : hash_mismatch **seq 10** |
| 2 | empreinte d'un enreg. (seq 10) | hash_mismatch **seq 10** + chain_break **seq 11** |
| 3 | suppression du milieu (seq 10) | chain_break **seq 11** + count 20→19 |
| 4 | **suppression du dernier (seq 20)** | chaînage : **NON vu** ; ancre chain_head : **MISMATCH**, last_seq 20→19 |
| 5 | insertion forgée | hash_mismatch + chain_break **seq 21** |
| 6 | échange adjacents (10,11) | hash_mismatch **seq 10 et 11** |

Constat structurel : insérer « entre deux » est **impossible par construction** —
seq dupliqué → violation de PK ; renuméroter → `column "seq" can only be updated
to DEFAULT` (`GENERATED ALWAYS`). Le cas 4 n'est vu que par `chain_head`, table
hors de portée du rôle applicatif (étape 1) : c'est son rôle exact.

### Étape 3 — réécriture complète + ancre signée

L'admin modifie seq 500 et recalcule TOUTES les empreintes + `chain_head` :
couche chaînage = 0 anomalie, `chain_head` = MATCH (les deux couches internes
aveugles), **ancre signée = RÉÉCRITURE DÉTECTÉE** (hash au seq ancré changé ;
signature Ed25519 non reforgeable sans la clé privée).

**Q8** : clé Ed25519, `/home/isc/anchor-key/anchor_priv.pem`, mode 600, hors
base et hors conteneur. Dans le modèle §4 (rôle applicatif, sans FS), hors de
portée. Franchise jury : dans le sandbox la clé vit sur la MÊME VM — **limite du
MODÈLE, pas du dispositif** ; §4 et ISC-TM §9 excluent l'attaquant qui possède
le FS/l'admin. *L'ancre prouve l'intégrité contre l'analyste corrompu ; elle ne
prétend pas défendre contre quelqu'un qui possède la machine, et cette frontière
est assumée, pas masquée.* Déploiement réel : clé sur machine distincte / HSM.

**Fenêtre de vulnérabilité, chiffrée** = exactement `ANCHOR_INTERVAL` :

| ANCHOR_INTERVAL | enregistrements réécrits après l'ancre | résultat |
|---|---|---|
| 100 | 100 | non détectée (fenêtre = 100) |
| 1 000 | 1 000 | non détectée (fenêtre = 1 000) |
| 5 000 | 5 000 | non détectée (fenêtre = 5 000) |

Conséquence : au débit mesuré (~300 tps) une fenêtre de 1 000 = ~3 s d'activité ;
au débit nominal SQ-30 (50/s) = ~20 s. À faible trafic (nuit) un seuil en nombre
ne se déclenche jamais → second paramètre **ANCHOR_MAX_DELAY** en durée.

### Étape 4 — coût

- **Latence, 1 écrivain, 10 000 insertions durables** : p50 2,48 ms · p95
  4,18 ms · p99 6,08 ms · max 59 ms · débit 361 tps.
- **Concurrence** : mécanisme = verrou de ligne `FOR UPDATE` sur `chain_head`
  (le veilleur attend sur `ShareLock` de `transactionid`). Débit 1 écrivain
  328 tps → 10 écrivains **263 tps** : ne monte pas, baisse. Plafond structurel
  ~260–360 tps.
- **Q6** : à 10 écrivains, la chaîne suivie par les LIENS est unique et intacte
  (1 racine genesis, 0 orphelin, 0 parent dupliqué, 0 empreinte fausse) ; mais
  l'ordre `seq` diverge de l'ordre de chaînage (4589 fausses ruptures / 5000 si
  vérifié `ORDER BY seq`). → vérifier par les liens, ou assigner l'index d'ordre
  DANS le verrou.
- **Vérification** : 10 000 → 351 ms / 62 ms ; 100 000 → 828 ms / 11 ms ;
  500 000 → **6,83 s complète** / **17 ms incrémentale** (depuis ancre à 1 000).
- **SQ-30 (50 tx/s)** : tenue, marge ~5–7×.

### Réponses Q1–Q8

- Q1 : non — TRUNCATE, DROP, ALTER, setval, COPY-fichier, SECURITY DEFINER
  tous refusés (8/8).
- Q2 : seq, payload, prev_hash, written_at (UTC canonique) — rien de modifiable
  hors empreinte.
- Q3 : milieu → oui (chain_break) ; dernier → **non par chaînage**, oui par
  `chain_head`.
- Q4 : oui, réécriture complète détectée par l'ancre signée seule.
- Q5 : 361 tps mono / 263 tps à 10 ; SQ-30 tenue ×5–7.
- Q6 : verrou de ligne sur `chain_head` sérialise ; chaîne cohérente par liens ;
  seq ≠ ordre de chaîne → obligation de conception.
- Q7 : 500 000 vérifiés en 6,83 s (complète) / 17 ms (incrémentale) — exécutable
  en direct.
- Q8 : Ed25519 hors base ; tient dans §4 ; co-localisation VM = limite de modèle
  assumée.

## Décision (ADR)

Proposition : **ADR-020 passe en FERME** (l'acte appartient au propriétaire),
avec, comme OBLIGATIONS du schéma d'audit :

1. Table d'audit possédée par l'administrateur, jamais par le rôle applicatif ;
   rôle applicatif restreint à SELECT + INSERT.
2. **`REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA <audit> FROM PUBLIC`** par
   défaut — aucune fonction (a fortiori SECURITY DEFINER) exposée par héritage
   PUBLIC. (3ᵉ occurrence projet d'un privilège PUBLIC invisible dans les GRANT.)
3. La colonne d'ordre **DOIT** être `GENERATED ALWAYS AS IDENTITY` : rend
   l'insertion « entre deux » impossible par construction.
4. Empreinte couvrant seq, payload, prev_hash, written_at (UTC).
5. Chaînage sérialisé par verrou de ligne sur une tête singleton ; **l'index
   d'ordre de la chaîne est assigné DANS le verrou** (ou la vérification suit les
   liens) — sinon la vérification par `seq` est fausse sous concurrence.
6. Ancre signée par clé hors base (Ed25519), exportée périodiquement ; deux
   paramètres nommés : **ANCHOR_INTERVAL** (nb d'enregistrements) et
   **ANCHOR_MAX_DELAY** (durée), l'ancre se déclenchant au plus tôt des deux.
   Valeurs à trancher par le propriétaire ; départ proposé : 1 000 / 10 s.
7. Condition de validité : PostgreSQL 16 ; toute montée de version rejoue les
   huit tentatives de l'étape 1.

Limite assumée (hors modèle §4) : l'ancre ne défend pas contre un attaquant qui
possède la VM (FS + admin) ; en déploiement réel la clé va sur machine
distincte / HSM.

## Statut

**RÉUSSI AVEC RÉSERVES.**

- ES-09 tient : écriture seule prouvée (8/8), chaînage + ancre signée détectent
  toute altération y compris la réécriture complète, dans le modèle §4.
- AC-09 / AC-10 (analyste corrompu) : couverts dans le modèle §4.
- Réserves : (1) le chaînage seul ne voit ni la suppression du dernier enreg. ni
  la réécriture — d'où `chain_head` + ancre signée ; (2) sous concurrence,
  l'ordre `seq` diverge de l'ordre de chaîne (obligation 5) ; (3) fenêtre de
  réécriture = `ANCHOR_INTERVAL`, à borner aussi en temps (`ANCHOR_MAX_DELAY`) ;
  (4) clé co-localisée sur la VM = limite de modèle.
- Acte 4 d'ISC-DEMO : réalisable en direct — altération détectée et localisée en
  millisecondes (incrémentale 17 ms sur 500 000), vérification complète 6,83 s.
