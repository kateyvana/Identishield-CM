#!/bin/bash
# Porte check-stack : sante, schemas, isolation reseau (TACHE-00), et
# impermeabilite du role d'audit (T2-01, ED-06/OB-3, tournee a chaque demarrage).
# Portable Git Bash / Ubuntu : MSYS_NO_PATHCONV est ignore hors MSYS.
set -uo pipefail
COMPOSE="docker compose -f infra/docker-compose.yml"
export MSYS_NO_PATHCONV=1
fail=0

# --- Garde-fou espace disque (premier, pour une reponse immediate) ---
# CE QU'IL COUVRE : le remplissage du systeme de fichiers qui porte les donnees
# Docker de la machine ou tourne la pile (images, volumes, logs de build). En
# dessous de 10 Go, un build peut le saturer et faire echouer ou suspendre la pile.
# CE QU'IL NE COUVRE PAS : quand la pile tourne dans la VM, ce controle mesure le
# disque INVITE. Le disque HOTE qui porte le .vdi dynamique de la VM n'est PAS
# visible d'ici. La saturation du disque hote -- cause reelle des pauses de VM du
# 15/09/2026, d'abord prises a tort pour un manque de memoire -- ECHAPPE donc a
# cette porte ; la detecter demande un controle cote hote, distinct (hygiene de
# poste, hors du code du projet).
disk_target=/
if df -Pk /var/lib/docker >/dev/null 2>&1; then disk_target=/var/lib/docker; fi
avail_kb=$(df -Pk "$disk_target" 2>/dev/null | awk 'NR==2{print $4}')
avail_gb=$(( ${avail_kb:-0} / 1024 / 1024 ))
if [ "${avail_kb:-0}" -ge $((10 * 1024 * 1024)) ]; then
  echo "[OK] espace disque : ${avail_gb} Go libres sur $disk_target (seuil 10 Go)"
else
  echo "[ECHEC] espace disque : ${avail_gb} Go libres sur $disk_target (< 10 Go requis) -- liberer de l'espace avant de relancer la pile"; fail=1
fi

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

