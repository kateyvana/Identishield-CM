# IdentiShield-CM - commandes du depot (TACHE-00 tache 4 ; ISC-DEV §4, ISC-DEPLOY §4.4)
# Recettes en shell POSIX portable : memes commandes sous Git Bash et dans la VM Ubuntu.
SHELL := /bin/bash
.DEFAULT_GOAL := help

# MSYS_NO_PATHCONV empeche Git Bash de reecrire les chemins /... dans les arguments
# docker ; la variable est ignoree partout ailleurs. Regle documentee dans le README.
COMPOSE := MSYS_NO_PATHCONV=1 docker compose -f infra/docker-compose.yml
PY      ?= $(shell command -v python >/dev/null 2>&1 && echo python || echo python3)
GRADLE_PROJECTS := core pdp
PY_PROJECTS     := simulator attacker hlr-agent evaluation tools/mobile-mock tools/survey
# Faux serveur de l'API mobile (M-03) : port et mode démo sont des paramètres, pas du code.
MOBILE_MOCK_PORT ?= 8089
MOBILE_MOCK_DEMO ?=

.PHONY: help up down reset reset-db apk-publie apk-depublie check test abuse evaluate diagrams check-docs check-stack \
        build format unit arch contract secrets fictitious-msisdn named-params traceability \
        mobile-mock eval-tests

help:
	@echo "Cibles : up down reset reset-db apk-publie apk-depublie check test abuse evaluate diagrams check-docs check-stack mobile-mock"

## ---- pile ------------------------------------------------------------------
up:
	@bash infra/gen-secrets.sh
	@$(COMPOSE) up -d --build --wait   # --build : jamais tourner sur une image perimee (cache Docker => quasi gratuit si rien n'a change)

down:
	@$(COMPOSE) down

# Carte #68 (L7-2) : une fois l'APK publie (sentinelle /certs/APK-PUBLIE dans le volume isc-certs),
# reset REFUSE : down -v detruirait l'autorite de certification epinglee par tous les telephones.
# Le garde-fou est une commande SEULE ; s'il sort non nul, make s'arrete AVANT toute suppression.
reset:
	@bash tools/reset_guard.sh
	@$(COMPOSE) down -v

# Reinitialise la BASE seule (volume isc-pgdata) : l'autorite (isc-certs) et l'APK publie survivent.
# Sert aux changements de schema apres publication. PRECONDITION inchangee : exporter la chaine
# d'audit et la verifier intacte d'abord (regle du 2026-10-17). R-S : la cible est listee, puis
# supprimee par une commande seule, sur une ligne a part.
reset-db:
	@$(COMPOSE) down
	@docker volume ls --filter name=isc-pgdata
	@docker volume rm isc-pgdata

# Pose la sentinelle au moment de publier l'APK (acte de la proprietaire) : date, version de l'APK,
# empreinte SHA-256 de la CA epinglee. APK_VERSION est obligatoire. La cle privee ne sort pas du volume.
apk-publie:
	@test -n "$(APK_VERSION)" || { echo "APK_VERSION manquante : make apk-publie APK_VERSION=<version de l'APK publie>"; exit 2; }
	@MSYS_NO_PATHCONV=1 docker run --rm -v isc-certs:/certs -v "$(CURDIR)/tools/apk_publie.sh:/apk_publie.sh:ro" isc-tools:latest sh /apk_publie.sh "$(APK_VERSION)"

# Retire la sentinelle (acte de la proprietaire, avant de publier un NOUVEL APK qui epinglera une
# autre CA). La cible est listee, puis la suppression est une commande seule (R-S).
apk-depublie:
	@MSYS_NO_PATHCONV=1 docker run --rm -v isc-certs:/certs:ro alpine:latest ls -l /certs/APK-PUBLIE
	@MSYS_NO_PATHCONV=1 docker run --rm -v isc-certs:/certs alpine:latest rm /certs/APK-PUBLIE

check-stack:
	@bash tools/check_stack.sh

## ---- portes legeres (ISC-DEV §4) -------------------------------------------
check: build format unit arch contract mobile check-docs secrets fictitious-msisdn named-params eval-tests
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
	@echo ">>> contract : redocly evaluate.yaml + mobile-api.yaml"
	@npx --yes @redocly/cli@2.14.5 lint contracts/evaluate.yaml contracts/mobile-api.yaml

