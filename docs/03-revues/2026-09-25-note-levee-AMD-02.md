# Note de levée — 8e interdiction — fusion et poussée d'AMD-02 dans main

**Date :** 2026-09-25
**Tâche :** AMD-02 — intégration de l'application mobile dans les documents de conception (ISC-AMD-02 v1.1), couloir Mobile.
**Branche :** `amd-02-integration-mobile`, créée depuis `origin/main` = 910dd8d (fusion de T3-00) ; 7 commits (7c96d3d … 7e333ae) + cette note.

## Ordre du propriétaire
« AMD-02 relu et accepté. Je lève la 8e interdiction pour : fusionner amd-02-integration-mobile dans main (--no-ff), make check VERT sur main, pousser. Rien d'autre. » Il s'agit d'une levée explicite de la 8e interdiction de CLAUDE.md §4 (actions sur le dépôt distant).

## Contenu fusionné (résumé)
- Documents mobiles sous `docs/02-specifications/mobile/` : ISC-MOB-CDC et ISC-MOB-SPEC v1.1 (conservées) et v1.2 ; 6 diagrammes `.mmd`/`.png` ; maquettes (36 PNG + README) ; dossier de design `.pptx`.
- ISC-SRS-FR v1.4, ISC-SAD v1.3 (fournis par le propriétaire) ; modèle de menace v1.3 ; ISC-AMD-02 v1.1 (`docs/03-revues/`).
- ADR-036 et ADR-037 (PROPOSÉ) ; amendements d'ADR-029 et ADR-030 ; ADR-035 FERME ; lignes de confirmation sur ADR-010, ADR-018 et ADR-031.
- `config/ruleset.yaml` rs-0.3.0 (paramètres de ré-enrôlement et de l'application mobile ; CAR_ENROL retiré) ; `docs/requirements-index.csv` en v1.4 ; porte de traçabilité AC-01..AC-16 ; commentaire de parité FR/EN dans `tools/check_docs.py`.
- CLAUDE.md (propriétaire, deux couloirs, versions des documents) ; plan Kanban v1.2.

## Validation
`make check` VERT sur la branche (7e333ae), prouvé dans le rapport : build, format, unit, arch, contract, check-docs, secrets, fictitious-msisdn, named-params. Contrat `/evaluate` : diff vide avec `origin/main`.

## Discipline git
`git branch --show-current` exécuté avant chaque commit : `amd-02-integration-mobile`, jamais `main`. La fusion se fait DANS `main`, par un checkout délibéré dans le dossier principal. La session Données travaille dans le worktree `wt-t3` sur `t3-04a-sim-legitimes-sidecar`, qui n'est pas touché ; elle rebasera sur le nouveau `main`.

## Geste
Fusion `--no-ff` dans `main`, `make check` VERT sur `main`, puis `git push origin main`. Aucune autre action distante.

## Cartes
Les cartes AMD-02, M-01 et M-03 demandées n'existent pas sur le tableau GitHub au moment de la fusion : elles n'ont pas été déplacées. Leur création n'est pas couverte par cette levée ; elle est remontée au propriétaire.
