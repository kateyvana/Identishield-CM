# Échantillon de contrôle — trafic légitime v1

- **Fichier** : `data/synth/echantillon_legit_v1.csv` (SHA-256 `1486aeaecc41dd561a7b0d0c361514ca5e8a57773aa7015347d181a7c4285dc2`)
- **Graine** : ISC_SEED = 20260925 ; personas v2.0
- **Population** : 400 usagers, 7 jours à partir du 2026-10-25 → **4018 transactions**, toutes étiquetées `LEGIT`
- **Étalon** : `profil_reel_v1.1.json` (source `84cd01ae…`)

> Rapport généré par `python -m isc_simulator.stats` (déterministe). Les tolérances de PERSONAS §5 sont indiquées à titre d'information : les tenir est l'objet de T3-06.

## a. Montant — population entière vs réel (montants > 0, salaire compris)

| Quantile | Synthétique | Réel | Écart | Tolérance | Dans la tolérance |
|---|--:|--:|--:|--:|---|
| p10 | 125.0 | 128.2 | -2.5 % | ±15 % | oui |
| p25 | 500.0 | 500.0 | +0.0 % | ±15 % | oui |
| p50 | 1500.0 | 1854.0 | -19.09 % | ±15 % | **non** |
| p75 | 4817.0 | 5000.0 | -3.66 % | ±15 % | oui |
| p90 | 18931.5 | 15340.0 | +23.41 % | ±15 % | **non** |
| p95 | 69889.75 | 50750.0 | +37.71 % | ±25 % | **non** |
| p99 | 300766.49 | 500000.0 | -39.85 % | ±25 % | **non** |

n = 4018. Distance L1 d'histogramme = **0.074** (cible < 0.1).

| Tranche | Synthétique % | Réel % |
|---|--:|--:|
| 1–500 | 21.98 | 20.25 |
| 500–1k | 14.39 | 14.38 |
| 1k–5k | 38.7 | 39.85 |
| 5k–10k | 9.01 | 9.87 |
| 10k–25k | 7.79 | 8.23 |
| 25k–50k | 1.39 | 1.9 |
| 50k–100k | 3.93 | 1.95 |
| 100k–500k | 2.07 | 2.47 |
| 500k+ | 0.75 | 1.08 |

Montants ronds (le synthétique ne doit pas être plus rond que le réel) :

| Multiple de | Synthétique % | Réel % | Pas plus rond que le réel |
|--:|--:|--:|---|
| 100 | 66.53 | 81.56 | oui |
| 1000 | 39.32 | 46.82 | oui |
| 500 | 52.09 | 61.28 | oui |
| 5000 | 11.67 | 15.77 | oui |

## b. Heure du jour — population

- Distance L1 sur 24 h : **0.0776** (cible < 0.08).
- Part NUIT (heures [0, 1, 2, 3, 4, 23], lue dans NIGHT) : **6.15 %** (réel 6.51 %, cible ±1,5 pt).

## c. Sorties et nouveaux bénéficiaires

- Part de sorties : **38.88 %** (réel 49.58 % parmi IN+OUT ; non forcé).
- Sorties sautées pour solde insuffisant : 184 (10.54 % des sorties tentées).
- `is_new_beneficiary` : **6.34 % des sorties** (2.46 % de toutes les transactions).

| Archétype | Usagers | Transactions | Part du volume % | Part de sorties % | Nouveau / sorties % |
|---|--:|--:|--:|--:|--:|
| agent | 4 | 540 | 13.44 | 52.41 | 13.78 |
| marchand | 63 | 1174 | 29.22 | 14.82 | 9.77 |
| ordinaire | 208 | 752 | 18.72 | 43.62 | 4.27 |
| salarie | 104 | 1453 | 36.16 | 50.72 | 3.93 |
| victime | 21 | 99 | 2.46 | 40.4 | 0.0 |

## d. Soldes

- Soldes négatifs : **0** (solde minimal observé : 12).
- Incohérences arithmétiques (après ≠ avant ± montant) : 0.
- Ruptures de chaîne (solde d'avant ≠ solde d'après précédent du même usager) : 0.

## e. Contrôles

- MSISDN hors de 677000000–677009999 (usagers et bénéficiaires) : **0**.
- Auto-transferts : 0. Étiquettes présentes : LEGIT.

## f. Vélocité — DIAGNOSTIC, jamais une cible (PERSONAS v2.0 §5.4)

- Règle : >3 transactions en 10 min (glissant) (lue dans VEL). Périmètre : population hors agents, sur 7 jours (activité médiane du réel : 322 jours ; fenêtres non identiques).
- Transactions monétaires : **0.82 %** des usagers (réel 26.92 %).
- Sorties seules : **0.0 %** (réel 10.2 %).

## g. Canal et liaison d'appareil (PERSONAS v1.2, hypothèses déclarées)

| Archétype | Usagers actifs | Téléphone lié % | APP % des tx | USSD % des tx |
|---|--:|--:|--:|--:|
| agent | 4 | 25.0 | 7.59 | 92.41 |
| marchand | 63 | 55.56 | 25.13 | 74.87 |
| ordinaire | 181 | 40.88 | 26.2 | 73.8 |
| salarie | 104 | 76.92 | 60.91 | 39.09 |
| victime | 19 | 31.58 | 19.19 | 80.81 |

- Transactions APP sans téléphone lié : **0** (attendu : 0).

## h. Rythme monétaire par usager — population hors agents (PERSONAS v2.0 §5.2)

367 usagers ; tx / durée d'activité de l'usager (jours, ≥ 1).

| Quantile | Synthétique | Réel | Écart | Tolérance | Dans la tolérance |
|---|--:|--:|--:|--:|---|
| p10 | 0.771 | 0.1793 | +330.27 % | ±25 % | **non** |
| p25 | 1.0 | 0.543 | +84.16 % | ±15 % | **non** |
| p50 | 1.724 | 1.071 | +60.98 % | ±15 % | **non** |
| p75 | 2.541 | 1.5671 | +62.13 % | ±15 % | **non** |
| p90 | 3.311 | 2.1816 | +51.78 % | ±15 % | **non** |
| p95 | 3.841 | 2.6332 | +45.85 % | ±25 % | **non** |

Maximum (rapporté seulement) : 6.349 (réel 4.7264).

## i. Sessions — population hors agents (PERSONAS v2.0 §5.5)

- Écarts consécutifs < 10 min : **12.76 %** (réel 13.04 % ; écart -2.14 %, tolérance ±25 % : oui).
- Écarts consécutifs < 1 min : 4.69 % (réel 5.38 %, rapporté).
