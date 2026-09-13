#!/bin/bash
# T2-09 — Verifie la console (EF-30, porte ISC-DEV #10 : "la console affiche la
# transaction et sa decision en moins de deux secondes"). Provoque un transfert,
# puis interroge le flux servi par la console (port publie) et verifie que la
# transaction y apparait avec sa decision, sous 2 s. Necessite la pile debout
# (postgres, pdp, coeur service, console).
set -uo pipefail
export MSYS_NO_PATHCONV=1
CONSOLE=${CONSOLE_URL:-http://localhost:3000}
fail=0

# Provoque une transaction (run CLI du coeur, ecrit dans la meme base que le service).
did=$(docker run --rm --network isc-net --env-file infra/.env -e CORE_MODE=transfer -e AMOUNT_XAF=15000 \
      -v isc-certs:/certs:ro -v "$(pwd)/config:/config:ro" isc-core \
      java -Dspring.main.web-application-type=none -Dcore.pdp.timeout-ms=5000 -jar /app/app.jar 2>&1 \
      | sed -n 's/^TRANSFER_DECISION_ID=//p' | tr -d '[:space:]')

# La console interroge le coeur toutes les secondes : la transaction doit apparaitre < 2 s.
found=0
for _ in 1 2 3 4; do
  body=$(curl -sS "$CONSOLE/api/flux" 2>/dev/null || true)
  if echo "$body" | grep -q "$did"; then found=1; break; fi
  sleep 0.5
done

if [ "$found" = "1" ]; then
  echo "[OK] la console affiche la transaction en moins de 2 s (EF-30, porte #10)"
else echo "[ECHEC] transaction $did absente du flux de la console"; fail=1; fi

if echo "$body" | grep -q '"decision":"ALLOW"'; then
  echo "[OK] la console montre la decision (ALLOW) et le decisionId"
else echo "[ECHEC] decision absente du flux de la console"; fail=1; fi

[ $fail -eq 0 ] && echo "verify-console: VERT" || echo "verify-console: ROUGE"
exit $fail
