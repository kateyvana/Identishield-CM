# Mesuré SUR BATTERIE, non représentatif

Ce passage (phase à 50 req/s de dix minutes réelles, c4a1167 contre ab44b64) a tourné de 14:55 à
15:38 UTC. Le poste était **débranché du secteur de 14:45:38 à 15:24:03 UTC** : la batterie est passée
de 49 % à **5,9 %** (`alimentation-poste.txt`, Kernel-Power 105). Le dernier passage (ab44b64, tour 2)
a tourné sur secteur, mais avec une batterie presque vide.

Les minutes lentes coïncident avec les effondrements de la fréquence effective du poste, jusqu'à
389 MHz, alors que l'utilisation du poste reste entre 11 et 28 % (`croisement-frequence.txt`) :
c'est une limitation d'alimentation, pas une limite du système, et elle touche les deux commits de
la même façon. Les minutes où le poste est entre 1,6 et 3,3 GHz donnent un p95 de 10 à 40 ms.

Résultats conservés tels quels. Le passage de dix minutes valide est à refaire sur secteur,
batterie chargée.
