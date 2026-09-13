# Levée de la 8e interdiction — poussée du correctif T2-09

**Date :** 2026-09-13
**Portée :** ce push uniquement. La levée ne s'étend à aucune autre action distante.

## Motif (ordre du propriétaire)

Correctif essentiel trouvé pendant la validation dans la VM : sans `HOSTNAME=0.0.0.0`, la console Next.js standalone est injoignable en Docker. Sans ce correctif sur `origin`, la branche ne produit pas une console fonctionnelle. Le propriétaire a levé explicitement, pour ce push seul, la 8e interdiction : « je lève pour pousser le correctif T2-09 ».

## Ce qui est fait sur la forge — et rien d'autre

- `git push origin feat/T2-09-console` : poussée des commits `2559c4e` (fix console HOSTNAME=0.0.0.0) et `6d999ea` (ADR-026 FERME + incident Docker 13/09), plus cette note.

## Ce qui n'est PAS fait

Aucune poussée sur `main`, aucune pull request, aucune fusion, aucun `--force`, aucune modification de visibilité ni de réglages du dépôt.
