# Note de levée — fusion de T3-04a (2026-09-26)

**Interdiction levée :** 8e interdiction de CLAUDE.md §4 (aucune action sur un dépôt distant ni sur une API de forge).
**Par :** le propriétaire, en session, le 2026-09-26, après relecture et acceptation de T3-04a.
**Texte de la levée :** « Je lève la 8e interdiction pour : fusionner t3-04a-sim-legitimes-sidecar dans main (--no-ff), make check VERT sur main, pousser. Rien d'autre. »

## Décisions du propriétaire consignées à la relecture

- `archetype` et `is_new_beneficiary` restent hors du flux détecteur : le PDP doit calculer « nouveau bénéficiaire » à partir de l'historique, et le sidecar servira à vérifier ce calcul au Mois 5.
- Les deux modifications de tests (constance par usager remplacée par « change seulement aux événements » ; test de CLI remplacé par le déterminisme des trois fichiers) sont validées : spécification plus stricte, aucun test assoupli.
- PERSONAS §10 (PO-1 encore présenté comme « décision à prendre » alors que l'horizon de 6 mois est ratifié) : correction reportée à la prochaine version.

## Périmètre

- Fusion `--no-ff` de `t3-04a-sim-legitimes-sidecar`, rebasée sur `origin/main` (`d8b56da`), avec `make check` VERT sur la branche puis sur `main`, et push de `main`.
- Cartes : #7 (T3-04a) vers Done avec preuve ; #20 (T3-04b) reste en Backlog.

## Hors périmètre (non levé)

Toute autre branche, toute pull request, tout réglage du dépôt, tout push forcé.
