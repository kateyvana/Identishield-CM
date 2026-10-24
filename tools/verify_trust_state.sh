#!/usr/bin/env bash
# A1 + A3 + A4 — preuve EN BASE REELLE : machine a etats de confiance (pdp.trust_state), application
# du FREEZE (DecisionEnforcer), chaine complete de /evaluate (EvaluateChainDbTest), operations
# differees du coeur (core.transaction).
#
# Lance dans la VM une instance PostgreSQL JETABLE : meme image (postgres:16), meme
# infra/init/init.sql et memes roles que la pile, SANS toucher a la pile en service. Son port
# n'est lie qu'a 127.0.0.1 dans la VM ; un tunnel SSH l'amene sur le poste, ou
# les tests sur base s'executent sous les roles applicatifs isc_pdp_app et isc_core_app.
# Mots de passe : aleatoires, ephemeres, jamais ecrits sur disque ni dans le depot.
# Le conteneur n'est PAS supprime ici (CLAUDE.md §4, commandes destructrices) : la commande de
# retrait est affichee a la fin.
#
# Usage (racine du depot, poste) : bash tools/verify_trust_state.sh [isc@<vm>]
set -euo pipefail

VM="${1:-isc@100.93.166.19}"
NAME=isc-a1-pg
PORT=55432
DIR=/tmp/isc-a1

admin_pw=$(openssl rand -hex 16)
core_pw=$(openssl rand -hex 16)
pdp_pw=$(openssl rand -hex 16)

echo ">>> base jetable $NAME dans la VM ($VM)"
ssh "$VM" "mkdir -p $DIR"
scp -q infra/init/init.sql infra/init/01-apply.sh infra/init/apply-migrations.sh "$VM:$DIR/"
ssh "$VM" "mkdir -p $DIR/migrations"
for f in infra/init/migrations/[0-9][0-9][0-9]-*.sql; do
  [ -e "$f" ] && scp -q "$f" "$VM:$DIR/migrations/"
done
ssh "$VM" "docker run -d --name $NAME \
  -e POSTGRES_USER=isc_admin -e POSTGRES_DB=identishield \
  -e POSTGRES_PASSWORD=$admin_pw -e ISC_CORE_APP_PASSWORD=$core_pw -e ISC_PDP_APP_PASSWORD=$pdp_pw \
  -v $DIR/init.sql:/opt/isc/init.sql:ro -v $DIR/01-apply.sh:/docker-entrypoint-initdb.d/01-apply.sh:ro \
  -v $DIR/apply-migrations.sh:/opt/isc/apply-migrations.sh:ro \
  -v $DIR/migrations:/opt/isc/migrations:ro \
  -p 127.0.0.1:$PORT:5432 postgres:16" >/dev/null

echo ">>> attente de la fin d'init.sql"
for i in $(seq 1 60); do
  if ssh "$VM" "docker logs $NAME 2>&1 | grep -q 'init process complete' \
      && docker exec $NAME pg_isready -q -h 127.0.0.1 -U isc_admin -d identishield"; then
    break
  fi
  if [ "$(ssh "$VM" "docker inspect -f '{{.State.Running}}' $NAME")" != "true" ]; then
    ssh "$VM" "docker logs $NAME 2>&1 | tail -20"
    echo "ECHEC : init.sql n'a pas abouti (conteneur arrete)" >&2
    exit 1
  fi
  sleep 2
done

echo ">>> tunnel 127.0.0.1:$PORT -> VM"
ssh -N -o ExitOnForwardFailure=yes -L "$PORT:127.0.0.1:$PORT" "$VM" &
tunnel=$!
trap 'kill $tunnel' EXIT
sleep 3

URL="jdbc:postgresql://127.0.0.1:$PORT/identishield"
echo ">>> pdp : TrustStateMachineDbTest, DecisionEnforcerDbTest, EvaluateChainDbTest (role isc_pdp_app)"
(cd pdp && ISC_TEST_PG_URL="$URL" ISC_TEST_PG_USER=isc_pdp_app ISC_TEST_PG_PASSWORD="$pdp_pw" \
  ./gradlew --quiet cleanTest test --tests 'cm.identishield.pdp.trust.*' \
  --tests 'cm.identishield.pdp.web.EvaluateChainDbTest')
echo ">>> core : TransactionRepositoryDbTest (role isc_core_app)"
(cd core && ISC_TEST_PG_URL="$URL" ISC_TEST_PG_CORE_USER=isc_core_app \
  ISC_TEST_PG_CORE_PASSWORD="$core_pw" \
  ./gradlew --quiet cleanTest test --tests 'cm.identishield.core.transaction.*')

# Preuve que les tests ont TOURNE (et non ete ignores faute de base).
grep -h -o '<testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  pdp/build/test-results/test/TEST-cm.identishield.pdp.trust.*.xml \
  pdp/build/test-results/test/TEST-cm.identishield.pdp.web.EvaluateChainDbTest.xml \
  core/build/test-results/test/TEST-cm.identishield.core.transaction.*.xml
echo "verify_trust_state : VERT"
echo "Retrait de la base jetable (a faire a la main) : ssh $VM docker rm -f $NAME"
