# Note de levée — 8e interdiction — fusion et poussée de `chore/check-progress`

**Date :** 2026-09-24
**Branche :** `chore/check-progress` (depuis `main` = `5d1a397`).

## Ordre du propriétaire
Dans la même revue de clôture de T2-16 :
1. « ADR-034 : je le clôture FERME. Passe son statut de PROPOSÉ FERME à FERME. »
2. « Visibilité : option A retenue. Une ligne `@echo ">>> <porte> …"` en tête de
   chaque cible de `check`. Applique-la sur une branche `chore/check-progress`,
   vérifie que `make check` reste VERT et que chaque porte s'annonce, puis fusionne
   et pousse. »

Levée explicite de la 8e interdiction (fusion locale + push origin).

## Contexte
Le run `make check` du 2026-09-24 a été aveugle ~30 min (caches gradle froids après
une longue pause) : la cible `check` n'émettait qu'un `VERT` final, le `--quiet`
gradle masquant toute progression. Diagnostic : ce n'était pas un blocage mais
l'absence de signal par porte. Correction retenue par le propriétaire = **option A**
(annonce toujours active, coût nul ; l'option B « cible dédiée horodatée » n'est PAS
retenue pour l'instant).

## Gestes
- **ADR-034** (`docs/01-decisions/ADR-034.md`) : statut `PROPOSÉ FERME` → **`FERME`**
  (clôturé par le propriétaire le 2026-09-24). Aucun changement de fond.
- **Makefile** : `@echo ">>> <porte> …"` en tête de chacune des 9 cibles de `check`
  (build, format, unit, arch, contract, check-docs, secrets, fictitious-msisdn,
  named-params). Chaque run — normal comme long — annonce désormais la porte en
  cours. Aucun changement de comportement des portes elles-mêmes.

## Preuve
`make check` sur `chore/check-progress` : **VERT**, et chaque porte s'annonce
(`>>> build … >>> format … … >>> named-params`) avant le `make check : VERT` final.

## Méthode
Fusion `--no-ff` dans `main`, puis `git push origin main`. Aucune autre action distante.
Pas de force-push.
