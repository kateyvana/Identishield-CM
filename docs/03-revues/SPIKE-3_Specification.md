# SPIKE-3 — Journal d'audit inaltérable

**Boîte de temps : 1 jour.** Code jetable, dans `spikes/spike-3-audit-chain/`, jamais importé par le produit.

---

## 1. Pourquoi ce spike existe

ES-09 exige que toute décision et toute action d'analyste soient journalisées en écriture seule, chaque enregistrement portant l'empreinte SHA-256 du précédent, avec une empreinte de tête signée et exportée périodiquement.

Cette exigence répond à AC-09 et AC-10 : un analyste corrompu qui lève un gel qu'il ne devrait pas lever, et quelqu'un qui modifie le journal pour effacer sa trace. Le modèle de menace place T-04 — l'analyste malveillant — **dans** le périmètre.

Trois suppositions n'ont jamais été vérifiées :

- qu'un rôle PostgreSQL restreint à `INSERT` **empêche réellement** toute modification, y compris par des chemins indirects ;
- que le chaînage détecte toute altération, y compris la suppression d'une ligne entière ou la réécriture de la chaîne complète ;
- que le coût d'écriture du chaînage reste compatible avec ENF-01 et SQ-30.

ADR-020 est PROVISOIRE en attente de ces réponses.

Ce spike a une seconde raison d'être, propre à ton projet : **c'est la démonstration la plus visuelle de ta soutenance.** L'acte 4 d'ISC-DEMO consiste à altérer une ligne du journal en direct et à montrer la rupture détectée. Ce spike valide que cette démonstration est possible.

---

## 2. Question principale

> Un journal en écriture seule au niveau du rôle base de données, chaîné par empreintes SHA-256 et ancré périodiquement, résiste-t-il à un adversaire disposant du rôle applicatif, et détecte-t-il toute altération, y compris une réécriture complète de la chaîne ?

**Réussite** : le rôle applicatif ne peut ni modifier ni supprimer ; toute altération est détectée et localisée ; une réécriture complète est détectée par l'ancrage.

**Échec** : un chemin de modification subsiste, ou une altération passe inaperçue. ADR-020 reste PROVISOIRE et ES-09 est rouverte.

---

## 3. Questions secondaires

| # | Question | Pourquoi elle compte |
|---|---|---|
| Q1 | Le rôle applicatif peut-il contourner la restriction par `TRUNCATE`, `COPY`, une fonction `SECURITY DEFINER`, ou en modifiant la séquence ? | Une restriction qui ne couvre que `UPDATE` et `DELETE` est incomplète |
| Q2 | Que contient exactement l'empreinte d'un enregistrement ? | Si un champ est hors du calcul, il est modifiable sans rompre la chaîne |
| Q3 | La suppression d'une ligne du milieu est-elle détectée ? Et la suppression de la dernière ligne ? | La seconde est le cas difficile : rien ne pointe vers elle |
| Q4 | Une réécriture complète de la chaîne — l'adversaire recalcule toutes les empreintes — est-elle détectée ? | C'est la seule attaque que le chaînage seul ne voit pas. L'ancrage doit la couvrir |
| Q5 | Quel est le coût d'écriture : latence par enregistrement, débit maximal, et impact sur SQ-30 ? | Le chaînage sérialise les écritures : c'est un goulot potentiel |
| Q6 | Que se passe-t-il si deux écritures concurrentes tentent de chaîner sur la même tête ? | Le PDP traitera des requêtes concurrentes ; la chaîne doit rester cohérente |
| Q7 | La vérification d'intégrité de 500 000 enregistrements prend combien de temps ? | Elle doit être exécutable devant un jury, en direct |
| Q8 | Comment l'ancrage est-il signé, et où la clé de signature vit-elle ? | Un ancrage signé par une clé accessible à l'adversaire ne prouve rien |

---

## 4. Le modèle d'adversaire de ce spike

Précisément défini, parce que tout en dépend :

