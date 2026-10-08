# Rapport de plausibilité — dev horizon v1 (gelé) contre l'étalon réel v1.1

- **Version** : v1 — **FIGÉE** le 2026-10-08, après relecture du propriétaire
- **Date** : 2026-10-01 (T3-06) ; corrections de relecture du 2026-10-08 (§4.3 en deux lentilles, renvois de sensibilité, calcul de la limite n° 2)
- **Jeu évalué** : dev horizon v1, figé en T3-05 (`data/eval/dev_horizon_v1.manifest.json`), PERSONAS v2.1
- **Référence** : étalon réel v1.1 (`docs/03-donnees/profil_reel_v1.1.json`)
- **Outil** : `python -m isc_simulator.stats` (déterministe, bibliothèque standard seule) ; tolérances de PERSONAS §5 (v2.0/v2.1)
- **Preuve brute** : `docs/03-donnees/rapport_plausibilite_v1_stats.json` (sortie de l'outil sur le jeu gelé) et `rapport_plausibilite_v1_diag322_stats.json` (variante diagnostique 322 jours, §5)

> Ce rapport répond à une seule question : **le trafic légitime de synthèse sur lequel le PDP sera évalué ressemble-t-il au trafic réel, et sur quelles dimensions peut-on l'affirmer ?** Chaque dimension porte l'un de trois statuts. Seules les dimensions **CALIBRÉES** reçoivent un verdict ; les autres sont des hypothèses déclarées ou des diagnostics, et le rapport le dit.

---

## 1. Référence : l'étalon réel v1.1

L'étalon est un ensemble d'**agrégats** tirés d'un CSV réel confidentiel de transactions Mobile Money (jamais versionné, `data/real/` est ignoré par git ; source SHA-256 `84cd01ae…1fb1`). Aucune ligne réelle n'entre dans le dépôt ni dans le jeu de synthèse.

| Caractéristique | Valeur |
|---|---|
| Usagers | **64** (52 avec au moins une transaction monétaire, 49 avec au moins une sortie) |
| Fenêtre d'observation | 2021-06-06 → 2026-03-27, **1 755 jours** |
| Activité par usager | médiane **322 jours** (p10 68, p90 840) |
| Rythme monétaire | médiane **1,07 tx/jour** par usager (p90 2,18 ; max 4,73) |

**Correction de T3-02, rappelée.** En T3-01, la fenêtre d'observation n'avait pas été mesurée. La médiane de 240 transactions par usager a été lue comme la marque de gros comptes, et T3-02 a assimilé le réel à l'archétype *agent*. Une fois la fenêtre mesurée (T3-05a : 1 755 jours, 322 jours d'activité médiane), ces 240 transactions font **moins d'une par jour** : le réel est le **grand public, population entière**, pas des agents. PERSONAS v2.0 a refait la calibration sur cette base, et v2.1 y a ajouté les sessions orientées (PO-7). Le texte v1.x reste barré dans PERSONAS §0 et sert de trace.

---

## 2. Jeu évalué : dev horizon v1 (gelé)

| Caractéristique | Valeur |
|---|---|
| Générateur | `isc_simulator.generator`, graine **20260925**, 2 480 usagers, 182 jours depuis le 2026-10-01, code `680d2a1` |
| Configuration | `config/synthese.yaml` syn-1.0.0 |
| Volume | **624 645** transactions, toutes `LEGIT` |
| Mix | ordinaire 1 292, salarié 646, victime 129, marchand 388, agent 25 (1 %) |
| Contrôles d'intégrité | 0 solde négatif, 0 incohérence arithmétique, 0 rupture de chaîne de solde, 0 auto-transfert, 0 MSISDN hors de 677000000–677009999 |
| Sorties sautées (solde insuffisant) | 6 082 (2,54 % des sorties tentées) |

**Vérification préalable (étape 1 de T3-06).** Le jeu a été régénéré depuis son manifeste. Les trois empreintes sont identiques au gel (§7) : le rapport porte donc bien sur le jeu figé, à l'octet près.

---

