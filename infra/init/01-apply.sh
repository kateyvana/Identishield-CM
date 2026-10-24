#!/bin/bash
# Applique init.sql en injectant les mots de passe des roles applicatifs
# depuis l'environnement (.env genere par gen-secrets.sh, jamais versionne),
# puis les migrations du schema par apply-migrations.sh (meme script que
# le conteneur isc-migrate utilise sur une base existante : une base neuve
# recoit ainsi le MEME schema qu'une base migree, infra/init/migrations/README.md).
# N'est execute qu'a la creation d'une base NEUVE (docker-entrypoint-initdb.d).
set -euo pipefail
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v core_pw="$ISC_CORE_APP_PASSWORD" \
  -v pdp_pw="$ISC_PDP_APP_PASSWORD" \
  -f /opt/isc/init.sql
bash /opt/isc/apply-migrations.sh /opt/isc/migrations
