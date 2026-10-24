#!/bin/sh
# Carte #68 (L7-2) — pose la sentinelle /certs/APK-PUBLIE DANS le volume isc-certs, au moment ou la
# proprietaire publie l'APK. Execute dans un conteneur isc-tools (openssl) avec le volume monte en
# lecture-ecriture par `make apk-publie APK_VERSION=<version>`. Contenu : date UTC, version de l'APK,
# empreinte SHA-256 de la CA epinglee par cet APK. La cle privee ne sort jamais du volume.
# A partir de la, `make reset` et `gen-ca.sh FORCE=1` REFUSENT (tools/reset_guard.sh, infra/ca/gen-ca.sh).
set -eu
CERTS="${CERTS:-/certs}"
APK_VERSION="${1:?version de l APK publie manquante (make apk-publie APK_VERSION=...)}"
[ -f "$CERTS/ca.crt" ] || { echo "[apk-publie] $CERTS/ca.crt absent : la pile n'a jamais demarre, rien a epingler" >&2; exit 2; }
if [ -f "$CERTS/APK-PUBLIE" ]; then
  echo "[apk-publie] REFUS : une sentinelle existe deja (un APK est deja publie) :" >&2
  sed 's/^/[apk-publie]   /' "$CERTS/APK-PUBLIE" >&2
  echo "[apk-publie] Retirer d'abord : make apk-depublie (acte de la proprietaire)." >&2
  exit 3
fi
fp=$(openssl x509 -in "$CERTS/ca.crt" -noout -fingerprint -sha256 | cut -d= -f2)
{
  echo "publie: $(date -u +%FT%TZ)"
  echo "apk: $APK_VERSION"
  echo "ca-sha256: $fp"
} > "$CERTS/APK-PUBLIE"
chmod 644 "$CERTS/APK-PUBLIE"
echo "[apk-publie] sentinelle posee :"
sed 's/^/[apk-publie]   /' "$CERTS/APK-PUBLIE"
