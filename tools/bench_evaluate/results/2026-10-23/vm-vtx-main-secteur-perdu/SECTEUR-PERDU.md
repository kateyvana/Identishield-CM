# Secteur perdu pendant la mesure : ce passage n'est PAS un verdict

Second passage du banc moteur sur `main` (`8929a3f`), deux tours, phase à 50 req/s de dix minutes
réelles, du 2026-10-23 17:53:35 à 18:13:59 UTC (`fenetres-phases.txt`). Il refait le passage
contaminé du même jour.

**Critère fixé AVANT la mesure, non tenu.** « Secteur branché de bout en bout ». Le poste a été
débranché à **18:08:23 UTC** (Kernel-Power 105, `AcOnline=false`, `alimentation-poste.txt`), en plein
milieu de la phase à 50 req/s du tour 2 (18:03:53 → 18:13:53). Aucune minute n'est exclue : le
passage entier perd son statut de verdict, et il reste ici tel quel.

**Constat nouveau, plus grave pour la démonstration.** Avant la coupure, alors que le poste était
sur secteur avec une batterie à 100 %, la fréquence effective s'est effondrée à **389 MHz** de
18:04:22 à 18:05:12, puis de 18:07:22 à 18:08:03, pour une utilisation de 13 %
(`frequence-poste.csv`). Le tour 2 décroche exactement sur ces fenêtres : p95 de 1 à 11 s.
**Le poste peut donc s'effondrer même branché.** La cause (chargeur, limite de puissance, protection
thermique) n'est pas identifiée.

**Ce qui reste établi.** Le tour 1 (17:53:42 → 18:03:42), sur secteur et à fréquence stable, donne
à 50 req/s un p95 de 10 à 17 ms et un p99 de 20 à 37 ms à chaque minute. C'est une observation,
pas un verdict.

Décalage d'horloge VM → poste : 28,192 s avant, 28,353 s après (`decalage-horloge.txt`), soit une
dérive de 0,16 s en 21 min, négligeable devant les fenêtres en jeu.
