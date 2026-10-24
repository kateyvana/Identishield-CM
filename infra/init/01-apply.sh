#!/bin/bash
# Applique init.sql en injectant les mots de passe des roles applicatifs
# depuis l'environnement (.env genere par gen-secrets.sh, jamais versionne),
# puis les migrations du schema (infra/init/migrations/NNN-*.sql), dans l'ordre
# de leur nom : une base NEUVE recoit ainsi le meme schema qu'une base existante
# migree par `make migrate` (infra/init/migrations/README.md).
set -euo pipefail
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v core_pw="$ISC_CORE_APP_PASSWORD" \
  -v pdp_pw="$ISC_PDP_APP_PASSWORD" \
  -f /opt/isc/init.sql
for f in /opt/isc/migrations/[0-9][0-9][0-9]-*.sql; do
  [ -e "$f" ] || continue
  echo "[init] migration $(basename "$f")"
  psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f "$f"
done
