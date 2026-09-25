# Modèle de personas de la population de synthèse — v1

- **Date** : 2026-09-25
- **Version** : v1 (conception ; aucun code générateur)
- **Étalon de référence** : [profil_reel_v1.json](profil_reel_v1.json) (T3-01, SHA-256 du CSV source `84cd01ae…83401fb1`, 26 989 transactions)
- **Objet** : définir la population synthétique — archétypes, lois, mix, graphe de bénéficiaires, cibles de plausibilité et graine — qui produira ~500 000 transactions (T3-05), sera validée contre l'étalon (T3-06) et servira de base au calibrage des seuils (T3-07).

---

## 0. Règle cardinale — dimensions CALIBRÉES vs MODÉLISÉES

> **Une variable est soit ancrée sur une mesure réelle, soit assumée. On ne mélange jamais les deux sans le dire.**

**Découverte de T3-01, à garder en tête pour tout ce document :** l'étalon réel est constitué de **64 gros comptes** (médiane 240 tx/usager, vélocité 39 %). Ce ne sont **pas** des usagers du grand public : par leur volume, leur médiane de transactions et leurs rafales, **ils correspondent à l'archétype AGENT** défini plus bas. Autrement dit, **le réel mesure les agents, pas le public**. La victime d'un SIM-swap (AC-02) est au contraire un usager **ordinaire**, absent du jeu réel.

Conséquence directe sur la calibration :

