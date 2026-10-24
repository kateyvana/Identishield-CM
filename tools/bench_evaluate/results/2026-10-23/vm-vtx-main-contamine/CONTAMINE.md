# Contaminé : ce passage n'est PAS un verdict

Passage du banc moteur sur `main` (`8929a3f`), deux tours, phase à 50 req/s de dix minutes réelles,
du 2026-10-23 16:48:17 à 17:09:10 UTC. Le poste était sur secteur, batterie à 89 % au départ.

**Recouvrement.** Le couloir B a lancé un `make check` sur le même poste de 16:47:26 à 16:49:29 UTC.
Il recouvre la phase séquentielle du tour 1 et les ~32 premières secondes de sa phase à 50 req/s
(`recouvrement.txt`, calculé depuis les `atMs` et l'heure de fin des fichiers, avec le décalage
d'horloge VM → poste mesuré à 28,2 s). Pendant ce recouvrement, le poste montre 34 à 52 %
d'utilisation et une fréquence effective de 1,8 à 2,7 GHz, contre 13 à 16 % et ~3,4 GHz ensuite
(relevé du poste). Le second `make check` du couloir B (17:16:38 à 17:18:41) tombe hors de toute
phase mesurée.

**Règle appliquée (superviseur technique, 2026-10-23).** Une phase mesurée recouverte retire au
passage entier son statut de verdict. Aucune minute n'est exclue après coup. Le passage est refait
entièrement, et ce résultat reste ici tel quel, marqué contaminé, comme les passages NEM.
