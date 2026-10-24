#!/usr/bin/env bash
# Preuve du garde-fou de `make reset` (carte #68, L7-2), dans la VM, sur un volume JETABLE
# (isc-test-certs) : la pile et le vrai volume isc-certs ne sont jamais touchés.
#
# Rouge puis vert, quatre cas :
#   1. volume sans sentinelle            -> reset_guard.sh sort 0 (reset autorisé) ;
#   2. sentinelle posée                  -> reset_guard.sh sort 1 et son message nomme la cause ;
#   3. gen-ca.sh FORCE=1 avec sentinelle -> REFUS (sortie non nulle) et AUCUN fichier du volume changé ;
#   4. sentinelle retirée, FORCE=1       -> gen-ca.sh regenere (l'empreinte de la CA change).
#
# Rien n'est supprimé par ce script (R-S) : la commande de retrait du volume est affichée à la fin.
# Usage (racine du dépôt, poste) : bash tools/verify_reset_guard.sh [isc@<vm>]
set -euo pipefail
VM="${1:-isc@100.93.166.19}"
VOL=isc-test-certs
fail=0
DIR=$(ssh "$VM" "mktemp -d /tmp/isc-reset-guard.XXXXXX")

echo ">>> envoi de reset_guard.sh et gen-ca.sh dans la VM ($VM:$DIR)"
scp -q tools/reset_guard.sh infra/ca/gen-ca.sh "$VM:$DIR/"
ssh "$VM" "docker volume create $VOL >/dev/null"

genca() {  # $1 FORCE
  ssh "$VM" "docker run --rm -v $VOL:/certs -v $DIR/gen-ca.sh:/gen-ca.sh:ro -e FORCE=$1 isc-tools:latest sh /gen-ca.sh"
}
guard() { ssh "$VM" "cd $DIR && bash reset_guard.sh $VOL"; }
fp() { ssh "$VM" "docker run --rm -v $VOL:/certs:ro isc-tools:latest openssl x509 -in /certs/ca.crt -noout -fingerprint -sha256" | cut -d= -f2; }
# Empreinte de CHAQUE fichier du volume (busybox sha256sum) : une comparaison de contenu, plus forte
# qu'un listing, et sans option GNU que busybox ignorerait en silence.
listing() { ssh "$VM" "docker run --rm -v $VOL:/certs:ro alpine:latest sh -c 'cd /certs && sha256sum * | sort'"; }

echo ">>> génération initiale de la CA dans le volume jetable"
genca 1 >/dev/null
fp0=$(fp)

echo ">>> cas 1 : sans sentinelle, le garde-fou autorise"
if guard >/dev/null 2>&1; then echo "[OK] reset_guard.sh sort 0 sans sentinelle"; else echo "[ECHEC] refus sans sentinelle"; fail=1; fi

echo ">>> pose de la sentinelle (comme make apk-publie)"
ssh "$VM" "docker run --rm -v $VOL:/certs isc-tools:latest sh -c 'printf \"publie: 2026-10-24T00:00:00Z\napk: 0.0.0-preuve\nca-sha256: %s\n\" \"\$(openssl x509 -in /certs/ca.crt -noout -fingerprint -sha256 | cut -d= -f2)\" > /certs/APK-PUBLIE'"

echo ">>> cas 2 : avec sentinelle, le garde-fou REFUSE et nomme la cause"
out=$(guard 2>&1 || true)
if guard >/dev/null 2>&1; then
  echo "[ECHEC] reset_guard.sh a autorisé malgré la sentinelle"; fail=1
elif echo "$out" | grep -q "REFUS" && echo "$out" | grep -q "ca-sha256: $fp0"; then
  echo "[OK] reset_guard.sh sort 1 ; le message cite REFUS et l'empreinte de la CA épinglée"
else
  echo "[ECHEC] refus sans la cause attendue :"; echo "$out" | head -8; fail=1
fi

echo ">>> cas 3 : gen-ca.sh FORCE=1 avec sentinelle -> refus, volume intact"
before=$(listing)
if genca 1 >/dev/null 2>&1; then echo "[ECHEC] gen-ca.sh a régénéré malgré la sentinelle"; fail=1; else echo "[OK] gen-ca.sh FORCE=1 refusé (sortie non nulle)"; fi
after=$(listing)
if [ "$before" = "$after" ] && [ "$(fp)" = "$fp0" ]; then echo "[OK] aucun fichier du volume modifié, empreinte CA inchangée"; else echo "[ECHEC] le volume a changé malgré le refus"; fail=1; fi

echo ">>> cas 4 : sentinelle retirée (comme make apk-depublie), FORCE=1 régénère"
ssh "$VM" "docker run --rm -v $VOL:/certs alpine:latest sh -c 'ls -l /certs/APK-PUBLIE'"
ssh "$VM" "docker run --rm -v $VOL:/certs alpine:latest rm /certs/APK-PUBLIE"
if genca 1 >/dev/null 2>&1 && [ "$(fp)" != "$fp0" ]; then echo "[OK] sans sentinelle, FORCE=1 régénère (nouvelle empreinte CA)"; else echo "[ECHEC] régénération attendue après retrait de la sentinelle"; fail=1; fi

echo "Retrait du volume jetable (à faire à la main) : ssh $VM docker volume rm $VOL"
[ $fail -eq 0 ] && echo "verify-reset-guard: VERT" || echo "verify-reset-guard: ROUGE"
exit $fail
