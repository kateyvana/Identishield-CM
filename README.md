# IdentiShield-CM

Secure Identity & Behavioral Trust Layer for Mobile Money Platforms in Cameroon
— projet de fin d'études, ICT University, Yaoundé.

**État : environnement de dépôt en place (TACHE-00). Aucun code applicatif
n'existe encore** — les projets `core/`, `pdp/`, `console/` et les projets
Python sont des squelettes de build sans aucune logique métier.

## Prérequis

Poste de travail : ISC-DEV §2 (Docker, JDK 21 Temurin, Node 20, uv, GNU make,
Git). Environnement d'exécution : ISC-DEPLOY.

## Les trois commandes

```sh
make up          # démarre PostgreSQL (schémas core/pdp, rôles, réseau isc-net isolé)
                 # UNE seule commande de démarrage : inclut infra/compose.b3a.yml (profil demo,
                 # comptes de démonstration) dès que ISC_DEMO_SEED_CSV est défini dans infra/.env
make check-stack # santé, schémas, restriction du rôle d'audit (ED-06), isolation réseau
make check       # portes légères : build, format, unit, arch, contract, check-docs,
                 # secrets, fictitious-msisdn
```

## Note Windows / Git Bash

Le Makefile s'exécute sous **Git Bash** (`SHELL := /bin/bash`). Git Bash
réécrit les chemins commençant par `/` dans les arguments de commandes ;
toute invocation manuelle de `docker compose exec` avec un chemin absolu ou
une requête SQL doit être préfixée par `MSYS_NO_PATHCONV=1` (le Makefile le
fait déjà). Les fins de ligne sont imposées à LF par `.gitattributes`.

## Repères

Conception complète : `docs/02-specifications/`. Règles de l'agent de codage :
`CLAUDE.md`. Ordre des opérations (ISC-DEV §7) : TACHE-00, puis les cinq
spikes (`spikes/`, chacun clos par un ADR), puis ISC-SAD v1.1 (ADR-016 à 021
en FERME), puis le squelette marchant — premier code de production.
