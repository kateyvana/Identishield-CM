#!/usr/bin/env bash
# SPIKE-3 étape 1 — imperméabilité du rôle isc_pdp_app. Huit tentatives de
# modification, chacune doit échouer ; puis les privilèges effectifs.
# Exécuté DANS la VM. Sorties brutes sur stdout.
set -uo pipefail
cd "$(dirname "$0")/.."

export POSTGRES_PASSWORD=${POSTGRES_PASSWORD:?}
APP_PW="${APP_PW:?}"

DC="docker compose"
PSQL_ADMIN="$DC exec -T db psql -v ON_ERROR_STOP=1 -U postgres -d spike3"
# app-role psql: connect over TCP inside the container so password auth applies
appsql() { $DC exec -T -e PGPASSWORD="$APP_PW" db psql -h 127.0.0.1 -U isc_pdp_app -d spike3 "$@"; }

echo "===== bring up postgres ====="
$DC up -d
until $DC exec -T db pg_isready -U postgres -d spike3 >/dev/null 2>&1; do sleep 1; done
$DC exec -T db psql -U postgres -d spike3 -c "SELECT version();" | head -3

echo "===== apply schema (as admin) ====="
$DC exec -T -e PGOPTIONS="" db psql -v ON_ERROR_STOP=1 -v app_pw="$APP_PW" -U postgres -d spike3 < sql/00-schema.sql

echo "===== confirm the app role CAN insert (append is legitimate) ====="
appsql -c "INSERT INTO audit.entry(payload, prev_hash, hash, written_at) VALUES ('{\"decision\":\"ALLOW\",\"actor\":\"pdp\"}','x','x','2026-01-01T09:00:00Z'); SELECT 'INSERT ok, rows=' || count(*) FROM audit.entry;"

run() {  # $1 = label, $2 = SQL
  echo "----- TENTATIVE $1 -----"
  echo "SQL: $2"
  appsql -c "$2" 2>&1 || true
  echo
}

echo "===== les huit tentatives (rôle isc_pdp_app) ====="
run "1 UPDATE"        "UPDATE audit.entry SET payload = '{\"decision\":\"ALLOW\"}' WHERE seq = 3;"
run "2 DELETE"        "DELETE FROM audit.entry WHERE seq = 3;"
run "3 TRUNCATE"      "TRUNCATE audit.entry;"
run "4 DROP TABLE"    "DROP TABLE audit.entry;"
run "5 ALTER TABLE"   "ALTER TABLE audit.entry DROP CONSTRAINT entry_pkey;"
run "6 setval"        "SELECT setval('audit.entry_seq_seq', 1, false);"
run "7 COPY FROM file"  "COPY audit.entry FROM '/tmp/x.csv';"
run "8 SECURITY DEFINER fn" "SELECT audit.admin_tamper(3, '{\"decision\":\"ALLOW\"}');"

echo "===== privilèges EFFECTIFS (catalogues, PUBLIC inclus) ====="
$PSQL_ADMIN < sql/effective-privs.sql
