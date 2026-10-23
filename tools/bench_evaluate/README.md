# Banc de latence de `/evaluate`

Mesure la latence de la chaîne `/evaluate` RÉELLE (contrôleur, journal d'audit chaîné, machine à
états de confiance, moteur de règles, veto de PROBATION, gel) sur une vraie base PostgreSQL, et
compare deux commits. Écrit pour la carte A4, afin de mesurer ce que coûte la transaction unique
« audit + gel » du commit 1 (`ab44b64`) par rapport à `c4a1167`.

Relancer : `bash tools/bench_evaluate/run.sh <dossier de résultats> [commit A] [commit B] [isc@<vm>]`
(racine du dépôt, sur le poste ; la VM doit être joignable et avoir les images `postgres:16` et
`eclipse-temurin:21-jdk-jammy`). Statistiques d'un dossier : `python tools/bench_evaluate/stats.py <dossier>`.

## Constat NEM : les résultats du 21 et du 22/10 ne sont pas représentatifs

Constaté le 2026-10-23 dans les journaux VirtualBox du poste (`VBox.log*` du serveur
`UbuntuServer_24.04_VB_LinuxVMImages.COM`, ligne `HM: HMR3Init`) :

| Démarrage de la VM (UTC) | Mode d'exécution |
|---|---|
| 2026-10-14 15:56, 2026-10-16 13:48, **2026-10-17 22:24** | `Attempting fall back to NEM: VT-x is not available` |
| **2026-10-22 16:02** | `VT-x w/ nested paging and unrestricted guest execution hw support` |

Hyper-V, actif sur le poste, empêchait VirtualBox d'utiliser VT-x. La VM tournait alors en **NEM**,
le mode de secours sur l'API de Hyper-V, deux à trois fois plus lent et irrégulier. Hyper-V a été
désactivé le 2026-10-22 à 16 h 57, heure locale : la VM tourne en VT-x depuis son démarrage de
16 h 02 UTC le même jour.

