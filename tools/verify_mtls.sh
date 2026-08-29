#!/bin/bash
# T2-04 — Rejoue les QUATRE tentatives de SPIKE-4 contre le PDP Spring Boot REEL
# (server.ssl.client-auth=need), et rapporte pour chacune le message brut ET LE
# NIVEAU du refus. Reprend le protocole du spike ; ici le terminateur n'est plus
# nginx/openssl mais le produit.
#
#  - Tentatives 1-3 (cote client, contre le vrai PDP) : sans certificat / autre
#    autorite / certificat expire. Les trois DOIVENT etre refusees au niveau de
#    la POIGNEE DE MAIN. Un refus applicatif (la requete atteint le service)
#    serait OB-5 VIOLEE.
#  - Tentative 4 (le coeur refuse un faux PDP), deux motifs SEPARES (OB-6) :
#    4a faux PDP auto-signe, nom correct -> refus sur la CHAINE/signature ;
#    4b PDP bien signe par la CA mais nom errone -> refus sur le NOM.
#
# Le materiel adverse est genere EPHEMERE ici (jamais dans le volume runtime).
# Necessite la pile debout (pdp) et les images isc-tools / isc-core.
set -uo pipefail
export MSYS_NO_PATHCONV=1
NET=isc-net
VNET=isc-verify
CERTS=isc-certs
fail=0

