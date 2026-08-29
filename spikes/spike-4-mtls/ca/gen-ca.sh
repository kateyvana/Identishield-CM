#!/bin/sh
# SPIKE-4 étape 1 — génération automatique de l'autorité et des certificats au
# démarrage de la pile, SANS aucune commande manuelle. Idempotent : si la CA
# existe déjà dans le volume, on la RÉUTILISE (Q9). FORCE=1 régénère.
set -eu
CERTS=/certs
CNF=/ca/openssl-ca.cnf
LEAF_DAYS=365
CA_DAYS=3650

if [ -f "$CERTS/ca.crt" ] && [ "${FORCE:-0}" != "1" ]; then
  echo "[ca-init] $(date -u +%FT%TZ) CA déjà présente dans le volume → RÉUTILISATION (Q9), pas de régénération"
  exit 0
fi

echo "[ca-init] $(date -u +%FT%TZ) génération de l'autorité locale et des certificats"
umask 077
rm -rf "$CERTS"/*  2>/dev/null || true
mkdir -p "$CERTS/cadb/newcerts"
: > "$CERTS/cadb/index.txt"

newkey() { openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1" 2>/dev/null; }

# Root CA (self-signed)
newkey "$CERTS/ca.key"
openssl req -x509 -new -key "$CERTS/ca.key" -days "$CA_DAYS" -subj "/CN=IdentiShield-CM Local CA" \
  -out "$CERTS/ca.crt" 2>/dev/null

# A leaf signed by the real CA, with SAN and serverAuth+clientAuth.
sign_leaf() {  # $1 name  $2 CN  $3 SAN
  newkey "$CERTS/$1.key"
  openssl req -new -key "$CERTS/$1.key" -subj "/CN=$2" -out "$CERTS/$1.csr" 2>/dev/null
  cat > "$CERTS/$1.ext" <<EOF
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectAltName=DNS:$3
EOF
  openssl x509 -req -in "$CERTS/$1.csr" -CA "$CERTS/ca.crt" -CAkey "$CERTS/ca.key" \
    -CAcreateserial -days "$LEAF_DAYS" -extfile "$CERTS/$1.ext" -out "$CERTS/$1.crt" 2>/dev/null
  rm -f "$CERTS/$1.csr" "$CERTS/$1.ext"
}

sign_leaf pdp    pdp        pdp        # PDP server + client identity
sign_leaf core   core       core       # core client identity
sign_leaf strict pdp-strict pdp-strict # strict terminator server cert (own name)

# Tentative 2 : a leaf signed by a DIFFERENT authority.
newkey "$CERTS/other-ca.key"
openssl req -x509 -new -key "$CERTS/other-ca.key" -days "$CA_DAYS" -subj "/CN=Rogue CA" -out "$CERTS/other-ca.crt" 2>/dev/null
newkey "$CERTS/otherclient.key"
openssl req -new -key "$CERTS/otherclient.key" -subj "/CN=core" -out "$CERTS/oc.csr" 2>/dev/null
openssl x509 -req -in "$CERTS/oc.csr" -CA "$CERTS/other-ca.crt" -CAkey "$CERTS/other-ca.key" \
  -CAcreateserial -days "$LEAF_DAYS" -out "$CERTS/otherclient.crt" 2>/dev/null
rm -f "$CERTS/oc.csr"

# Tentative 4a : a fake PDP server cert NOT signed by the real CA (self-signed).
# Same name (pdp) so the ONLY possible failure reason is the untrusted signature.
newkey "$CERTS/fakepdp.key"
openssl req -x509 -new -key "$CERTS/fakepdp.key" -days "$CA_DAYS" -subj "/CN=pdp" \
  -addext "subjectAltName=DNS:pdp" -out "$CERTS/fakepdp.crt" 2>/dev/null

# Tentative 4b : a server cert PROPERLY signed by the real CA but for a DIFFERENT
# name (wrongpdp). Models a legitimate other service impersonating the PDP:
# signature is valid, only the name check can catch it.
sign_leaf wrongname wrongpdp wrongpdp

# Tentative 3 : a client cert signed by the REAL CA but EXPIRED (openssl ca, past dates).
newkey "$CERTS/expiredclient.key"
openssl req -new -key "$CERTS/expiredclient.key" -subj "/CN=core" -out "$CERTS/exp.csr" 2>/dev/null
openssl ca -batch -config "$CNF" -extensions v3_leaf \
  -startdate 20200101000000Z -enddate 20200301000000Z \
  -in "$CERTS/exp.csr" -out "$CERTS/expiredclient.crt" 2>/dev/null
rm -f "$CERTS/exp.csr"

# World-readable certs; keys stay 600 (umask). nginx runs as root here, reads keys.
chmod 644 "$CERTS"/*.crt
echo "[ca-init] $(date -u +%FT%TZ) terminé. Contenu de $CERTS :"
ls -l "$CERTS" | grep -E '\.(crt|key)$'
echo "[ca-init] empreintes :"
echo "  ca.crt      $(openssl x509 -in "$CERTS/ca.crt" -noout -fingerprint -sha256 | cut -d= -f2)"
echo "  pdp.crt     valide jusqu'à $(openssl x509 -in "$CERTS/pdp.crt" -noout -enddate | cut -d= -f2)"
echo "  expiredclient.crt enddate $(openssl x509 -in "$CERTS/expiredclient.crt" -noout -enddate | cut -d= -f2)"
