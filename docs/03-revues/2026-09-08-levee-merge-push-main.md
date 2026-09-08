# Levée de la 8e interdiction — fusion et poussée de main

**Date :** 2026-09-08
**Portée :** cette action uniquement. La levée ne s'étend à aucune autre action distante.

## Motif (ordre du propriétaire)

Ordre explicite du propriétaire, en session : « Fusionne docs/amd-01-integration dans main, puis pousse. Tant que ce n'est pas fait, main porte une conception périmée : le SRS v1.2 y est encore la référence alors que la v1.3 est décidée. »

## Ce qui est fait sur la forge — et rien d'autre

- Fusion locale `--no-ff` de `docs/amd-01-integration` dans `main` (commit de fusion, convention du dépôt).
- `git push origin main` : poussée de `main` mis à jour vers `origin` (https://github.com/kateyvana/Identishield-CM.git).

## Ce qui n'est PAS fait

Aucune pull request, aucun `--force`, aucune suppression de branche, aucune modification de visibilité ni de réglages du dépôt, aucune protection de branche. Le travail T2-09 en cours reste local, non commité et non concerné.
