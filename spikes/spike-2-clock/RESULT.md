# Résultat du spike

## Question

Une abstraction d'horloge injectable permet-elle d'évaluer des règles temporelles à un
instant virtuel arbitraire, de façon déterministe, sans attente réelle, et sans qu'aucun
chemin de code ne puisse contourner l'injection ? → **OUI, avec une réserve sur le
contournement** (voir preuve 8 : la règle d'architecture actuelle a quatre angles morts,
tous fermés par le durcissement proposé).

## Dispositif

- JDK : Temurin 21.0.5 LTS. Gradle : 8.14.3 (wrapper). ArchUnit : 1.3.0.
  JUnit : 5.10.2. commons-lang3 3.14.0 — uniquement dans le source set
  `contournement`, jamais sur le classpath d'exécution (levée du propriétaire pour la
  tentative « bibliothèque tierce »).
- Construit : une `VirtualClock` (java.time.Clock mutable, paquet `time`), quatre règles
  triviales pures couvrant les quatre familles de dépendance au temps (instant reçu en
  paramètre), un évaluateur à lecture unique de l'horloge, un scénario à graine de six
  mois virtuels (180 événements, rafales sous 10 minutes), l'empreinte SHA-256 de la
  séquence des décisions, et la copie conforme de TA-CLOCK-01 durcie confrontée à six
  tentatives de contournement compilées à part.
- Volume : 573 lignes (production) + 139 (contournement) + 225 (tests) + 7 (config).
- Paramètres nommés (ES-15) dans `config/parameters.properties` : CAR_IMSI=PT72H,
  NIGHT=23:00-05:00, VEL=4, VEL_WINDOW=PT10M, TTL_FREEZE=PT24H. Aucun littéral en code.
- Reproduction : `./gradlew run --args="step1|step2|step3"` ; `./gradlew test`.

## Résultat

**1. Déterminisme** (deux exécutions, graine 42, départ 2026-01-01T00:00:00Z, chaque
ligne couple l'événement et l'instant relu depuis l'horloge) :

```
run1.sha256=2cb8915cc592152f7214d3b43d71dc39939409f513591fe500866b2a803d7d03
run2.sha256=2cb8915cc592152f7214d3b43d71dc39939409f513591fe500866b2a803d7d03
identical=true
```

**2. Instant arbitraire** (CAR_IMSI=PT72H lu de la configuration ;
imsi_change=2026-02-05T16:38:50Z) :

```
t+23h  clock=2026-02-06T15:38:50Z|CARENCE=EN_CARENCE|NUIT=-|VEL=-|GEL=-
t+47h  clock=2026-02-07T15:38:50Z|CARENCE=EN_CARENCE|NUIT=-|VEL=-|GEL=-
t+71h  clock=2026-02-08T15:38:50Z|CARENCE=EN_CARENCE|NUIT=-|VEL=-|GEL=-
t+73h  clock=2026-02-08T17:38:50Z|CARENCE=-|NUIT=-|VEL=-|GEL=-
```

**3. Absence d'attente** (six mois virtuels, 180 événements, chronométrage brut,
JVM chaude ; 1re exécution à froid du mode PT1S : 1 474 ms, échauffement JIT) :

```
event-jump  duration_ms=241   (177 et 616 sur les autres exécutions)
step-PT1S   duration_ms=738   (~15,7 millions de ticks d'une seconde)
step-PT1H   duration_ms=28
```

**4. Invariance à la vitesse** (empreintes côte à côte, stables sur trois JVM) :

```
step-PT1S   sha256=ba5bf35257264c95f4eee5d6cbf98802a8c9447d66cc313b37ca8f684e232b55
step-PT1H   sha256=ba5bf35257264c95f4eee5d6cbf98802a8c9447d66cc313b37ca8f684e232b55
event-jump  sha256=ba5bf35257264c95f4eee5d6cbf98802a8c9447d66cc313b37ca8f684e232b55
identical(all)=true
verdicts sollicités : EN_CARENCE=3, NUIT=49, DEPASSEMENT=23, EXPIRE=84
```

CONSTATÉ : identiques — y compris le mode « saut d'événement en événement », qui est
le mode par lots de SPIKE-5. La vélocité a bien tiré (23 DEPASSEMENT sur les rafales).
DÉDUIT : l'invariance est une propriété des **règles**, pas de l'horloge — elles sont
des fonctions pures de (historique, instant) et recomptent leur fenêtre à chaque
évaluation ; aucun état n'est élagué au fil des ticks. Un compteur incrémental élagué
à la volée — la forme naturelle en production — la casserait.

**5. Q4 échéances** : les trois échéances différées (fin de carence, expiration de
gel, fenêtre de vélocité) sont évaluées **à la lecture**, sans ordonnanceur : le
verdict à t+73 h est correct sans qu'aucun code n'ait « tourné » entre t+71 h et
t+73 h (preuve 2 ; EXPIRE=84 en preuve 4). Aucun ordonnanceur n'est nécessaire pour
décider ; un ordonnanceur ne serait requis que pour des effets spontanés (notifier une
expiration sans requête), hors du besoin du PDP qui décide toujours sur sollicitation.

