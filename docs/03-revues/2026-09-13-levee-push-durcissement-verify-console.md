# Levée de la 8e interdiction — poussée du durcissement de verify_console.sh

**Date :** 2026-09-13
**Portée :** ce push uniquement. La levée ne s'étend à aucune autre action distante.

## Motif (ordre du propriétaire)

Durcissement de `tools/verify_console.sh` contre un faux positif (decisionId vide → `grep` sur motif vide qui faux-passait). Le propriétaire a levé explicitement, pour ce push seul, la 8e interdiction : « je lève pour pousser le durcissement ».

## Ce qui est fait sur la forge — et rien d'autre

- `git push origin feat/T2-09-console` : poussée du commit `4898544` (verify_console.sh fail-closed sur decisionId invalide), plus cette note.

## Ce qui n'est PAS fait

Aucune poussée sur `main`, aucune pull request, aucune fusion, aucun `--force`, aucune modification de visibilité ni de réglages du dépôt.
