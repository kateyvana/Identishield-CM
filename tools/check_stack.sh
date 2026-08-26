#!/bin/bash
# Porte check-stack (TACHE-00 tache 4) : sante, schemas, ED-06, isolation reseau.
# Portable Git Bash / Ubuntu : MSYS_NO_PATHCONV est ignore hors MSYS.
set -uo pipefail
COMPOSE="docker compose -f infra/docker-compose.yml"
export MSYS_NO_PATHCONV=1
fail=0

h=$(docker inspect --format '{{.State.Health.Status}}' isc-postgres 2>/dev/null)
if [ "$h" = "healthy" ]; then echo "[OK] postgres en bonne sante"; else echo "[ECHEC] postgres: ${h:-absent}"; fail=1; fi

s=$($COMPOSE exec -T postgres psql -U isc_admin -d identishield -tAc \
  "SELECT count(*) FROM pg_namespace WHERE nspname IN ('core','pdp');" 2>/dev/null | tr -d '[:space:]')
if [ "$s" = "2" ]; then echo "[OK] schemas core et pdp presents"; else echo "[ECHEC] schemas presents: $s/2"; fail=1; fi

# ED-06 : SELECT+INSERT accordes, UPDATE/DELETE/TRUNCATE refuses
p=$($COMPOSE exec -T postgres psql -U isc_admin -d identishield -tAc \
  "SELECT string_agg(priv || '=' || has_table_privilege('isc_pdp_app','pdp.audit_entry',priv)::text, ',' ORDER BY priv)
   FROM unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE']) AS priv;" 2>/dev/null | tr -d '[:space:]')
expected="DELETE=false,INSERT=true,SELECT=true,TRUNCATE=false,UPDATE=false"
if [ "$p" = "$expected" ]; then echo "[OK] role d'audit restreint (ED-06): $p"; else echo "[ECHEC] privileges isc_pdp_app: $p"; fail=1; fi

# CT-02 : aucun acces sortant depuis isc-net - une resolution DNS externe doit echouer
if docker run --rm --network isc-net postgres:16 timeout 8 getent hosts deb.debian.org >/dev/null 2>&1; then
  echo "[ECHEC] isc-net resout un nom externe : le reseau n'est pas isole"; fail=1
else
  echo "[OK] isc-net sans acces sortant (resolution DNS externe en echec, attendu)"
fi

[ $fail -eq 0 ] && echo "check-stack: VERT" || echo "check-stack: ROUGE"
exit $fail
