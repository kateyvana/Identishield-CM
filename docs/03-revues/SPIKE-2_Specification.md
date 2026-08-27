# SPIKE-2 — Abstraction temporelle injectable

**Boîte de temps : 1 jour.** Code jetable, dans `spikes/spike-2-clock/`, jamais importé par le produit.

---

## 1. Pourquoi ce spike existe

C-08 est une contrainte structurelle du projet : *le temps est une dépendance injectable ; aucune règle ne lit l'horloge système.* Elle est écrite dans ISC-ADD §2, reprise dans ENF-11, EF-18, et gravée dans le SAD §11.

Trois exigences en dépendent directement :

- **SQ-20** — un test doit pouvoir placer l'horloge à `t+47 h` après un changement d'IMSI et obtenir la décision correspondante, en moins d'une seconde réelle.
- **SQ-21** — le simulateur doit injecter six mois simulés en temps virtuel, sans qu'aucune règle ne produise un résultat différent selon la vitesse d'exécution.
- **ADR-019**, aujourd'hui PROVISOIRE, grave la forme de cette abstraction.

Ce spike ne construit pas le PDP. Il répond à une question de faisabilité et de **correction**.

Un point d'ordre : ce spike passe avant SPIKE-5 parce que la question Q7 de SPIKE-5 — *l'injection par lots produit-elle les mêmes décisions que l'injection unitaire ?* — n'est démontrable que si le mécanisme d'horloge validé ici est déterministe. SPIKE-2 fournit l'instrument que SPIKE-5 utilisera.

---

## 2. Question principale

> Une abstraction d'horloge injectable permet-elle d'évaluer des règles temporelles à un instant virtuel arbitraire, de façon déterministe, sans attente réelle, et sans qu'aucun chemin de code ne puisse contourner l'injection ?

**Réussite** : les quatre propriétés sont établies par mesure — évaluation à instant arbitraire, déterminisme, absence d'attente réelle, impossibilité de contournement.

**Échec** : l'une des quatre n'est pas établie. ADR-019 reste PROVISOIRE et la conception du moteur de règles est rouverte.

---

## 3. Questions secondaires

| # | Question | Pourquoi elle compte |
|---|---|---|
| Q1 | Quelle forme d'abstraction retenir : `java.time.Clock` du JDK, ou une interface propre au projet ? | ADR-019 doit trancher entre une primitive standard et une abstraction maison |
| Q2 | Comment une règle reçoit-elle l'instant : par injection de l'horloge dans la règle, ou par passage de l'instant en paramètre de la méthode d'évaluation ? | Détermine la signature du moteur de règles, donc l'API interne du PDP |
| Q3 | Le temps virtuel peut-il avancer par sauts arbitraires sans que les règles perdent leur ordre relatif ? | Condition de SQ-21 et de Q7 de SPIKE-5 |
| Q4 | Les échéances différées — expiration de nonce, TTL de gel, fin de carence — sont-elles évaluables à la lecture, sans ordonnanceur, ou faut-il un ordonnanceur consommant la même horloge ? | SAD §11 laisse les deux ouverts ; il faut trancher |
| Q5 | Le fuseau horaire est-il une préoccupation de l'horloge ou une préoccupation de la règle `NIGHT` ? | La fenêtre 23 h – 05 h est définie en heure serveur UTC ; il faut savoir qui porte la conversion |
| Q6 | TA-CLOCK-01, dans sa version durcie de TACHE-01, autorise-t-elle réellement toutes les formes légitimes dont ce spike a besoin ? | Une règle d'architecture trop stricte bloquerait l'implémentation ; il faut le savoir maintenant |
| Q7 | Une règle qui reçoit un instant peut-elle malgré tout lire l'horloge système par un chemin indirect — bibliothèque tierce, `Random` amorcé sur le temps, journalisation ? | C'est le trou par lequel le déterminisme se perd |

---

## 4. Ce qui doit être démontré, et dans quel ordre

L'ordre importe. Comme pour SPIKE-1, on établit la **correction** avant la commodité.

**Étape 1 — le déterminisme, avant toute règle métier.** Deux exécutions du même scénario, à la même graine et au même instant virtuel de départ, doivent produire des sorties **identiques bit à bit**, comparées par empreinte SHA-256 de la séquence complète des décisions. Tant que ce n'est pas établi, le reste ne vaut rien.

**Étape 2 — l'évaluation à instant arbitraire.** Les règles temporelles du projet sont évaluées à des instants choisis, sans attente réelle.

**Étape 3 — l'invariance à la vitesse.** Le même scénario, avancé par pas d'une seconde puis par sauts d'une heure, doit produire les mêmes décisions. C'est la préfiguration exacte de Q7 de SPIKE-5.

**Étape 4 — l'impossibilité de contournement.** Vérifiée par la règle d'architecture et par une tentative délibérée.

---

## 5. Les règles temporelles à implémenter dans le spike

Quatre règles suffisent, choisies parce qu'elles couvrent les quatre familles de dépendance au temps du projet. Elles sont **triviales** : ce spike ne valide pas leur logique métier, il valide le mécanisme d'horloge.

| Règle | Famille | Comportement minimal |
|---|---|---|
| `CarenceImsi` | Fenêtre depuis un événement passé | Retourne `EN_CARENCE` si l'instant courant est à moins de `CAR_IMSI` de l'instant du changement d'IMSI |
| `NuitOuvree` | Fenêtre récurrente dans la journée | Retourne `NUIT` si l'heure UTC de l'instant courant est dans `NIGHT` |
| `Velocite` | Comptage sur fenêtre glissante | Retourne `DEPASSEMENT` si plus de `VEL` sorties dans les 10 minutes précédant l'instant courant |
| `ExpirationGel` | Échéance future | Retourne `EXPIRE` si l'instant courant dépasse l'instant de création plus `TTL_FREEZE` |

