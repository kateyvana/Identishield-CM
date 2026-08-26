# Prompt à donner à Claude Code — SPIKE-1

À envoyer **après** la fusion des quatre branches et avec les deux téléphones branchés.

---

```
CONTEXTE

TACHE-02 est acceptée. La paire de preuves sur check-docs (rouge sans la branche documentaire, vert avec) établit l'ordre de fusion comme un fait mesuré. Le socle est réparé.

On ouvre SPIKE-1 : la vérification dont dépend toute la thèse cryptographique du projet.

La spécification complète est dans docs/03-revues/SPIKE-1_Specification.md. Lis-la intégralement avant d'écrire une ligne. Ce prompt ne la remplace pas : il la cadre.

CE QU'EST UN SPIKE, ET CE QU'IL N'EST PAS

Un spike est une expérience. Il répond à une question par oui ou non, puis on jette le code. Il ne produit ni architecture, ni composant réutilisable, ni interface soignée.

Le risque de cette tâche est l'inverse de d'habitude : la tentation d'en faire trop. Une application Android propre, une abstraction pour la génération de clé, un client HTTP, une couche de persistance — tout cela allongerait la boîte de temps sans rapprocher de la réponse.

Boîte de temps : 2 jours. Si à la fin du deuxième jour la question principale n'a pas de réponse, tu t'arrêtes et tu consignes ce qui bloque. Un spike qui déborde est un spike qui a changé de nature.

RÈGLE ABSOLUE

Tout le code vit dans spikes/spike-1-keystore/. Rien de ce qui y est écrit ne sera importé par core/, pdp/ ou android/. La règle d'architecture TA-SPIKE-01 le vérifie ; ne la contourne pas, même « temporairement ».

RÉPARTITION DU TRAVAIL

Partie A, application Android : le propriétaire crée le projet dans Android Studio (action manuelle, TACHE-00). Tu écris le code que tu lui indiques de coller, ou tu travailles dans le projet une fois créé — demande-lui avant, ne crée rien dans android/ ni dans spikes/spike-1-keystore/android/ sans son accord.

Partie B, vérificateur Java : elle est entièrement de ton ressort. Java 21 autonome, sans Spring, sans dépendance hors JDK.

L'ORDRE D'EXÉCUTION, ET IL N'EST PAS NÉGOCIABLE

Le piège numéro un de ce spike est l'encodage du canonical. Une signature qui ne se vérifie pas ne dit pas si c'est la crypto ou la chaîne d'octets qui est en cause. Donc :

Étape 1 — le canonical AVANT la crypto. Les deux côtés construisent la chaîne et affichent son empreinte SHA-256. Tu compares. Tant que les empreintes diffèrent sur les trois cas de test de la spécification §4, tu ne signes rien. Cette étape seule justifie le spike.

Étape 2 — la génération de clé, avec la réponse à Q1 et Q2 (StrongBox, attestation).

Étape 3 — signature et vérification du cas nominal.

Étape 4 — les quatre cas négatifs. Tous doivent produire REFUSÉE.

Étape 5 — les questions Q3 à Q7 : latence, taille, survie au redémarrage, désinstallation, export impossible.

Rapporte le résultat de chaque étape avant de passer à la suivante.

CE QUE JE VEUX DANS LE RAPPORT

Les sorties brutes, pas des résumés. En particulier :
- les deux empreintes de canonical côte à côte, pour chacun des trois cas ;
- le message exact de l'exception obtenue en tentant d'exporter la clé privée — c'est une preuve de non-exportabilité, pas une erreur ;
- pour chaque appareil : modèle, version Android, niveau d'API, et si StrongBox a réussi ou si le repli TEE a été utilisé ;
- les quatre verdicts REFUSÉE avec leur cas ;
- p50 et p95 de latence sur 100 signatures.

CONSTATÉ / DÉDUIT / SUPPOSÉ, comme dans tes trois derniers rapports.

CE QUE TU FAIS SI ÇA ÉCHOUE

Tu ne contournes pas. La spécification §9 décrit les trois scénarios d'échec et ce qu'ils impliquent. Un échec proprement consigné vaut mieux qu'un succès obtenu par un contournement non documenté : c'est précisément pour cela que le spike existe avant le développement et non après.

Si tu dois choisir entre « ça marche avec une astuce » et « ça ne marche pas », tu rapportes les deux : ce qui marche, avec quelle astuce, et ce que l'astuce coûte.

CE QUE TU NE FAIS PAS

- Aucune action distante.
- Aucun code hors de spikes/spike-1-keystore/.
- Aucune modification d'un document de docs/02-specifications/.
- Aucune création dans android/ sans accord explicite du propriétaire.
- Aucune dépendance hors JDK côté serveur, hors SDK côté Android.
- Tu ne remplis pas RESULT.md au fur et à mesure avec des résultats partiels présentés comme définitifs : tu le remplis à la fin, avec ce qui est constaté.

RENDU

Le rapport des cinq étapes, puis RESULT.md rempli selon le gabarit et la spécification §6, puis une proposition de mise à jour d'ADR-018 — FERME ou PROVISOIRE avec la raison.

Une phrase finale : ES-02 et HP-2 tiennent-elles, oui ou non, et avec quelle réserve.

Commence par lire la spécification, puis par l'étape 1.
```
