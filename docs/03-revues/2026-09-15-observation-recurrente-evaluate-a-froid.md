# Observation récurrente — le premier appel à froid dégrade la mesure (EXECUTED_DEGRADED)

**Date :** 2026-09-15
**Nature :** ~~observation à trancher par le propriétaire avant le mois 5. Ne pas corriger maintenant.~~ **TRANCHÉE le 2026-10-25** (décision du superviseur technique) : voir § « Décision » en fin de document.
**Exigence concernée :** EI-01 (budget de latence 800 ms).

## Le motif

À froid, le premier appel `/evaluate` (ou la première mesure de latence) part avant que le PDP Spring Boot ne soit prêt : initialisation paresseuse du `dispatcherServlet`, ouverture du pool Hikari, JIT non chauffé. Le premier appel est donc anormalement lent, ou revient `EXECUTED_DEGRADED` ; le deuxième, à chaud, est nominal. C'est un artefact de **mesure**, pas un défaut du cœur — mais il fausse toute mesure prise au premier coup.

## Les cinq occurrences (mêmes causes, cinq contextes)

| Date | Contexte | Manifestation |
|---|---|---|
| 2026-08-27 | **SPIKE-2** (horloge injectable) | mesure prise **à froid**, non représentative |
| 2026-08-30 | **T2-07** (cœur, transfert P2P) | a nécessité un **préchauffage** explicite avant la mesure du fil |
| 2026-09-13 / 14 | **T2-10** (HLR simulé) | **flake** : premier passage dégradé, vert au suivant |
| 2026-09-15 | **check-stack** (`verify_transfer`) | premier `/evaluate` en **EXECUTED_DEGRADED** (init dispatcherServlet + Hikari ≈ 340 ms), VERT au 2e appel |
| 2026-09-15 | **T2-14** (`verify_notification`) | le transfert qui déclenche la notification revient `EXECUTED_DEGRADED` au premier `/evaluate` ; la notification est émise quand même |
| 2026-10-25 | **Fenêtre 3** (dotation réelle de 677000200, USSD, VM) | premier `/evaluate` après redémarrage du PDP : pool Hikari ouvert au premier appel (≈ 1,0 s > 800 ms) → le cœur exécute en **EXECUTED_DEGRADED** alors que le PDP a décidé `ALLOW` (audit seq 120281) ; **première occurrence sur un mouvement réel**, pas une mesure ; détail dans `2026-10-24-deploiement-main.md` § Fenêtre 3 |

Trace du garde-fou déjà en place : `tools/verify_transfer.sh:6` note que « le budget de 800 ms reste celui de la production (cœur chaud) » — reconnaissance du problème, sans décision.

## Contournements accumulés — la dette

Chaque occurrence a été **contournée**, jamais tranchée. En T2-14, l'assertion de `verify_notification.sh` a été écrite pour porter sur la PRÉSENCE de la notification (`P2P_EXECUTED`), pas sur l'état du transfert — précisément pour rester verte quel que soit `EXECUTED` ou `EXECUTED_DEGRADED`. C'est un contournement **délibéré du symptôme** : légitime une fois (l'objet de T2-14 est la notification, pas la latence), mais c'est le cinquième. Une observation qui atteint cinq occurrences et qu'on continue de contourner n'est plus une observation, c'est une **dette** : les contournements masquent le signal et un jour l'un d'eux masquera un vrai défaut. La question de la section suivante doit être **tranchée avant le mois 5** ; d'ici là, tout nouveau contournement est à consigner ici, pas à normaliser.

## Famille voisine — à garder en vue (mécanisme distinct)

Constaté en T2-15 : le **premier appel serveur→cœur de la console** (fetch Next.js vers `core:8080`), juste après le démarrage, peut échouer à froid — `verify_layerless.sh` a d'abord vu « cœur injoignable », puis VERT une fois la pile chaude. Même **motif** (« le premier appel à froid n'est pas représentatif »), mais **mécanisme différent** de celui d'`/evaluate` (serveur Next côté console, pas le PDP) : ce n'est donc PAS une sixième occurrence. Traité par une porte de disponibilité (attente que `/api/mode` réponde) dans `verify_layerless.sh`. Noté ici pour que le motif complet — « premier appel à froid », toutes couches confondues — soit visible quand la dette sera tranchée.

## La question posée (à trancher avant le mois 5)

Deux lectures possibles, mutuellement exclusives dans leurs conséquences :