**L'adversaire dispose** du rôle applicatif `isc_pdp_app` et de ses privilèges, d'un accès SQL direct avec ce rôle, et de la connaissance complète du schéma et de l'algorithme de chaînage.

**L'adversaire ne dispose pas** du rôle administrateur de la base, ni d'un accès au système de fichiers du serveur, ni de la clé de signature des ancrages.

C'est le modèle T-04 d'ISC-TM : l'analyste corrompu agit à travers l'application, pas à travers l'infrastructure. Un administrateur de base malveillant est **hors modèle** — ISC-TM §9 l'exclut explicitement, et ce spike ne prétend pas y répondre.

---

## 5. Ce qui doit être démontré, et dans quel ordre

**Étape 1 — l'imperméabilité du rôle, avant tout chaînage.** Huit tentatives de modification avec le rôle applicatif, chacune devant échouer. Tant qu'un chemin de modification subsiste, le chaînage est décoratif.

**Étape 2 — la détection d'altération.** Le chaînage construit, puis six scénarios d'altération exécutés avec le rôle **administrateur** — puisque le rôle applicatif ne peut rien —, chacun devant être détecté et localisé.

**Étape 3 — la réécriture complète et l'ancrage.** Le cas que le chaînage seul ne peut pas voir.

**Étape 4 — le coût.** Latence, débit, concurrence, durée de vérification à l'échelle du jeu v1.

---

## 6. Étape 1 — les huit tentatives

Chacune exécutée avec le rôle `isc_pdp_app`, chacune devant produire une erreur de permission :

| # | Tentative | Ce qu'elle teste |
|---|---|---|
| 1 | `UPDATE` sur une ligne | Le cas évident |
| 2 | `DELETE` sur une ligne | Le cas évident |
| 3 | `TRUNCATE` sur la table | Privilège distinct de `DELETE` en PostgreSQL |
| 4 | `DROP TABLE` | Nécessite `OWNER`, à vérifier |
| 5 | `ALTER TABLE` pour retirer une contrainte | Un adversaire pourrait vouloir désactiver une contrainte avant d'agir |
| 6 | `setval` sur la séquence | Réutiliser un numéro d'ordre pour insérer un doublon |
| 7 | `COPY` en écriture vers la table | Chemin d'écriture distinct de `INSERT` |
| 8 | Appel d'une fonction `SECURITY DEFINER` existante qui écrirait sur la table | L'élévation de privilège par fonction |

Preuve exigée : la sortie brute de chaque tentative, avec le message d'erreur PostgreSQL.

Puis, à l'inverse : la liste des privilèges **effectifs** du rôle, obtenue par requête sur les catalogues système — pas la liste des `GRANT` écrits. Les deux peuvent différer par héritage.

---

## 7. Étape 2 — les six altérations

Exécutées avec le rôle administrateur, puisque l'application ne peut rien. Chacune doit être détectée, et sa position exacte rapportée :

| # | Altération | Difficulté |
|---|---|---|
| 1 | Modifier le contenu d'un enregistrement du milieu | Cas de base |
| 2 | Modifier l'empreinte d'un enregistrement | Le chaînage doit rompre en aval |
| 3 | Supprimer un enregistrement du milieu | Rupture de continuité |
| 4 | Supprimer le dernier enregistrement | **Le cas difficile** : rien ne pointe vers lui |
| 5 | Insérer un enregistrement entre deux existants | Rupture d'ordre |
| 6 | Échanger deux enregistrements adjacents | Les empreintes doivent rendre l'ordre inviolable |

Le cas 4 mérite une attention particulière. Si la vérification consiste seulement à parcourir la chaîne du début à la fin, supprimer la dernière ligne produit une chaîne parfaitement valide, simplement plus courte. **C'est exactement ce que ferait un analyste voulant effacer sa dernière action.** L'ancrage doit couvrir ce cas, ou un compteur, ou les deux.

---

## 8. Étape 3 — la réécriture complète

L'adversaire, avec l'accès administrateur, recalcule **toutes** les empreintes après avoir modifié un enregistrement. La chaîne redevient valide de bout en bout.

