#!/bin/sh
# SPIKE-4 étapes 2 et 3 — exécuté DANS le conteneur `core`, sur le réseau interne.
# Pour chaque appel : message brut + NIVEAU du refus (poignée de main TLS vs
# couche applicative). Règle : http_code=000 => échec avant HTTP = niveau TLS ;
# http_code 2xx/4xx => la requête a atteint le service = niveau applicatif.
set -u
CA=/certs/ca.crt

call() {  # $1 = label ; reste = arguments curl
  label=$1; shift
  code=$(curl -sS -o /tmp/body -w '%{http_code}' "$@" 2>/tmp/err) || true
  msg=$(tr -d '\r' < /tmp/err | grep -iE 'curl|alert|verify|certificate|handshake|SSL|subject|host name' | head -1)
  if [ "$code" = "000" ]; then niveau="POIGNÉE DE MAIN TLS (la requête n'a jamais atteint le service)";
  else niveau="COUCHE APPLICATIVE (http_code=$code — la requête a atteint le service)"; fi
  echo "----- $label -----"
  echo "  http_code = $code"
  echo "  message   = ${msg:-<aucun>}"
  echo "  niveau    = $niveau"
  if grep -q '<center>' /tmp/body 2>/dev/null; then
    vue=$(tr -d '\n' < /tmp/body | grep -oE '<center>[^<]*</center>' | head -1 | sed 's/<[^>]*>//g')
  else
    vue=$(head -c 120 /tmp/body 2>/dev/null)
  fi
  [ -n "$vue" ] && echo "  corps/vue = $vue"
  echo
}

FAKE_IP=$(getent hosts pdp-fake  | awk '{print $1}')
WRONG_IP=$(getent hosts pdp-wrong | awk '{print $1}')

echo "########## ÉTAPE 2 — les quatre refus ##########"
call "1. appel au PDP SANS certificat client" \
     --cacert "$CA" https://pdp:8443/
call "2. certificat client signé par une AUTRE autorité" \
     --cert /certs/otherclient.crt --key /certs/otherclient.key --cacert "$CA" https://pdp:8443/
call "3. certificat client valide mais EXPIRÉ" \
     --cert /certs/expiredclient.crt --key /certs/expiredclient.key --cacert "$CA" https://pdp:8443/
echo "=== tentative 4 : POURQUOI le coeur refuse un faux PDP — deux motifs distincts ==="
call "4a. faux PDP auto-signé, NOM correct (pdp) -> seul motif possible : SIGNATURE non émise par la CA" \
     --resolve pdp:8443:"$FAKE_IP" --cert /certs/core.crt --key /certs/core.key --cacert "$CA" https://pdp:8443/
call "4b. serveur BIEN signé par la CA mais NOM = wrongpdp, attendu pdp -> seul motif : NOM (SAN) ne correspond pas" \
     --resolve pdp:8443:"$WRONG_IP" --cert /certs/core.crt --key /certs/core.key --cacert "$CA" https://pdp:8443/

echo "=== contraste : les MÊMES tentatives 1-3 contre un terminateur STRICT ==="
echo "    (openssl s_server -Verify, comportement du PDP Spring Boot client-auth=need)"
call "1s. sans certificat client -> terminateur strict" \
     --cacert "$CA" https://pdp-strict:8443/
call "2s. autre autorité -> terminateur strict" \
     --cert /certs/otherclient.crt --key /certs/otherclient.key --cacert "$CA" https://pdp-strict:8443/
call "3s. certificat expiré -> terminateur strict" \
     --cert /certs/expiredclient.crt --key /certs/expiredclient.key --cacert "$CA" https://pdp-strict:8443/

echo "########## ÉTAPE 3 — l'acceptation, dans les deux sens ##########"
call "5. appel légitime coeur -> PDP (certs signés par la CA locale)" \
     --cert /certs/core.crt --key /certs/core.key --cacert "$CA" https://pdp:8443/
echo "Sens mutuel prouvé par cet unique appel :"
echo "  - le PDP a authentifié le coeur : le corps ci-dessus porte client=CN=core, verify=SUCCESS"
echo "  - le coeur a authentifié le PDP : --cacert a validé le certificat serveur (sinon échec TLS comme en 4)"
