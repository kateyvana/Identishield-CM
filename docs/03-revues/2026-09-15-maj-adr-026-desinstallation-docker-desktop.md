# Demande de changement — ADR-026 : Docker Desktop désinstallé du poste (répartition → contrainte absolue)

**Date :** 2026-09-15
**Décision de référence :** ADR-026 (construction/validation de la console sur la VM)
**Nature :** mise à jour d'une décision qui m'appartient. Contrairement à la correction du SAD ([[../02-specifications]] hors de ma main, cf. `2026-09-14-correction-sad-5-outils-stdlib.md`), `docs/01-decisions/` relève de l'agent de codage : la correction est donc **portée directement** dans ADR-026 (section « Mise à jour du 15/09/2026 »). Cette note en trace le motif.

## Ce qui a changé le 15/09/2026

Le disque `C:` du poste était **plein (0 Go / 471)**, ce qui suspendait la VM à répétition (le `.vdi` dynamique ne pouvait plus grossir) — diagnostiqué à tort comme un manque de mémoire pendant des heures (voir `2026-09-15-...` / mémoire d'incident). Sous **levée explicite du propriétaire** (option « Docker local + caches, ~12 Go »), Docker Desktop — corrompu depuis le 13/09 (`exec format error`, ADR-026) et inutilisé depuis — a été **désinstallé** : distribution WSL `docker-desktop` désenregistrée puis `.vhdx` orphelin (9,5 Go) supprimé. **Le poste n'a plus aucun Docker.**

## Pourquoi ADR-026 devient inexact

ADR-026 décrit une **répartition** : « le poste Windows ne fait que la compilation légère », la VM fait le build/validation Docker. Cette formulation présuppose un Docker *présent mais volontairement peu sollicité* sur le poste, et prévoit « on ne répare pas, on bascule » en cas de panne. Ce n'est plus une répartition : c'est une **contrainte absolue**. Il n'y a plus rien à basculer ni à réparer — la VM (et la CI Linux) est le **seul** environnement Docker.

## La correction portée dans ADR-026

Ajout d'une section « Mise à jour du 15/09/2026 » qui :
- acte la désinstallation de Docker Desktop et la levée qui l'a autorisée ;
- reformule la décision : **tout** travail Docker (build d'images, pile, validation, mesures publiables) se fait **exclusivement** dans la VM/CI ; le poste fait la compilation légère et les portes légères, **sans Docker** ;
- consigne la preuve : `make check` sur `main` passe **VERT sans Docker** (vérifié le 15/09, Docker absent du poste).

## Suivi éventuel (hors de ma main)

Si `ISC-DEPLOY` (`docs/02-specifications/`) décrit un Docker local sur le poste dans sa répartition poste/VM (§6.2), la mise en cohérence relève d'une nouvelle version du document livrée par l'encadrement — CLAUDE.md §2, je ne modifie pas les spécifications. Signalé, non exécuté.
