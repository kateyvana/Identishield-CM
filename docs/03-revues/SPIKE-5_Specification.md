# SPIKE-5 — Débit d'injection du simulateur

**Boîte de temps : 1 jour.** Code jetable, dans `spikes/spike-5-injection/`, jamais importé par le produit.

---

## 1. Pourquoi ce spike passe devant les spikes 2, 3 et 4

C'est un écart à l'ordre initialement planifié, et il est délibéré.

SPIKE-5 est le seul dont l'échec changerait la **taille du projet**. Si l'injection de 500 000 transactions prend huit heures, alors :

- ISC-EVAL §3.5 est infaisable en l'état et doit être révisé ;
- ADR-017 impose un autre mode d'injection ;
- la conception du générateur du mois 3 change **avant** d'être écrite.

Les spikes 2, 3 et 4 portent sur des mécanismes internes — abstraction d'horloge, chaînage d'audit, authentification mutuelle. Leur échec se corrige localement, sans toucher à un document de conception.

**Principe retenu : on lève d'abord le risque qui peut invalider un plan, pas celui qui peut compliquer une implémentation.**

---

## 2. Question principale

> Quel est le débit réel d'injection de transactions dans le sandbox, en transactions par seconde, selon le mode d'injection, et lequel permet de produire le jeu v1 dans une durée acceptable ?

**Réussite** : le jeu v1 (≥ 500 000 transactions) est produit en moins de 30 minutes par au moins un mode d'injection mesuré.

**Échec** : aucun mode ne descend sous 30 minutes. Alors la taille du jeu, la durée simulée ou le mode changent, et ISC-EVAL est révisé.

---

## 3. Questions secondaires

| # | Question | Pourquoi elle compte |
|---|---|---|
| Q1 | Quel est le débit d'un appel HTTP par transaction, avec le PDP en processus séparé ? | C'est le mode qui préserve le plus la fidélité au système réel |
| Q2 | Quel est le débit d'un appel HTTP par lot, avec N transactions par requête ? | Compromis fidélité/vitesse ; quelle valeur de N donne le meilleur gain |
| Q3 | Quel est le débit d'un appel direct en procédure, PDP embarqué dans le processus d'injection ? | Le plus rapide, le moins fidèle : la couche réseau et la sérialisation disparaissent |
| Q4 | Quelle part du temps est consommée par la base de données, par la sérialisation, par le réseau ? | Détermine ce qu'il servirait d'optimiser |
| Q5 | Le débit se dégrade-t-il quand la base grossit ? Mesure à 10 000, 100 000 et 500 000 lignes | Un débit mesuré sur une base vide est trompeur |
| Q6 | Quelle est l'empreinte disque du jeu v1 dans PostgreSQL ? | Dimensionne la VM (ISC-DEPLOY §2.1 prévoit 60 Go) |
| Q7 | Le temps virtuel avancé par lots produit-il les mêmes décisions que le temps virtuel avancé transaction par transaction ? | Question de **correction**, pas de vitesse. Voir §5 |

---

## 4. Ce qu'on injecte

Le générateur du mois 3 n'existe pas. Ce spike n'attend pas : il produit un flux de transactions **synthétiques minimales**, suffisant pour mesurer un débit.

Chaque transaction porte : un identifiant de décision (UUID v4), un identifiant de compte tiré parmi 1 000, un MSISDN de la plage fictive, un IMSI, un canal, un type d'opération, un montant entier, un identifiant de bénéficiaire, un horodatage virtuel, une localité.

Distribution : peu importe le réalisme ici. Ce spike mesure un **débit**, pas une plausibilité. Le réalisme est l'affaire du mois 3.

Le PDP n'existe pas non plus. Le spike utilise un **PDP factice** : une fonction qui applique deux ou trois règles triviales, écrit une entrée dans `pdp.decision` et une dans `pdp.audit_entry` avec son chaînage d'empreinte. C'est le coût d'écriture qui est mesuré, pas la logique de décision.

---

## 5. Le piège de ce spike, et il n'est pas la vitesse

Q7 est la question la plus importante, et elle n'est pas une question de performance.

L'injection par lots avance l'horloge virtuelle par paquets. Or plusieurs règles du PDP dépendent de l'ordre temporel : la vélocité (`VEL`, plus de 3 sorties en 10 minutes), les fenêtres de carence, l'expiration des gels et des nonces. Si l'horloge saute de dix minutes entre deux lots, une règle de vélocité peut ne jamais se déclencher — ou se déclencher à tort.

