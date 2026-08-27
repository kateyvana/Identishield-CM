#!/usr/bin/env bash
# SPIKE-3 étape 2 — les six altérations (rôle administrateur) + preuve COPY.
# Chacune : reset d'une chaîne de 20, altération, vérification, détection+position.
set -uo pipefail
cd "$(dirname "$0")/.."

export POSTGRES_PASSWORD=${POSTGRES_PASSWORD:?}
APP_PW="${APP_PW:?}"
DC="docker compose"
admin() { $DC exec -T db psql -v ON_ERROR_STOP=1 -U postgres -d spike3 "$@"; }
adminq() { $DC exec -T db psql -U postgres -d spike3 "$@"; }   # no stop, capture errors
appsql() { $DC exec -T -e PGPASSWORD="$APP_PW" db psql -h 127.0.0.1 -U isc_pdp_app -d spike3 "$@"; }

reset() { admin < sql/reset-chain.sql >/dev/null; }
verify() { admin < sql/verify.sql; }

echo "############ PREUVE demandée : COPY FROM STDIN (rôle applicatif) ############"
echo "But : fournir un prev_hash et un hash FAUX et prouver que le trigger les écrase."
reset
echo '"{""forged"":1}",DEADBEEFbadprev,DEADBEEFbadhash,2026-06-01T12:00:00Z' \
  | appsql -c "\copy audit.entry(payload,prev_hash,hash,written_at) FROM STDIN WITH (FORMAT csv)"
echo "--- la ligne insérée par COPY (seq 21) : valeurs réelles vs fournies ---"
adminq -c "SELECT seq, payload, prev_hash, hash FROM audit.entry WHERE seq = 21;"
echo "--- vérification : la chaîne reste-t-elle valide après ce COPY ? ---"
verify

for CASE in 1 2 3 4 5 6; do
  echo
  echo "############ ALTÉRATION $CASE ############"
  reset
  case $CASE in
    1) echo "Modifier le contenu d'un enregistrement du milieu (seq 10, payload)"
       admin -c "UPDATE audit.entry SET payload = '{\"decision\":\"FRAUD\"}' WHERE seq = 10;" ;;
    2) echo "Modifier l'empreinte d'un enregistrement (seq 10, hash)"
       admin -c "UPDATE audit.entry SET hash = repeat('a',64) WHERE seq = 10;" ;;
    3) echo "Supprimer un enregistrement du milieu (seq 10)"
       admin -c "DELETE FROM audit.entry WHERE seq = 10;" ;;
    4) echo "Supprimer le DERNIER enregistrement (seq 20) — LE CAS DIFFICILE"
       admin -c "DELETE FROM audit.entry WHERE seq = 20;" ;;
    5) echo "Insérer un enregistrement forgé se réclamant du milieu"
       echo "(trigger désactivé par l'admin ; prev_hash pointant vers seq 10)"
       admin -c "ALTER TABLE audit.entry DISABLE TRIGGER chain_before_insert;" >/dev/null
       admin -c "INSERT INTO audit.entry(seq,payload,prev_hash,hash,written_at)
                 OVERRIDING SYSTEM VALUE
                 SELECT 21, '{\"decision\":\"FORGED\"}',
                        (SELECT hash FROM audit.entry WHERE seq=10),
                        encode(digest('forged','sha256'),'hex'),
                        '2026-01-01T08:00:10.5Z';" >/dev/null
       admin -c "ALTER TABLE audit.entry ENABLE TRIGGER chain_before_insert;" >/dev/null ;;
    6) echo "Échanger deux enregistrements adjacents (payloads seq 10 et 11)"
       admin -c "ALTER TABLE audit.entry DISABLE TRIGGER chain_before_insert;" >/dev/null
       admin -c "UPDATE audit.entry a SET payload = b.payload
                 FROM audit.entry b
                 WHERE a.seq IN (10,11) AND b.seq IN (10,11) AND a.seq <> b.seq;" >/dev/null
       admin -c "ALTER TABLE audit.entry ENABLE TRIGGER chain_before_insert;" >/dev/null ;;
  esac
  verify
done

echo
echo "############ CONSTAT structurel : insertion 'entre deux' par seq ############"
reset
echo "-- a) seq dupliqué => violation de clé primaire"
adminq -c "INSERT INTO audit.entry(seq,payload,prev_hash,hash,written_at) OVERRIDING SYSTEM VALUE VALUES (10,'{}','x','y','2026-01-01T08:00:10Z');" 2>&1
echo "-- b) renuméroter (UPDATE seq) => interdit car GENERATED ALWAYS"
adminq -c "UPDATE audit.entry SET seq = seq + 1 WHERE seq >= 11;" 2>&1
