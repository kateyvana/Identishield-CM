# AC-02 de bout en bout sur la pile — pièce de démonstration

**Date du passage :** 2026-10-23, 11 h 44 (UTC)
**Commit exécuté :** `9711569` (branche `ci-01-verification-manuelle`). Il entre dans `main` par la
**pull request #61**, sans modification : aucun amend, aucun rebase, aucune poussée forcée après ce
passage. Les commits ajoutés ensuite par-dessus ne touchent que des documents.
**Machine :** VM `isc-vm` (`UbuntuServer_24.04_VB_LinuxVMImages.COM`), Ubuntu 24.04, 4 vCPU, en **VT-x**
(démarrage du 2026-10-22 à 16 h 02 UTC ; voir « Constat NEM » dans `tools/bench_evaluate/README.md`).
**Pile :** images `isc-pdp` et `isc-core` construites le 2026-10-22 à 14 h 34 (UTC) depuis `main` =
`9064e00`. Entre `9064e00` et `9711569`, aucun fichier ne change dans `core/`, `pdp/`, `infra/`,
`attacker/`, `sim/`, `console/` ni `config/` : le code exécuté est celui de `9711569`.
**Sortie brute :** `2026-10-23-ac02-bout-en-bout-sortie.txt`, à côté de ce document. Les deux OTP
interceptés y sont masqués.

## Ce que la démonstration prouve

AC-02 (ISC-TM, S-02 / T-02) : un agent complice réattribue le numéro de la victime à la SIM de
l'attaquant. L'attaquant reçoit l'OTP, s'authentifie par USSD et vide le compte vers un bénéficiaire
inconnu.

- **Phase 1, sans la couche** : l'attaque RÉUSSIT. C'est l'état « avant ».
- **Phase 2, avec la couche** : la MÊME attaque, avec le même outil, est neutralisée. Le PDP voit
  le changement d'IMSI : le compte entre en PROBATION, et le veto de probation gèle le compte à la
  première sortie vers un bénéficiaire inconnu.

L'outil attaquant ne sait rien du mode. C'est le script, dans le rôle du propriétaire de la pile, qui
bascule la couche avant de le lancer. Chaque résultat est vérifié **en base**, indépendamment de ce
que l'outil affiche.

## Résultats

| Étape | Résultat |
|---|---|
| `make check-stack` | **VERT** — `/evaluate` idempotent, chaîne d'audit vérifiée (altération détectée et localisée), transfert P2P et USSD exécutés après `/evaluate` |
| Latence `/evaluate` rapportée par `check-stack` | **11 ms** : un appel ALLOW à chaud, en VT-x |
| **Phase 1** (`tools/verify_attacker.sh`) | **VERT** — solde de la victime **500 000 → 0 FCFA**, 1 sortie EXECUTED vers 677000008, OTP consommé parti vers l'IMSI de l'attaquant (624010000000666), 0 notification sur l'ancien appareil |
| **Phase 2** (`tools/verify_ac02_protected.sh`) | **VERT** — solde de la victime **intact, 500 000 FCFA**, **0 sortie exécutée** (transaction REJECTED côté cœur), **une seule décision FREEZE / PROBATION_VIOLATION** au journal, compte **FROZEN** côté PDP, chaîne d'audit **0 anomalie** (`pdp.audit_verify`) |
| `make abuse` (tests T-AC automatisés) | **3 réussis sur 3** |
| Mode laissé en fin de passage | protégé (`layerless: false`) |

### Passage précédent

Un premier passage a eu lieu le 2026-10-22 sur `main` = `9064e00`, la VM tournant alors en **NEM**.
La phase 1 et la phase 2 étaient vertes avec les mêmes constats : solde intact (500 000 FCFA),
FREEZE/PROBATION_VIOLATION, chaîne sans anomalie. `check-stack` rapportait alors **32 ms**. Ce
chiffre a été mesuré sous NEM : il n'est pas représentatif. Le passage de référence est celui du
23/10. La phase 2 venait encore d'un script hors dépôt ; elle est versionnée depuis, dans
`tools/verify_ac02_protected.sh`.

## Rejouer

Depuis le dépôt dans la VM (`~/identishield-cm`), sur le commit cité ou sur un commit de `main` qui
le contient :

```
# 0. Coordination (CLAUDE.md) : ~/VM-OCCUPEE absent, sinon attendre ; puis le poser.
make reset && make up        # make reset SEULEMENT si infra/init/init.sql a changé ; sinon make up
make check-stack             # relancer une fois si le premier /evaluate sort à froid (EXECUTED_DEGRADED)
bash tools/verify_attacker.sh            # phase 1 : l'attaque réussit sans la couche
bash tools/verify_ac02_protected.sh      # phase 2 : la même attaque est neutralisée
PATH=$HOME/.local/bin:$PATH make abuse   # tests T-AC automatisés
# Fin : make down, puis effacer ~/VM-OCCUPEE.
```

Les deux scripts recréent eux-mêmes leur mise en scène. Le compte de la victime est recréé à chaque
passage, avec un identifiant neuf que le PDP n'a jamais vu. On peut donc les rejouer autant de fois
qu'on veut, dans n'importe quel ordre. `make reset` efface la base de la pile : exporter d'abord le
journal d'audit s'il contient autre chose que des données de test.

## Limites, écrites avant qu'on les demande

- **« Titulaire servi » n'est pas couvert ici.** La carte A7b demande aussi qu'un titulaire enrôlé
  fasse un virement ALLOW journalisé. Cela attend l'application branchée sur le vrai système
  (couloir B, B7).
- **La latence du FREEZE n'est pas mesurée.** Le critère de T-AC-02 dans ISC-TM v1.4 (« FREEZE,
  latence p95 < 500 ms ») porte sur un 95e centile, qu'un passage unique ne donne pas. Les 11 ms sont
  ceux d'un seul appel ALLOW à chaud, sans contrôle du régime thermique du poste. Ce n'est PAS une
  mesure ENF-01. La mesure relève de la carte débit (#58).
- **L'IMSI factice du cœur (A5-00, #57) n'intervient pas ici** : `verify_*` renseignent le HLR de la
  victime avant l'attaque. Le défaut reste bloquant avant la soutenance.
