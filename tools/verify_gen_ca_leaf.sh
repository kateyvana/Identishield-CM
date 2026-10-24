#!/usr/bin/env bash
# Preuve rouge/vert de l'idempotence PAR FEUILLE de infra/ca/gen-ca.sh (défaut trouvé le 2026-10-24
# en écrivant le plan de déploiement : un volume créé avant L7-b porte la CA et pdp/core mais pas
# core-mobile, et le cœur ne démarre pas sans elle). Sur un volume Docker JETABLE dans la VM, avec
# l'image isc-tools de la pile (openssl) ; la pile et le volume isc-certs ne sont PAS touchés.
#
#   1. gen-ca.sh d'AVANT L7-b (git 47a1834) sur le volume vide  -> CA + pdp + core, pas de core-mobile
#      (c'est l'état réel du volume isc-certs de la VM) ;
#   2. ROUGE attendu : gen-ca.sh de L7-b tel que fusionné (git 4620365) -> « REUTILISATION » et
#      core-mobile toujours ABSENTE ;
#   3. VERT exigé  : gen-ca.sh du dépôt courant -> core-mobile PRÉSENTE, signée par la CA existante,
#      empreinte de la CA INCHANGÉE, pdp/core inchangées ; une seconde exécution n'ajoute rien ;
#   4. chemin NEUF : le même script sur un volume VIDE produit CA + 3 feuilles (644) et 4 clés (600).
#
# Rien n'est supprimé ici (CLAUDE.md, R-S) : la commande de retrait du volume est affichée à la fin.
# Usage (racine du dépôt, poste) : bash tools/verify_gen_ca_leaf.sh [isc@<vm>]
set -euo pipefail
VM=${1:-isc@100.93.166.19}
VOL=isc-certs-preuve-$(date -u +%H%M%S)
TMP=/tmp/gen-ca-preuve-$$
fail=0

git show 47a1834:infra/ca/gen-ca.sh > /tmp/gen-ca-avant-l7b.sh
git show 4620365:infra/ca/gen-ca.sh > /tmp/gen-ca-l7b-fusionne.sh
ssh "$VM" "mkdir -p $TMP"
scp -q /tmp/gen-ca-avant-l7b.sh /tmp/gen-ca-l7b-fusionne.sh "$VM:$TMP/"
scp -q infra/ca/gen-ca.sh "$VM:$TMP/gen-ca-courant.sh"

run() {  # $1 script
  ssh "$VM" "docker run --rm -v $VOL:/certs -v $TMP:/scripts:ro isc-tools:latest sh /scripts/$1" 2>&1
}
has() { ssh "$VM" "docker run --rm -v $VOL:/certs isc-tools:latest sh -c 'test -f /certs/$1 && echo oui || echo non'"; }
fp()  { ssh "$VM" "docker run --rm -v $VOL:/certs isc-tools:latest openssl x509 -in /certs/$1 -noout -fingerprint -sha256" | cut -d= -f2; }
verify_chain() { ssh "$VM" "docker run --rm -v $VOL:/certs isc-tools:latest openssl verify -CAfile /certs/ca.crt /certs/$1" 2>&1; }

echo ">>> 1. état d'avant L7-b sur un volume vide ($VOL)"
run gen-ca-avant-l7b.sh | sed 's/^/    /'
ca0=$(fp ca.crt); pdp0=$(fp pdp.crt); core0=$(fp core.crt)
echo "    core-mobile.crt présente : $(has core-mobile.crt) (attendu : non)"

echo ">>> 2. ROUGE attendu : gen-ca.sh de L7-b fusionné (4620365) sur ce volume"
run gen-ca-l7b-fusionne.sh | sed 's/^/    /'
if [ "$(has core-mobile.crt)" = "non" ]; then
  echo "    [OK] le défaut est reproduit : REUTILISATION sans la feuille core-mobile"
else
  echo "    [ECHEC] le script fusionné a créé core-mobile : le défaut n'est pas celui décrit"; fail=1
fi

echo ">>> 3. VERT exigé : gen-ca.sh courant sur ce même volume"
run gen-ca-courant.sh | sed 's/^/    /'
[ "$(has core-mobile.crt)" = "oui" ] && [ "$(has core-mobile.key)" = "oui" ] \
  && echo "    [OK] core-mobile.crt et .key présentes" || { echo "    [ECHEC] core-mobile absente"; fail=1; }
