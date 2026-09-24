# Profil de référence des transactions réelles — v1

- **Date de l'artefact** : 2026-09-25
- **Fichier source** : `cleaned_transactions__1_.csv` (confidentiel, NON versionné)
- **Empreinte SHA-256 du source** : `84cd01ae42d0fde10292b2df7e16302cf8dee3cd1359107328529a2e83401fb1`
- **Effectif** : 26989 lignes de données (26 990 lignes totales avec l'en-tête)
- **Version** : v1

> Étalon du Mois 3 : distributions de référence tirées des transactions réelles, contre lesquelles le générateur sera jugé (T3-06) et les seuils calibrés (T3-07). Généré par `tools/profile_real.py` (déterministe).

## a. Montant (FCFA)

| Statistique | Tous les rangs | Montants > 0 |
|---|--:|--:|
| n | 26989 | 19903 |
| min | 0.0 | 0.5 |
| max | 2015424.0 | 2015424.0 |
| mean | 11806.32 | 16009.69 |
| median | 550.0 | 1854.0 |
| p10 | 0.0 | 128.2 |
| p25 | 0.0 | 500.0 |
| p50 | 550.0 | 1854.0 |
| p75 | 2600.0 | 5000.0 |
| p90 | 10000.0 | 15340.0 |
| p95 | 27369.4 | 50750.0 |
| p99 | 415240.0 | 500000.0 |

Montants nuls (événements non monétaires : SMS/promo/autre) : **7086** (26.26 %).

**Histogramme par tranches**

| Tranche | Compte | % |
|---|--:|--:|
| 0 | 7086 | 26.26 |
| 1–500 | 4031 | 14.94 |
| 500–1k | 2863 | 10.61 |
| 1k–5k | 7932 | 29.39 |
| 5k–10k | 1964 | 7.28 |
| 10k–25k | 1638 | 6.07 |
| 25k–50k | 378 | 1.4 |
| 50k–100k | 389 | 1.44 |
| 100k–500k | 492 | 1.82 |
| 500k+ | 215 | 0.8 |

## b. Heure du jour

| Heure | Compte | % |
|--:|--:|--:|
| 00 | 773 | 2.86 |
| 01 | 221 | 0.82 |
| 02 | 143 | 0.53 |
| 03 | 131 | 0.49 |
| 04 | 129 | 0.48 |
| 05 | 223 | 0.83 |
| 06 | 705 | 2.61 |
| 07 | 1207 | 4.47 |
| 08 | 1515 | 5.61 |
| 09 | 1469 | 5.44 |
| 10 | 1624 | 6.02 |
| 11 | 1749 | 6.48 |
| 12 | 1665 | 6.17 |
| 13 | 1655 | 6.13 |
| 14 | 1587 | 5.88 |
| 15 | 1593 | 5.9 |
| 16 | 1544 | 5.72 |
| 17 | 1659 | 6.15 |
| 18 | 1728 | 6.4 |
| 19 | 1605 | 5.95 |
| 20 | 1540 | 5.71 |
| 21 | 1229 | 4.55 |
| 22 | 935 | 3.46 |
| 23 | 360 | 1.33 |

**Fenêtre NUIT** (23h–05h (heures 23,0,1,2,3,4)) : **1757** transactions, **6.51 %**.

## c. Par usager

Nombre d'usagers : **64**.

| Transactions / usager | Valeur |
|---|--:|
| min | 2 |
| median | 240.5 |
| p90 | 876.1 |
| p95 | 1522.55 |
| max | 2486 |

**Vélocité** (>3 transactions en 10 min (fenêtre glissante)) : **25** usagers, **39.06 %**.

## d. Sorties (débits)

Champ de sens : Direction (valeurs : OUT = sortie/débit, IN = entrée, unknown = non renseigné).

| Direction | Compte |
|---|--:|
| IN | 12556 |
| OUT | 12345 |
| UNKNOWN | 2056 |
| INFO | 32 |

- **Taux de sorties** : 45.74 % (12345 sorties).
- **Part des sorties ≥ 25000 FCFA (futur CAP_PROB)** : 1.8 % des sorties (222).

## e. Champs absents pour notre modèle

- **Champ bénéficiaire présent ?** **NON**. Aucune colonne bénéficiaire/destinataire/contrepartie. Le CSV décrit des opérations d'un usager (UserId), sans le tiers crédité. Impact modèle : la règle « nouveau bénéficiaire » (ES-05b) n'est pas calibrable sur ce jeu ; il faudra la SYNTHÉTISER en T3-02+.
- **Autres champs manquants :**
  - IMSI / identité SIM (pas de CAR_IMSI observable ici)
  - identifiant d'appareil / liaison d'appareil (device binding)
  - décision / veto / score du PDP (données AVANT toute couche)
  - canal précis USSD vs application (seul Operator + Txn_type sont donnés)
  - signature / nonce (anti-rejeu)
- **Champs présents utiles :**
  - Amount (montant)
  - Time / Datetime (heure, vélocité)
  - UserId (par usager)
  - Direction (sorties/entrées)
  - Operator, Txn_type/Txn_type_std (type d'opération)
