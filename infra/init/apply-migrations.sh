#!/bin/bash
# Applique UNIQUEMENT les migrations du schema (infra/init/migrations/NNN-*.sql), dans l'ordre de
# leur nom. Appele par les DEUX chemins (infra/init/migrations/README.md) :
#   - 01-apply.sh (initdb de postgres), apres init.sql, a la creation d'une base NEUVE ;
#   - le conteneur isc-migrate (docker-compose.yml) au demarrage de la pile, sur une base EXISTANTE.
# Les migrations sont rejouables : les deux chemins passent par ce meme script, donc une base neuve
# et une base migree recoivent le MEME schema (preuve : tools/verify_migrations.sh).
#
# Regle 2 du README : chaque migration DOIT etre enveloppee dans BEGIN; ... COMMIT;. Ce script le
# verifie AVANT application. Un fichier non enveloppe fait echouer l'application entiere, et le
# conteneur isc-migrate sort en erreur, ce qui empeche le coeur et le PDP de demarrer.
set -euo pipefail
DIR="${1:-/opt/isc/migrations}"

n=0
for f in "$DIR"/[0-9][0-9][0-9]-*.sql; do
  [ -e "$f" ] || continue
  # Verification de la regle BEGIN/COMMIT (README des migrations, regle 2) :
  if ! grep -q -i '^[[:space:]]*BEGIN[[:space:]]*;' "$f"; then
    echo "[apply-migrations] REFUS : $(basename "$f") ne commence pas par BEGIN;" >&2
    echo "[apply-migrations] voir infra/init/migrations/README.md, regle 2" >&2
    exit 2
  fi
  if ! grep -q -i '^[[:space:]]*COMMIT[[:space:]]*;' "$f"; then
    echo "[apply-migrations] REFUS : $(basename "$f") ne finit pas par COMMIT;" >&2
    echo "[apply-migrations] voir infra/init/migrations/README.md, regle 2" >&2
    exit 2
  fi
  echo "[apply-migrations] $(basename "$f")"
  psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f "$f"
  n=$((n + 1))
done
echo "[apply-migrations] $n migration(s) appliquee(s) depuis $DIR"
