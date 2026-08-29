#!/bin/sh
# SPIKE-4 étape 4 — latence dans les trois modes (§7). Exécuté DANS le conteneur
# core. La latence de RÉFÉRENCE produit est mesurée contre le terminateur STRICT
# (comportement du PDP Spring Boot). Nuance honnête : openssl s_server ne fait
# pas de connexions persistantes ; le mode persistant — le mode réel du coeur —
# est donc mesuré contre nginx, qui gère keepalive + reprise de session. Le coût
# du mTLS est une propriété du protocole (handshake ECDHE + vérif de cert),
# quasi identique entre terminateurs mûrs ; nginx est le représentant valide
# pour ce mode. Chaque ligne dit CONTRE QUOI elle est mesurée.
set -u
CA=/certs/ca.crt
CERT="--cert /certs/core.crt --key /certs/core.key --cacert $CA"
N=${N:-1000}

pct() {  # $1 = label ; lit les temps (secondes) sur stdin
  sort -n | awk -v L="$1" '{a[NR]=$1}
    END{ if(NR==0){print L": n=0"; exit}
      printf "%-42s n=%d  p50=%.2f  p95=%.2f  p99=%.2f ms\n",
        L, NR, a[int(NR*0.50)]*1000, a[int(NR*0.95)]*1000, a[int(NR*0.99)]*1000 }'
}

# URLs répétées pour le mode persistant (une connexion, N requêtes)
rep() { i=0; while [ $i -lt "$N" ]; do echo "url = \"$1\""; i=$((i+1)); done; }

echo "=== 1) SANS mTLS, persistant (HTTP simple, plain:8080) ==="
rep "http://plain:8080/" > /tmp/u_plain
curl -s -o /dev/null -w '%{time_total}\n' -K /tmp/u_plain | pct "no-mTLS persistant (plain)"

echo "=== 2) mTLS, CONNEXION NEUVE à chaque appel, contre STRICT (produit) ==="
i=0; while [ $i -lt "$N" ]; do
  curl -s -o /dev/null -w '%{time_total}\n' $CERT https://pdp-strict:8443/
  i=$((i+1))
done | pct "mTLS connexion-neuve (STRICT)"

echo "=== 2b) mTLS, CONNEXION NEUVE, contre nginx (recoupement) ==="
i=0; while [ $i -lt "$N" ]; do
  curl -s -o /dev/null -w '%{time_total}\n' $CERT https://pdp:8443/
  i=$((i+1))
done | pct "mTLS connexion-neuve (nginx)"

echo "=== 3) mTLS, CONNEXIONS PERSISTANTES, contre nginx (mode réel du coeur) ==="
rep "https://pdp:8443/" > /tmp/u_pdp
curl -s -o /dev/null -w '%{time_total}\n' $CERT -K /tmp/u_pdp | pct "mTLS persistant (nginx keepalive)"

echo "=== Q6) reprise de session TLS (nginx ssl_session_cache) ==="
echo | openssl s_client -connect pdp:8443 -servername pdp -CAfile $CA \
  -cert /certs/core.crt -key /certs/core.key -reconnect 2>/dev/null \
  | grep -iE 'New|Reused' | sort | uniq -c | sed 's/^/    /'