Le chaînage seul ne peut pas détecter cela. Seul l'ancrage le peut : une empreinte de tête, signée, exportée hors de la base à intervalle régulier.

Ce que le spike doit établir : où vit la clé de signature, comment l'ancrage est exporté, et quelle est la fenêtre de vulnérabilité — c'est-à-dire combien d'enregistrements peuvent être réécrits entre deux ancrages sans être détectés.

Cette fenêtre est un paramètre de conception, et elle doit être nommée : `ANCHOR_INTERVAL`, à ajouter aux paramètres nommés si le spike confirme le mécanisme.

---

## 9. Étape 4 — le coût

| Mesure | Protocole |
|---|---|
| Latence d'écriture | p50, p95, p99 sur 10 000 insertions chaînées |
| Débit maximal | Insertions par seconde, avec le chaînage actif |
| Concurrence | Dix écrivains simultanés : la chaîne reste-t-elle cohérente ? Quel mécanisme la sérialise ? |
| Vérification complète | Durée de vérification de 10 000, 100 000, 500 000 enregistrements |
| Impact SQ-30 | Le chaînage tient-il 50 transactions/s ? |

Ces mesures se font **dans la machine virtuelle**, conformément à ISC-DEPLOY §5.2.

---

## 10. Ce que `RESULT.md` doit contenir

**Question** — celle du §2.

**Dispositif** — configuration de la VM, version de PostgreSQL, schéma de la table, algorithme de chaînage, description en trois lignes.

**Résultat** — les huit tentatives de l'étape 1 avec leur message d'erreur ; les six altérations de l'étape 2 avec leur détection et leur position ; le résultat de l'étape 3 avec la fenêtre de vulnérabilité chiffrée ; les mesures de l'étape 4 ; les réponses Q1 à Q8.

**Décision (ADR)** — ADR-020 passe en FERME avec le schéma de la table, la composition de l'empreinte, le mécanisme d'ancrage et son intervalle ; ou reste PROVISOIRE avec la raison.

**Statut** — RÉUSSI, ÉCHOUÉ, ou RÉUSSI AVEC RÉSERVES, suivi de ce que le résultat implique pour ES-09, AC-09, AC-10 et pour l'acte 4 d'ISC-DEMO.

---

## 11. Critères d'acceptation

| # | Critère |
|---|---|
| 1 | Les huit tentatives de modification échouent, avec leur message |
| 2 | Les six altérations sont détectées **et localisées**, y compris la suppression de la dernière ligne |
| 3 | La réécriture complète est détectée par l'ancrage, et la fenêtre de vulnérabilité est chiffrée |
| 4 | Les privilèges effectifs sont mesurés sur les catalogues système, pas déduits des `GRANT` |
| 5 | Le chaînage tient 50 transactions/s, ou son incapacité est chiffrée |
| 6 | La vérification de 500 000 enregistrements est exécutable en un temps compatible avec une démonstration |
| 7 | Aucun fichier de `spikes/` n'est importé par le produit |

---

## 12. Si le spike échoue

**Un chemin de modification subsiste.** Le rôle est resserré, et la tentative qui a réussi devient un test permanent — comme l'a été le test `UPDATE` de TACHE-00.

**La suppression de la dernière ligne n'est pas détectée.** Le mécanisme est incomplet. Deux options : un compteur d'enregistrements dans l'ancrage, ou un ancrage plus fréquent. À trancher sur mesure, pas sur préférence.

**Le coût est incompatible avec SQ-30.** Le chaînage sérialise les écritures ; c'est structurel. Options, dans cet ordre : chaînage par lot avec une empreinte couvrant N enregistrements ; ou écriture asynchrone avec fenêtre de perte bornée et nommée. La seconde affaiblit ES-09 et devrait être documentée comme telle.

**La vérification est trop lente pour une démonstration.** L'acte 4 d'ISC-DEMO est revu : vérification sur un sous-ensemble, ou vérification incrémentale depuis le dernier ancrage.

Dans tous les cas : on consigne, on met à jour l'ADR, on ne contourne pas.
