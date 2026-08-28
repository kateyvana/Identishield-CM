#!/bin/sh
# SPIKE-4 étape 4 — Q7 (expiration) et Q6 (reprise de session), dans le core.
set -u
CA=/certs/ca.crt

echo "########## Q6 — reprise de session TLS (test propre -sess_out/-sess_in) ##########"
CC="-connect pdp:8443 -servername pdp -CAfile $CA -cert /certs/core.crt -key /certs/core.key"
echo "  1re connexion : $(echo | openssl s_client $CC -sess_out /tmp/sess.pem 2>/dev/null | grep -E '^(New|Reused)' | head -1)"
echo "  2e  connexion : $(echo | openssl s_client $CC -sess_in  /tmp/sess.pem 2>/dev/null | grep -E '^(New|Reused)' | head -1)"

echo
echo "########## Q7 — expiration d'un certificat (le scénario du jour J) ##########"
cd /tmp
cp "$CA" /certs/ca.key /tmp/ 2>/dev/null
mkdir -p cadb/newcerts; : > cadb/index.txt
cat > ca.cnf <<EOF
[ca]
default_ca = d
[d]
dir=/tmp/cadb
new_certs_dir=\$dir/newcerts
database=\$dir/index.txt
serial=\$dir/serial
certificate=/tmp/ca.crt
private_key=/tmp/ca.key
default_md=sha256
policy=pol
copy_extensions=copy
rand_serial=yes
[pol]
commonName=supplied
[v3]
basicConstraints=CA:FALSE
extendedKeyUsage=serverAuth
EOF
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out srv.key 2>/dev/null
openssl req -new -key srv.key -subj /CN=localhost -addext subjectAltName=DNS:localhost -out srv.csr 2>/dev/null
NB=$(date -u +%Y%m%d%H%M%SZ)
NA=$(date -u -d '+12 seconds' +%Y%m%d%H%M%SZ)
openssl ca -batch -config ca.cnf -extensions v3 -startdate "$NB" -enddate "$NA" \
  -in srv.csr -out srv.crt 2>/dev/null
echo "  certificat serveur émis, valide 12 s : $(openssl x509 -in srv.crt -noout -enddate | cut -d= -f2)"
openssl s_server -accept 9443 -cert srv.crt -key srv.key -www -quiet >/dev/null 2>&1 &
SRV=$!
sleep 1
echo "  appel AVANT expiration : http=$(curl -sS -o /dev/null -w '%{http_code}' --cacert /tmp/ca.crt https://localhost:9443/ 2>/dev/null)"
echo "  attente de l'expiration (13 s)..."
sleep 13
echo "  appel APRÈS expiration : $(curl -sS --cacert /tmp/ca.crt https://localhost:9443/ 2>&1 | grep -oiE 'certificate.*expired|curl:.*' | head -1)"
kill "$SRV" 2>/dev/null
rm -f /tmp/ca.key
