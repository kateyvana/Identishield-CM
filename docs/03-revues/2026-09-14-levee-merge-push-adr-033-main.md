# Levée de la 8e interdiction — fusion de docs/adr-033-outils-stdlib dans main et poussée

**Date :** 2026-09-14
**Portée :** cette action uniquement. La levée ne s'étend à aucune autre action distante.

## Motif (ordre du propriétaire)

Ordre explicite du propriétaire, en session : lever la 8e interdiction pour fusionner T2-11 puis la branche `docs/adr-033-outils-stdlib`, et pousser. La branche porte ADR-033 (outils CLI Python, bibliothèque standard uniquement — clôturé FERME par le propriétaire), la demande de correction du SAD §5, et le risque consigné sur l'endpoint /hlr/reassign.

## Ce qui est fait sur la forge — et rien d'autre

- Fusion locale `--no-ff` de `docs/adr-033-outils-stdlib` dans `main` (commit de fusion).
- `make check` vérifié VERT sur `main` après la fusion et avant la poussée.
- `git push origin main`.

## Ce qui n'est PAS fait

Aucune pull request, aucun `--force`, aucune suppression de branche, aucune modification de visibilité ni de réglages du dépôt. Le SAD n'est pas modifié (CLAUDE.md §2) : sa correction reste à la charge de l'encadrement.
