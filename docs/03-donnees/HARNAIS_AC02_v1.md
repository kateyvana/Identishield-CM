# HARNAIS_AC02 — Évaluation de référence d'AC-02 (T3-04b)

**Version :** 1.0 — **Date :** 2026-09-27
**Étudiante :** YENGUE MESSAH KATE YVANA — ICTU20234409
**Statut :** livré par la supervision, à intégrer et exécuter par le propriétaire.

## 1. Objet et nature

Ce harnais **mesure une défense**. Il n'introduit aucune capacité d'attaque
nouvelle : il **orchestre le scénario de test AC-02 déjà présent dans le dépôt**
(l'outil `isc_attacker ac02`, fusionné et vert depuis le Mois 2), une fois par
cas d'un plan figé, contre la **pile isolée** du bac à sable (`isc-net`,
`internal: true`, aucune sortie, données fictives, plage MSISDN 677 000 000 –
677 009 999, IMSI 624-01). Il **consigne** ensuite le résultat des quatre
oracles d'AC-02 (ISC-TM §8).

Il n'existe qu'**une seule définition d'AC-02** dans le projet : celle du dépôt.
Ce harnais ne la réécrit pas ; il l'appelle.

## 2. Pourquoi une évaluation à N cas (et non une seule)

La contribution du mémoire est de **mesurer** la protection apportée par la
couche. Une seule exécution est une anecdote ; N exécutions étiquetées donnent
une puissance statistique :

- **Suréchantillonnage assumé et déclaré.** N = 300 cas ne représentent **pas**
  la fréquence réelle de la fraude au Cameroun. Ils servent uniquement à estimer
  le taux d'échec de la défense.
- **TPR et FPR sont indépendants de la prévalence.** Ils se mesurent sur ce jeu
  suréchantillonné sans biais.
- **Précision et volume d'alertes dépendent de la prévalence** : ils seront
  **recalculés à une prévalence réaliste** au Mois 5, à partir du même plan.
- **Règle de trois :** si les 300 cas passent tous (couche éteinte) et qu'au
  Mois 5, couche active, 0 aboutit, on affirme avec 95 % de confiance que le
  taux d'échec de la défense est **< 1 %** (3 / 300).

## 3. État actuel : la mesure « avant »

À ce stade, **la couche est éteinte** (mode « layerless »). La mesure de
référence attendue est donc que **les 300 cas réussissent l'attaque** : c'est le
« avant » contre lequel le « après » (Mois 5, couche active) sera comparé. Un cas
qui échouerait maintenant signalerait un défaut du banc d'essai, pas une
protection.

## 4. Deux vues séparées (règle anti-fuite de M4)

| Fichier | Contenu | Lecteur |
|---|---|---|
| `data/eval/ac02_plan.csv` | plan figé : run_id, victime, IMSI, instant, bénéficiaire attaquant, IMSI attaquant, solde à placer | données, aucune exécution |
| `data/eval/ac02_runs.csv` | faits mesurés : les 4 oracles par cas, `all_oracles_ok`, durée | vue d'évaluation |
| `data/eval/ac02_runs.labels.csv` | vérité-terrain : run_id, `label=FRAUD`, source | sidecar, jamais dans une vue détecteur |

Le test anti-fuite vérifie qu'aucune colonne de vérité-terrain (label, archétype,
« victime », fraud, ground_truth, intent, legit) n'entre dans une vue destinée au
détecteur, et que vue et sidecar ne partagent que `run_id`.

## 5. Reproductibilité

- Le **plan** est entièrement dérivé de `PLAN_SEED = 20260927`. Deux exécutions
  produisent un fichier identique à l'octet (double hash vérifié par test).
- Le **consignateur** est déterministe en `--dry-run` (tests hors sandbox). En
  exécution réelle, le non-déterminisme provient uniquement de la pile (durées,
  identifiants de décision), pas du harnais.
- Le plan, une fois figé, est versionné : n'importe qui peut rejouer les mêmes
  300 cas au Mois 5.

## 6. Deux modules, deux rôles

- `tools/eval_harness/ac02_plan.py` — **produit le plan**. Pur calcul, aucune
  exécution, aucune pile. Sélectionne 300 victimes parmi les personas
  « ordinaire » de la vue détecteur, à un instant tiré, en **excluant** les
  usagers porteurs d'un changement de SIM légitime dans une fenêtre de 72 h
  (sinon on mélangerait le signal fraude et le signal remplacement légitime).
- `tools/eval_harness/ac02_consign.py` — **orchestre et consigne**. Pour chaque
  cas : place un solde fictif sur la victime, bascule la pile en mode éteint
  (comme `verify_attacker.sh`), appelle le scénario existant, lit ses oracles,
  écrit les deux vues, puis restaure le mode protégé. Le mode `--dry-run`
  n'appelle ni la pile ni Docker et sert aux tests.

## 7. Points d'intégration — vérifiés le 2026-09-27 contre le dépôt réel

Les interfaces ont été confirmées contre `attacker/src/isc_attacker/cli.py` et
`tools/verify_attacker.sh`, et le harnais a été adapté au dépôt (jamais l'inverse).
État des quatre points anciennement marqués `TODO-INT` :

1. **CLI** — `python -m isc_attacker ac02` a **six** options obligatoires :
   `--victim`, `--victim-imsi`, `--attacker-imsi`, `--beneficiary`, `--agent-id`,
   `--motif`. Les deux dernières manquaient au harnais ; ajoutées, avec les valeurs
   éprouvées de `verify_attacker.sh` (`agent-kyc-07`, `« remplacement SIM »`).
2. **Oracles** — lignes `ORACLE_*` de `cli.py`. Attention : `ORACLE_OUTPUT_BENEFICIARY`
   ne fait qu'**écho de l'entrée**, il n'est pas probant seul. « Sortie aboutie » se
   mesure donc sur `ORACLE_OUTPUTS ≥ 1` **et** `ORACLE_OUTPUT_STATE == EXECUTED`
   (comme la vérif SQL `count(*) … state='EXECUTED'` du script). Oracle renforcé.
3. **Solde/HLR** — schéma réel : `core.account(msisdn, balance_xaf, created_at)`
   et `core.hlr_subscriber(msisdn, current_imsi, updated_at)` (pas de table `account`
   unique). `seed_victim` réécrit à l'identique du script : purge préalable, compte
   victime + bénéficiaire, HLR ; via `docker exec isc-postgres psql -U isc_admin
   -d identishield -tAc`. Invocation Docker alignée : `python:3.12-alpine` +
   montage de `attacker/src`, pas d'image `isc-attacker:latest`.
4. **Mode** — `POST CONSOLE/api/mode`, corps `{"layerless": true|false}` ; la boucle
   d'attente guette la présence de la clé `"layerless"` (porte de disponibilité
   console), et non plus sa valeur (bug corrigé pour `layerless=false`).

## 8. Limites déclarées

- La sélection des victimes suppose la vue détecteur du jeu de dev disponible ;
  le plan ne modifie jamais cette vue.
- Le placement d'un solde fictif crée des comptes de test dans la pile ; ils sont
  dans la plage fictive et la pile est jetable.
- Aucune mesure de sécurité n'est tirée d'une exécution : c'est un banc d'essai,
  pas une preuve de sécurité en soi.
