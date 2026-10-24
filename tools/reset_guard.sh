#!/usr/bin/env bash
# Carte #68 (L7-2) — garde-fou de `make reset` : une fois l'APK publié, `make reset` REFUSE de
# tourner, mécaniquement, parce que `docker compose down -v` détruirait le volume isc-certs et donc
# l'autorité de certification que l'APK épingle : tous les téléphones des évaluateurs refuseraient
# le serveur, avec un message qui ressemble à une panne réseau.
#
# Sentinelle : /certs/APK-PUBLIE dans le volume isc-certs, posée par `make apk-publie` au moment de
# publier. Elle vit et meurt avec la CA qu'elle protège : une VM neuve sans volume n'a rien à
# protéger, et rien ne la bloque. La clé privée de l'autorité ne sort JAMAIS du volume.
#
# Sortie 0 : pas de sentinelle (ou pas de volume) -> le reset peut continuer.
# Sortie 1 : sentinelle présente -> REFUS, le message nomme la cause et l'empreinte de la CA épinglée.
# Limite déclarée : une commande Docker lancée à la main (`docker volume rm`, `compose down -v`)
# contourne ce garde-fou. Il arrête l'accident, pas un acte délibéré.
#
# Usage : bash tools/reset_guard.sh [volume]   (défaut : isc-certs ; utilisé par la preuve)
set -euo pipefail
export MSYS_NO_PATHCONV=1
VOLUME="${1:-isc-certs}"

if ! docker volume inspect "$VOLUME" >/dev/null 2>&1; then
  echo "[reset-guard] volume $VOLUME absent : rien a proteger, reset autorise"
  exit 0
fi

# Lecture SEULE du volume, dans un conteneur jetable (image alpine, deja presente dans la VM).
if sentinel=$(docker run --rm -v "$VOLUME:/certs:ro" alpine:latest sh -c 'test -f /certs/APK-PUBLIE && cat /certs/APK-PUBLIE' 2>/dev/null); then
  echo "[reset-guard] REFUS : l'APK publie epingle l'autorite de certification de ce volume ($VOLUME)." >&2
  echo "[reset-guard] make reset (compose down -v) detruirait cette autorite : tous les telephones" >&2
  echo "[reset-guard] des evaluateurs refuseraient ensuite le serveur. Sentinelle /certs/APK-PUBLIE :" >&2
  printf '%s\n' "$sentinel" | sed 's/^/[reset-guard]   /' >&2
  echo "[reset-guard] Pour reinitialiser la BASE sans toucher l'autorite : make reset-db." >&2
  echo "[reset-guard] Pour retirer la sentinelle (nouvel APK a publier) : make apk-depublie, acte de la proprietaire." >&2
  exit 1
fi
echo "[reset-guard] aucune sentinelle dans $VOLUME : reset autorise"
exit 0
