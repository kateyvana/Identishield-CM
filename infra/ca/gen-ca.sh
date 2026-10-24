#!/bin/sh
# T2-04 — Autorite locale et certificats de service, generes au DEMARRAGE de la
# pile, sans aucune action manuelle (ADR-021 obligation 5, ENF-05). Reprend le
# dispositif MESURE en SPIKE-4 : ECDSA P-256, CA 10 ans, feuilles 1 an, SAN +
# serverAuth/clientAuth. Idempotent : si la CA existe deja dans le volume, on la
# REUTILISE (Q9 du spike : down/up conserve, down -v regenere). FORCE=1 regenere.
#
# L7-b (carte #67) : troisieme feuille `core-mobile`, certificat SERVEUR de la
# surface mobile du coeur (TLS simple telephone <-> coeur), CN=isc-core, SAN
# DNS:isc-core, DNS:isc-vm, DNS:isc-vm.tail3824f1.ts.net (MagicDNS, seule
# adresse composee par l'APK), IP:100.93.166.19. Les feuilles pdp et core sont
# INCHANGEES : coeur <-> PDP reste en mTLS client-auth=need.
#
# Idempotence PAR FEUILLE (defaut trouve le 2026-10-24 en ecrivant le plan de
# deploiement) : un volume cree avant L7-b porte la CA et pdp/core, mais pas
# core-mobile ; or le coeur ne demarre pas sans elle (application.yml). Une
# feuille ATTENDUE et ABSENTE est signee avec la CA existante, qui n'est pas
# touchee : les telephones qui l'epinglent ne voient aucune difference.
#
# Seuls les certificats LEGITIMES vivent dans le volume runtime (ca, pdp, core,
# core-mobile) : le materiel adverse des quatre tentatives est genere ephemere
# par la porte de verification (tools/verify_mtls.sh), jamais conserve. Aucune
# cle n'est dans le depot ni dans une image : elles naissent ici, dans le
# volume, au premier up.
set -eu
CERTS=/certs
CA_DAYS=3650
LEAF_DAYS=365
LEAVES="pdp core core-mobile"

fingerprint() { openssl x509 -in "$CERTS/ca.crt" -noout -fingerprint -sha256 | cut -d= -f2; }
newkey() { openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1" 2>/dev/null; }

# Feuille signee par la CA : SAN + serverAuth/clientAuth (les deux : le PDP est
# serveur, le coeur est client ; la meme forme sert aux trois feuilles). Les
# entrees SAN sont passees telles quelles (prefixe DNS: ou IP:).
sign_leaf() {  # $1 nom_fichier ; $2 CN ; $3+ entrees SAN (ex. DNS:isc-core, IP:100.93.166.19)
  name=$1; cn=$2; shift 2
  san_list=""
  for entry in "$@"; do
    if [ -z "$san_list" ]; then san_list="$entry"; else san_list="$san_list,$entry"; fi
  done
  newkey "$CERTS/$name.key"
  openssl req -new -key "$CERTS/$name.key" -subj "/CN=$cn" -out "$CERTS/$name.csr" 2>/dev/null
  cat > "$CERTS/$name.ext" <<EOF
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectAltName=$san_list
EOF
  openssl x509 -req -in "$CERTS/$name.csr" -CA "$CERTS/ca.crt" -CAkey "$CERTS/ca.key" \
    -CAcreateserial -days "$LEAF_DAYS" -extfile "$CERTS/$name.ext" -out "$CERTS/$name.crt" 2>/dev/null
  rm -f "$CERTS/$name.csr"
  rm -f "$CERTS/$name.ext"
  chmod 644 "$CERTS/$name.crt"
}

# Les trois feuilles attendues, une seule definition de leur identite.
sign_expected() {  # $1 nom de feuille
  case "$1" in
    pdp)  sign_leaf pdp  pdp  DNS:pdp ;;   # certificat serveur du PDP (nom verifie par le coeur : DNS:pdp)
    core) sign_leaf core core DNS:core ;;  # identite client du coeur (DN verifie par le PDP : CN=core)
    # L7-b : certificat serveur de la surface mobile du coeur. CN=isc-core (nom du contrat), SAN a
    # quatre entrees pour permettre a l'APK de composer DNS:isc-vm.tail3824f1.ts.net (Tailscale
    # MagicDNS), tout en acceptant DNS:isc-core, DNS:isc-vm et IP:100.93.166.19. NE REPREND PAS
    # CN=core : le PDP accepte tout porteur de CN=core comme etant le coeur, et une seconde cle de
    # ce nom elargirait cette confiance.
    core-mobile) sign_leaf core-mobile isc-core \
      DNS:isc-core DNS:isc-vm DNS:isc-vm.tail3824f1.ts.net IP:100.93.166.19 ;;
    *) echo "[ca-init] feuille inconnue : $1" >&2; exit 5 ;;
  esac
}