# Porte mobile (M-05, ADR-037) : lint, tests unitaires (dont le test d'interface Compose sur la JVM)
# et assemblage de l'APK debug. SDK Android cherché dans ANDROID_HOME, ANDROID_SDK_ROOT,
# %LOCALAPPDATA%/Android/Sdk (Windows) puis ~/Android/Sdk. SDK absent : la porte le DIT
# (« SAUTÉE — rien n'a été vérifié ») sans faire échouer make check, comme la console sans tests ;
# ISC_REQUIRE_MOBILE=1 (VM/CI équipée du SDK) transforme cette absence en ÉCHEC.
.PHONY: mobile
mobile:
	@echo ">>> mobile : lint, tests unitaires, APK debug (mobile/)"
	@sdk="$${ANDROID_HOME:-$${ANDROID_SDK_ROOT:-}}"; \
	if [ -z "$$sdk" ] && [ -n "$$LOCALAPPDATA" ] && [ -d "$$LOCALAPPDATA/Android/Sdk" ]; then sdk="$$LOCALAPPDATA/Android/Sdk"; fi; \
	if [ -z "$$sdk" ] && [ -d "$$HOME/Android/Sdk" ]; then sdk="$$HOME/Android/Sdk"; fi; \
	if [ -z "$$sdk" ] || [ ! -d "$$sdk/platforms" ]; then \
	  if [ -n "$(ISC_REQUIRE_MOBILE)" ]; then \
	    echo "mobile : ÉCHEC — SDK Android introuvable (ANDROID_HOME, ANDROID_SDK_ROOT, ~/Android/Sdk) et ISC_REQUIRE_MOBILE=1"; exit 1; \
	  fi; \
	  echo "mobile : SAUTÉE — SDK Android introuvable (ANDROID_HOME, ANDROID_SDK_ROOT, ~/Android/Sdk) : RIEN n'a été vérifié pour mobile/ (installer le SDK, voir mobile/README.md)"; \
	  exit 0; \
	fi; \
	cd mobile && ANDROID_HOME="$$sdk" ./gradlew --quiet lint testDebugUnitTest assembleDebug && echo "mobile : VERT (SDK : $$sdk)"

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

# T3-04b : tests du harnais d'evaluation AC-02, en --dry-run (sans pile, sans Docker, n'execute rien).
eval-tests:
	@echo ">>> eval-tests"
	@uv run --quiet --project simulator python -m pytest tests/test_ac02_eval.py -q -p no:cacheprovider

## ---- autres cibles ----------------------------------------------------------
# Faux serveur de l'API mobile (M-03) pour développer l'application : NE VÉRIFIE NI la signature
# NI l'attestation (voir tools/mobile-mock/README.md). HTTPS obligatoire (ADR-038) avec la CA de
# DÉVELOPPEMENT de tools/mobile-mock/.tls/ (git-ignoré), générée par `make mobile-mock-tls`
# (SAN supplémentaires : MOBILE_MOCK_SAN="IP:192.168.1.20"). `MOBILE_MOCK_DEMO=1` expose l'OTP de test.
.PHONY: mobile-mock-tls
mobile-mock-tls:
	@bash tools/mobile-mock/gen-dev-tls.sh $(MOBILE_MOCK_SAN)

mobile-mock:
	@if [ ! -f tools/mobile-mock/.tls/mock.crt ] || [ ! -f tools/mobile-mock/.tls/mock.key ]; then \
	  echo "mobile-mock : certificat HTTPS absent (ADR-038) -- lancer d'abord : make mobile-mock-tls"; exit 1; \
	fi
	@cd tools/mobile-mock && uv run --quiet isc-mobile-mock --port $(MOBILE_MOCK_PORT) \
	  --ruleset ../../config/ruleset.yaml --contract ../../contracts/mobile-api.yaml \
	  --fixtures fixtures.json --cert .tls/mock.crt --key .tls/mock.key \
	  $(if $(MOBILE_MOCK_HOST),--host $(MOBILE_MOCK_HOST),) $(if $(MOBILE_MOCK_DEMO),--demo,)

test: unit

abuse:
	@echo ">>> abuse : tests des cas d'abus T-AC (ENF-09) — exige la pile debout (make up)"
	@echo "    hors pile (ex. poste sans Docker), les T-AC s'ignorent proprement ; ils tournent en VM/CI."
	@# Depuis la RACINE : les tests trouvent l'outil attaquant par os.getcwd()/attacker/src (CI-01).
	@uv run --quiet --project evaluation pytest -q -rs evaluation/tests

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
