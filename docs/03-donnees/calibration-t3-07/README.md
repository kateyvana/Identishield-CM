# Calibration T3-07 — preuves brutes (ADR-016, PROVISOIRE)

Scripts hors ligne (bibliothèque standard seule) et leurs sorties, tels qu'exécutés le 2026-10-01
(phase 1) et le 2026-10-06 (étude ADR-016, vérification sous blocage). Aucun n'est importé par le
code de production ; aucun ne lit le moteur du PDP (qui n'existe pas encore).

| Fichier | Rôle |
|---|---|
| `fpr_diag.py` → `fpr_diag.json` | Phase 1 : FPR des seuils candidats signal par signal (VEL, NIGHT, CAP_PROB, fail-closed), signature AC-02 |
| `adr016_study.py` → `adr016_study.json` | Rejeu de la fonction de décision (somme bornée / max), grilles de poids, THR_HIGH, NIGHT, courbe perte/friction CAP_PROB (cas a/b), H5 par persona, TPR AC-02 |
| `verify_block.py` → `verify_block.out` | Vérification exigée avant écriture : FPR légitime **sous la politique USSD = BLOQUER**, avec les valeurs exactes de rs-0.6.0 |
| `tpr_final.py` → `tpr_final.out` | TPR AC-02 attendu avec les valeurs exactes de rs-0.6.0 |

## Entrées (non versionnées, régénérables à l'octet)

- Jeu légitime gelé : `data/eval/dev_horizon_v1.manifest.json` (recette de régénération ; vue `c165d55b…`, sidecar `6bc9ecbc…`, HLR `12fb1a91…`). `fpr_diag.py` revérifie les trois sha256 et s'arrête s'ils diffèrent.
- Plan AC-02 gelé : `data/eval/ac02_plan.csv`, sha256 `72876d18…3b85` (branche `t3-04b-plan`).

## Reproduire (depuis la racine du dépôt)

```text
uv run --project simulator python -m isc_simulator.generator --seed 20260925 --users 2480 --days 182 --start 2026-10-01 --out <h>.csv
uv run --project simulator python docs/03-donnees/calibration-t3-07/fpr_diag.py <h> <plan>.csv data/eval/dev_horizon_v1.manifest.json <sortie>.json
uv run --project simulator python docs/03-donnees/calibration-t3-07/adr016_study.py <h> <plan>.csv <sortie>.json
uv run --project simulator python docs/03-donnees/calibration-t3-07/verify_block.py <h> <plan>.csv -
uv run --project simulator python docs/03-donnees/calibration-t3-07/tpr_final.py <h> <plan>.csv -
```

`<h>` est le chemin du jeu **sans** extension (les trois vues `<h>.csv`, `<h>.labels.csv`, `<h>.hlr_events.csv`).

## Conventions de mesure

- FPR = ISC-EVAL §5.1 : (FREEZE + STEP_UP non résolu menant à l'annulation) / toutes les transactions légitimes du persona ; agents rapportés à part.
- Fenêtre de mesure : jours 31 à 182 (rodage de 30 j : nouveauté du bénéficiaire et médiane 30 j exigent un historique observé).
- Nouveauté = bénéficiaire jamais vu dans le flux du compte (ce que le PDP peut calculer), pas le sidecar.
- Heure NIGHT = UTC + 1 h (WAT supposé, déclaré).
- Intervalles de confiance : Wilson 95 %.
