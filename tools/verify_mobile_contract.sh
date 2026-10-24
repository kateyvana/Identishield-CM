#!/usr/bin/env bash
# B3a (#46) — test de contrat contre le VRAI cœur : les réponses des routes /mobile/v1 du premier
# enrôlement sont validées contre contracts/mobile-api.yaml par le validateur du faux serveur
# (tools/mobile-mock, isc_mobile_mock.schema), jamais par un second validateur.
#
# Vérification LOURDE (procédure manuelle CI-01) : à lancer DANS la VM, depuis la racine du dépôt,
# la pile démarrée avec la surcouche du couloir B (profil demo, comptes de test) :
#   (cd infra && docker compose -f docker-compose.yml -f compose.b3a.yml up -d --build)
#   bash tools/verify_mobile_contract.sh
# Le test tourne dans un conteneur attaché au réseau FERMÉ isc-net (le cœur n'est jamais exposé à
# l'hôte), image python:3.12-alpine, bibliothèque standard seule, tout monté en lecture seule.
#
# REJOUABLE : le test POSE lui-même l'état dont il a besoin. Son compte (le compte « neuf » des
# fixtures, sans appareil lié) est remis à zéro avant chaque passage, comme dans
# verify_attacker.sh : ni enrôlement, ni session, ni OTP, ni clé ni code au PDP, IMSI courant =
# IMSI de démonstration. Deux passages de suite donnent le même résultat. Les erreurs SQL
# arrêtent le script : rien n'est masqué.
set -euo pipefail
cd "$(dirname "$0")/.."

MSISDN=677000200
ACCOUNT="(SELECT id FROM core.account WHERE msisdn = '$MSISDN')"
adm() { docker exec isc-postgres psql -U isc_admin -d identishield -v ON_ERROR_STOP=1 -tAc "$1"; }

if [ "$(adm "SELECT count(*) FROM core.account WHERE msisdn = '$MSISDN' AND pin_hash IS NOT NULL;")" != "1" ]; then
  echo "verify_mobile_contract : compte de test $MSISDN absent ou sans PIN (pile demarree sans compose.b3a.yml ?)" >&2
  exit 2
fi
echo ">>> remise a zero du compte de test $MSISDN"
adm "DELETE FROM core.session WHERE account_id = $ACCOUNT;
     DELETE FROM core.enrolment WHERE account_id = $ACCOUNT;
     DELETE FROM core.otp WHERE msisdn = '$MSISDN';
     DELETE FROM pdp.recovery_code WHERE device_id IN
       (SELECT id FROM pdp.device WHERE account_id = (SELECT id::text FROM core.account WHERE msisdn = '$MSISDN'));
     DELETE FROM pdp.device WHERE account_id = (SELECT id::text FROM core.account WHERE msisdn = '$MSISDN');
     UPDATE core.hlr_subscriber SET current_imsi = '624010$MSISDN', updated_at = now() WHERE msisdn = '$MSISDN';" >/dev/null

docker run --rm --network isc-net \
  -e PYTHONPATH=/mock/src \
  -e CONTRACT_MSISDN="$MSISDN" \
  -v "$PWD/tools/mobile-mock/src:/mock/src:ro" \
  -v "$PWD/contracts:/contracts:ro" \
  -v "$PWD/tools/mobile-mock/fixtures.json:/fixtures/fixtures.json:ro" \
  -v "$PWD/tools/verify_mobile_contract.py:/t/verify_mobile_contract.py:ro" \
  python:3.12-alpine python /t/verify_mobile_contract.py
