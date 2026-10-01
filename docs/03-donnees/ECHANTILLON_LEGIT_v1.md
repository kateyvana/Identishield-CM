# Échantillon de contrôle — trafic légitime v1

- **Fichier** : `data/synth/echantillon_legit_v1.csv` (SHA-256 `a9cf441be14423123491d8d77b5959bb1df2e1168d1648a410c8c07b6f33f833`)
- **Graine** : ISC_SEED = 20260925 ; personas v2.1
- **Population** : 400 usagers, 7 jours à partir du 2026-10-25 → **4036 transactions**, toutes étiquetées `LEGIT`
- **Étalon** : `profil_reel_v1.1.json` (source `84cd01ae…`)

> Rapport généré par `python -m isc_simulator.stats` (déterministe). Les tolérances de PERSONAS §5 sont indiquées à titre d'information : les tenir est l'objet de T3-06.

## a. Montant — population entière vs réel (montants > 0, salaire compris)

| Quantile | Synthétique | Réel | Écart | Tolérance | Dans la tolérance |
|---|--:|--:|--:|--:|---|
| p10 | 125.0 | 128.2 | -2.5 % | ±15 % | oui |
| p25 | 500.0 | 500.0 | +0.0 % | ±15 % | oui |
| p50 | 1500.0 | 1854.0 | -19.09 % | ±15 % | **non** |
| p75 | 4279.75 | 5000.0 | -14.4 % | ±15 % | oui |
| p90 | 19997.0 | 15340.0 | +30.36 % | ±15 % | **non** |
| p95 | 70847.25 | 50750.0 | +39.6 % | ±25 % | **non** |
| p99 | 283987.9 | 500000.0 | -43.2 % | ±25 % | **non** |

n = 4036. Distance L1 d'histogramme = **0.0689** (cible < 0.1).

| Tranche | Synthétique % | Réel % |
|---|--:|--:|
| 1–500 | 21.95 | 20.25 |
| 500–1k | 14.57 | 14.38 |
| 1k–5k | 38.88 | 39.85 |
| 5k–10k | 8.67 | 9.87 |
| 10k–25k | 7.73 | 8.23 |
| 25k–50k | 1.61 | 1.9 |
| 50k–100k | 3.52 | 1.95 |
| 100k–500k | 2.43 | 2.47 |
| 500k+ | 0.64 | 1.08 |

Montants ronds (le synthétique ne doit pas être plus rond que le réel) :

| Multiple de | Synthétique % | Réel % | Pas plus rond que le réel |
|--:|--:|--:|---|
| 100 | 66.3 | 81.56 | oui |
| 1000 | 38.9 | 46.82 | oui |
| 500 | 51.76 | 61.28 | oui |
| 5000 | 11.17 | 15.77 | oui |

## b. Heure du jour — population

- Distance L1 sur 24 h : **0.0724** (cible < 0.08).
- Part NUIT (heures [0, 1, 2, 3, 4, 23], lue dans NIGHT) : **6.1 %** (réel 6.51 %, cible ±1,5 pt).

## c. Sorties et nouveaux bénéficiaires

- Part de sorties : **38.92 %** (réel 49.58 % parmi IN+OUT ; non forcé).
- Sorties sautées pour solde insuffisant : 160 (9.24 % des sorties tentées).
- `is_new_beneficiary` : **5.79 % des sorties** (2.25 % de toutes les transactions).

| Archétype | Usagers | Transactions | Part du volume % | Part de sorties % | Nouveau / sorties % |
|---|--:|--:|--:|--:|--:|
| agent | 4 | 540 | 13.38 | 52.41 | 13.78 |
| marchand | 63 | 1174 | 29.09 | 14.82 | 9.77 |
| ordinaire | 208 | 771 | 19.1 | 42.8 | 3.94 |
| salarie | 104 | 1453 | 36.0 | 51.27 | 2.95 |
| victime | 21 | 98 | 2.43 | 39.8 | 0.0 |

## d. Soldes

- Soldes négatifs : **0** (solde minimal observé : 12).
- Incohérences arithmétiques (après ≠ avant ± montant) : 0.
- Ruptures de chaîne (solde d'avant ≠ solde d'après précédent du même usager) : 0.

## e. Contrôles

- MSISDN hors de 677000000–677009999 (usagers et bénéficiaires) : **0**.
- Auto-transferts : 0. Étiquettes présentes : LEGIT.

## f. Vélocité — DIAGNOSTIC, jamais une cible (PERSONAS v2.0 §5.4)

- Règle : >3 transactions en 10 min (glissant) (lue dans VEL). Périmètre : population hors agents, sur 7 jours (activité médiane du réel : 322 jours ; fenêtres non identiques).
- Transactions monétaires : **1.37 %** des usagers (réel 26.92 %).
- Sorties seules : **0.62 %** (réel 10.2 %).

## g. Canal et liaison d'appareil (PERSONAS v1.2, hypothèses déclarées)

| Archétype | Usagers actifs | Téléphone lié % | APP % des tx | USSD % des tx |
|---|--:|--:|--:|--:|
| agent | 4 | 25.0 | 7.59 | 92.41 |
| marchand | 63 | 55.56 | 25.13 | 74.87 |
| ordinaire | 180 | 41.11 | 28.02 | 71.98 |
| salarie | 104 | 76.92 | 63.45 | 36.55 |
| victime | 19 | 31.58 | 19.39 | 80.61 |

- Transactions APP sans téléphone lié : **0** (attendu : 0).

## h. Rythme monétaire par usager — population hors agents (PERSONAS v2.0 §5.2)

366 usagers ; tx / durée d'activité de l'usager (jours, ≥ 1).

| Quantile | Synthétique | Réel | Écart | Tolérance | Dans la tolérance |
|---|--:|--:|--:|--:|---|
| p10 | 0.84 | 0.1793 | +368.37 % | ±25 % | **non** |
| p25 | 1.033 | 0.543 | +90.21 % | ±15 % | **non** |
| p50 | 1.69 | 1.071 | +57.79 % | ±15 % | **non** |
| p75 | 2.549 | 1.5671 | +62.67 % | ±15 % | **non** |
| p90 | 3.33 | 2.1816 | +52.62 % | ±15 % | **non** |
| p95 | 4.005 | 2.6332 | +52.08 % | ±25 % | **non** |

Maximum (rapporté seulement) : 6.349 (réel 4.7264).

## i. Sessions — population hors agents (PERSONAS v2.0 §5.5)

- Écarts consécutifs < 10 min : **12.81 %** (réel 13.04 % ; écart -1.75 %, tolérance ±25 % : oui).
- Écarts consécutifs < 1 min : 4.63 % (réel 5.38 %, rapporté).
- Écarts entre SORTIES consécutives < 10 min : **4.66 %** (réel 5.05 % ; écart -7.75 %, tolérance ±25 % : oui) ; < 1 min 1.76 % (réel 1.49 %).
