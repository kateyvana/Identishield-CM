# Levée de la 8e interdiction — fusion de T2-13 et de la note d'observation dans main et poussée

**Date :** 2026-09-15
**Portée :** ces deux fusions et la poussée qui les suit, uniquement. La levée ne s'étend à aucune autre action distante.

## Motif (ordre du propriétaire)

Levée **conditionnelle** accordée en session : « Quand la correction est faite et make check vert : je lève, fusionne feat/T2-13-operation-types et docs/observation-evaluate-cold-start dans main, et pousse. » La condition est remplie :
- Correction ES-11 appliquée (le fail-closed ne vise que les sorties ; un CASH_IN passe toujours en dégradé — commit 059faeb, note `docs/03-revues/2026-09-15-correction-es-11-fail-closed-sorties-seulement.md`).
- `make check` VERT après la correction.

## Ce qui est fait sur la forge — et rien d'autre

- Fusion locale `--no-ff` de `feat/T2-13-operation-types` dans `main`, puis `make check` vérifié VERT.
- Fusion locale `--no-ff` de `docs/observation-evaluate-cold-start` dans `main`, puis `make check` vérifié VERT.
- `git push origin main`.
- Carte Kanban T2-13 déplacée vers Done.

## Ce qui n'est PAS fait

Aucune pull request, aucun `--force`, aucune suppression de branche, aucune modification de visibilité ni de réglages du dépôt. La correction d'ES-11 dans le SRS reste à porter par l'encadrement (hors forge).
