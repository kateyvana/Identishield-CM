# SPIKE-4 — Authentification mutuelle entre le cœur et le PDP

**Boîte de temps : 1 jour.** Code jetable, dans `spikes/spike-4-mtls/`, jamais importé par le produit.

---

## 1. Pourquoi ce spike existe

ES-11 exige que le canal entre le cœur opérateur et le PDP soit mutuellement authentifié. C'est la frontière de confiance (c) du modèle de menace : sans elle, deux attaques deviennent possibles sur le réseau interne.

Un adversaire qui se fait passer pour le cœur peut interroger le PDP directement, apprendre comment il décide, et cartographier les règles. Un adversaire qui se fait passer pour le PDP peut répondre `ALLOW` à tout : la couche de sécurité entière devient un décor.

ADR-021 est PROVISOIRE. Il retient le mTLS avec une autorité locale générée au démarrage, et rejette le jeton partagé au motif qu'il ne démontre pas l'authentification mutuelle. Ce spike valide ou infirme ce choix.

**Ce spike est le moins risqué des cinq sur le plan technique** — le mTLS est mature — et le plus risqué sur le plan opérationnel : il touche au démarrage de la pile, donc à ENF-05 et à la démonstration du jour J. Un certificat expiré le matin de la soutenance ferait échouer les six actes.

---

## 2. Question principale

> Deux services conteneurisés peuvent-ils s'authentifier mutuellement par certificat, avec une autorité générée automatiquement au démarrage de la pile, sans secret versionné, sans intervention manuelle, et à un coût de latence compatible avec ENF-01 ?

**Réussite** : les cinq conditions sont établies par mesure.

**Échec** : l'une n'est pas tenue. ADR-021 est rouvert, et l'alternative du jeton partagé revient sur la table — avec sa conséquence : une frontière de confiance plus faible, à déclarer.

---

## 3. Questions secondaires

| # | Question | Pourquoi elle compte |
|---|---|---|
| Q1 | L'autorité et les certificats se génèrent-ils au premier `make up` sans aucune action manuelle ? | ENF-05 : un tiers doit obtenir l'environnement en moins de dix minutes |
| Q2 | Un appel sans certificat client est-il refusé, et à quel niveau — TLS ou applicatif ? | Un refus applicatif signifie que la requête a été traitée ; ce n'est pas la même garantie |
| Q3 | Un certificat signé par une autre autorité est-il refusé ? | Vérifie que la chaîne de confiance est réellement contrainte |
| Q4 | Le cœur refuse-t-il un PDP dont le certificat ne correspond pas ? | C'est le sens « mutuel » ; la moitié souvent oubliée |
| Q5 | Quel est le surcoût de latence : p50, p95, p99 avec et sans mTLS ? | ENF-01 impose 500 ms p95 de bout en bout ; combien reste-t-il après ? |
| Q6 | La reprise de session TLS est-elle active ? Quel est l'écart entre une connexion neuve et une reprise ? | Sans reprise, chaque appel paie une négociation complète |
| Q7 | Que se passe-t-il quand un certificat expire ? Le message d'erreur est-il compréhensible ? | Le scénario du jour J |
| Q8 | Où vivent les clés privées, et sont-elles réellement absentes du dépôt et des images ? | ENF-07, et la leçon des trois privilèges PUBLIC de SPIKE-3 |
| Q9 | Le redémarrage de la pile régénère-t-il l'autorité, ou la réutilise-t-il ? Quelles sont les conséquences de chaque choix ? | Une régénération à chaque démarrage invalide les certificats en vol ; une réutilisation exige un stockage persistant |

---

## 4. Le modèle d'adversaire de ce spike

**L'adversaire dispose** d'un accès au réseau Docker interne — il peut émettre des requêtes vers n'importe quel conteneur —, de la connaissance complète des noms de service et des ports, et de la capacité d'exécuter un conteneur sur ce réseau.

**L'adversaire ne dispose pas** du système de fichiers de l'hôte, ni des volumes montés contenant les clés privées, ni du rôle administrateur Docker.

C'est cohérent avec ISC-TM §9, qui exclut l'insider technique. Ce spike ne prétend pas défendre contre quelqu'un qui possède l'hôte.

---

## 5. Ce qui doit être démontré, et dans quel ordre

**Étape 1 — la génération automatique, avant toute mesure.** L'autorité et les deux certificats se créent au démarrage, sans intervention. Si ça exige une commande manuelle, ENF-05 tombe et le reste importe peu.

**Étape 2 — le refus, avant l'acceptation.** Quatre tentatives d'appel illégitime, chacune devant échouer. Un canal qui accepte l'appel légitime mais n'a jamais refusé un appel illégitime n'a rien prouvé.

**Étape 3 — l'acceptation.** L'appel légitime passe, dans les deux sens.

**Étape 4 — le coût et le cycle de vie.** Latence, reprise de session, expiration, redémarrage.

---

## 6. Étape 2 — les quatre tentatives

Chacune émise depuis un conteneur sur le réseau interne, chacune devant être refusée :

