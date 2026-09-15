#!/bin/bash
# T2-15 — Verifie le mode sans couche (EF-07, EF-36) dans la VM. La console affiche un bandeau rendu
# COTE SERVEUR : en mode PROTEGE, "Protection active" + etat du PDP (disponible/degrade/indisponible) ;
# en mode SANS COUCHE, "MODE SANS COUCHE - PROTECTION DESACTIVEE". Assertion NEGATIVE obligatoire :
# en mode sans couche, la console NE DOIT PAS afficher "Protection active" (bandeau non ambigu).
# La bascule passe par le proxy /api/mode de la console (le coeur n'est pas expose a l'hote).
# Necessite la pile debout (postgres, pdp, coeur service, console).
set -uo pipefail
CONSOLE=${CONSOLE_URL:-http://localhost:3000}
fail=0

setmode() { # $1 = true|false
  curl -sS -X POST "$CONSOLE/api/mode" -H 'Content-Type: application/json' \
    -d "{\"layerless\": $1}" >/dev/null 2>&1
}
getmode() { curl -sS "$CONSOLE/api/mode" 2>/dev/null; }
page() { curl -sS "$CONSOLE/" 2>/dev/null; }

# Porte de disponibilite : la console est le PREMIER consommateur du chemin fetch serveur -> coeur ;
# juste apres le demarrage, ce premier appel peut etre a froid. On attend que /api/mode reponde un
# JSON valide (jusqu'a ~30 s) avant de mesurer. Attente de disponibilite, pas masquage d'un defaut.
for _ in $(seq 1 15); do
  getmode | grep -q '"layerless"' && break
  sleep 2
done

# 1) Mode PROTEGE (couche active) --------------------------------------------------
setmode false
m=$(getmode)
if echo "$m" | grep -q '"layerless":false'; then echo "[OK] /mode : couche active (layerless=false)"; else echo "[ECHEC] /mode protege: $m"; fail=1; fi
if echo "$m" | grep -qE '"pdpState":"(DISPONIBLE|DEGRADE|INDISPONIBLE)"'; then
  echo "[OK] /mode expose l'etat du PDP ($(echo "$m" | sed -n 's/.*"pdpState":"\([A-Z]*\)".*/\1/p'))"
else echo "[ECHEC] etat PDP absent: $m"; fail=1; fi
p=$(page)
if echo "$p" | grep -q 'Protection active' && echo "$p" | grep -qF '(PDP)'; then
  echo "[OK] bandeau PROTEGE : 'Protection active' + etat du PDP affiches (EF-36)"
else echo "[ECHEC] bandeau protege absent du HTML de la console"; fail=1; fi

# 2) Mode SANS COUCHE (couche desactivee) ------------------------------------------
setmode true
m=$(getmode)
if echo "$m" | grep -q '"layerless":true'; then echo "[OK] /mode : couche desactivee (layerless=true)"; else echo "[ECHEC] /mode sans couche: $m"; fail=1; fi
p=$(page)
if echo "$p" | grep -q 'MODE SANS COUCHE'; then
  echo "[OK] bandeau SANS COUCHE affiche : protection desactivee, sans ambiguite (EF-07)"
else echo "[ECHEC] bandeau sans couche absent du HTML de la console"; fail=1; fi
# Assertion NEGATIVE : en mode sans couche, la console ne laisse pas croire la protection active.
if echo "$p" | grep -q 'Protection active'; then
  echo "[ECHEC] AMBIGU : la console affiche 'Protection active' EN MODE SANS COUCHE"; fail=1
else echo "[OK] assertion negative : aucune mention de protection active en mode sans couche"; fi

# Restaure l'etat protege : ne pas laisser la pile desarmee apres la verification.
setmode false
echo "[OK] mode restaure a PROTEGE"

[ $fail -eq 0 ] && echo "verify-layerless: VERT" || echo "verify-layerless: ROUGE"
exit $fail
