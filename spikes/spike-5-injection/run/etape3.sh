#!/bin/sh
# SPIKE-5 etape 3 — les quatre modes aux trois paliers (S7). reset avant chaque
# mesure (dans bench.measure), chauffe non comptee, repetitions, moyenne+ecart-type,
# empreinte disque. Le serveur HTTP (PDP en processus separe) est demarre pour
# les modes http-*.
set -u
cd "$(dirname "$0")/.."

# (re)demarre le serveur PDP HTTP en tache de fond dans l'injecteur
docker compose exec -T injector pkill -f server.py 2>/dev/null || true
docker compose exec -d injector python server.py
sleep 2

for mode in direct http-1000 http-100 http-unit; do
  for n in 10000 100000 500000; do
    reps=3
    # 500k des modes lents : moins de repetitions (mesure, pas extrapolation)
    [ "$n" = 500000 ] && reps=2
    { [ "$n" = 500000 ] && [ "$mode" = "http-unit" ]; } && reps=1
    echo "########## mode=$mode palier=$n reps=$reps ##########"
    docker compose exec -T injector python bench.py measure "$mode" "$n" "$reps"
  done
done
echo "########## ETAPE 3 TERMINEE ##########"
