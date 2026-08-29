#!/bin/sh
# T2-04 — Autorite locale et certificats de service, generes au DEMARRAGE de la
# pile, sans aucune action manuelle (ADR-021 obligation 5, ENF-05). Reprend le
# dispositif MESURE en SPIKE-4 : ECDSA P-256, CA 10 ans, feuilles 1 an, SAN +
# serverAuth/clientAuth. Idempotent : si la CA existe deja dans le volume, on la
# REUTILISE (Q9 du spike : down/up conserve, down -v regenere). FORCE=1 regenere.
#
# Seuls les certificats LEGITIMES vivent dans le volume runtime (ca, pdp, core) :
# le materiel adverse des quatre tentatives est genere ephemere par la porte de
# verification (tools/verify_mtls.sh), jamais conserve. Aucune cle n'est dans le
# depot ni dans une image : elles naissent ici, dans le volume, au premier up.
set -eu
CERTS=/certs
CA_DAYS=3650
LEAF_DAYS=365

if [ -f "$CERTS/ca.crt" ] && [ "${FORCE:-0}" != "1" ]; then
  echo "[ca-init] $(date -u +%FT%TZ) CA deja presente dans le volume -> REUTILISATION (Q9), pas de regeneration"
  echo "[ca-init] empreinte CA : $(openssl x509 -in "$CERTS/ca.crt" -noout -fingerprint -sha256 | cut -d= -f2)"
  exit 0
fi

echo "[ca-init] $(date -u +%FT%TZ) generation de l'autorite locale et des certificats de service"
umask 077
rm -rf "$CERTS"/* 2>/dev/null || true

newkey() { openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1" 2>/dev/null; }

# Racine auto-signee, 10 ans.
newkey "$CERTS/ca.key"
openssl req -x509 -new -key "$CERTS/ca.key" -days "$CA_DAYS" \
  -subj "/CN=IdentiShield-CM Local CA" -out "$CERTS/ca.crt" 2>/dev/null

# Feuille signee par la CA : SAN + serverAuth/clientAuth (les deux : le PDP est
# serveur, le coeur est client ; la meme forme sert aux deux).
sign_leaf() {  # $1 nom_fichier ; $2 CN=SAN
  newkey "$CERTS/$1.key"
  openssl req -new -key "$CERTS/$1.key" -subj "/CN=$2" -out "$CERTS/$1.csr" 2>/dev/null
  cat > "$CERTS/$1.ext" <<EOF
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectAltName=DNS:$2
EOF
  openssl x509 -req -in "$CERTS/$1.csr" -CA "$CERTS/ca.crt" -CAkey "$CERTS/ca.key" \
    -CAcreateserial -days "$LEAF_DAYS" -extfile "$CERTS/$1.ext" -out "$CERTS/$1.crt" 2>/dev/null
  rm -f "$CERTS/$1.csr" "$CERTS/$1.ext"
}

sign_leaf pdp  pdp    # certificat serveur du PDP (nom verifie par le coeur : DNS:pdp)
sign_leaf core core   # identite client du coeur (DN verifie par le PDP : CN=core)

# Certificats lisibles par tous ; cles privees en 600 (umask). Les conteneurs
# montent le volume en lecture seule.
chmod 644 "$CERTS"/ca.crt "$CERTS"/pdp.crt "$CERTS"/core.crt
echo "[ca-init] $(date -u +%FT%TZ) termine. Cles (600) et certificats (644) dans $CERTS :"
ls -l "$CERTS" | grep -E '\.(crt|key)$'
echo "[ca-init] empreintes :"
echo "  ca.crt   $(openssl x509 -in "$CERTS/ca.crt"  -noout -fingerprint -sha256 | cut -d= -f2)"
echo "  pdp.crt  valide jusqu'a $(openssl x509 -in "$CERTS/pdp.crt"  -noout -enddate | cut -d= -f2)  SAN=$(openssl x509 -in "$CERTS/pdp.crt" -noout -ext subjectAltName | tail -1 | tr -d ' ')"
echo "  core.crt valide jusqu'a $(openssl x509 -in "$CERTS/core.crt" -noout -enddate | cut -d= -f2)  subject=$(openssl x509 -in "$CERTS/core.crt" -noout -subject | cut -d= -f2-)"
