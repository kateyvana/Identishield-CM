# Observation récurrente — le premier appel à froid dégrade la mesure (EXECUTED_DEGRADED)

**Date :** 2026-09-15
**Nature :** observation à trancher par le propriétaire avant le mois 5. **Ne pas corriger maintenant.**
**Exigence concernée :** EI-01 (budget de latence 800 ms).

## Le motif

À froid, le premier appel `/evaluate` (ou la première mesure de latence) part avant que le PDP Spring Boot ne soit prêt : initialisation paresseuse du `dispatcherServlet`, ouverture du pool Hikari, JIT non chauffé. Le premier appel est donc anormalement lent, ou revient `EXECUTED_DEGRADED` ; le deuxième, à chaud, est nominal. C'est un artefact de **mesure**, pas un défaut du cœur — mais il fausse toute mesure prise au premier coup.

## Les quatre occurrences (mêmes causes, quatre contextes)

| Date | Contexte | Manifestation |
|---|---|---|
| 2026-08-27 | **SPIKE-2** (horloge injectable) | mesure prise **à froid**, non représentative |
| 2026-08-30 | **T2-07** (cœur, transfert P2P) | a nécessité un **préchauffage** explicite avant la mesure du fil |
| 2026-09-13 / 14 | **T2-10** (HLR simulé) | **flake** : premier passage dégradé, vert au suivant |
| 2026-09-15 | **check-stack** (`verify_transfer`) | premier `/evaluate` en **EXECUTED_DEGRADED** (init dispatcherServlet + Hikari ≈ 340 ms), VERT au 2e appel |

Trace du garde-fou déjà en place : `tools/verify_transfer.sh:6` note que « le budget de 800 ms reste celui de la production (cœur chaud) » — reconnaissance du problème, sans décision.

## La question posée (à trancher avant le mois 5)

Deux lectures possibles, mutuellement exclusives dans leurs conséquences :

1. **Le budget EI-01 de 800 ms est trop serré** s'il doit tenir *y compris* au premier appel à froid — auquel cas c'est l'exigence ou son interprétation qu'il faut réviser (par l'encadrement, `docs/02-specifications/`).
2. **La pile doit être chauffée avant toute mesure** — auquel cas 800 ms reste le budget *à chaud* (position implicite actuelle), et il faut un **préchauffage standard** avant chaque mesure publiable (un appel de rodage jeté), documenté dans le protocole d'évaluation (ISC-EVAL) et appliqué uniformément par les `verify_*`.

Le mois 5 porte l'évaluation chiffrée (ISC-EVAL) : la question doit être tranchée **avant**, sinon les chiffres publiables hériteront de l'ambiguïté. Recommandation de l'agent : lecture 2 (chauffer, 800 ms à chaud) — c'est déjà la conduite de fait et elle sépare proprement « mesure d'infrastructure » de « mesure de décision » — mais le choix appartient au propriétaire.