| # | Tentative | Ce qu'elle teste |
|---|---|---|
| 1 | Appel au PDP **sans certificat client** | Le cas de base |
| 2 | Appel au PDP avec un certificat signé par une **autre autorité** | La chaîne de confiance est-elle contrainte |
| 3 | Appel au PDP avec un certificat **valide mais expiré** | La validité temporelle est-elle vérifiée |
| 4 | Un **faux PDP** répond au cœur avec son propre certificat | Le sens mutuel — celui qu'on oublie |

Pour chacune : le message d'erreur brut, et à quel niveau le refus s'est produit — poignée de main TLS ou couche applicative. La distinction compte : un refus applicatif signifie que la requête a atteint le service.

---

## 7. Étape 4 — le coût et le cycle de vie

| Mesure | Protocole |
|---|---|
| Latence sans mTLS | p50, p95, p99 sur 1 000 appels, HTTP simple |
| Latence avec mTLS, connexion neuve à chaque appel | Idem |
| Latence avec mTLS et connexions persistantes | Idem — c'est le mode réel du cœur |
| Surcoût net | La différence, en millisecondes et en pourcentage du budget ENF-01 |
| Expiration | Un certificat émis avec une validité de quelques secondes ; comportement et message observés |
| Redémarrage | `make down` puis `make up` : l'autorité est-elle régénérée ou réutilisée ? Conséquence sur les certificats existants |
| Démarrage à froid | Durée totale de `make up` avec la génération de l'autorité, comparée à sans |

Ces mesures se font **dans la machine virtuelle**, conformément à ISC-DEPLOY §5.2.

---

## 8. Le point qui décide de l'ADR

Le mTLS coûte une négociation cryptographique par connexion. Si le cœur ouvre une connexion neuve à chaque appel `/evaluate`, ce coût se paie à chaque transaction et peut consommer une part importante du budget de 500 ms.

Avec des connexions persistantes, il se paie une fois et devient négligeable.

**Ce que le spike doit établir** : le surcoût dans les deux modes, et la recommandation qui en découle pour le client PDP du cœur. Si le surcoût en connexions persistantes est inférieur à 5 % du budget, ADR-021 est confirmé sans réserve. S'il dépasse 20 %, l'alternative du jeton partagé doit être réexaminée avec sa conséquence sur la frontière de confiance.

---

## 9. Ce que `RESULT.md` doit contenir

**Question** — celle du §2.

**Dispositif** — configuration de la VM, versions de Docker et de la bibliothèque TLS, algorithme et durée de validité des certificats, description en trois lignes.

**Résultat** — la génération automatique observée ; les quatre refus avec leur message et leur niveau ; l'acceptation dans les deux sens ; le tableau de latence dans les trois modes ; le comportement à l'expiration et au redémarrage ; les réponses Q1 à Q9.

**Décision (ADR)** — ADR-021 passe en FERME avec l'algorithme, la durée de validité, le mode de génération, le stockage des clés et la recommandation sur les connexions persistantes ; ou reste PROVISOIRE avec la raison.

**Statut** — RÉUSSI, ÉCHOUÉ, ou RÉUSSI AVEC RÉSERVES, suivi de ce que le résultat implique pour ES-11, ENF-01, ENF-05 et pour le jour de la soutenance.

---

## 10. Critères d'acceptation

| # | Critère |
|---|---|
| 1 | L'autorité et les certificats se génèrent au premier démarrage, sans action manuelle |
| 2 | Les quatre tentatives illégitimes sont refusées, avec leur message et leur niveau de refus |
| 3 | Le sens mutuel est prouvé : le cœur refuse aussi un faux PDP |
| 4 | Le surcoût de latence est chiffré dans les trois modes, et rapporté au budget ENF-01 |
| 5 | Aucune clé privée n'est présente dans le dépôt ni dans une image Docker — vérifié, pas supposé |
| 6 | Le comportement à l'expiration produit un message compréhensible, et la conduite à tenir est écrite |
| 7 | La durée de `make up` reste compatible avec ENF-05 |
| 8 | Aucun fichier de `spikes/` n'est importé par le produit |

---

## 11. Si le spike échoue

**Le surcoût dépasse 20 % du budget.** L'alternative du jeton partagé revient à l'examen. Elle est plus simple et moins coûteuse, mais elle n'authentifie que dans un sens et ne résiste pas à un adversaire qui lit le jeton. Si elle est retenue, la frontière de confiance (c) est déclarée plus faible, et le mémoire le dit.

**La génération automatique exige une intervention manuelle.** ENF-05 est menacée. Options : script d'initialisation dans le conteneur, ou certificats générés une fois et stockés dans un volume persistant hors dépôt.

**L'autorité est régénérée à chaque démarrage.** Ce n'est pas un défaut en soi, mais il faut savoir ce que ça implique : aucun état ne survit entre deux démarrages, et un client conservant un ancien certificat sera refusé. À écrire dans ADR-021.

**L'expiration produit un message incompréhensible.** Ce n'est pas un échec du mTLS, c'est un risque du jour J. Le remède est une vérification de validité dans `make check-stack`, qui échoue si un certificat expire dans moins de N jours.

Dans tous les cas : on consigne, on met à jour l'ADR, on ne contourne pas.