v=$(verify_chain core-mobile.crt); echo "    $v"
echo "$v" | grep -q ': OK$' && echo "    [OK] core-mobile signée par la CA existante" || { echo "    [ECHEC] core-mobile non vérifiable par la CA"; fail=1; }
ca1=$(fp ca.crt); pdp1=$(fp pdp.crt); core1=$(fp core.crt)
[ "$ca0" = "$ca1" ]     && echo "    [OK] empreinte CA inchangée : $ca1"   || { echo "    [ECHEC] la CA a changé : $ca0 -> $ca1"; fail=1; }
[ "$pdp0" = "$pdp1" ]   && echo "    [OK] pdp.crt inchangée"                || { echo "    [ECHEC] pdp.crt a changé"; fail=1; }
[ "$core0" = "$core1" ] && echo "    [OK] core.crt inchangée"               || { echo "    [ECHEC] core.crt a changé"; fail=1; }
san=$(ssh "$VM" "docker run --rm -v $VOL:/certs isc-tools:latest openssl x509 -in /certs/core-mobile.crt -noout -ext subjectAltName" | tail -1 | tr -d ' ')
echo "    SAN core-mobile : $san"
echo "$san" | grep -q 'DNS:isc-vm.tail3824f1.ts.net' && echo "    [OK] SAN MagicDNS présent" || { echo "    [ECHEC] SAN MagicDNS absent"; fail=1; }

echo ">>> 4. seconde exécution : rien n'est ajouté"
out=$(run gen-ca-courant.sh); echo "$out" | sed 's/^/    /'
echo "$out" | grep -q '0 feuille(s) ajoutee(s)' && echo "    [OK] 0 feuille ajoutée" || { echo "    [ECHEC] la seconde exécution a modifié le volume"; fail=1; }
cm1=$(fp core-mobile.crt)
run gen-ca-courant.sh >/dev/null
[ "$cm1" = "$(fp core-mobile.crt)" ] && echo "    [OK] core-mobile.crt stable" || { echo "    [ECHEC] core-mobile.crt régénérée"; fail=1; }

echo ">>> 5. chemin NEUF : gen-ca.sh courant sur un second volume VIDE ($VOL-neuf)"
VOL2=$VOL-neuf
out=$(ssh "$VM" "docker run --rm -v $VOL2:/certs -v $TMP:/scripts:ro isc-tools:latest sh /scripts/gen-ca-courant.sh" 2>&1); echo "$out" | sed 's/^/    /'
listing=$(ssh "$VM" "docker run --rm -v $VOL2:/certs isc-tools:latest sh -c 'ls -l /certs | grep -E \"\.(crt|key)$\"'")
ncrt=$(echo "$listing" | grep -c 'rw-r--r--.*\.crt$' || true); nkey=$(echo "$listing" | grep -c 'rw-------.*\.key$' || true)
[ "$ncrt" = "4" ] && [ "$nkey" = "4" ] && echo "    [OK] 4 certificats en 644 et 4 clés en 600 (ca, pdp, core, core-mobile)"   || { echo "    [ECHEC] attendu 4 crt (644) + 4 key (600), vu $ncrt crt / $nkey key :"; echo "$listing" | sed 's/^/    /'; fail=1; }
for leaf in pdp core core-mobile; do
  v=$(ssh "$VM" "docker run --rm -v $VOL2:/certs isc-tools:latest openssl verify -CAfile /certs/ca.crt /certs/$leaf.crt" 2>&1)
  echo "$v" | grep -q ': OK$' && echo "    [OK] $leaf.crt vérifiée par la CA neuve" || { echo "    [ECHEC] $v"; fail=1; }
done

echo
echo "Retrait des volumes jetables (à faire à la main, une commande SEULE chacune) :"
echo "  ssh $VM docker volume rm $VOL"
echo "  ssh $VM docker volume rm $VOL2"
echo "  ssh $VM rm -r $TMP"
[ $fail -eq 0 ] && echo "verify_gen_ca_leaf : VERT" || { echo "verify_gen_ca_leaf : ROUGE"; exit 1; }