Les deux passages du banc ont tourné pendant le démarrage du 17/10 22:24, donc **sous NEM**. Leurs
résultats restent dans le dépôt, à leur place (`results/2026-10-21/`, `results/2026-10-22/`),
marqués **« mesurés sous NEM, non représentatifs »**. Rien n'est effacé. La conclusion qu'ils
portaient, « ni c4a1167 ni ab44b64 ne tiennent 50 req/s », peut être un artefact du mode NEM : elle
n'est plus défendable en l'état. Le passage de référence est celui de `results/<date>/vm-vtx/`, avec
le même `run.sh`, les mêmes commits et la même méthode (carte débit, #58).

## Pourquoi pas T3-07

`T307ReplayTest` appelle `RuleEngine` directement : ni contrôleur, ni journal, ni transaction, aucun
`latencyMs`. Il ne passe pas par le tronçon mesuré ici.

## Ce qui est mesuré

- L'appel `EvaluateController.evaluate(...)`, chronométré par `System.nanoTime()` autour de l'appel.
  C'est une latence côté serveur, SANS la couche HTTP, sans la sérialisation JSON et sans mTLS.
- Le contrôleur est construit avec ses vrais composants ; seuls le registre d'appareils et le
  magasin de nonces sont des simulacres (canal USSD, `proofType` NONE : aucune signature).
- Pool de connexions Hikari de **10** (valeur par défaut de Spring Boot, celle du PDP).
- **60 appels de rodage**, jetés avant toute mesure (observation du 2026-09-15 : le premier appel à
  froid n'est pas représentatif).

## Charge

Le plan AC-02 gelé de T3-07 (`data/eval/ac02_plan.csv`, 300 cas) fournit les victimes, les
bénéficiaires et les soldes. Chaque cas donne deux requêtes, chacune sur un compte NEUF :

- **FREEZE** : IMSI de l'attaquant, changement d'IMSI une heure avant, sortie du solde entier vers
  le bénéficiaire du plan. Le veto de PROBATION gèle le compte. Une requête FREEZE écrit TROIS
  entrées chaînées : `TRUST_PROBATION_ENTERED`, la décision `FREEZE`, puis `TRUST_FROZEN`.
- **ALLOW** : IMSI de la victime, aucun changement d'IMSI, 1 000 FCFA vers le même bénéficiaire.
  Une requête ALLOW écrit UNE entrée chaînée.

Trois phases, 600 requêtes chacune (moitié FREEZE, moitié ALLOW) :

| Phase | Régime |
|---|---|
| `seq` | séquentiel, FREEZE et ALLOW alternés |
| `sq30` | 50 requêtes/s en boucle ouverte (rythme de SQ-30), 12 s, 64 fils |
| `sat16` | saturation, 16 fils en boucle fermée ; débit = 16 / latence moyenne (loi de Little) |

## Exécution DANS la VM, et pourquoi

Le banc tourne dans un conteneur JDK 21 en réseau hôte, à côté de la base jetable : c'est la
position du PDP dans la pile. Un premier passage lancé sur le poste, avec la base jointe par
Tailscale, est **écarté** : l'aller-retour mesuré était de 14 ms en moyenne (3 à 23 ms) pour CHAQUE
instruction SQL, contre environ 0,1 ms dans la pile. Le temps de verrou mesuré était donc surtout du
réseau. Ses résultats bruts sont conservés dans `results/2026-10-21/poste-tunnel-ecarte/`, pour la
trace.

Le JDK, et non le JRE, est requis : Mockito (simulacres du registre et des nonces) a besoin de
l'attachement d'agent.

## Comparaison

Deux tours, commits **alternés** (A, B, A, B) sur la même base, pour que la dérive de la machine
pèse sur les deux. La base est créée par le `init.sql` du commit B. Entre `c4a1167` et `ab44b64`,
`init.sql` ne diffère que par un commentaire.

## Réserves

- **Le mélange n'est pas celui de la spécification.** SQ-30 (ISC-ADD v1.0, §5.4) dit : « Le
  générateur soumet 50 transactions/s pendant 10 min sur la machine de démonstration, PDP nominal ;
  mesure : /evaluate p95 < 500 ms, p99 < 800 ms, aucune décision perdue. » Il ne donne aucune
  composition de charge (part de FREEZE, comptes neufs ou connus, canal). Ni ISC-EVAL, ni ISC-TEST,
  ni le SRS ne la donnent. **Aucun verdict ENF-01 n'est donc rendu.**
- 50 % de FREEZE, des comptes tous neufs et trois écritures chaînées par FREEZE font un mélange
  pessimiste.
- Le rythme de 50 req/s tient 12 s, pas 10 min.
- Le banc et PostgreSQL se partagent les 4 vCPU de la VM.

## Résultats du 21 et du 22/10 — mesurés sous NEM, non représentatifs

Conservés tels qu'ils ont été mesurés et écrits, voir « Constat NEM » plus haut. Médiane / p95 en
ms, 600 échantillons par cellule (deux tours de 300).

| Régime | Cas | 21/10 c4a1167 | 21/10 ab44b64 | 22/10 c4a1167 | 22/10 ab44b64 |
|---|---|---|---|---|---|
| seq | ALLOW | 33 / 53 | 38 / 62 | 35 / 55 | 42 / 73 |
| seq | FREEZE | 66 / 96 | 70 / 123 | 71 / 121 | 81 / 148 |
| sat16 | ALLOW | 309 / 856 | 421 / 1 372 | 273 / 786 | 475 / 1 414 |
| sat16 | FREEZE | 553 / 1 234 | 589 / 1 454 | 491 / 1 059 | 643 / 1 707 |
| sq30 | ALLOW | 1 501 / 6 584 | 1 744 / 6 916 | 1 382 / 9 949 | 1 890 / 8 642 |
| sq30 | FREEZE | 1 902 / 6 733 | 1 346 / 6 237 | 1 946 / 9 429 | 1 516 / 8 073 |

Débit saturé (`sat16`) : 21/10, `c4a1167` 28 à 37 req/s, `ab44b64` 23 à 32 req/s ; 22/10,
`c4a1167` 36 à 37 req/s, `ab44b64` 23 à 24 req/s.

- Le 21/10, la base contenait déjà les entrées du passage écarté (exécution à la main, mêmes
  étapes que `run.sh`). Le 22/10, `run.sh` est parti d'une base neuve.
- Écrit le 22/10, avant le constat NEM, et caduc depuis : « Seul ce qui suit est établi : NI
  `c4a1167` NI `ab44b64` ne tiennent 50 req/s sur ce mélange. L'écart imputable au commit 1 est de
  4 à 10 ms de médiane en séquentiel, et de 15 à 37 % de débit saturé (par tour : −19 et −15 % le
  21/10, −37 et −33 % le 22/10). »
- Résultats bruts : `results/<date>/vm/result-<commit>-<tour>.csv` (une ligne par requête :
  phase, cas, décision, durée en µs, `latencyMs` rendu par le contrôleur) ; `stats.txt` à côté.
