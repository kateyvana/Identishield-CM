# IdentiShield-CM - commandes du depot (TACHE-00 tache 4 ; ISC-DEV §4, ISC-DEPLOY §4.4)
# Recettes en shell POSIX portable : memes commandes sous Git Bash et dans la VM Ubuntu.
SHELL := /bin/bash
.DEFAULT_GOAL := help

# MSYS_NO_PATHCONV empeche Git Bash de reecrire les chemins /... dans les arguments
# docker ; la variable est ignoree partout ailleurs. Regle documentee dans le README.
COMPOSE := MSYS_NO_PATHCONV=1 docker compose -f infra/docker-compose.yml
PY      ?= $(shell command -v python >/dev/null 2>&1 && echo python || echo python3)
GRADLE_PROJECTS := core pdp
PY_PROJECTS     := simulator attacker hlr-agent evaluation

.PHONY: help up down reset check test abuse evaluate diagrams check-docs check-stack \
        build format unit arch contract secrets fictitious-msisdn named-params traceability

help:
	@echo "Cibles : up down reset check test abuse evaluate diagrams check-docs check-stack"

## ---- pile ------------------------------------------------------------------
up:
	@bash infra/gen-secrets.sh
	@$(COMPOSE) up -d --build --wait   # --build : jamais tourner sur une image perimee (cache Docker => quasi gratuit si rien n'a change)

down:
	@$(COMPOSE) down

reset:
	@$(COMPOSE) down -v

check-stack:
	@bash tools/check_stack.sh

## ---- portes legeres (ISC-DEV §4) -------------------------------------------
check: build format unit arch contract check-docs secrets fictitious-msisdn named-params
	@echo "make check : VERT"

build:
	@echo ">>> build : assemble core+pdp, deps py, console"
	@for p in $(GRADLE_PROJECTS); do \
	  (cd $$p && ./gradlew --quiet assemble) || exit 1; \
	done
	@for p in $(PY_PROJECTS); do \
	  (cd $$p && uv sync --quiet) || exit 1; \
	done
	@if [ ! -d console/node_modules ]; then (cd console && npm ci --no-audit --no-fund); fi
	@if [ -n "$$MSYSTEM" ]; then 	  echo "console : next build complet indisponible sous Windows (bug amont vercel/next.js#87719) — mode compile seul ; le build complet tourne en CI et dans la VM"; 	  (cd console && npm run --silent build:compile); 	else 	  (cd console && npm run --silent build); 	fi

format:
	@echo ">>> format : spotless, ruff, prettier"
	@for p in $(GRADLE_PROJECTS); do \
	  (cd $$p && ./gradlew --quiet spotlessCheck) || exit 1; \
	done
	@for p in $(PY_PROJECTS); do \
	  (cd $$p && uv run --quiet ruff format --check . && uv run --quiet ruff check .) || exit 1; \
	done
	@cd console && npx --yes prettier@3.9.6 --log-level warn --check .
	@(cd simulator && uv run --quiet ruff format --check ../tools && uv run --quiet ruff check ../tools) 	  || { echo "format : ÉCHEC — les scripts des portes (tools/) ne passent pas ruff"; exit 1; }

unit:
	@echo ">>> unit : tests core+pdp+py"
	@for p in $(GRADLE_PROJECTS); do \
	  (cd $$p && ./gradlew --quiet test) || exit 1; \
	done
	@for p in $(PY_PROJECTS); do \
	  (cd $$p && uv run --quiet pytest -q) || exit 1; \
	done
	@echo "unit : console — aucun test n'existe encore (TACHE-00) ; rien n'a été vérifié pour ce projet"

arch:
	@echo ">>> arch : ArchUnit core+pdp"
	@for p in $(GRADLE_PROJECTS); do \
	  (cd $$p && ./gradlew --quiet test --tests '*ArchitectureTest') || exit 1; \
	done

contract:
	@echo ">>> contract : redocly evaluate.yaml"
	@npx --yes @redocly/cli@2.14.5 lint contracts/evaluate.yaml

check-docs:
	@echo ">>> check-docs"
	@$(PY) tools/check_docs.py

# Régénère docs/traceability.csv depuis les marqueurs @implements/@covers du code
# et les lignes attestées de docs/traceability-seed.csv. Échoue (porte) si un
# marqueur référence une exigence inexistante dans le SRS. Non câblé dans
# `make check` : cette cible écrit un fichier suivi, un check ne doit pas muter
# l'arbre. La porte « exigence obligatoire sans test » (ISC-TEST §12.1) viendra
# quand les tests existeront.
traceability:
	@$(PY) tools/gen_traceability.py

secrets:
	@echo ">>> secrets"
	@$(PY) tools/check_secrets.py

fictitious-msisdn:
	@echo ">>> fictitious-msisdn"
	@$(PY) tools/check_msisdn.py

named-params:
	@echo ">>> named-params"
	@$(PY) tools/check_named_params.py

## ---- autres cibles ----------------------------------------------------------
test: unit

abuse:
	@echo ">>> abuse : tests des cas d'abus T-AC (ENF-09) — exige la pile debout (make up)"
	@echo "    hors pile (ex. poste sans Docker), les T-AC s'ignorent proprement ; ils tournent en VM/CI."
	@(cd evaluation && uv run --quiet pytest -q -rs)

evaluate:
	@echo "non implémenté — jalon M4 (protocole ISC-EVAL)"

# Fail-closed (TACHE-01 correction 4) : une porte dont l'outil est absent ou
# non fonctionnel ÉCHOUE, elle ne réussit jamais silencieusement.
diagrams:
	@plantuml -version >/dev/null 2>&1 || { \
	  echo "diagrams : ÉCHEC — outil manquant ou non fonctionnel : plantuml (ISC-DEV §2.1)"; exit 1; }
	@files=$$(find docs/diagrams -name '*.puml' 2>/dev/null); \
	if [ -z "$$files" ]; then echo "diagrams : aucun diagramme à rendre (VERT)"; \
	else plantuml -checkonly $$files && echo "diagrams : VERT"; fi
