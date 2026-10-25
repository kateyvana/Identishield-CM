# Mesure T-AC-16 du gel des ré-enrôlements — refus « gelé » contre « code faux » (2026-10-25)

**Couloir A.** Ordre du superviseur technique du 2026-10-25, point 4 : mesurer, ne pas supposer. La
branche gelée de `DeviceEnrolment.register` (#106, #110) rend le **même** refus qu'un code faux
(`401 RECOVERY_INVALID`, même corps), mais fait **une écriture d'audit de plus** (`REENROL_REFUSED`,
cause `REENROL_FROZEN`) : différence de temps **déclarée** dans l'amendement ADR-030 du 25/10. Ce
document la chiffre. **Aucun seuil n'est décidé ici** : le T-AC-16 (ISC-TM §8, AC-16) exige « des
temps de réponse dans la même fenêtre statistique » sans la chiffrer ; la décision appartient au
superviseur.

## Méthode (outil, pas porte)

- `tools/measure_tac16_freeze.sh isc@192.168.56.10 200` → base PostgreSQL **jetable** `isc-tac16-pg`
  dans la VM (même image, `init.sql`, migrations 002→006), tunnel SSH vers le poste ; la pile en
  service n'est pas touchée.
- `FreezeTimingTac16Tool` (pdp, JUnit, exécuté seulement si `ISC_TAC16=1`) : `DeviceEnrolment.register`
  appelé **directement**, avec `RecoveryCodes` (PBKDF2 **44 000** itérations, valeur de production lue
  dans `application.yml`), `DeviceRegistry`, `AuditLog`, `TrustStateMachine` **réels**, throttle
  **neutralisé** (mock : jamais bloqué). Deux comptes liés (clé ACTIVE + code haché) ; le second gelé
  par `notMe(REENROL_ATTEMPT)`. Code **faux** dans les deux cas, attestation valide. 20 appels de
  chauffe puis **200 appels par cas, en alternance** (la dérive machine touche les deux cas
  pareillement). Durée mesurée au `System.nanoTime` autour de l'appel.
- Le processus Java tourne **sur le poste** et la base **dans la VM** : chaque aller-retour SQL passe
  par le tunnel SSH (poste → host-only → VM). C'est **la limite principale** de cette mesure (voir
  « Portée »).

## Résultat brut (CONSTATÉ, 2026-10-25 08:2xZ)

```text
TAC16_PBKDF2_ITERATIONS=44000
TAC16_N=200 WARMUP=20
TAC16_NOT_FROZEN_us median=77668 p95=98020 min=65570 max=108947
TAC16_FROZEN_us     median=100642 p95=126650 min=87810 max=140951
TAC16_DELTA_us      median=22974 p95=28630
TAC16_AUDIT_REENROL_REFUSED_rows=220 (attendu 220)
<testsuite name="cm.identishield.pdp.device.FreezeTimingTac16Tool" tests="1" skipped="0" failures="0" errors="0"
```

| Cas (code faux, compte lié) | médiane | p95 | min | max |
|---|---|---|---|---|
| non gelé | 77,7 ms | 98,0 ms | 65,6 ms | 108,9 ms |
| gelé (`REENROL_FROZEN`, + 1 écriture d'audit) | 100,6 ms | 126,7 ms | 87,8 ms | 141,0 ms |
| **écart** | **+23,0 ms (+30 %)** | **+28,6 ms** | | |

Les deux distributions se **chevauchent** (max non gelé 108,9 ms > min gelé 87,8 ms) mais leurs
médianes sont séparées de 23 ms : avec des mesures répétées, un observateur **distinguerait** le cas
gelé du cas non gelé dans ces conditions. Les 220 refus gelés ont chacun produit leur entrée
d'audit (une par refus, comme attendu).

## Portée et ce que la mesure ne dit pas

- Les ~23 ms de l'écart sont le coût de l'écriture d'audit **à travers le tunnel SSH** (verrou de la
  tête de chaîne + insertion + relectures : plusieurs allers-retours SQL poste→VM). **En production**
  (PDP et PostgreSQL dans la même VM, réseau Docker interne), un aller-retour SQL coûte une fraction
  de milliseconde : l'écart réel est **très probablement bien plus petit**, mais il n'est **pas
  mesuré ici** — il faudrait exécuter l'outil **dans la VM** (image `isc-pdp` avec les tests, ou
  JDK dans la VM), ce que l'ordre du jour ne prévoyait pas.
- Le temps observé par un **attaquant** passe en plus par le cœur (`/enrolment/complete` → `/device/register`
  en mTLS) et par le réseau : le bruit y est plus grand, l'écart relatif plus petit.
- Hors gel, le refus « code faux » n'écrit **aucune** entrée d'audit (il est seulement compté) ;
  c'est cette asymétrie qui crée l'écart.

## Décision demandée (je ne corrige pas seul)

L'écart dépasse ce qu'on peut appeler « même fenêtre statistique » **dans ces conditions de mesure**.
Options, à trancher par le superviseur :

1. **Mesurer d'abord en conditions réelles** (outil exécuté dans la VM, base locale) avant toute
   correction — l'écart réel peut être de l'ordre du millisecond.
2. **Symétriser** : écrire aussi une entrée d'audit pour **tout** refus de ré-enrôlement (cause
   `CODE_INVALID` / `ATTESTATION_INVALID` / `SIGNATURE_INVALID`), ce qui égalise le coût et améliore la
   traçabilité (ES-24 : la tentative échouée est aujourd'hui comptée, pas journalisée). Coût : une
   entrée d'audit par tentative échouée (bornée par le freinage ES-21).
3. **Accepter l'écart déclaré** en le consignant comme limite (ES-22 sur le contenu et le code, pas
   sur le temps à la milliseconde), si la mesure en conditions réelles le réduit sous le bruit réseau.

Recommandation du couloir A : **1 puis 2** — mesurer dans la VM, et symétriser si l'écart y reste
distinguable ; l'option 2 est de toute façon saine pour l'audit.
