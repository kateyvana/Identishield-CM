# Résultat du spike

## Question

Quel est le débit réel d'injection de transactions dans le sandbox, selon le
mode, et lequel permet de produire le jeu v1 (≥ 500 000 transactions) en moins
de 30 minutes ? → **RÉUSSI** : trois modes sur quatre produisent 500 000
transactions en **3 à 4 minutes**, et l'injection par lots produit des décisions
**identiques** à l'injection unitaire (Q7).

## Dispositif

- **VM (mesure publiable, §7)** : Intel i7-1185G7 @ 3,00 GHz, **4 vCPU**,
  **7,8 Gio**, disque **60 Go (49 libres)**, Ubuntu 24.04.4, VirtualBox.
  PostgreSQL **16**, Python **3.12**, psycopg **3.2.3**. Durabilité par défaut
  (fsync + synchronous_commit actifs).
- Construit dans `spikes/spike-5-injection/` : générateur de flux déterministe à
  graine (rafales pour déclencher la vélocité) ; PDP factice à **règles pures**
  (VEL/NIGHT/AMOUNT, recomptage par évaluation, deque élaguée par horodatage
  d'événement — obligation ADR-019) ; audit chaîné avec `chain_index` assigné
  **dans le verrou** `chain_head FOR UPDATE` (ADR-020 §5) ; quatre modes
  d'injection + banc de mesure (reset, chauffe non comptée, répétitions).

## Résultat

### Q7 — correction avant vitesse (empreintes côte à côte)

Même jeu de 10 000, injecté transaction par transaction puis par lots de 1000 :
```
Q7 unitaire.sha256  = f5ee03021650c6048bb180ed1b018ac5863c4b68e68949137a0d7da9bac2b5a7
Q7 lots-1000.sha256 = f5ee03021650c6048bb180ed1b018ac5863c4b68e68949137a0d7da9bac2b5a7
identiques = True
```
Décisions : ALLOW 4955 · STEP_UP/NIGHT 2299 · STEP_UP/AMOUNT 1663 · **FREEZE/VEL
1083**. Le mode par lots donne exactement les mêmes décisions, y compris les
1083 FREEZE de vélocité (sensibles à l'ordre temporel). Correctif de méthode :
au premier essai la vélocité ne tirait jamais (0 FREEZE) ; sans rafales, Q7
aurait été un faux vert (test insensible à l'ordre). Rafales ajoutées → Q7
teste réellement le chemin que le mode par lots pourrait fausser.

### Étape 3 — débit des quatre modes (tps moyen ± écart-type)

| Mode | 10 000 | 100 000 | 500 000 | Durée 500k | < 30 min |
|---|---|---|---|---|---|
| direct (commit/1000) | 2689 ±444 | 2880 ±52 | 2563 ±29 | 195 s | OUI |
| **http lots 1000** | 1814 ±867 | 2592 ±277 | **2822 ±48** | **177 s** | OUI |
| http lots 100 | 2542 ±115 | 2240 ±45 | 2088 ±25 | 239 s | OUI |
| http unitaire | 98 ±5 | 97 ±0 | *(non mesuré)* | ~86 min (extrapolé) | NON |

HTTP unitaire extrapolé depuis un débit plat (98→97) : ~86 min pour 500k, échec
par ~3× (RA-06 confirmé). Extrapolation justifiée par l'absence de dégradation.

### Étape 4 — coût, chaînage, répartition

**Débit AVEC vs SANS chaînage d'audit** (le coût structurel de ES-09) :

| Régime | avec chaînage | sans chaînage | coût du chaînage |
|---|---|---|---|
| par lots (commit/1000), 500k | 2563 tps | 6953 tps | ÷2,7 (le verrou domine) |
| **unitaire (commit/txn), 100k** | **133 tps** | 167 tps | ÷1,25 (le commit domine) |

**Q4 — répartition du temps par transaction (régime unitaire, ~ms/txn)** :
- écriture décision + **commit fsync** (base) : ~6,0 ms — **dominant**
- + chaînage (audit + verrou `chain_head`) : +1,5 ms (~20 %)
- + réseau/HTTP (mode http unitaire) : +2,8 ms
En unitaire, c'est le **commit durable par transaction** qui domine, pas le
chaînage. En lots, le commit étant amorti (1/1000), c'est le **verrou de
chaînage** qui devient le coût dominant (÷2,7).

**Q5 — dégradation avec la taille** : aucun effondrement. direct 2880→2563 ;
http-1000 2592→2822 (monte) ; http-100 2240→2088 (−7 %) ; unitaire 114→133.
La courbe est plate ou montante entre 100k et 500k → pas de piège d'extrapolation.

**Q6 — empreinte disque** (décision + audit chaîné) : 4,5 Mo (10k), 44,9 Mo
(100k), **221,7 Mo (500k)** — croissance linéaire ~443 octets/txn. Sans chaînage :
58,6 Mo à 500k (l'audit chaîné ajoute ~326 octets/txn). Sur 60 Go : **0,37 %**.

### Verdict SQ-30 — sur le régime RÉEL, pas sur le mode le plus rapide

SQ-30 exige **50 tx/s** en évaluation en ligne. Ce régime est **unitaire,
séquentiel, avec chaînage** (une décision à la fois, un verrou et un commit par
transaction) — **pas** le mode par lots. Mesuré : **~133 tx/s soutenus**.
- **ES-09 tient SQ-30 avec une marge de ~2,6×** (133 / 50), et non les ×56 que
  suggéreraient les 2822 tx/s du mode par lots.
- Même via HTTP par transaction (97 tx/s), la marge reste ~1,9×.
- **Plafond réel du journal d'audit sur cette VM : ~133 écritures chaînées
  durables par seconde.** À verser dans ADR-020 comme plafond réel.

### Réponses Q1–Q7

- Q1 (HTTP unitaire) : ~97 tx/s — trop lent pour 500k (~86 min).
- Q2 (HTTP par lots) : lots 1000 = 2822 tx/s (meilleur), lots 100 = 2088 tx/s.
- Q3 (appel direct) : 2563 tx/s (commit/1000) — le plus rapide n'est pas plus
  rapide que les lots 1000, la couche réseau n'étant pas le goulot ici.
- Q4 : commit durable dominant (~6 ms), chaînage +1,5 ms, réseau +2,8 ms.
- Q5 : pas de dégradation d'effondrement jusqu'à 500k.
- Q6 : 221,7 Mo pour 500k, soit 0,37 % des 60 Go.
- Q7 : lots ≡ unitaire (empreintes identiques), y compris la vélocité.

## Écart de parité (ISC-DEPLOY §6.2) — à vérifier

L'image `spike5-injector` a été construite sur le **poste Windows** (Docker
Desktop) puis chargée dans la VM (`docker load`), parce que la VM avait perdu
son accès Internet (changement de réseau : hotspot ne routant pas le pont).
Elle n'a donc **pas** été produite par le même chemin que les images de la CI.
Empreinte de l'image : `sha256:8a3fce15c0e49b66d649c565f421e33b8efe15e23ff57868fc99128b08e894d5`.
Probablement sans conséquence (même base `python:3.12-slim`, même architecture
linux/amd64), mais « probablement » n'est pas « vérifié ». **Limite de la
mesure** à lever au mois 3 : reconstruire l'image par le chemin CI et comparer
l'empreinte avant toute publication de chiffre définitif.

## Décision (ADR)

Proposition : **ADR-017 passe en FERME** (l'acte appartient au propriétaire) :
- **Mode d'injection retenu : HTTP par lots de 1000.** Justification chiffrée :
  2822 tx/s, 500k en 177 s ; conserve la fidélité au système réel (PDP en
  processus séparé, couche réseau) ; décisions prouvées identiques à l'unitaire
  (Q7). L'appel direct n'est pas plus rapide et sacrifie la fidélité ; le HTTP
  unitaire est rejeté (~86 min).
- Conséquence pour **ADR-020** : le plafond réel du journal d'audit en régime
  d'évaluation en ligne (unitaire, chaîné, durable) est **~133 écritures/s** sur
  cette VM ; SQ-30 (50/s) tenu avec marge ~2,6×. C'est ce chiffre, pas le débit
  d'injection par lots, qui borne ES-09 en production.

## Statut

**RÉUSSI.**
- ISC-EVAL §3.5 **tient en l'état** : le jeu v1 de 500 000 transactions se
  produit en ~3 min par le mode par lots — aucun ajustement du §10 nécessaire
  (ni réduction de durée simulée, ni de transactions, ni d'agents).
- Réserve : écart de parité de l'image (ci-dessus), à lever au mois 3.
- Résultat le plus utile : le débit d'injection par lots (2822/s) et le débit
  d'évaluation unitaire (133/s) divergent d'un facteur ~21 ; le second est le
  seul pertinent pour SQ-30 et ES-09.
