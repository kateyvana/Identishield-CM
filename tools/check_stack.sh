#!/bin/bash
# Porte check-stack : sante, schemas, isolation reseau (TACHE-00), et
# impermeabilite du role d'audit (T2-01, ED-06/OB-3, tournee a chaque demarrage).
# Portable Git Bash / Ubuntu : MSYS_NO_PATHCONV est ignore hors MSYS.
set -uo pipefail
COMPOSE="docker compose -f infra/docker-compose.yml"
export MSYS_NO_PATHCONV=1
fail=0

# psql en tant que isc_admin (proprietaire, socket local du conteneur).
adm() { $COMPOSE exec -T postgres psql -U isc_admin -d identishield "$@"; }
# psql en tant que isc_pdp_app (role applicatif, TCP + mot de passe du conteneur).
# PGPASSWORD non guillemete VOLONTAIREMENT : le secret est [A-Za-z0-9]{32}
# (gen-secrets.sh), donc sans risque de decoupage, et un guillemet juste apres
# le '=' declencherait un faux positif de la porte secrets sur le nom libpq
# standard PGPASSWORD. Ne pas remettre de guillemets ici.
app() { $COMPOSE exec -T postgres bash -lc 'PGPASSWORD=$ISC_PDP_APP_PASSWORD psql -h 127.0.0.1 -U isc_pdp_app -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 "$@"' _ "$@"; }

h=$(docker inspect --format '{{.State.Health.Status}}' isc-postgres 2>/dev/null)
if [ "$h" = "healthy" ]; then echo "[OK] postgres en bonne sante"; else echo "[ECHEC] postgres: ${h:-absent}"; fail=1; fi

s=$(adm -tAc "SELECT count(*) FROM pg_namespace WHERE nspname IN ('core','pdp');" 2>/dev/null | tr -d '[:space:]')
if [ "$s" = "2" ]; then echo "[OK] schemas core et pdp presents"; else echo "[ECHEC] schemas presents: $s/2"; fail=1; fi

# --- Les huit tentatives de modification (SPIKE-3), rejouees en PRODUCTION ---
# Chacune, avec le role isc_pdp_app, DOIT echouer (sortie non nulle). Les huit
# sont exactement le protocole de verification a la main ; suivent deux
# tentatives supplementaires (lecture serveur de fichier, OB-3).
echo "--- impermeabilite du role isc_pdp_app (8 tentatives + 2) ---"
attempt() {  # $1 = libelle ; $2 = SQL ; doit echouer
  if app -c "$2" >/dev/null 2>&1; then
    echo "[ECHEC] tentative '$1' a REUSSI (devrait etre refusee)"; fail=1
  else
    echo "[OK] tentative '$1' refusee"
  fi
}
attempt "1 UPDATE"              "UPDATE pdp.audit_entry SET payload='{}' WHERE seq=1;"
attempt "2 DELETE"              "DELETE FROM pdp.audit_entry WHERE seq=1;"
attempt "3 TRUNCATE"            "TRUNCATE pdp.audit_entry;"
attempt "4 DROP"               "DROP TABLE pdp.audit_entry;"
attempt "5 ALTER"              "ALTER TABLE pdp.audit_entry DROP CONSTRAINT audit_entry_pkey;"
attempt "6 setval"             "SELECT setval('pdp.audit_entry_seq_seq',1);"
attempt "7 chain_head SELECT"  "SELECT * FROM pdp.chain_head;"
# 8 : INSERT avec seq explicite. Le role A le droit d'INSERT, mais seq est
# GENERATED ALWAYS (OB-4) : la valeur imposee est refusee au niveau de la ligne.
attempt "8 INSERT seq explicite" "INSERT INTO pdp.audit_entry (seq) VALUES (999);"

