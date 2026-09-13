# Levée de la 8e interdiction — fusion de feat/T2-10-hlr dans main et poussée

**Date :** 2026-09-14
**Portée :** cette action uniquement. La levée ne s'étend à aucune autre action distante.

## Motif (ordre du propriétaire)

Ordre explicite du propriétaire, en session : « fusionne feat/T2-10-hlr dans main, je lève, puis pousse ». T2-10 (HLR simulé, EF-02/EF-08/EI-02/ES-16b) est validée dans la VM (`verify_hlr.sh` VERT, `check-stack` VERT) et la carte est en In Review. La branche porte aussi ADR-032 (immutabilité de l'historique HLR en base), l'assertion check-stack associée, et le passage de `make up` en `--build` (découvert en investiguant le mode dégradé).

## Ce qui est fait sur la forge — et rien d'autre

- Fusion locale `--no-ff` de `feat/T2-10-hlr` dans `main` (commit de fusion, convention du dépôt).
- `make check` vérifié VERT sur `main` **après** la fusion et **avant** la poussée.
- `git push origin main` : poussée de `main` mis à jour vers `origin` (https://github.com/kateyvana/Identishield-CM.git).

## Ce qui n'est PAS fait

Aucune pull request, aucun `--force`, aucune suppression de branche, aucune modification de visibilité ni de réglages du dépôt, aucune protection de branche.
