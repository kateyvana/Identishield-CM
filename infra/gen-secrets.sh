#!/bin/bash
# Genere infra/.env avec des mots de passe aleatoires s'il n'existe pas.
# Le fichier .env n'est JAMAIS versionne (.gitignore). ENF-07.
set -euo pipefail
cd "$(dirname "$0")"
if [ -f .env ]; then
    echo "infra/.env existe deja - rien a faire."
    exit 0
fi
rand() { LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 32; }
umask 077
cat > .env << ENV
POSTGRES_USER=isc_admin
POSTGRES_PASSWORD=$(rand)
POSTGRES_DB=identishield
ISC_CORE_APP_PASSWORD=$(rand)
ISC_PDP_APP_PASSWORD=$(rand)
ENV
echo "infra/.env genere."