**6. Q5 fuseau** : l'horloge fournit un `Instant` sans fuseau ; la conversion est
portée par la règle `NuitOuvree` en UTC (ED-04). Preuve par les bornes : 23:38:50 UTC
(dans NIGHT UTC, serait 00:38 en heure de Yaoundé UTC+1) → NUIT ; 15:38:50 UTC → `-`.
49 verdicts NUIT sur le scénario, tous cohérents avec la fenêtre UTC 23:00–05:00.

**7. Q6 règle d'architecture** : TA-CLOCK-01 (copie conforme de la version durcie
TACHE-01) est **verte** sur le code du spike. Formes légitimes qu'elle a autorisées :
`clock.instant()` dans l'évaluateur ; `System.nanoTime` dans `time/Stopwatch`
(exemption du paquet `time`) ; `new Random(graine)` avec graine explicite dans le
générateur ; toute l'arithmétique `Instant`/`Duration`. Rien de nécessaire n'a été
bloqué.

**8. Q7 contournement** — six tentatives délibérées, verdicts bruts d'ArchUnit :

| Tentative | TA-CLOCK-01 actuelle | Durcissement proposé |
|---|---|---|
| `Instant.now()` | DÉTECTÉE | DÉTECTÉE |
| `System.currentTimeMillis()` | DÉTECTÉE | DÉTECTÉE |
| `new Random()` sans argument | **NON VUE** | DÉTECTÉE |
| Bibliothèque tierce (commons StopWatch) | **NON VUE** | DÉTECTÉE |
| Journalisation horodatée (java.util.logging) | **NON VUE** | DÉTECTÉE |
| `UUID.randomUUID()` | **NON VUE** | DÉTECTÉE |

Démonstration d'exécution : `new Random()` produit deux verdicts différents pour le
même (historique, instant) — `ALEA--2254823839271467739` puis
`ALEA-8184078426163219524`. Idem `UUID.randomUUID()`. CONSTATÉ : l'algorithme
SecureRandom par défaut de cette JVM est **DRBG** — l'entropie de `randomUUID` vient
de l'OS, pas de l'horloge ; la menace est le non-déterminisme, pas la lecture du temps.
Le durcissement proposé (voir Décision) reste vert sur le code légitime du spike.

**Réserve explicite** : ces résultats valent pour quatre règles triviales et **pures**.
Rien ne garantit qu'une règle future le soit — c'est précisément pourquoi la pureté
doit devenir une obligation écrite d'ADR-019 plutôt qu'un constat de spike.

## Décision (ADR)

Proposition : **ADR-019 passe en FERME** avec le contenu suivant (l'acte de fermeture
appartient au propriétaire) :

- **Forme (Q1)** : `java.time.Clock` du JDK comme abstraction — `Clock.systemUTC()`
  en production (confiné au paquet `time`), `VirtualClock` mutable du projet pour
  tests et simulation. Pas d'interface maison : la primitive standard suffit et
  l'écosystème (`Instant.now(clock)`) la comprend.
- **Signature (Q2)** : l'horloge n'est injectée qu'à la frontière de l'évaluateur,
  qui lit l'instant **une seule fois** par évaluation ; chaque règle reçoit cet
  instant en paramètre et n'a jamais accès à une horloge.
- **Contrainte de pureté (issue de l'étape 3, formulée comme obligation)** : toute
  règle temporelle du moteur DOIT être une fonction pure de (historique, instant) et
  recalculer sa fenêtre à chaque évaluation ; aucun état mutable élagué au fil du
  temps n'est admis dans une règle. **Coût nommé et assumé** : le recomptage par
  évaluation coûte plus cher qu'un compteur incrémental ; ce coût est accepté pour
  préserver la reproductibilité de l'évaluation (SQ-21, Q7 de SPIKE-5). Tout
  problème de latence futur se traite par structure d'index immuable, pas par état
  mutable dans la règle.
- **Échéances (Q4)** : évaluation à la lecture ; pas d'ordonnanceur dans le moteur.
- **Fuseau (Q5)** : l'horloge livre des `Instant` sans fuseau ; la conversion UTC
  appartient à la règle qui en a besoin (`NIGHT`), conformément à ED-04.

Tâche distincte à ouvrir (ne fait pas partie de ce spike) : durcir TA-CLOCK-01 dans
core et pdp — ajouter le constructeur `Random()` sans argument, `UUID.randomUUID()`,
l'interdiction de journalisation dans les règles, et la liste noire des enveloppes
temporelles tierces de la stack (`org.apache.commons.lang3.time.*`). La version
mesurée ici est dans `src/test/.../ArchitectureTest.java` (TA_CLOCK_01_DURCI).

## Statut

**RÉUSSI AVEC RÉSERVES.**

- SQ-20 tient : décision correcte à t+47 h en 0 attente réelle, millisecondes.
- SQ-21 tient : six mois virtuels < 1 s, décisions invariantes à la granularité —
  **à condition** que la contrainte de pureté ci-dessus soit gravée et tenue.
- Q7 de SPIKE-5 : le mode par lots est envisageable — le mode « saut d'événement en
  événement » produit une empreinte identique aux modes pas-à-pas.
- Réserves : (1) la pureté des règles est démontrée sur quatre règles triviales, pas
  garantie pour les règles futures → obligation ADR-019 ; (2) TA-CLOCK-01 actuelle ne
  voit pas quatre chemins de contournement indirects → tâche de durcissement.