**Un mode d'injection qui va vite mais fausse les décisions est un échec, pas un succès.**

Preuve exigée : le même jeu de 10 000 transactions, injecté transaction par transaction puis par lots, doit produire **exactement les mêmes décisions**, comparées par empreinte de la séquence complète des décisions. Si elles diffèrent, le mode par lots est rejeté quelle que soit sa vitesse, ou il est corrigé jusqu'à produire l'identique.

---

## 6. Dispositif à construire

Un programme Python dans `spikes/spike-5-injection/`, sans dépendance hors bibliothèque standard sauf le pilote PostgreSQL et un client HTTP.

**Composant 1 — générateur de flux.** Produit N transactions déterministes à graine fixée, avec horodatage virtuel croissant.

**Composant 2 — PDP factice.** Deux ou trois règles triviales, écriture de la décision et de l'entrée d'audit chaînée. Utilisable en processus séparé (derrière HTTP) ou embarqué (appel direct).

**Composant 3 — quatre modes d'injection** : HTTP unitaire, HTTP par lots de 100, HTTP par lots de 1 000, appel direct.

**Composant 4 — banc de mesure.** Pour chaque mode : durée totale, transactions par seconde, p50 et p95 de latence unitaire, répartition du temps entre base, sérialisation et réseau, et empreinte disque finale.

---

## 7. Protocole de mesure

Les mesures sont faites **dans la machine virtuelle**, jamais sur le poste Windows : ISC-DEPLOY §5.2 est explicite, seule la VM produit des chiffres publiables. La configuration de la VM est rapportée avec les résultats.

Pour chaque mode :

1. Base réinitialisée (`make reset`) avant chaque mesure — un débit mesuré sur une base résiduelle ne veut rien dire.
2. Chauffe de 1 000 transactions, non comptée.
3. Mesure sur 10 000, puis 100 000, puis 500 000 transactions.
4. Trois répétitions, moyenne et écart-type rapportés.
5. Empreinte disque relevée après chaque palier.

---

## 8. Ce que `RESULT.md` doit contenir

**Question** — celle du §2.

**Dispositif** — configuration exacte de la VM (vCPU, mémoire, disque), versions de PostgreSQL et Python, description en trois lignes de ce qui a été construit.

**Résultat** — le tableau des quatre modes aux trois paliers, avec durée, débit, latences, écart-type. La réponse à Q1 à Q7 avec sa mesure. Et pour Q7, les deux empreintes de séquence de décisions côte à côte.

**Décision (ADR)** — ADR-017 passe en FERME avec le mode retenu et sa justification chiffrée, ou reste PROVISOIRE avec la raison.

**Statut** — RÉUSSI, ÉCHOUÉ, ou RÉUSSI AVEC RÉSERVES, suivi de ce que le résultat implique pour ISC-EVAL §3.5.

---

## 9. Critères d'acceptation

| # | Critère |
|---|---|
| 1 | Les quatre modes sont mesurés aux trois paliers, dans la VM |
| 2 | Au moins un mode produit 500 000 transactions en moins de 30 minutes |
| 3 | Q7 est tranchée par comparaison d'empreintes, pas par raisonnement |
| 4 | La dégradation du débit avec la taille de la base est mesurée, pas supposée |
| 5 | L'empreinte disque du jeu v1 est connue et comparée aux 60 Go de la VM |
| 6 | Aucun fichier de `spikes/` n'est importé par le produit |

---

## 10. Si le spike échoue

**Aucun mode sous 30 minutes.** Trois ajustements, dans cet ordre de préférence : réduire la durée simulée de 6 à 3 mois en conservant 1 000 agents ; puis accepter une durée de campagne plus longue en la lançant la nuit ; en dernier recours seulement, réduire le nombre de transactions. Réduire le nombre d'agents est **exclu** : la stratification par persona perdrait sa puissance statistique (ISC-EVAL §3.5).

**Le mode rapide fausse les décisions (Q7).** Le mode rapide est rejeté. On mesure alors si le mode fidèle tient dans une durée acceptable, quitte à revoir la taille du jeu.

**L'empreinte disque dépasse la VM.** Le dimensionnement d'ISC-DEPLOY §2.1 est révisé, ou le jeu est stocké hors base après génération.

Dans tous les cas : on consigne, on révise ISC-EVAL, on ne contourne pas.
