# Échantillon de contrôle — trafic légitime v1

- **Fichier** : `data/synth/echantillon_legit_v1.csv` (SHA-256 `b75111922bd2c3084f6a85c3cca63948a349ae538dff20ae248cefa8fe796b3f`)
- **Graine** : ISC_SEED = 20260925 ; personas v1.1
- **Population** : 400 usagers, 7 jours à partir du 2026-10-25 → **8460 transactions**, toutes étiquetées `LEGIT`
- **Étalon** : `profil_reel_v1.json` (source `84cd01ae…`)

> Rapport généré par `python -m isc_simulator.stats` (déterministe). Les tolérances de PERSONAS §5 sont indiquées à titre d'information : les tenir est l'objet de T3-06.

## a. Montant — agents seuls vs réel (montants > 0)

| Quantile | Synthétique | Réel | Écart | Tolérance | Dans la tolérance |
|---|--:|--:|--:|--:|---|
| p10 | 134.0 | 128.2 | +4.52 % | ±15 % | oui |
| p25 | 517.75 | 500.0 | +3.55 % | ±15 % | oui |
| p50 | 1892.0 | 1854.0 | +2.05 % | ±15 % | oui |
| p75 | 5043.5 | 5000.0 | +0.87 % | ±15 % | oui |
| p90 | 15098.5 | 15340.0 | -1.57 % | ±15 % | oui |
| p95 | 42964.75 | 50750.0 | -15.34 % | ±25 % | oui |
| p99 | 349753.7 | 500000.0 | -30.05 % | ±25 % | **non** |

Agents : n = 4336. Distance L1 d'histogramme = **0.1099** (cible < 0.1).

| Tranche | Synthétique % | Réel % |
|---|--:|--:|
| 1–500 | 24.24 | 20.25 |
| 500–1k | 13.51 | 14.38 |
| 1k–5k | 37.15 | 39.85 |
| 5k–10k | 9.2 | 9.87 |
| 10k–25k | 8.51 | 8.23 |
| 25k–50k | 2.88 | 1.9 |
| 50k–100k | 1.25 | 1.95 |
| 100k–500k | 2.72 | 2.47 |
| 500k+ | 0.53 | 1.08 |

Population entière (informatif, non forcé) : p10 = 230.0, p25 = 678.75, p50 = 2021.0, p75 = 5214.25, p90 = 14061.8, p95 = 32988.25, p99 = 198950.85.

## b. Heure du jour — population

- Distance L1 sur 24 h : **0.0648** (cible < 0.08).
- Part NUIT (heures [0, 1, 2, 3, 4, 23], lue dans NIGHT) : **6.39 %** (réel 6.51 %, cible ±1,5 pt).

## c. Sorties et nouveaux bénéficiaires

- Part de sorties : **38.45 %** (réel 49.58 % parmi IN+OUT ; non forcé).
- Sorties sautées pour solde insuffisant : 157 (4.6 % des sorties tentées).
- `is_new_beneficiary` : **11.8 % des sorties** (4.54 % de toutes les transactions).

| Archétype | Usagers | Transactions | Part du volume % | Part de sorties % | Nouveau / sorties % |
|---|--:|--:|--:|--:|--:|
| agent | 20 | 4336 | 51.25 | 48.29 | 15.43 |
| marchand | 60 | 2538 | 30.0 | 14.11 | 8.1 |
| ordinaire | 200 | 368 | 4.35 | 45.92 | 5.92 |
| salarie | 100 | 1169 | 13.82 | 52.1 | 3.45 |
| victime | 20 | 49 | 0.58 | 46.94 | 4.35 |

## d. Soldes

- Soldes négatifs : **0** (solde minimal observé : 12).
- Incohérences arithmétiques (après ≠ avant ± montant) : 0.
- Ruptures de chaîne (solde d'avant ≠ solde d'après précédent du même usager) : 0.

## e. Contrôles

- MSISDN hors de 699000000–699009999 (usagers et bénéficiaires) : **0**.
- Auto-transferts : 0. Étiquettes présentes : LEGIT.

## f. Vélocité — rapportée, NON alignée sur le réel (PERSONAS §5.4)

- Règle : >3 transactions en 10 min (glissant) (lue dans VEL).
- Population : **11.98 %** des usagers ; agents seuls : **100.0 %** (bande cible 25–55 %, réel 39.06 %).
