#!/bin/bash
# Applique init.sql en injectant les mots de passe des roles applicatifs
# depuis l'environnement (.env genere par gen-secrets.sh, jamais versionne).
set -euo pipefail
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v core_pw="$ISC_CORE_APP_PASSWORD" \
  -v pdp_pw="$ISC_PDP_APP_PASSWORD" \
  -f /opt/isc/init.sql
