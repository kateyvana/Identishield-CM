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
n'est plus défendable en l'état.

Passages en VT-x (carte débit, #58), le 2026-10-23 :

| Dossier | Ce qu'il mesure |
|---|---|
| `results/2026-10-23/vm-vtx-12s/` | c4a1167 contre ab44b64, phase à 50 req/s de **12 s**, après 2 min de chauffe du poste. La fréquence effective est **remontée** à 2,8–3,4 GHz pendant la mesure (relevé `frequence-poste.csv`) : ce n'est pas un régime chaud. |
| `results/2026-10-23/vm-vtx-10min-batterie/` | c4a1167 contre ab44b64, dix minutes réelles, sans chauffe. **Mesuré SUR BATTERIE, non représentatif** : poste débranché de 14:45 à 15:24 UTC, batterie tombée à 5,9 %, fréquence effective effondrée jusqu'à 389 MHz. Voir `BATTERIE.md` et `croisement-frequence.txt`. |
| `results/<date>/vm-vtx/` | *(à refaire, sur secteur et batterie chargée)* c4a1167 contre ab44b64, dix minutes réelles : le **coût du commit 1**. |
| `results/2026-10-23/vm-vtx-main-contamine/` | `main`, moteur, dix minutes réelles. **CONTAMINÉ** : un `make check` du couloir B (16:47:26 → 16:49:29 UTC) recouvre la phase séquentielle et ~32 s de la phase à 50 req/s du tour 1 (`CONTAMINE.md`, `recouvrement.txt`). Pas un verdict. |
| `results/2026-10-23/vm-vtx-main-secteur-perdu/` | `main`, moteur, dix minutes réelles, refait. **SECTEUR PERDU** à 18:08:23 UTC pendant le tour 2, et fréquence effondrée à 389 MHz même branché (`SECTEUR-PERDU.md`). Pas un verdict. |
| `results/<date>/vm-vtx-main/` | *(à refaire)* `main`, moteur, dix minutes réelles : la **durée interne du moteur** pour le verdict ENF-01. |
| `results/2026-10-23/e2e-main/` | `main`, générateur de bout en bout `e2e_load.py`, deux tours de dix minutes réelles, **secteur tenu** de bout en bout : **la latence qui répond à ENF-01.** |

## État du verdict ENF-01 (2026-10-23, 18:20 UTC)

Le verdict cite **deux chiffres nommés**. **C'est le second qui répond à ENF-01** : ENF-01 parle de la
route `/evaluate` appelée en mTLS par le cœur.

| Chiffre | Instrument | Passage | Résultat à 50 req/s pendant dix minutes réelles |
|---|---|---|---|
| **Latence vue par un client mTLS du réseau** | `e2e_load.py` (boucle ouverte, latence depuis l'instant prévu) | `e2e-main`, `main` = `8929a3f`, secteur tenu, 60 000 requêtes, **0 erreur** | tour 1 : p95 **28,0 ms**, p99 **60,2 ms**, max 740 ms ; tour 2 : p95 **24,8 ms**, p99 **41,9 ms**, max 127 ms |
| Durée interne du moteur de décision (borne basse) | ce banc | **aucun passage valide à ce jour** (contaminé, puis secteur perdu) | observé sur les tours propres, à titre indicatif : p95 10–20 ms, p99 22–28 ms |

- **Contre le budget** (p95 < 500 ms, p99 < 800 ms), la latence vue par le client mTLS est tenue avec
  une marge de ×18 au p95 et de ×13 au p99, sur un mélange pessimiste (50 % de FREEZE, comptes neufs).
  Le mélange de SQ-30 n'est pas défini (voir « Réserves »).
- **Réserve sur `e2e-main`, tour 1.** À la minute 6 (17:37:23 → 17:38:23 UTC), une charge étrangère
  apparaît sur le poste : utilisation jusqu'à 58 %, fréquence descendue à 2,0 GHz. Elle coïncide avec le
  maximum de 740 ms. Sa source n'est pas identifiée. Aucun `make check` connu n'y tombe. Elle n'est pas
  exclue (`croisement-frequence.txt`).
- **Le verdict reste incomplet** tant que la durée interne du moteur n'a pas un passage valide.

**Risque pour la démonstration, constaté le 23/10.** Le poste peut s'effondrer à **389 MHz même sur
secteur**, batterie pleine, pour une utilisation de 13 %. C'est arrivé deux fois pendant le second
passage moteur. Pendant ces fenêtres, la latence monte à plusieurs secondes. ENF-01 tient sur une
machine à fréquence normale. Il ne tient pas sur une machine qui se met à 389 MHz. La cause (chargeur,
limite de puissance, protection thermique) n'est pas identifiée.

**HYPOTHÈSE (non prouvée) : une alimentation intermittente, par le chargeur ou la prise.** Superviseur
technique, 2026-10-23. Les deux effondrements à 389 MHz « sur secteur » (18:04:22 → 18:05:12, puis
18:07:22 → 18:08:03 UTC) seraient des micro-coupures, que le journal système n'a pas notées comme
changements de source. La coupure franche de 18:08:23 UTC (`AcOnline=false`) suivrait la même
histoire. Le passage sur batterie du même après-midi (fréquence à 389 MHz sur batterie basse) va dans
le même sens. La propriétaire vérifie son chargeur. Tant que cette hypothèse n'est pas confirmée ou
écartée, la machine de démonstration reste exposée à ce risque.

## Préconditions de mesure (décisions du 2026-10-23)

- **Dix minutes réelles**, la définition de SQ-30 (`ISC_BENCH_SQ30_SECONDS`, 600 s par défaut).
  Mesurer 12 s et extrapoler ne mesure pas l'exigence.
- **Aucune chauffe artificielle**, ni avant ni pendant. Une chauffe parallèle volerait des cœurs à
  une mesure de débit de toute la pile. Une chauffe avant ne tient pas : pendant le passage de 12 s,
  la fréquence est remontée à 2,8–3,4 GHz. Ce sont les dix minutes qui disent, par la mesure, si la
  machine se bride.
- **La machine ne force pas.** À 50 req/s, l'utilisation du poste est restée entre 14 et 33 % pendant
  le passage de 12 s, et entre 11 et 28 % pendant celui de dix minutes.
- **Secteur branché et batterie chargée**, vérifiés AVANT et APRÈS chaque passage :
  `Get-CimInstance Win32_Battery` (charge) et le journal système (Kernel-Power, événement 105,
  `AcOnline`). Le passage de dix minutes du 23/10 a été invalidé par une coupure de secteur : sur
  batterie, la fréquence s'effondre quelle que soit la charge, et les deux commits en souffrent de la
  même façon. Une montée de latence « identique sur les deux commits » peut donc venir de
  l'alimentation du poste, et non d'une limite du système.
- **Fréquence effective du poste relevée** pendant tout le passage, toutes les 10 s :
  `Get-Counter '\Processor Information(_Total)\% Processor Performance'` × 2 995 MHz (nominal,
  `Win32_Processor.MaxClockSpeed`). Sur ce poste, `Win32_Processor.CurrentClockSpeed` est **figé**
  à 1 198 MHz, au repos comme en charge : il n'est pas utilisable.
- **VM réservée** par le témoin `~/VM-OCCUPEE` (CLAUDE.md), pile du projet arrêtée.
- **Fréquence au repos ≥ 2 GHz, ou ARRÊT** (superviseur technique, 2026-10-23). Avant toute mesure,
  relever l'état du secteur, puis la fréquence effective du poste au repos pendant une minute. Si elle
  est **sous 2 GHz**, on ne mesure pas, et on le consigne.
- **Aucune charge étrangère sur le poste pendant la mesure** (règle R-Z, étendue) : pas de
  `make check`, pas de compilation, **pas de `git push`**, dans aucun couloir. La compression et
  l'envoi d'une poussée chargent le processeur. La charge étrangère de 17:37–17:39 UTC sur le passage
  de bout en bout coïncide avec une poussée du couloir B achevée à 17:40:04 UTC (journal de la
  référence distante). Son heure de début reste à confirmer.
- **Décalage d'horloge VM → poste mesuré avant ET après** chaque passage (à mi-aller-retour SSH), et
  sa dérive consignée. Une dérive significative impose de synchroniser l'horloge de la VM avant de
  mesurer.

## Marge du journal d'audit : correction d'un chiffre du SAD

Le SAD v1.4 annonce une « marge ×2,6 sur SQ-30 », d'après le « plafond réel du journal » de
SPIKE-5 (~133 écritures chaînées/s) et en supposant **une** écriture par requête. Calcul du
superviseur technique (2026-10-23) : avec le mélange de ce banc, moitié FREEZE (3 écritures
chaînées) et moitié ALLOW (1 écriture), **une requête écrit en moyenne 2 entrées**. 50 req/s font
donc 100 écritures chaînées/s, et la marge annoncée tomberait à ×1,33.

**La mesure réfute la prémisse.** En VT-x, sur secteur, la saturation (`vm-vtx-12s`, 16 fils)
atteint 299 à 423 req/s avec ce mélange, soit **~600 à 850 écritures chaînées/s**, cinq à six fois le
« plafond » de SPIKE-5. Ce plafond a été mesuré sous NEM, comme tous les spikes VM (voir la
carte #63). La marge du SAD est fausse dans les deux sens : elle suppose une écriture par requête,
et elle part d'un plafond sous-estimé.

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
- **C'est une BORNE BASSE de la latence de `/evaluate`.** ENF-01 parle de la route HTTP appelée en
  mTLS par le cœur. Ce banc donne la durée interne du moteur de décision, celle que rend aussi le
  champ `latencyMs` (les 32 ms et 11 ms de `check-stack` sont de cette nature, et non un
  bout-en-bout).

## Bout en bout : `e2e_load.py`

- **Ce qu'il fait.** Un client mTLS, placé là où est le cœur (conteneur `python:3.12-alpine` sur
  `isc-net`, certificat du cœur), envoie de vrais `POST /evaluate` en JSON, sur des connexions
  **persistantes** comme `PdpClient` (OB-6), au rythme de SQ-30 : 50 req/s pendant dix minutes. Le
  mélange est celui du banc.
- **Boucle ouverte.** Chaque latence est comptée depuis l'instant **prévu** d'envoi : si le PDP prend
  du retard, l'attente est comptée et non cachée. La durée depuis l'envoi réel est aussi relevée.
- **Ce qu'il ne mesure pas** (limite déclarée) : le temps ajouté par le client Java du cœur
  lui-même. `PdpClient` n'est pas instrumenté : c'est du code de production, et on ne le modifie pas
  pour une mesure. Ordre de grandeur disponible : `tools/bench_mtls.sh` (surcoût du transport mTLS),
  dont le chiffre de référence (1,7 ms p95, SPIKE-4) date lui aussi de l'époque NEM.
- **Statistiques :** `python tools/bench_evaluate/e2e_stats.py <dossier>`.
- **Le verdict ENF-01 citera deux chiffres, nommés :** la durée interne du moteur de décision (ce
  banc), et la latence vue par un client mTLS du réseau (`e2e_load.py`). **C'est la seconde qui
  répond à ENF-01.**

## Charge

Le plan AC-02 gelé de T3-07 (`data/eval/ac02_plan.csv`, 300 cas) fournit les victimes, les
bénéficiaires et les soldes. Chaque cas donne deux requêtes, chacune sur un compte NEUF :

- **FREEZE** : IMSI de l'attaquant, changement d'IMSI une heure avant, sortie du solde entier vers
  le bénéficiaire du plan. Le veto de PROBATION gèle le compte. Une requête FREEZE écrit TROIS
  entrées chaînées : `TRUST_PROBATION_ENTERED`, la décision `FREEZE`, puis `TRUST_FROZEN`.
- **ALLOW** : IMSI de la victime, aucun changement d'IMSI, 1 000 FCFA vers le même bénéficiaire.
  Une requête ALLOW écrit UNE entrée chaînée.

Trois phases (moitié FREEZE, moitié ALLOW) :

| Phase | Régime |
|---|---|
| `seq` | séquentiel, FREEZE et ALLOW alternés |
| `sq30` | 50 requêtes/s en boucle ouverte (rythme de SQ-30), 64 fils ; dix minutes (30 000 requêtes) depuis le 23/10, 12 s (600 requêtes) avant |
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