| Dimension | Statut | Ancrage |
|---|---|---|
| **Montant** (loi, quantiles, queue lourde) | **CALIBRÉ sur le réel — mais uniquement comme loi de l'archétype AGENT** | `profil_reel_v1.json` → `a_montant.montants_strictement_positifs` (le réel = comportement agent) |
| **Heure du jour** (profil diurne, part nuit) | **CALIBRÉ sur le réel — base commune à tous les archétypes** | `profil_reel_v1.json` → `b_heure_du_jour` (l'heure est la dimension la moins sensible à l'archétype) |
| **Fréquence / vélocité du grand public** | **MODÉLISÉ** (aucun ancrage : le réel n'a pas de public) | Hypothèses documentées §2, sensibilité en T3-07 |
| **Graphe de bénéficiaires** (connus, nouveau bénéficiaire) | **MODÉLISÉ** (champ ABSENT du réel, cf. T3-01 §e) | Hypothèses §4, **limite de validité explicite** |

**Ce qui n'est PAS forcé à ressembler au réel, et c'est VOULU :** la distribution des montants **au niveau population** (elle inclut du public que le réel ne contient pas) et la **vélocité au niveau population**. On les **rapporte** sans les contraindre (§5). Ce qui doit ressembler au réel : les montants et la vélocité **de la sous-population AGENT seule**, et les **heures de toute la population**.

---

## 1. Archétypes

Cinq archétypes. Pour chacun : loi de fréquence (tx/jour), loi de montant par direction (IN = entrée/crédit, OUT = sortie/débit), heures actives, comportement de vélocité, bénéficiaires connus + taux de nouveau bénéficiaire.

Les montants suivent des **lognormales** (paramétrées par la médiane et l'écart-type log σ), tronquées à un plafond ; ce choix reproduit l'asymétrie et la queue lourde observées dans l'étalon. Les fréquences suivent des **lois de Poisson** (public) ou **Poisson sur-dispersée par rafales** (marchand, agent — c'est le mécanisme qui produit la vélocité).

### 1.1 Ordinaire occasionnel
Usager du grand public, faible activité (recharges, petits transferts, réception occasionnelle).

| Paramètre | Valeur | Statut |
|---|---|---|
| Fréquence | Poisson, λ = **0,30 tx/jour** (≈ 9 tx/mois, beaucoup de jours à zéro) | MODÉLISÉ |
| Montant OUT | lognormale, médiane **1 000 FCFA**, σ = **1,1**, plafond 150 000 | MODÉLISÉ (dérivé, non ancré) |
| Montant IN | lognormale, médiane **1 500 FCFA**, σ = **1,1**, plafond 200 000 | MODÉLISÉ |
| Heures actives | profil diurne réel (base commune) | CALIBRÉ |
| Vélocité | quasi nulle : bursts désactivés → franchit >3 tx/10 min de façon négligeable | MODÉLISÉ |
| Bénéficiaires connus (init) | **2 à 4** (uniforme) | MODÉLISÉ |
| Taux nouveau bénéficiaire (par OUT) | **5 %** | SUPPOSÉ |

### 1.2 Salarié régulier
Revenu mensuel, paiements de factures, transferts familiaux réguliers.

| Paramètre | Valeur | Statut |
|---|---|---|
| Fréquence | Poisson, λ = **1,2 tx/jour** + **pic de paie** (jours 28-30 : rafale de factures/transferts) | MODÉLISÉ |
| Montant OUT | lognormale, médiane **5 000 FCFA**, σ = **1,0**, plafond 300 000 | MODÉLISÉ |
| Montant IN | **bimodal** : courant lognormale médiane 2 000 σ 1,0 ; **pic mensuel de paie** ~ 80 000 (jour de paie) ; plafond 500 000 | MODÉLISÉ |
| Heures actives | profil réel, léger renforcement midi + soirée (après le travail) | CALIBRÉ (base) + décalage MODÉLISÉ |
| Vélocité | faible ; petite rafale possible le jour de paie (factures groupées) | MODÉLISÉ |
| Bénéficiaires connus (init) | **3 à 6** | MODÉLISÉ |
| Taux nouveau bénéficiaire (par OUT) | **3 %** | SUPPOSÉ |

### 1.3 Petit marchand
Encaisse de nombreux paiements clients (IN, petits), règle des fournisseurs (OUT, plus gros).

| Paramètre | Valeur | Statut |
|---|---|---|
| Fréquence | Poisson, λ = **6 tx/jour**, concentrées en heures ouvrées (8h-19h) | MODÉLISÉ |
| Montant IN | lognormale, médiane **1 500 FCFA**, σ = **1,2**, plafond 200 000 (paiements clients) | MODÉLISÉ |
| Montant OUT | lognormale, médiane **8 000 FCFA**, σ = **1,3**, plafond 500 000 (réappro/fournisseurs) | MODÉLISÉ |
| Heures actives | heures ouvrées concentrées (base réelle tronquée 8h-19h) | CALIBRÉ (base) + décalage MODÉLISÉ |
| Vélocité | **rafales légitimes** : plusieurs encaissements en <10 min → **franchit parfois >3 tx/10 min** (c'est la source principale de faux positifs VEL — cf. modèle de menace « FPR sur persona commerçant ») | MODÉLISÉ |
| Bénéficiaires connus (init) | **10 à 30** (clients/fournisseurs récurrents) | MODÉLISÉ |
| Taux nouveau bénéficiaire (par OUT) | **10 %** (nouveaux fournisseurs/clients) | SUPPOSÉ |

### 1.4 Agent (queue lourde)
Point de cash-in/cash-out. **C'est l'archétype qui rend compte des 64 comptes réels.** Sa loi de montant est **directement calibrée** sur l'étalon.

| Paramètre | Valeur | Statut |
|---|---|---|
| Fréquence | Poisson **sur-dispersée par rafales**, λ = **30 tx/jour** (≈ 900/mois, dans la bande réelle [médiane 240 ; p95 1 523]) | MODÉLISÉ (volume) |
| Montant IN et OUT | **lognormale calée sur `montants_strictement_positifs` réels** : médiane **1 854 FCFA**, σ ≈ **1,65**, plafond **2 015 424** (max réel). Reproduit p90≈15 340, p95≈50 750, p99≈500 000 et la queue lourde 100k-500k+ | **CALIBRÉ sur le réel** |
| Heures actives | profil réel complet, **y compris le pic de minuit** (heure 0 = 2,86 % dans l'étalon : règlements/lots de fin de journée) | CALIBRÉ |
| Vélocité | **élevée par construction** : processus de rafales (sessions de 4-8 tx dans une fenêtre de 10 min) → franchit régulièrement >3 tx/10 min, comme le réel (39 %) | MODÉLISÉ (mécanisme) pour reproduire une cible CALIBRÉE |
| Bénéficiaires connus (init) | **50 à 200** (nombreuses contreparties) | MODÉLISÉ |
| Taux nouveau bénéficiaire (par OUT) | **15 %** | SUPPOSÉ |

> **Pourquoi la loi de montant de l'agent = l'étalon.** L'étalon réel *est* la distribution de montants de 64 agents. La médiane réelle (1 854) est basse et la queue est très lourde (p99 = 500 000, max = 2 M) : beaucoup de petites opérations, quelques très grosses. On adopte donc cette distribution telle quelle comme loi de l'agent, et **c'est elle que T3-06 vérifiera au plus près** (§5).

### 1.5 Victime (usager ordinaire ciblé par AC-02)
**Comportement de base = Ordinaire occasionnel** (§1.1) — mêmes lois de fréquence, montant, heures, vélocité, bénéficiaires. La victime n'est PAS un archétype comportemental distinct ; c'est un **ordinaire étiqueté** sur lequel le générateur d'attaque (mois 4) superpose le scénario AC-02.

| Paramètre | Valeur | Statut |
|---|---|---|
| Baseline | identique à Ordinaire occasionnel (§1.1) | idem |
| Surcouche d'attaque | vidage scripté du solde vers un **bénéficiaire NOUVEAU** (le compte attaquant), sortie unique ≈ solde entier (jusqu'à ~500 000). Événement à `taux nouveau bénéficiaire = 100 %` par scénario | SCRIPTÉ (hors baseline) |

> La surcouche AC-02 est ce que les règles ES-04 (bénéficiaire nouveau interdit en PROBATION) / ES-05 (nouveau bénéficiaire + montant → STEP_UP) et VEL doivent attraper. Elle appartient au générateur d'attaque (T-AC-02, mois 4), **pas** à la baseline de la population : ici on ne définit que la baseline et l'étiquette « cible ».

---

## 2. Mix de population (justifié) — cap ~500 000 transactions

**Recommandation retenue : grand public largement majoritaire, agents petite minorité — l'INVERSE du réel, et c'est VOULU.** Le réel ne contient que des agents ; notre population de synthèse doit représenter le **public** que la couche protège (la victime en fait partie), tout en **reproduisant la queue lourde de volume** via une minorité d'agents.

| Archétype | Part des USAGERS | Rôle |
|---|--:|---|
| Ordinaire occasionnel | **50 %** | cœur du grand public |
| Salarié régulier | **25 %** | grand public régulier |
| Victime (ordinaire étiqueté) | **5 %** | cibles AC-02 (grand public) |
| Petit marchand | **15 %** | petit commerce (semi-public) |
| Agent | **5 %** | minorité, porte la queue lourde de volume |
| **Grand public (ordinaire+salarié+victime)** | **80 %** | > 80 % comme recommandé |

### 2.1 Dimensionnement vers ~500 000 transactions
Instanciation concrète (à ajuster finement en T3-05 pour viser 500 000 pile) : **≈ 5 800 usagers sur une fenêtre de 30 jours**.

| Archétype | Usagers | tx/jour/usager | tx/jour | Part du VOLUME |
|---|--:|--:|--:|--:|
| Ordinaire occasionnel | 2 900 | 0,30 | 870 | 5,2 % |
| Salarié régulier | 1 450 | 1,20 | 1 740 | 10,5 % |
| Victime | 290 | 0,30 | 87 | 0,5 % |
| Petit marchand | 870 | 6,0 | 5 220 | 31,4 % |
| Agent | 290 | 30,0 | 8 700 | 52,4 % |
| **Total** | **5 800** | — | **≈ 16 617** | **100 %** |

**≈ 16 617 tx/jour × 30 jours ≈ 498 500 transactions** (≈ 500k ; le réglage fin de l'effectif ou de la fenêtre en T3-05 atteint 500 000 pile).

### 2.2 Justification (à écrire noir sur blanc)
- **Pourquoi l'inverse du réel :** l'objet du projet est de protéger un usager **ordinaire** contre AC-02. Copier les 64 comptes réels produirait une population 100 % agents, sans victime crédible et avec une vélocité de base ingérable. On modélise donc le public **par tête**.
- **Pourquoi la queue lourde survit quand même :** bien que les agents ne soient que **5 % des usagers**, ils génèrent **≈ 52 % des transactions** ; avec les marchands (15 % des usagers, 31 % du volume), **20 % des usagers portent ~84 % du volume**. On **reproduit ainsi la queue lourde** de l'étalon (peu de comptes, beaucoup de transactions) **sans** faire de la population entière des agents. La distribution *de volume* reste fidèle à la réalité ; la distribution *de population* est réaliste pour le public.
- **Cohérence des per-usager :** l'agent synthétique fait ≈ 900 tx/mois, dans la bande réelle [médiane 240 ; p95 1 523]. Le public fait 9 à 36 tx/mois, plausible pour du grand public.

> **Constat honnête (angle mort) :** la **durée d'observation du CSV réel est inconnue** (non capturée en T3-01). La bande « médiane 240/usager » n'est donc pas rapportable à une fenêtre. On fixe la fenêtre de synthèse à 30 jours par convention et on **rapportera** les tx/usager obtenues ; leur comparaison au réel reste indicative, pas un critère de rejet.

---

## 3. Tableau de synthèse des archétypes

Récapitulatif des lois (détail et statut CALIBRÉ/MODÉLISÉ/SUPPOSÉ en §1).

| Archétype | λ tx/jour | Montant médian OUT | σ log | Vélocité >3/10 min | Bénéf. connus | p_new (OUT) |
|---|--:|--:|--:|---|--:|--:|
| Ordinaire occasionnel | 0,30 | 1 000 | 1,1 | négligeable | 2-4 | 5 % |
| Salarié régulier | 1,20 (+pic paie) | 5 000 | 1,0 | faible | 3-6 | 3 % |
| Petit marchand | 6,0 | 8 000 | 1,3 | **occasionnelle (légitime)** | 10-30 | 10 % |
| Agent (queue lourde) | 30,0 (rafales) | **1 854** (calé réel) | **1,65** | **élevée (~réel)** | 50-200 | 15 % |
| Victime | 0,30 (= ordinaire) | 1 000 | 1,1 | négligeable | 2-4 | 5 % + attaque scriptée |

---

## 4. Graphe de bénéficiaires (synthétique)

**Champ ABSENT du réel (T3-01 §e) → 100 % modélisé. C'est la base d'ES-04/ES-05 (« nouveau bénéficiaire »).**

### 4.1 Acquisition des bénéficiaires connus
- À l'initialisation, chaque usager reçoit **K bénéficiaires connus** tirés selon son archétype (§1 : ordinaire 2-4, salarié 3-6, marchand 10-30, agent 50-200).
- Les bénéficiaires sont d'autres usagers de la population (le graphe est **interne** et cohérent : un transfert va vers un compte qui existe).

### 4.2 Événement « nouveau bénéficiaire »
- Chaque transaction **OUT** est, avec une probabilité **p_new** propre à l'archétype (§1 : 3 % à 15 %), dirigée vers un bénéficiaire **jamais vu** de cet usager.
- Un nouveau bénéficiaire est alors **ajouté** à l'ensemble connu de l'usager (le graphe **grandit** au fil du temps).
- L'attaque AC-02 est, par construction, un OUT vers un nouveau bénéficiaire (p_new = 100 % scénarisé) — c'est le signal que la couche doit exploiter.

### 4.3 Limite de validité (à documenter comme telle)
> **Aucun ancrage réel n'est possible pour ce graphe** : le CSV ne contient pas de contrepartie (T3-01 §e). Les valeurs de **K** et de **p_new** sont des **hypothèses (SUPPOSÉ)**, non des mesures. Elles conditionnent directement le taux de faux positifs de la règle « nouveau bénéficiaire » (ES-05) et de VEL.newBeneficiaries. **Elles DOIVENT faire l'objet d'une analyse de sensibilité en T3-07** (courbe FPR vs p_new, par archétype, en particulier le marchand). Toute conclusion du mois 4 sur ES-05 sera qualifiée par cette limite.

---

## 5. Cibles de plausibilité (ce que T3-06 vérifiera)

Toutes calculables en stdlib à partir de `profil_reel_v1.json`.

### 5.1 Montant — **sur la sous-population AGENT seule** (calibration directe)
Comparaison des quantiles synthétiques *agents* aux quantiles réels `montants_strictement_positifs` :

| Quantile | Cible réelle | Tolérance (erreur relative) |
|---|--:|---|
| p10, p25, p50, p75, p90 | 128 / 500 / 1 854 / 5 000 / 15 340 | **≤ 15 %** |
| p95, p99 | 50 750 / 500 000 | **≤ 25 %** (queue plus bruitée) |

- **Distance d'histogramme** (10 tranches de l'étalon) : distance L1 = Σ|part_synth − part_réel| **< 0,10** (≤ 10 points de masse déplacés au total).

### 5.2 Montant — **au niveau population** (informationnel, NON forcé)
On **rapporte** les quantiles population, **attendus SOUS** la médiane réelle (davantage de petit public). **Aucun rejet** sur ce critère : l'écart est intentionnel (population ≠ agents).

### 5.3 Heure du jour — **toute la population** (base commune calibrée)
- **Distance L1** sur les 24 parts horaires vs `b_heure_du_jour.par_heure` : **< 0,08**.
- **Part NUIT (23h-05h)** : dans **±1,5 point** de **6,51 %**.

### 5.4 Vélocité — **NON alignée sur le réel**, on rapporte les deux
> On ne force PAS la vélocité de la population à rejoindre les 39 % réels (population différente : le réel est 100 % agents).

- **Rapporter** : (a) part d'usagers >3 tx/10 min sur **toute la population** (attendue **faible, < 5 %**, dominée par les marchands) ; (b) part sur les **agents seuls** (attendue dans une **bande [25 % ; 55 %]** encadrant les 39 % réels).
- **Critère de passage** : seulement que (b) tombe dans la bande **et** que les deux chiffres soient rapportés côte à côte. (a) est informationnel.

---

## 6. Stratégie de graine (reproductibilité)

**Une graine maîtresse unique → population + flux entièrement déterministes.** Rejouer avec la même graine reproduit **le même jeu, à l'octet près** (comme le SHA-256 de T3-01 fige l'étalon).

- **Graine maîtresse** : un entier `ISC_SEED` (consigné dans le **manifeste** du jeu généré, aux côtés de la version des personas et du cap d'effectif).
- **Sous-flux dérivés déterministes** (pas de PRNG global partagé) : de `ISC_SEED` on dérive des sous-graines nommées, une par usager et par usage :
  - `seed_population` → affectation des archétypes + tirage des K bénéficiaires ;
  - pour chaque usager : `seed_user = f(ISC_SEED, user_id)` → un générateur **indépendant** par usager, lui-même scindé par usage : timing (fréquence/rafales), montants, heures, événements nouveau-bénéficiaire ;
  - `seed_attack` → scénarisation AC-02 des victimes (mois 4).
- **Propriété visée** : ajouter/retirer un usager n'altère pas les flux des autres (indépendance par `seed_user`), et l'ordre d'itération n'influe pas sur le contenu (chaque tirage est adressé par sa sous-graine).
- **Consignation** : `ISC_SEED`, version des personas (v1), effectif et fenêtre figurent dans le manifeste du jeu (T3-05), pour que T3-06 rejoue exactement le même jeu.

---

## 7. Décision : impact SRS (paramètres nouveaux)

**Constat.** Tous les paramètres **produit** invoqués ici existent déjà et sont versionnés dans `config/ruleset.yaml` (rs-0.2.0, source unique, ISC-TM v1.1 §7) : `CAP_PROB` (25 000 FCFA/jour ; 10 % solde), `THR_HIGH`, `VEL` (count 3 / window PT10M ; newBeneficiaries 3 / beneficiaryWindow PT24H), `NIGHT` (23:00-05:00), `TTL_FREEZE`, `CAR_IMSI`, etc.

**Déduit.** Les variables introduites par ce document — mix de population, lois par archétype (λ, médianes, σ), K bénéficiaires, `p_new`, `ISC_SEED` — sont des **paramètres de SYNTHÈSE de données de test**, **pas des exigences logicielles du produit**. Elles pilotent le générateur (T3-03+), pas le PDP. Leur place versionnée est **ce document (v1)** puis la **config du générateur**, **pas le SRS**.

**Décision (acte à confirmer par le propriétaire).** **Aucune modification du SRS n'est requise pour T3-02** : aucun paramètre *produit* nouveau n'apparaît. Rien n'est supprimé. Si la revue estime qu'une variable de synthèse doit figurer au SRS (p. ex. comme exigence de reproductibilité `ISC_SEED`), elle sera **ajoutée** par incrément versionné (v1.3 → v1.4), sans suppression.

---

## 8. Traçabilité amont/aval
- **Amont** : [profil_reel_v1.json](profil_reel_v1.json) (T3-01, étalon), `config/ruleset.yaml` (rs-0.2.0, paramètres produit), modèle de menace v1.2 (ES-04/ES-05, VEL, CAP_PROB).
- **Aval** : T3-03+ (générateur, consomme §1-§4-§6), T3-05 (~500k tx, applique §2 + manifeste graine §6), T3-06 (valide §5), T3-07 (calibrage seuils + sensibilité `p_new`/K §4.3).
