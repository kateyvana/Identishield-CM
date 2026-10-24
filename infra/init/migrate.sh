#!/bin/bash
# Applique les migrations du schéma (infra/init/migrations/NNN-*.sql) à une base EXISTANTE, dans
# l'ordre de leur nom, par psql dans le conteneur PostgreSQL, en tant que propriétaire des schémas.
# Chaque migration est rejouable (README du dossier) : relancer est sans effet.
# Sert à `make migrate` et à la preuve tools/verify_migrations.sh (même code, deux usages).
#
# Usage : bash infra/init/migrate.sh [conteneur] [dossier des migrations]
set -euo pipefail
export MSYS_NO_PATHCONV=1
CONTAINER="${1:-isc-postgres}"
DIR="${2:-infra/init/migrations}"

n=0
for f in "$DIR"/[0-9][0-9][0-9]-*.sql; do
  [ -e "$f" ] || continue
  echo ">>> migration $(basename "$f")"
  docker exec -i "$CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <"$f"
  n=$((n + 1))
done
echo "migrations appliquées : $n ($DIR, conteneur $CONTAINER)"
