#!/usr/bin/env bash
# ADR-038 — CA de DÉVELOPPEMENT du faux serveur mobile et certificat de serveur HTTPS.
#
# Jamais la CA du bac à sable (ADR-021) : sa clé ne quitte pas le volume `certs` de la VM.
# Même recette que infra/ca/gen-ca.sh : ECDSA P-256, SAN, serverAuth. Tout est écrit dans
# tools/mobile-mock/.tls/ (ignoré par git, jamais versionné).
#
# La CA porte le marqueur OU=ISC-DEV-MOBILE-MOCK : la tâche SandboxTrustTask de l'application la
# REFUSE pour un build release.
#
# Usage : gen-dev-tls.sh [SAN ...]     ex. gen-dev-tls.sh IP:192.168.1.20
#   SAN par défaut, toujours inclus : IP:10.0.2.2 (émulateur), IP:127.0.0.1, DNS:localhost.
# Idempotent pour la CA : si elle existe, elle est RÉUTILISÉE (les applications déjà construites
# restent valides) ; le certificat du serveur est réémis à chaque appel (nouveaux SAN).
set -euo pipefail
export MSYS_NO_PATHCONV=1 # Git Bash : ne pas réécrire « /O=…/CN=… » en chemin Windows

cd "$(dirname "$0")"
OUT="${ISC_DEV_TLS_DIR:-.tls}" # dossier de sortie (tests : dossier temporaire)
CA_DAYS=365
LEAF_DAYS=90
mkdir -p "$OUT"
umask 077

SAN="IP:10.0.2.2,IP:127.0.0.1,DNS:localhost"
for extra in "$@"; do SAN="$SAN,$extra"; done

newkey() { openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1" 2>/dev/null; }

if [ -f "$OUT/dev-ca.crt" ] && [ -f "$OUT/dev-ca.key" ]; then
  echo "[dev-tls] CA de développement présente -> RÉUTILISÉE"
else
  echo "[dev-tls] génération de la CA de DÉVELOPPEMENT (OU=ISC-DEV-MOBILE-MOCK)"
  newkey "$OUT/dev-ca.key"
  openssl req -x509 -new -key "$OUT/dev-ca.key" -days "$CA_DAYS" \
    -subj "/O=IdentiShield-CM/OU=ISC-DEV-MOBILE-MOCK/CN=IdentiShield-CM DEV faux serveur mobile - interdit en release" \
    -out "$OUT/dev-ca.crt" 2>/dev/null
fi

echo "[dev-tls] certificat du faux serveur, SAN : $SAN"
newkey "$OUT/mock.key"
openssl req -new -key "$OUT/mock.key" -subj "/O=IdentiShield-CM/OU=ISC-DEV-MOBILE-MOCK/CN=isc-mobile-mock" \
  -out "$OUT/mock.csr" 2>/dev/null
printf 'basicConstraints=CA:FALSE\nkeyUsage=digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=%s\n' \
  "$SAN" >"$OUT/mock.ext"
openssl x509 -req -in "$OUT/mock.csr" -CA "$OUT/dev-ca.crt" -CAkey "$OUT/dev-ca.key" -CAcreateserial \
  -days "$LEAF_DAYS" -extfile "$OUT/mock.ext" -out "$OUT/mock.crt" 2>/dev/null
chmod 644 "$OUT/dev-ca.crt" "$OUT/mock.crt"

echo "[dev-tls] empreinte de la CA : $(openssl x509 -in "$OUT/dev-ca.crt" -noout -fingerprint -sha256 | cut -d= -f2)"
echo "[dev-tls] à fournir à l'application : -Pisc.sandboxCa=$(pwd)/$OUT/dev-ca.crt (build debug seulement)"
