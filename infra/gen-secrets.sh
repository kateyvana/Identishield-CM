#!/bin/bash
# Genere infra/.env avec des mots de passe aleatoires s'il n'existe pas.
# Le fichier .env n'est JAMAIS versionne (.gitignore). ENF-07.
# Si .env existe deja (base deployee), on NE regenere PAS les mots de passe (ils sont partages
# avec le volume postgres : les changer en silence casserait la base). On AJOUTE seulement les
# nouvelles variables manquantes, avec leur valeur par defaut : c'est le chemin d'ajout propre
# apres chaque mise a jour du depot dans la VM, sans toucher aux secrets existants.
set -euo pipefail
cd "$(dirname "$0")"
rand() { LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 32; }
umask 077

# Ajoute une variable au .env si elle n'y est pas deja. Argument : la LIGNE a ajouter, telle
# qu'elle apparaitra. Les commentaires precedents sont ajoutes separement par le script appelant.
append_if_missing() {  # $1 nom de la variable ; $2 ligne complete a ajouter
  if ! grep -qE "^$1=" .env; then
    echo "$2" >> .env
  fi
}

if [ -f .env ]; then
  append_if_missing ISC_MOBILE_BIND "ISC_MOBILE_BIND=127.0.0.1"
  echo "infra/.env existe deja - variables manquantes ajoutees (sans toucher aux secrets existants)."
  exit 0
fi
cat > .env << ENV
POSTGRES_USER=isc_admin
POSTGRES_PASSWORD=$(rand)
POSTGRES_DB=identishield
ISC_CORE_APP_PASSWORD=$(rand)
ISC_PDP_APP_PASSWORD=$(rand)
# L7-b (carte #67) : adresse de bind du 8443 du coeur, surface mobile. Valeur par defaut 127.0.0.1
# (developpement local, pile non exposee a l'exterieur). Dans la VM, la proprietaire REMPLACE par
# l'adresse Tailscale (100.93.166.19), apres mise en place de la regle DOCKER-USER qui coupe la
# sortie Internet du reseau isc-mobile (prerequis L7-3). docker compose up echoue AVEC un message
# qui nomme la variable si elle est vide ou absente.
ISC_MOBILE_BIND=127.0.0.1
ENV
echo "infra/.env genere."