1. **Le budget EI-01 de 800 ms est trop serré** s'il doit tenir *y compris* au premier appel à froid — auquel cas c'est l'exigence ou son interprétation qu'il faut réviser (par l'encadrement, `docs/02-specifications/`).
2. **La pile doit être chauffée avant toute mesure** — auquel cas 800 ms reste le budget *à chaud* (position implicite actuelle), et il faut un **préchauffage standard** avant chaque mesure publiable (un appel de rodage jeté), documenté dans le protocole d'évaluation (ISC-EVAL) et appliqué uniformément par les `verify_*`.

Le mois 5 porte l'évaluation chiffrée (ISC-EVAL) : la question doit être tranchée **avant**, sinon les chiffres publiables hériteront de l'ambiguïté. Recommandation de l'agent : lecture 2 (chauffer, 800 ms à chaud) — c'est déjà la conduite de fait et elle sépare proprement « mesure d'infrastructure » de « mesure de décision » — mais le choix appartient au propriétaire.

## Décision (2026-10-25) — TRANCHÉE

**Décision du superviseur technique du 2026-10-25 : lecture 2 (le budget EI-01 de 800 ms est celui
de la pile à chaud) ET la pile est chaude PAR CONSTRUCTION — plus aucun contournement.** Après la
sixième occurrence (fenêtre 3 : la dotation réelle de 677000200 exécutée en `EXECUTED_DEGRADED` alors
que le PDP avait décidé `ALLOW`), la question n'est plus « chauffer avant de mesurer » mais « ne
jamais servir à froid ». PR « prêt par construction » :

- **PDP** : le pool de connexions s'ouvre à l'initialisation des singletons (`DatabaseReadiness`,
  `SELECT 1`), donc **avant** l'ouverture du port 8443 ; `spring.mvc.servlet.load-on-startup=1`
  (DispatcherServlet initialisé au démarrage, pas à la première requête).
- **`/health` du PDP** = vrai aller-retour en base : `200 UP` seulement si prêt, `503 DOWN` sinon
  (`HealthController`, `@implements ES-11` inchangé).
- **Cœur** : `PdpClient.warmup()` réessaie `/health` jusqu'à 200, borné par `core.pdp.warmup-attempts`
  × `core.pdp.warmup-delay-ms` (20 × 500 ms, configuration versionnée), journalise « PDP prêt
  (essai n/N) » ou « PDP non prêt après N essais », ne lève jamais ; le serveur HTTP du cœur est déjà
  ouvert quand l'`ApplicationRunner` s'exécute : le démarrage n'est jamais bloqué.
- **Outils (#112)** : `--entrypoint java` dans les 9 outils (l'`ENTRYPOINT` de l'image est `java
  -jar`, les `-D` après le nom de l'image étaient inertes) ; `-Dspring.main.web-application-type=none`
  conservé ; **`-Dcore.pdp.timeout-ms=5000` retiré** : 800 ms = valeur de production, les outils
  mesurent la vraie chose ; la consigne « relancer si à froid » retirée de la procédure AC-02.
- **Tests unitaires** : `HealthControllerTest` (pool ouvert à la construction, 200/503, jamais de
  valeur en cache, base absente au démarrage = pas de service), `PdpClientWarmupTest` (prêt au
  premier essai sans attente ; refus puis 503 puis 200 = troisième essai, deux attentes ; jamais
  prêt = abandon après N essais sans exception ; interruption conservée).
- **Preuve (e), à la fenêtre 4, sous témoin** : trois fois « `docker restart isc-pdp` → premier
  virement réel par `verify_transfer` → `EXECUTED`, pas `DEGRADED` », sorties brutes collées ici
  (**non observée à la date de cette PR** : elle exige le déploiement).
  **Fenêtre 4 (2026-10-25, 11:19Z)** : prêt par construction **OBSERVÉ** à trois démarrages du PDP
  (« base jointe à l'initialisation » 11:19:27,07 **avant** « Tomcat started » 11:19:28,78 ; cœur
  « PDP pret (essai 3/20) »), mais la preuve (e) est **ARRÊTÉE à l'essai 1** : `REJECTED`, 0 entrée
  d'audit — le cœur a refusé avant `/evaluate` (A5-00 : 677000001 sans ligne HLR) et le compte est
  gelé jusqu'à 15:48:31Z ; pas un défaut du prêt par construction, décision demandée (HLR des
  comptes techniques), preuve 3/3 à refaire après 15:48:31Z. Détail :
  `2026-10-24-deploiement-main.md` § Fenêtre 4.
- **Registre des décisions** : ligne T-14 (PROPOSÉ, signature de la propriétaire).

Les deux « rodages jetés » de `tools/bench_evaluate` (banc de latence, JIT) ne sont pas des
contournements de cette observation : un banc jette toujours sa première mesure pour le JIT, pile
prête ou non. La « famille voisine » (premier appel serveur→cœur de la console) garde son mécanisme
distinct et sa porte de disponibilité.