cleanup() {
  docker rm -f isc-vfake isc-vwrong >/dev/null 2>&1 || true
  docker network rm "$VNET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ------- Tentatives 1-3 : cote client, un seul conteneur outils sur isc-net -------
CLIENT_PROG=$(cat <<'EOF'
set -u
CA=/certs/ca.crt
cd /tmp
# Tentative 2 : autorite differente (CA voyou + client CN=core).
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out rogue.key 2>/dev/null
openssl req -x509 -new -key rogue.key -days 3650 -subj "/CN=Rogue CA" -out rogue.crt 2>/dev/null
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out other.key 2>/dev/null
openssl req -new -key other.key -subj "/CN=core" -out other.csr 2>/dev/null
openssl x509 -req -in other.csr -CA rogue.crt -CAkey rogue.key -CAcreateserial -days 365 -out other.crt 2>/dev/null
# Tentative 3 : client signe par la VRAIE CA mais EXPIRE (openssl ca, dates passees).
mkdir -p cadb/newcerts; : > cadb/index.txt
cat > ca.cnf <<CNF
[ca]
default_ca = d
[d]
dir = /tmp/cadb
new_certs_dir = \$dir/newcerts
database = \$dir/index.txt
serial = \$dir/serial
certificate = /certs/ca.crt
private_key = /certs/ca.key
default_md = sha256
policy = pol
email_in_dn = no
rand_serial = yes
unique_subject = no
[pol]
commonName = supplied
CNF
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out exp.key 2>/dev/null
openssl req -new -key exp.key -subj "/CN=core" -out exp.csr 2>/dev/null
openssl ca -batch -config ca.cnf -startdate 20200101000000Z -enddate 20200301000000Z -in exp.csr -out exp.crt 2>/dev/null

call() {  # $1 = libelle ; reste = arguments curl. Imprime une ligne machine.
  label=$1; shift
  code=$(curl -sS -o /dev/null -w "%{http_code}" "$@" 2>/tmp/err) || true
  msg=$(tr -d '\r' < /tmp/err | grep -iE 'curl|alert|certificate|ssl|handshake|unknown' | head -1)
  niveau=$([ "$code" = "000" ] && echo HANDSHAKE || echo APPLICATIF)
  echo "ATTEMPT|$label|code=$code|niveau=$niveau|msg=$msg"
}
call "1 sans certificat client"        --cacert "$CA" https://pdp:8443/health
call "2 autre autorite"                --cert other.crt --key other.key --cacert "$CA" https://pdp:8443/health
call "3 certificat valide mais expire" --cert exp.crt --key exp.key --cacert "$CA" https://pdp:8443/health
EOF
)

echo "########## Tentatives 1-3 : cote client, contre le PDP Spring Boot reel ##########"
c13=$(docker run --rm --network "$NET" -v "$CERTS":/certs:ro isc-tools sh -c "$CLIENT_PROG" 2>&1)
echo "$c13" | grep '^ATTEMPT' | while IFS='|' read -r _ label code niveau msg; do
  printf "  %-34s %s | %s | %s\n" "$label" "$code" "$niveau" "$msg"
done
n_att=$(echo "$c13" | grep -c '^ATTEMPT')
n_app=$(echo "$c13" | grep '^ATTEMPT' | grep -c 'niveau=APPLICATIF')
if [ "$n_att" -eq 3 ] && [ "$n_app" -eq 0 ]; then
  echo "[OK] tentatives 1-3 refusees au HANDSHAKE (OB-5 tenue : la requete n'atteint jamais le service)"
else
  echo "[ECHEC] OB-5 : $n_app tentative(s) refusee(s) au niveau APPLICATIF (la requete a atteint le service)"; fail=1
fi

# ------- Tentative 4 : le coeur refuse un faux PDP, deux motifs separes -------
FAKE_PROG=$(cat <<'EOF'
cd /tmp
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out f.key 2>/dev/null
openssl req -x509 -new -key f.key -days 3650 -subj "/CN=pdp" -addext "subjectAltName=DNS:pdp" -out f.crt 2>/dev/null
exec openssl s_server -accept 8443 -cert f.crt -key f.key -www -quiet
EOF
)
WRONG_PROG=$(cat <<'EOF'
cd /tmp
cp /certs/ca.crt /certs/ca.key /tmp/
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out w.key 2>/dev/null
openssl req -new -key w.key -subj "/CN=wrongpdp" -out w.csr 2>/dev/null
printf 'subjectAltName=DNS:wrongpdp\n' > w.ext
openssl x509 -req -in w.csr -CA /tmp/ca.crt -CAkey /tmp/ca.key -CAcreateserial -days 365 -extfile w.ext -out w.crt 2>/dev/null
exec openssl s_server -accept 8443 -cert w.crt -key w.key -www -quiet
EOF
)

probe_reason() {  # $1 = nom conteneur ; $2 = programme serveur ; imprime PROBE_REASON=...
  docker network create --internal "$VNET" >/dev/null 2>&1 || true
  docker run -d --name "$1" --network "$VNET" --network-alias pdp -v "$CERTS":/certs:ro isc-tools sh -c "$2" >/dev/null
  sleep 3
  out=$(docker run --rm --network "$VNET" -e CORE_MODE=probe -e CORE_TARGET=https://pdp:8443/health \
        -v "$CERTS":/certs:ro isc-core java -jar /app/app.jar 2>&1)
  docker rm -f "$1" >/dev/null 2>&1
  echo "$out" | grep -E '^PROBE_(REASON|MESSAGE)='
}

echo "########## Tentative 4 : POURQUOI le coeur refuse un faux PDP (OB-6) ##########"
echo "--- 4a : faux PDP auto-signe, nom=pdp (seul motif possible : SIGNATURE) ---"
p4a=$(probe_reason isc-vfake "$FAKE_PROG"); echo "$p4a" | sed 's/^/  /'
r4a=$(echo "$p4a" | sed -n 's/^PROBE_REASON=//p')
echo "--- 4b : PDP bien signe par la CA mais nom=wrongpdp (seul motif : NOM) ---"
p4b=$(probe_reason isc-vwrong "$WRONG_PROG"); echo "$p4b" | sed 's/^/  /'
r4b=$(echo "$p4b" | sed -n 's/^PROBE_REASON=//p')

if [ "$r4a" = "CHAIN_OR_SIGNATURE" ]; then
  echo "[OK] 4a refusee au HANDSHAKE sur la CHAINE/signature"
else echo "[ECHEC] 4a motif=$r4a (attendu CHAIN_OR_SIGNATURE)"; fail=1; fi
if [ "$r4b" = "NAME_MISMATCH" ]; then
  echo "[OK] 4b refusee au HANDSHAKE sur le NOM (la chaine etait pourtant valide)"
else echo "[ECHEC] 4b motif=$r4b (attendu NAME_MISMATCH)"; fail=1; fi

[ $fail -eq 0 ] && echo "verify-mtls: VERT" || echo "verify-mtls: ROUGE"
exit $fail