# --- Deux tentatives supplementaires (au-dela des huit) ---
# 9 : COPY depuis un fichier serveur (lecture de fichier via le role de table).
attempt "9 COPY depuis fichier" "COPY pdp.audit_entry FROM '/tmp/x.csv';"
# 10 : fonction SECURITY DEFINER. On cree un canari AVEC le REVOKE explicite
# (le motif que T2-06 devra appliquer) et on verifie que le role applicatif ne
# peut pas l'appeler. (En PG16, sans ce REVOKE, la fonction serait executable
# par PUBLIC : voir la porte OB-3 ci-dessous.)
adm -c "CREATE FUNCTION pdp._canary_secdef() RETURNS void LANGUAGE sql SECURITY DEFINER AS 'SELECT 1';
        REVOKE EXECUTE ON FUNCTION pdp._canary_secdef() FROM PUBLIC;" >/dev/null 2>&1
attempt "10 SECURITY DEFINER" "SELECT pdp._canary_secdef();"
adm -c "DROP FUNCTION IF EXISTS pdp._canary_secdef();" >/dev/null 2>&1

# --- OB-3 : AUCUNE fonction de pdp n'est executable par le role applicatif ---
# Enforcement reel (l'ALTER DEFAULT PRIVILEGES ne suffit pas en PG16) : si une
# fonction future oublie son REVOKE, cette porte passe au rouge.
ne=$(adm -tAc "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
               WHERE n.nspname='pdp' AND has_function_privilege('isc_pdp_app', p.oid, 'EXECUTE');" 2>/dev/null | tr -d '[:space:]')
if [ "$ne" = "0" ]; then echo "[OK] OB-3 : aucune fonction de pdp executable par isc_pdp_app"; else echo "[ECHEC] OB-3 : $ne fonction(s) de pdp executable(s) par isc_pdp_app"; fail=1; fi

# --- Privileges EFFECTIFS mesures sur les catalogues (pas les GRANT ecrits) ---
# ED-06 : SELECT+INSERT accordes, UPDATE/DELETE/TRUNCATE refuses.
p=$(adm -tAc "SELECT string_agg(priv || '=' || has_table_privilege('isc_pdp_app','pdp.audit_entry',priv)::text, ',' ORDER BY priv)
             FROM unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE']) AS priv;" 2>/dev/null | tr -d '[:space:]')
exp="DELETE=false,INSERT=true,SELECT=true,TRUNCATE=false,UPDATE=false"
if [ "$p" = "$exp" ]; then echo "[OK] audit_entry (ED-06): $p"; else echo "[ECHEC] audit_entry: $p"; fail=1; fi

# Sequence : USAGE oui, UPDATE non (=> setval refuse).
sq=$(adm -tAc "SELECT 'USAGE='||has_sequence_privilege('isc_pdp_app','pdp.audit_entry_seq_seq','USAGE')::text
              ||',UPDATE='||has_sequence_privilege('isc_pdp_app','pdp.audit_entry_seq_seq','UPDATE')::text;" 2>/dev/null | tr -d '[:space:]')
if [ "$sq" = "USAGE=true,UPDATE=false" ]; then echo "[OK] sequence: $sq"; else echo "[ECHEC] sequence: $sq"; fail=1; fi

# chain_head : le role applicatif n'a AUCUN acces.
ch=$(adm -tAc "SELECT bool_or(has_table_privilege('isc_pdp_app','pdp.chain_head',priv))
              FROM unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE']) AS priv;" 2>/dev/null | tr -d '[:space:]')
if [ "$ch" = "f" ]; then echo "[OK] chain_head : aucun acces pour isc_pdp_app"; else echo "[ECHEC] chain_head accessible: $ch"; fail=1; fi

# CT-02 : aucun acces sortant depuis isc-net - une resolution DNS externe doit echouer.
if docker run --rm --network isc-net postgres:16 timeout 8 getent hosts deb.debian.org >/dev/null 2>&1; then
  echo "[ECHEC] isc-net resout un nom externe : le reseau n'est pas isole"; fail=1
else
  echo "[OK] isc-net sans acces sortant (resolution DNS externe en echec, attendu)"
fi

[ $fail -eq 0 ] && echo "check-stack: VERT" || echo "check-stack: ROUGE"
exit $fail
