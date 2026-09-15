# Levée de la 8e interdiction — fusion de deux branches (garde-fou disque, ADR-026) dans main et poussée

**Date :** 2026-09-15
**Portée :** ces deux fusions et la poussée qui les suit, uniquement. La levée ne s'étend à aucune autre action distante.

## Motif (ordre du propriétaire)

Ordre explicite en session : « je lève, fusionne les deux branches dans main, et pousse ». Ordre imposé des fusions : `chore/check-stack-disk-guard` **puis** `docs/adr-026-desinstallation-docker-desktop`. `make check` VERT exigé après chaque fusion, avant la poussée.

Les deux branches ont été produites plus tôt le 15/09 :
- `chore/check-stack-disk-guard` : garde-fou d'espace disque en tête de `tools/check_stack.sh` (échec si < 10 Go), plus une correction de son commentaire précisant ce qu'il couvre (disque **invité** où tournent les images Docker) et ce qu'il ne couvre pas (le disque **hôte** portant le `.vdi`, cause réelle des pauses du 15/09 — relève d'un contrôle côté hôte distinct). Correction demandée par le propriétaire, qui a reconnu que la porte avait été commandée sans vérifier ce que check-stack peut observer.
- `docs/adr-026-desinstallation-docker-desktop` : section « Mise à jour du 15/09/2026 » d'ADR-026 (Docker Desktop désinstallé du poste → répartition devenue contrainte absolue) et la demande de changement associée.

## Ce qui est fait sur la forge — et rien d'autre

- Fusion locale `--no-ff` de `chore/check-stack-disk-guard` dans `main`, puis `make check` vérifié VERT.
- Fusion locale `--no-ff` de `docs/adr-026-desinstallation-docker-desktop` dans `main`, puis `make check` vérifié VERT.
- `git push origin main`.

## Ce qui n'est PAS fait

Aucune pull request, aucun `--force`, aucune suppression de branche, aucune modification de visibilité ni de réglages du dépôt. Le contrôle d'espace disque **côté hôte** reste une tâche distincte (hygiène de poste), non traitée ici.