Les paramètres nommés viennent d'un fichier de configuration, jamais en littéral — la contrainte ES-15 s'applique même dans un spike, parce que c'est elle qui permettra à SPIKE-5 et à l'analyse de sensibilité de faire varier `CAR_IMSI`.

---

## 6. Dispositif à construire

Un projet Gradle Java 21 dans `spikes/spike-2-clock/`, **sans Spring**, sans dépendance hors JDK à l'exception d'ArchUnit et JUnit pour les tests.

**Composant 1 — l'abstraction d'horloge.** Selon ce que Q1 tranche : soit `java.time.Clock` directement, soit une interface projet. Deux implémentations : réelle et virtuelle. La virtuelle doit permettre de fixer un instant, d'avancer d'une durée, et d'être interrogée.

**Composant 2 — les quatre règles**, écrites selon la forme retenue par Q2.

**Composant 3 — un évaluateur minimal** qui applique les quatre règles à un contexte et produit une décision. Aucune persistance, aucune base de données : ce spike ne mesure rien, il démontre.

**Composant 4 — un scénario rejouable** : une séquence d'événements horodatés en temps virtuel — enrôlement, changement d'IMSI, série de sorties, création de gel — qui traverse les quatre familles de règles.

**Composant 5 — la règle d'architecture**, copie de TA-CLOCK-01 dans sa version durcie, appliquée au code du spike lui-même.

---

## 7. Preuves exigées, une par question

| Question | Preuve attendue |
|---|---|
| Déterminisme | Deux empreintes SHA-256 identiques sur deux exécutions du même scénario |
| Instant arbitraire | Le scénario évalué à `t+23 h`, `t+47 h`, `t+71 h`, `t+73 h` après le changement d'IMSI, avec les décisions obtenues ; `CAR_IMSI` valant 72 h, les trois premiers doivent donner `EN_CARENCE` et le quatrième non |
| Absence d'attente | Durée réelle d'exécution du scénario complet couvrant six mois virtuels : sortie brute du chronomètre |
| Invariance à la vitesse | Deux empreintes identiques entre l'avance par pas d'une seconde et l'avance par sauts d'une heure. **Si elles diffèrent, la cause doit être identifiée et rapportée** : c'est le résultat le plus utile de ce spike |
| Q4 échéances | Le comportement observé pour les trois types d'échéance, avec la conclusion : ordonnanceur nécessaire ou non |
| Q5 fuseau | Le comportement de `NuitOuvree` avec une horloge virtuelle réglée sur un instant qui tombe dans la fenêtre en UTC mais hors fenêtre en heure locale, et inversement |
| Q6 règle d'architecture | La suite ArchUnit exécutée sur le code du spike : verte, et la liste des formes légitimes qu'elle a autorisées |
| Q7 contournement | Une tentative délibérée de lire l'heure système depuis une règle par un chemin indirect, et le verdict de la règle d'architecture face à elle |

---

## 8. Ce que `RESULT.md` doit contenir

**Question** — celle du §2.

**Dispositif** — versions du JDK, de Gradle, d'ArchUnit ; description en trois lignes de ce qui a été construit ; nombre de lignes de code du spike.

**Résultat** — les huit preuves du §7, avec leurs sorties brutes. Les empreintes côte à côte pour le déterminisme et pour l'invariance à la vitesse.

**Décision (ADR)** — ADR-019 passe en FERME avec la forme d'abstraction retenue, la manière dont une règle reçoit l'instant, le traitement des échéances et celui du fuseau ; ou reste PROVISOIRE avec la raison.

**Statut** — RÉUSSI, ÉCHOUÉ, ou RÉUSSI AVEC RÉSERVES, suivi de ce que le résultat implique pour SQ-20, SQ-21 et pour Q7 de SPIKE-5.

---

## 9. Critères d'acceptation

| # | Critère |
|---|---|
| 1 | Le déterminisme est établi par empreinte, pas par raisonnement |
| 2 | L'invariance à la vitesse est établie ou son échec est expliqué |
| 3 | Le scénario couvrant six mois virtuels s'exécute en moins d'une seconde réelle |
| 4 | Q1 à Q7 ont toutes une réponse constatée |
| 5 | La règle d'architecture est verte sur le code du spike, et la tentative de contournement est bien détectée |
| 6 | Aucun paramètre nommé n'apparaît en littéral dans le code |
| 7 | Aucun fichier de `spikes/` n'est importé par le produit |

---

## 10. Si le spike échoue

**L'invariance à la vitesse n'est pas établie.** C'est le scénario le plus probable et le plus instructif. Il signifierait qu'une règle à fenêtre glissante — la vélocité — dépend de la granularité d'avance de l'horloge. Conséquence : SPIKE-5 devra rejeter l'injection par lots, ou la conception du moteur devra rendre les règles insensibles à la granularité. **Ne contourne pas : identifie quelle règle diverge et pourquoi.**

**Le contournement reste possible.** Si une règle peut lire l'heure système par un chemin que TA-CLOCK-01 ne voit pas, la règle d'architecture doit être durcie, et le chemin documenté.

**L'abstraction impose un ordonnanceur pour les échéances.** Ce n'est pas un échec, c'est une conséquence à graver dans ADR-019 et à répercuter dans le SAD §11, qui laisse aujourd'hui les deux options ouvertes.

Dans tous les cas : on consigne, on met à jour l'ADR, on ne contourne pas.
