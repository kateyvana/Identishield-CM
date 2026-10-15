#!/usr/bin/env bash
# A2 — reproduction de la calibration T3-07 (ADR-016 §4) par le moteur de règles du PDP.
#
# Régénère le jeu légitime gelé (dev horizon v1, ~80 Mo, non versionné) HORS du dépôt, vérifie
# ses trois sha256 contre data/eval/dev_horizon_v1.manifest.json, puis lance T307ReplayTest, qui
# rejoue le plan AC-02 gelé (300 cas) et exige les chiffres exacts d'ADR-016 §4.
#
# Usage (racine du dépôt) : bash tools/verify_t3_07_replay.sh [dossier de travail]
set -euo pipefail

WORK="${1:-$(mktemp -d)}"
H="$WORK/h"
MANIFEST=data/eval/dev_horizon_v1.manifest.json

expected() {
  python -c "import json,sys; print(json.load(open('$MANIFEST', encoding='utf-8'))['horizon_hashes_attendus'][sys.argv[1]]['sha256'])" "$1"
}

check() {
  [ -f "$H.csv" ] &&
    [ "$(sha256sum "$H.csv" | cut -d' ' -f1)" = "$(expected vue_detecteur)" ] &&
    [ "$(sha256sum "$H.labels.csv" | cut -d' ' -f1)" = "$(expected sidecar_labels)" ] &&
    [ "$(sha256sum "$H.hlr_events.csv" | cut -d' ' -f1)" = "$(expected hlr_events)" ]
}

if check; then
  echo ">>> jeu gelé déjà présent dans $WORK (empreintes conformes)"
else
  echo ">>> régénération du dev horizon v1 dans $WORK"
  uv run --quiet --project simulator python -m isc_simulator.generator \
    --seed 20260925 --users 2480 --days 182 --start 2026-10-01 --out "$H.csv"
  check || { echo "ECHEC : empreintes du jeu régénéré différentes du manifeste" >&2; exit 1; }
  echo ">>> empreintes conformes au manifeste"
fi

echo ">>> T307ReplayTest"
(cd pdp && ISC_DEV_HORIZON="$H" ./gradlew --quiet cleanTest test --tests 'cm.identishield.pdp.rules.T307ReplayTest')

# Preuve que le test a TOURNÉ (et non été ignoré faute de jeu), avec ses chiffres.
grep -h -o '<testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  pdp/build/test-results/test/TEST-cm.identishield.pdp.rules.T307ReplayTest.xml
grep -h -o 'T3-07 rejoué[^<]*' pdp/build/test-results/test/TEST-cm.identishield.pdp.rules.T307ReplayTest.xml
echo "verify_t3_07_replay : VERT"