if [ -f "$CERTS/ca.crt" ] && [ "${FORCE:-0}" != "1" ]; then
  echo "[ca-init] $(date -u +%FT%TZ) CA deja presente dans le volume -> REUTILISATION (Q9), pas de regeneration"
  echo "[ca-init] empreinte CA : $(fingerprint)"
  if [ ! -f "$CERTS/ca.key" ]; then
    echo "[ca-init] ECHEC : ca.crt present mais ca.key absente, aucune feuille ne peut etre signee" >&2
    exit 4
  fi
  umask 077
  added=0
  for leaf in $LEAVES; do
    if [ ! -f "$CERTS/$leaf.crt" ] || [ ! -f "$CERTS/$leaf.key" ]; then
      echo "[ca-init] feuille $leaf ABSENTE -> signee avec la CA existante (CA inchangee)"
      sign_expected "$leaf"
      added=$((added + 1))
    fi
  done
  echo "[ca-init] $added feuille(s) ajoutee(s) ; empreinte CA apres : $(fingerprint)"
  exit 0
fi

# Carte #68 (L7-2) : une fois l'APK publie, l'autorite est EPINGLEE par les telephones. La
# regenerer (FORCE=1) les ferait tous refuser le serveur. Sentinelle posee par `make apk-publie`,
# retiree par `make apk-depublie` (acte de la proprietaire). Refus mecanique, pas une note.
if [ -f "$CERTS/APK-PUBLIE" ]; then
  echo "[ca-init] REFUS : l'APK publie epingle cette autorite (sentinelle $CERTS/APK-PUBLIE) :" >&2
  sed 's/^/[ca-init]   /' "$CERTS/APK-PUBLIE" >&2
  echo "[ca-init] regenerer la CA casserait tous les telephones des evaluateurs. Rien n'est modifie." >&2
  echo "[ca-init] Pour publier un NOUVEL APK : make apk-depublie (proprietaire), puis FORCE=1." >&2
  exit 3
fi

echo "[ca-init] $(date -u +%FT%TZ) generation de l'autorite locale et des certificats de service"
umask 077

# Nettoyage AVANT regeneration (FORCE=1 ou volume vide). CLAUDE.md R-S : la commande destructive
# est SEULE et ses erreurs ne sont pas masquees. Les cibles sont NOMMEES explicitement, jamais un
# glob : la liste contient tout ce que ce script peut ecrire dans $CERTS.
echo "[ca-init] contenu existant de $CERTS avant regeneration :"
ls -la "$CERTS"
rm -f -- \
  "$CERTS/ca.crt" "$CERTS/ca.key" "$CERTS/ca.srl" \
  "$CERTS/pdp.crt" "$CERTS/pdp.key" \
  "$CERTS/core.crt" "$CERTS/core.key" \
  "$CERTS/core-mobile.crt" "$CERTS/core-mobile.key"

# Racine auto-signee, 10 ans.
newkey "$CERTS/ca.key"
openssl req -x509 -new -key "$CERTS/ca.key" -days "$CA_DAYS" \
  -subj "/CN=IdentiShield-CM Local CA" -out "$CERTS/ca.crt" 2>/dev/null

for leaf in $LEAVES; do sign_expected "$leaf"; done

# Certificats lisibles par tous ; cles privees en 600 (umask). Les conteneurs
# montent le volume en lecture seule.
chmod 644 "$CERTS/ca.crt"
echo "[ca-init] $(date -u +%FT%TZ) termine. Cles (600) et certificats (644) dans $CERTS :"
ls -l "$CERTS" | grep -E '\.(crt|key)$'
echo "[ca-init] empreintes :"
echo "  ca.crt           $(fingerprint)"
echo "  pdp.crt          valide jusqu'a $(openssl x509 -in "$CERTS/pdp.crt"         -noout -enddate | cut -d= -f2)  SAN=$(openssl x509 -in "$CERTS/pdp.crt"         -noout -ext subjectAltName | tail -1 | tr -d ' ')"
echo "  core.crt         valide jusqu'a $(openssl x509 -in "$CERTS/core.crt"        -noout -enddate | cut -d= -f2)  subject=$(openssl x509 -in "$CERTS/core.crt"        -noout -subject | cut -d= -f2-)"
echo "  core-mobile.crt  valide jusqu'a $(openssl x509 -in "$CERTS/core-mobile.crt" -noout -enddate | cut -d= -f2)  SAN=$(openssl x509 -in "$CERTS/core-mobile.crt" -noout -ext subjectAltName | tail -1 | tr -d ' ')"