## 3. Trois statuts, par dimension

| Statut | Dimensions | Ce que le rapport affirme |
|---|---|---|
| **CALIBRÉ sur le réel** | rythme par usager ; montants (L1, quantiles, multiples ronds) ; heures du jour ; sessions toutes directions ; sessions de sorties | mesuré contre l'étalon, avec une bande fixée **avant** la mesure (PERSONAS §5) et un verdict |
| **DÉCLARÉ sans ancrage** | agent (rythme, 1 % des usagers) ; paie (salaire du 28) ; événements SIM ; canal et liaison d'appareil ; graphe de bénéficiaires | hypothèses documentées (PERSONAS §1, §4) ; rapportées, **sans verdict** ; analyse de sensibilité aux surcouches : non encore réalisée, à planifier |
| **DIAGNOSTIC seulement** | part d'usagers dépassant VEL (monétaire, sorties) | rapportée avec dénominateur et fenêtre ; **jamais une cible**, donc jamais un verdict |

Les agents sont **exclus** des comparaisons de rythme, de sessions et de VEL : ils sont une surcouche déclarée, que le réel ne contient pas.

---

## 4. Dimensions calibrées : mesures et verdicts

Périmètres : *montants* et *heures* portent sur **toutes** les transactions synthétiques (salaire compris), contre tous les montants > 0 du réel. *Rythme* et *sessions* portent sur les usagers **hors agents** (2 455 usagers de synthèse ; 52 réels pour le rythme).

### 4.1 Rythme par usager (tx monétaires / jours d'activité)

| Quantile | Réel | Bande | Jeu gelé | Écart | Verdict |
|---|--:|--:|--:|--:|---|
| p10 | 0,1793 | ±25 % | 0,169 | −5,88 % | **DANS LA BANDE** |
| p25 | 0,543 | ±15 % | 0,539 | −0,73 % | **DANS LA BANDE** |
| p50 | 1,071 | ±15 % | 1,046 | −2,30 % | **DANS LA BANDE** |
| p75 | 1,5671 | ±15 % | 1,700 | +8,49 % | **DANS LA BANDE** |
| p90 | 2,1816 | ±15 % | 2,261 | +3,64 % | **DANS LA BANDE** |
| p95 | 2,6332 | ±25 % | 2,749 | +4,39 % | **DANS LA BANDE** |
| max | 4,7264 | rapporté | 5,074 | +7,4 % | rapporté |

### 4.2 Montants

| Mesure | Réel | Bande | Jeu gelé | Verdict |
|---|--:|--:|--:|---|
| Distance L1 d'histogramme | — | < 0,10 | **0,0218** | **DANS LA BANDE** |
| p10 | 128,2 | ±15 % | 125 (−2,50 %) | **DANS LA BANDE** |
| p25 | 500 | ±15 % | 500 (0,00 %) | **DANS LA BANDE** |
| p50 | 1 854 | ±15 % | 1 732 (−6,58 %) | **DANS LA BANDE** |
| p75 | 5 000 | ±15 % | 5 000 (0,00 %) | **DANS LA BANDE** |
| p90 | 15 340 | ±15 % | 17 232 (+12,33 %) | **DANS LA BANDE** |
| p95 | 50 750 | ±25 % | 57 456 (+13,21 %) | **DANS LA BANDE** |
| p99 | 500 000 | ±25 % | 500 000 (0,00 %) | **DANS LA BANDE** |
| Part des multiples de 100 | 81,56 % | ≤ réel | 67,64 % | **DANS LA BANDE** |
| Part des multiples de 500 | 61,28 % | ≤ réel | 53,65 % | **DANS LA BANDE** |
| Part des multiples de 1 000 | 46,82 % | ≤ réel | 41,15 % | **DANS LA BANDE** |
| Part des multiples de 5 000 | 15,77 % | ≤ réel | 13,03 % | **DANS LA BANDE** |

La bande « ≤ réel » sur les multiples est voulue : un jeu **moins** rond que le réel ne donne au détecteur aucun indice artificiel de montant rond.

