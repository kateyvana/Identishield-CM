#!/usr/bin/env bash
# Banc de latence de /evaluate : compare deux commits, en alternance, DANS la VM (méthode et
# réserves : tools/bench_evaluate/README.md).
#
# 1. Extrait pdp, config et data/eval de chaque commit (git archive), y copie le banc, compile et
#    exporte le classpath de test (Gradle, sur le poste).
# 2. Assemble un paquet autonome par commit et l'envoie dans la VM.
# 3. Lance une base JETABLE isc-bench-pg (même image, même init.sql, mêmes rôles que la pile),
#    liée à 127.0.0.1 dans la VM seulement.
# 4. Exécute le banc dans un conteneur JDK 21 en réseau hôte, donc à côté de la base : deux tours,
#    commits alternés. Le JDK (et non le JRE) est requis par Mockito (inline mock maker).
# 5. Rapatrie les résultats bruts et imprime les statistiques.
# Mots de passe : aléatoires, éphémères, jamais écrits sur disque ni dans le dépôt.
# Rien n'est supprimé ici (CLAUDE.md §4, commandes destructrices) : les commandes de retrait sont
# affichées à la fin.
#
# Durée de la phase à 50 req/s : ISC_BENCH_SQ30_SECONDS, 600 s par défaut (les dix minutes de
# SQ-30) ; les passages du 21 au 23/10 ont tourné avec 12 s.
#
# Usage (racine du dépôt, poste) :
#   bash tools/bench_evaluate/run.sh <dossier de résultats> [commit A] [commit B] [isc@<vm>]
set -euo pipefail

OUT="${1:?dossier de résultats}"
A="${2:-c4a1167}"
B="${3:-ab44b64}"
VM="${4:-isc@100.93.166.19}"
HERE="$(pwd)/tools/bench_evaluate"
WEB=pdp/src/test/java/cm/identishield/pdp/web
NAME=isc-bench-pg
PORT=55433
DIR=/tmp/isc-bench
WORK="$(mktemp -d)"
mkdir -p "$OUT"

for c in "$A" "$B"; do
  echo ">>> $c : extraction, compilation, classpath"
  mkdir -p "$WORK/$c"
  git archive "$c" pdp config data/eval | tar -x -C "$WORK/$c"
  cp "$HERE/EvaluateLatencyBench.java" "$HERE/BenchMain.java" "$WORK/$c/$WEB/"
  (cd "$WORK/$c/pdp" && ./gradlew --quiet -I "$HERE/export-cp.gradle" printBenchCp)
done
python "$HERE/bundle.py" "$WORK" "$A" "$B"

echo ">>> envoi des paquets dans la VM ($VM:$DIR)"
git show "$B:infra/init/init.sql" >"$WORK/init.sql"
git show "$B:infra/init/01-apply.sh" >"$WORK/01-apply.sh"
ssh "$VM" "mkdir -p $DIR"
tar -C "$WORK" -cf - vm init.sql 01-apply.sh | ssh "$VM" "tar -xf - -C $DIR"

admin_pw=$(openssl rand -hex 16)
core_pw=$(openssl rand -hex 16)
pdp_pw=$(openssl rand -hex 16)

echo ">>> base jetable $NAME dans la VM"
ssh "$VM" "docker run -d --name $NAME \
  -e POSTGRES_USER=isc_admin -e POSTGRES_DB=identishield \
  -e POSTGRES_PASSWORD=$admin_pw -e ISC_CORE_APP_PASSWORD=$core_pw -e ISC_PDP_APP_PASSWORD=$pdp_pw \
  -v $DIR/init.sql:/opt/isc/init.sql:ro -v $DIR/01-apply.sh:/docker-entrypoint-initdb.d/01-apply.sh:ro \
  -p 127.0.0.1:$PORT:5432 postgres:16" >/dev/null
for i in $(seq 1 60); do
  if ssh "$VM" "docker logs $NAME 2>&1 | grep -q 'init process complete' \
      && docker exec $NAME pg_isready -q -h 127.0.0.1 -U isc_admin -d identishield"; then
    break
  fi
  sleep 2
done

for round in 1 2; do
  for c in "$A" "$B"; do
    echo ">>> VM, tour $round : $c"
    ssh "$VM" "docker run --rm --network host -v $DIR/vm:/b -w /b/$c/pdp \
      -e ISC_TEST_PG_URL=jdbc:postgresql://127.0.0.1:$PORT/identishield \
      -e ISC_TEST_PG_USER=isc_pdp_app -e ISC_TEST_PG_PASSWORD=$pdp_pw \
      -e ISC_BENCH_OUT=/b/result-$c-$round.csv -e ISC_BENCH_TAG=$c-$round \
      -e ISC_BENCH_SQ30_SECONDS=${ISC_BENCH_SQ30_SECONDS:-600} \
      eclipse-temurin:21-jdk-jammy \
      sh -c 'java -cp \"\$(cat cp.txt)\" cm.identishield.pdp.web.BenchMain' 2>&1 \
      | grep -E 'Exception|ERROR' || true"
  done
done

scp -q "$VM:$DIR/vm/result-*.csv" "$OUT/"
python "$HERE/stats.py" "$OUT"
echo "Retrait de la base jetable (à faire à la main) : ssh $VM docker rm -f $NAME"
echo "Paquets dans la VM : $DIR ; arbres extraits sur le poste : $WORK"
