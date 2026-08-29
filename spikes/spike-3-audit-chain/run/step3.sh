#!/usr/bin/env bash
# SPIKE-3 étape 3 — réécriture complète + ancre signée externe (Q4, Q8).
# La clé de signature vit HORS de la base, sur le système de fichiers de la VM,
# possédée par l'utilisateur isc en mode 600 : hors de portée du modèle §4
# (le rôle applicatif n'a aucun accès au système de fichiers). Franchise jury :
# la co-localisation sur la MÊME VM est une limite du MODÈLE (§4 exclut l'accès
# FS et admin), pas du dispositif ; un déploiement réel mettrait la clé sur une
# machine distincte ou un HSM.
set -uo pipefail
cd "$(dirname "$0")/.."

export POSTGRES_PASSWORD=${POSTGRES_PASSWORD:?}
DC="docker compose"
admin() { $DC exec -T db psql -v ON_ERROR_STOP=1 -U postgres -d spike3 "$@"; }
q() { $DC exec -T db psql -tAq -U postgres -d spike3 -c "$1"; }

KEYDIR="$HOME/anchor-key"
PRIV="$KEYDIR/anchor_priv.pem"
PUB="$KEYDIR/anchor_pub.pem"
WORK="$(mktemp -d)"

echo "===== Q8 : génération de la clé d'ancrage, HORS base ====="
mkdir -p "$KEYDIR"; chmod 700 "$KEYDIR"
[ -f "$PRIV" ] || openssl genpkey -algorithm ed25519 -out "$PRIV" 2>/dev/null
chmod 600 "$PRIV"
[ -f "$PUB" ] || openssl pkey -in "$PRIV" -pubout -out "$PUB" 2>/dev/null
echo "clé privée : $PRIV"; ls -l "$PRIV" | awk '{print $1, $3, $9}'
echo "algorithme : $(openssl pkey -in "$PRIV" -text -noout 2>/dev/null | head -1)"

# Sign the current head as a portable anchor exported outside the DB.
take_anchor() {   # -> writes $WORK/anchor.txt (a_seq|a_hash|a_count) + anchor.sig
  local a_seq a_hash a_count
  a_seq=$(q "SELECT last_seq FROM audit.chain_head;")
  a_hash=$(q "SELECT last_hash FROM audit.chain_head;")
  a_count=$(q "SELECT n_records FROM audit.chain_head;")
  printf '%s|%s|%s' "$a_seq" "$a_hash" "$a_count" > "$WORK/anchor.txt"
  openssl pkeyutl -sign -inkey "$PRIV" -rawin -in "$WORK/anchor.txt" -out "$WORK/anchor.sig"
  echo "ANCRE signée : seq=$a_seq count=$a_count hash=${a_hash:0:12}…"
}

# Verify: (1) signature authentic, (2) the CURRENT hash at the anchored seq still
# equals the signed hash. A rewrite of any record <= anchored seq changes it.
verify_anchor() {
  local a_seq a_hash a_count cur
  IFS='|' read -r a_seq a_hash a_count < "$WORK/anchor.txt"
  if openssl pkeyutl -verify -pubin -inkey "$PUB" -rawin -in "$WORK/anchor.txt" \
        -sigfile "$WORK/anchor.sig" >/dev/null 2>&1; then
    echo "  signature de l'ancre : VALIDE (non forgeable sans la clé privée)"
  else
    echo "  signature de l'ancre : INVALIDE"; return
  fi
  cur=$(q "SELECT hash FROM audit.entry WHERE seq = $a_seq;")
  if [ "$cur" = "$a_hash" ]; then
    echo "  hash au seq ancré $a_seq : INCHANGÉ  => réécriture NON détectée par cette ancre"
  else
    echo "  hash au seq ancré $a_seq : ${a_hash:0:12}… -> ${cur:0:12}…  => RÉÉCRITURE DÉTECTÉE"
  fi
}

seed() {  # $1 = number of records, as admin, chained
  admin -c "ALTER TABLE audit.entry ENABLE TRIGGER chain_before_insert;" >/dev/null
  admin -c "TRUNCATE audit.entry RESTART IDENTITY;" >/dev/null
  admin -c "UPDATE audit.chain_head SET last_seq=0,last_hash=repeat('0',64),n_records=0 WHERE id;" >/dev/null
  admin -c "INSERT INTO audit.entry(payload,prev_hash,hash,written_at)
            SELECT jsonb_build_object('i',g), '', '', timestamptz '2026-01-01T00:00:00Z' + make_interval(secs=>g)
            FROM generate_series(1,$1) g;" >/dev/null
}

echo
echo "===== A) La réécriture complète défait les DEUX couches de l'étape 2 ====="
seed 1000
take_anchor
echo "-- l'admin modifie seq 500 puis RECALCULE toutes les empreintes + chain_head --"
admin -c "UPDATE audit.entry SET payload='{\"decision\":\"FORGED\"}' WHERE seq=500;" >/dev/null
admin < sql/rewrite-all.sql >/dev/null
echo "-- couche chaînage (recompute+continuité) : anomalies ? --"
admin < sql/verify.sql | sed -n '/LAYER A/,/LAYER B/p' | head -4
echo "-- couche ancre chain_head : --"
admin < sql/verify.sql | sed -n '/LAYER B/,$p' | grep -E 'anchor_verdict|MATCH|MISMATCH' | tail -1
echo "-- ancre SIGNÉE externe : --"
verify_anchor

echo
echo "===== B) FENÊTRE DE VULNÉRABILITÉ chiffrée, 3 valeurs d'ANCHOR_INTERVAL ====="
echo "Protocole : ancre prise, puis N enregistrements écrits APRÈS l'ancre ;"
echo "l'admin réécrit ces N (recompute+chain_head). Combien passent inaperçus ?"
for N in 100 1000 5000; do
  seed 1000
  take_anchor >/dev/null            # ancre à seq=1000
  admin -c "INSERT INTO audit.entry(payload,prev_hash,hash,written_at)
            SELECT jsonb_build_object('i',g), '', '', timestamptz '2027-01-01T00:00:00Z' + make_interval(secs=>g)
            FROM generate_series(1,$N) g;" >/dev/null   # N enregistrements après l'ancre
  admin -c "UPDATE audit.entry SET payload='{\"rw\":true}' WHERE seq > 1000;" >/dev/null
  admin < sql/rewrite-all.sql >/dev/null
  a_hash=$(head -1 "$WORK/anchor.txt")
  cur=$(q "SELECT hash FROM audit.entry WHERE seq=1000;")
  IFS='|' read -r _ ah _ < "$WORK/anchor.txt"
  cur1000=$(q "SELECT hash FROM audit.entry WHERE seq=1000;")
  if [ "$cur1000" = "$ah" ]; then verdict="NON détectée"; else verdict="détectée"; fi
  echo "ANCHOR_INTERVAL=$N : $N enregistrements réécrits après l'ancre -> réécriture $verdict (fenêtre = $N)"
done

rm -rf "$WORK"