### 4.3 Heures du jour — deux lentilles

**Fuseau.** L'heure du CSV réel est **naïve** : aucune colonne ni aucun suffixe ne donne son fuseau. Décision du propriétaire du 2026-10-06 : elle est **déclarée** comme heure locale WAT (`Africa/Douala`, UTC+1). C'est une **hypothèse non vérifiée**, cohérente avec T3-07 et ADR-016 (`NIGHT_ZONE`). La part NUIT réelle reste donc **6,51 %** sans recalcul, puisque naïf = local. Le générateur, lui, part de minuit **UTC**, tire l'heure dans le profil réel et l'écrit avec le suffixe `Z` (`generator.py`, lignes 348 et 502). La synthèse est donc mesurée sous deux lentilles : **telle qu'écrite** (UTC) et **locale** (UTC + 1, ce que verra le PDP). Fenêtre de la règle `NIGHT` : 23:00–05:00 en heure locale, soit 22:00–04:00 UTC.

| Mesure | Réel (naïf = local, déclaré) | Bande | Jeu gelé, lentille UTC (telle qu'écrite) | Verdict UTC | Jeu gelé, lentille locale WAT | Verdict local |
|---|--:|--:|--:|---|--:|---|
| Distance L1 sur 24 h | — | < 0,08 | **0,0473** | **DANS LA BANDE** | **0,1804** | **HORS BANDE** |
| Part NUIT (23:00–05:00) | 6,51 % | ±1,5 pt | 5,87 % (−0,64 pt) | **DANS LA BANDE** | 8,58 % (+2,07 pt) | **HORS BANDE** |

**Le verdict n'est pas robuste au fuseau.** Il ne le serait que si les deux lentilles tombaient dans la bande. La lentille UTC reproduit le profil réel. La lentille locale le montre **décalé d'une heure trop tard** : le générateur a placé un profil (supposé) local sur des heures UTC. La forme est juste, la phase est déplacée d'une heure. Si l'hypothèse WAT est vraie, c'est un défaut de la base de temps du jeu gelé (limite n° 6). Le jeu gelé n'est pas modifié.

### 4.4 Sessions

| Mesure | Réel | Bande | Jeu gelé | Verdict |
|---|--:|--:|--:|---|
| Écarts consécutifs < 10 min, toutes directions | 13,04 % | ±25 % relatif | 13,49 % (+3,44 %) | **DANS LA BANDE** |
| Écarts consécutifs < 1 min, toutes directions | 5,38 % | rapporté | 5,29 % | rapporté |
| Écarts entre sorties consécutives < 10 min | 5,05 % | ±25 % relatif | 4,88 % (−3,40 %) | **DANS LA BANDE** |
| Écarts entre sorties consécutives < 1 min | 1,49 % | rapporté | 1,38 % | rapporté |

**Bilan des dimensions calibrées : 22 mesures sous bande. Lentille UTC (telle qu'écrite) : 22 dans la bande, 0 hors bande. Lentille locale WAT (fuseau réel supposé) : 20 dans la bande, 2 hors bande (les deux mesures horaires, §4.3).**

---

## 5. Diagnostic : VEL (jamais une cible)

Règle lue dans `config/ruleset.yaml` (`VEL`) : **plus de 3 transactions en 10 minutes**, en fenêtre glissante. Le dénominateur est le nombre d'usagers **hors agents** ayant au moins une transaction du périmètre considéré.

| Périmètre | Réel (activité médiane 322 j) | Jeu gelé (fenêtre **182 j**) | Variante diagnostique (fenêtre **322 j**) |
|---|--:|--:|--:|
| Transactions monétaires | **26,92 %** (14/52) | 31,08 % (763/2 455) | 40,77 % (1 001/2 455) |
| Sorties seules | **10,20 %** (5/49) | 7,97 % (195/2 447) | 13,69 % (336/2 454) |

**Comment le lire.**

- La part d'usagers dépassant VEL **croît avec la fenêtre** : plus on observe longtemps, plus il y a de chances de voir une rafale. Le réel est observé sur 322 jours d'activité en médiane, le jeu gelé sur 182. La variante à 322 jours rend les fenêtres comparables. Elle reste différente du réel, dont les fenêtres varient d'un usager à l'autre (p10 68 j, p90 840 j).
- Cette variante a la même graine, la même configuration et la même population ; seul le nombre de jours change (`--days 322`, 1 107 039 transactions, vue SHA-256 `b23b2450…3585`). **Ce n'est pas un jeu d'évaluation** : elle sert ce diagnostic seulement. Ses dimensions calibrées restent dans leurs bandes (rythme pire écart p75 +7,99 %, L1 montants 0,0221, L1 heures 0,0468, sessions 13,45 % et sorties 4,93 %).
- Le réel repose sur **peu d'usagers**. Intervalles de Wilson à 95 % : monétaire 14/52 → **[16,8 % ; 40,3 %]**, sorties 5/49 → **[4,4 % ; 21,8 %]**. À 322 jours, la part des sorties (13,69 %) tombe dans l'intervalle réel ; la part monétaire (40,77 %) le dépasse de peu.

**Conséquence pour l'évaluation.** À fenêtre comparable, le jeu de synthèse a **au moins autant** d'usagers légitimes en rafale que le réel. Une règle de vélocité y lèvera donc au moins autant de fausses alertes : le taux de faux positifs mesuré pour VEL sera **conservateur** (pessimiste pour le PDP), pas flatteur.

---

## 6. Limites déclarées

1. **Petit échantillon réel.** L'étalon repose sur 64 usagers (52 pour le rythme et les montants par usager, 49 pour les sorties). Les bandes sont relatives à des quantiles estimés sur ces effectifs, et les queues (p95, max) sont incertaines. La plausibilité est démontrée **contre cet étalon**, pas contre la population Mobile Money camerounaise.
2. **Agent non ancré.** Le réel ne contient pas d'agent. L'archétype agent (1 % des usagers ; **15,8 % du volume, mesuré sur le jeu gelé** (≈ 12,5 % attendu du modèle ; écart dû au tirage de seulement 25 agents, +1,2 écart-type)) est une surcouche déclarée (rythme lognormal de médiane 10 tx/j, σ 1,0). Il est exclu de toutes les comparaisons et n'a pas de verdict. *Calcul* : mesuré 98 756 / 624 645 = 15,81 %, soit 98 756 / (25 × 182) = 21,70 tx/j par agent. Attendu : moyenne de la lognormale 10 × e^(σ²/2) = 16,49 tx/j, × 25 × 182 ≈ 75 017 tx, soit 75 017 / (525 889 + 75 017) = 12,48 % du volume. Écart-type de la moyenne de 25 tirages : 10 × √(e·(e − 1)) / 5 = 4,32 tx/j ; (21,70 − 16,49) / 4,32 = +1,21 écart-type, donc cohérent avec le modèle.
3. **Autres surcouches déclarées sans ancrage.** Paie (salaire ~80 000 le 28), événements SIM (a 77, b 64, c 219 ; vérité-terrain dans le sidecar seulement), canal et liaison d'appareil, graphe de bénéficiaires (nouveau bénéficiaire : 7,07 % des sorties). Ces champs sont **absents du réel** (étalon section e). Leur effet sur les résultats relève d'une analyse de sensibilité aux surcouches : non encore réalisée, à planifier (T3-07 a porté sur la calibration ADR-016, pas sur cette sensibilité).
4. **VEL au-dessus du réel sur 322 jours** (monétaire 40,77 % contre 26,92 % ; sorties 13,69 % contre 10,20 %). C'est **assumé et conservateur** (§5) : VEL est un diagnostic, et sa surreprésentation pénalise le PDP au lieu de l'avantager.
5. **Part de sorties non forcée.** 37,38 % des transactions de synthèse sont des sorties, contre 49,58 % dans le réel parmi les entrées et sorties. Ce n'est pas une cible de PERSONAS §5. Déduit (non mesuré isolément) : l'écart vient surtout des marchands (14,92 % de sorties : ils encaissent) et de la paie (entrées de salaire), qui sont des surcouches déclarées ; ordinaire 48,36 %, victime 48,32 %, salarié 44,39 %. Rapporté pour transparence.
6. **Base de temps du jeu gelé décalée d'une heure sous l'hypothèse WAT** (§4.3). Si l'heure naïve réelle est locale, le profil horaire synthétique, vu en heure locale, est décalé d'une heure trop tard : NUIT 8,58 % contre 6,51 %, L1 0,1804 contre une cible < 0,08. Effet sur T3-07 et ADR-016, qui mesurent NIGHT en heure locale : le jeu contient **plus** de sorties nocturnes que le réel, donc le FPR de NIGHT y est **surestimé**. C'est pessimiste pour le PDP, pas flatteur. Correction possible, non faite : placer le profil en heure locale (UTC = local − 1 h) dans un horizon v2. Cela changerait les empreintes du gel et du plan AC-02.

---

## 7. Empreintes épinglées

| Objet | SHA-256 |
|---|---|
| Vue détecteur `dev_horizon_v1.csv` | `c165d55bed40c441c16e2f7d5e987b8a67cceb71eb71e470cbc2309d18ac8adc` — **identique au gel** |
| Sidecar `dev_horizon_v1.labels.csv` | `6bc9ecbc6387e678fa6bb374f7ae20a125a8d43db162268ef26ad443fb4e2998` — **identique au gel** |
| HLR `dev_horizon_v1.hlr_events.csv` | `12fb1a9121de3ce6ba80f6828465ec2b245b2a5afbde232300155cb55a93c36f` — **identique au gel** |
| Manifeste du gel | `data/eval/dev_horizon_v1.manifest.json` (commit `3d45ec0`, sur main `49037ee`) |
| Étalon `profil_reel_v1.1.json` | `0273636f1af0fbf97d49732bed1b50a0b2ecd795feeff670270c9c4e484c0508` |
| Source réelle de l'étalon (hors dépôt) | `84cd01ae42d0fde10292b2df7e16302cf8dee3cd1359107328529a2e83401fb1` |
| Configuration `config/synthese.yaml` | `5b697d6d99abb3d2e58e6e1f373822f51e92d9521cafb4aaa59b42d6b3901688` |
| Variante diagnostique 322 j (vue, hors dépôt) | `b23b24508aba76532cd86568d7dc4ad3e3d61333deebf9bb5fb7be7ff0473585` |

**Reproduire ce rapport** depuis la racine du dépôt :

```
uv run --project simulator python -m isc_simulator.generator --seed 20260925 --users 2480 --days 182 --start 2026-10-01 --out <out>.csv
uv run --project simulator python -m isc_simulator.stats --sample <out>.csv --etalon docs/03-donnees/profil_reel_v1.1.json --ruleset config/ruleset.yaml --out-json <stats>.json --out-md <stats>.md
```

Pour la variante diagnostique, remplacer `--days 182` par `--days 322`.

Lentille locale WAT du §4.3 : sur la vue détecteur, heure locale = heure UTC du `timestamp` + 1 ; NUIT = heures locales 23, 0, 1, 2, 3 et 4 ; L1 = Σ |part synthétique locale − part réelle| sur les 24 heures (profil réel : `b_heure_du_jour.par_heure` de l'étalon).

---

## 8. Traçabilité

- **Amont** : PERSONAS v2.1 §0 (statuts), §1.12 (sessions orientées), §5 (bandes) ; étalon v1.1 ; manifeste dev horizon v1 (T3-05) ; ADR-033 (bibliothèque standard), ADR-035 (identifiants MTN fictifs).
- **Aval** : T3-07 (calibration ADR-016, mesurée sur ce jeu) ; analyse de sensibilité aux surcouches déclarées (non encore réalisée, à planifier) ; évaluation AC-02 du mois 5 sur ce même jeu (plan `data/eval/ac02_plan.csv`, branche `t3-04b-plan`) ; chapitre « données » du mémoire.