# ADR-032 / T2-10 : historique HLR immuable pour le role applicatif. isc_core_app peut
# SELECT+INSERT sur core.hlr_reassignment mais NI UPDATE NI DELETE (ni TRUNCATE) : l'immutabilite
# de l'historique des reassignations est enforcee en base, pas seulement par discipline applicative.
ph=$(adm -tAc "SELECT string_agg(priv || '=' || has_table_privilege('isc_core_app','core.hlr_reassignment',priv)::text, ',' ORDER BY priv)
              FROM unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE']) AS priv;" 2>/dev/null | tr -d '[:space:]')
exph="DELETE=false,INSERT=true,SELECT=true,TRUNCATE=false,UPDATE=false"
if [ "$ph" = "$exph" ]; then echo "[OK] hlr_reassignment immuable (ADR-032): $ph"; else echo "[ECHEC] hlr_reassignment: $ph (attendu $exph)"; fail=1; fi

# CT-02 : aucun acces sortant depuis isc-net - une resolution DNS externe doit echouer.
if docker run --rm --network isc-net postgres:16 timeout 8 getent hosts deb.debian.org >/dev/null 2>&1; then
  echo "[ECHEC] isc-net resout un nom externe : le reseau n'est pas isole"; fail=1
else
  echo "[OK] isc-net sans acces sortant (resolution DNS externe en echec, attendu)"
fi

# ===================== T2-04 : mTLS coeur<->PDP (OB-5, OB-6, RA-10) =====================
hp=$(docker inspect --format '{{.State.Health.Status}}' isc-pdp 2>/dev/null)
if [ "$hp" = "healthy" ]; then echo "[OK] PDP en bonne sante (terminaison TLS directe)"; else echo "[ECHEC] PDP: ${hp:-absent}"; fail=1; fi

# RA-10 : validite des certificats. Un certificat qui expire dans moins de 30 jours
# fait echouer la porte (prevention de l'incident du jour J : ADR-021 obligation 7).
echo "--- validite des certificats (RA-10, echec si expiration < 30 jours) ---"
cv=$(docker run --rm -v isc-certs:/certs:ro isc-tools sh -c '
  for c in ca pdp core; do
    if openssl x509 -in /certs/$c.crt -noout -checkend 2592000 >/dev/null 2>&1; then st=OK; else st=SOON; fi
    echo "$st|$c|$(openssl x509 -in /certs/$c.crt -noout -enddate | cut -d= -f2)"
  done' 2>/dev/null)
echo "$cv" | while IFS='|' read -r st c end; do echo "  $c.crt : $end [$st]"; done
if echo "$cv" | grep -q '^SOON'; then
  echo "[ECHEC] RA-10 : un certificat expire dans moins de 30 jours"; fail=1
else
  echo "[OK] tous les certificats de service valides > 30 jours"
fi

# Les quatre tentatives de SPIKE-4 rejouees contre le PDP reel (OB-5 handshake ;
# OB-6 chaine ET nom). Deleguees a tools/verify_mtls.sh.
echo "--- mTLS : les quatre tentatives contre le PDP Spring Boot reel ---"
if bash tools/verify_mtls.sh >/tmp/isc_verify_mtls.out 2>&1; then
  grep -E '^\[OK\]|refusee|PROBE_REASON=' /tmp/isc_verify_mtls.out | sed 's/^/  /'
  echo "[OK] mTLS : 4 tentatives refusees au handshake, chaine ET nom verifies (OB-5, OB-6)"
else
  echo "[ECHEC] mTLS : au moins une tentative n'a pas ete refusee correctement"; sed 's/^/  /' /tmp/isc_verify_mtls.out; fail=1
fi

# ===================== T2-05 : POST /evaluate (EF-10/11, ENF-03) =====================
echo "--- /evaluate : idempotence par decisionId + latence ---"
if bash tools/verify_evaluate.sh >/tmp/isc_verify_eval.out 2>&1; then
  grep -E '^\[OK\]|verify-evaluate:' /tmp/isc_verify_eval.out | sed 's/^/  /'
  echo "[OK] /evaluate : ALLOW sans regle, idempotent, une entree d'audit chainee (EF-10/11, ENF-03)"
else
  echo "[ECHEC] /evaluate : voir sortie"; sed 's/^/  /' /tmp/isc_verify_eval.out; fail=1
fi

# ===================== T2-06 : verification du journal d'audit (ES-09) =====================
echo "--- integrite du journal d'audit : detection + localisation (porte #4) ---"
if bash tools/verify_audit.sh >/tmp/isc_verify_audit.out 2>&1; then
  grep -E '^\[OK\]|verify-audit:' /tmp/isc_verify_audit.out | sed 's/^/  /'
  echo "[OK] audit : chaine verifiee, alteration detectee ET localisee, ancre couvre la suppression du dernier (ES-09)"
else
  echo "[ECHEC] audit : voir sortie"; sed 's/^/  /' /tmp/isc_verify_audit.out; fail=1
fi

# ===================== T2-07 : coeur minimal, transfert P2P (EF-04/EF-05) =====================
echo "--- coeur : transfert P2P avec appel /evaluate AVANT commit + fail-closed ---"
if bash tools/verify_transfer.sh >/tmp/isc_verify_transfer.out 2>&1; then
  grep -E '^\[OK\]|verify-transfer:' /tmp/isc_verify_transfer.out | sed 's/^/  /'
  echo "[OK] coeur : transfert EXECUTED apres /evaluate, solde debite, audit ecrit (EF-04/EF-05)"
else
  echo "[ECHEC] coeur/transfert : voir sortie"; sed 's/^/  /' /tmp/isc_verify_transfer.out; fail=1
fi

# ===================== T2-08 : canal USSD (EF-06) =====================
echo "--- USSD : une session traverse le fil et est executee ---"
if bash tools/verify_ussd.sh >/tmp/isc_verify_ussd.out 2>&1; then
  grep -E '^\[OK\]|verify-ussd:' /tmp/isc_verify_ussd.out | sed 's/^/  /'
  echo "[OK] USSD : session -> transfert -> /evaluate -> EXECUTED (EF-06)"
else
  echo "[ECHEC] USSD : voir sortie"; sed 's/^/  /' /tmp/isc_verify_ussd.out; fail=1
fi

[ $fail -eq 0 ] && echo "check-stack: VERT" || echo "check-stack: ROUGE"
exit $fail
