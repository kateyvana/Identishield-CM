# CI-01 — Portes lourdes : vérification manuelle déclarée

**Date :** 2026-10-22
**Carte :** CI-01 (#56)
**Décision :** option 2, retenue par la propriétaire, qui a confié le choix à la session couloir A.
Le déclencheur automatique est retiré, et la vérification est déclarée manuelle.

## Le constat

Le flux `heavy` (`portes-lourdes`) exige un exécuteur auto-hébergé `isc-vm`. Aucun n'a jamais été
inscrit : depuis la création du dépôt, chaque pull request et chaque nuit ont mis ce travail en
attente, sans qu'il tourne une seule fois. Les tests qui demandent la pile et Docker n'étaient
vérifiés automatiquement par personne, et l'état UNSTABLE des pull requests le cachait.

Le premier passage manuel a d'ailleurs trouvé un défaut que personne n'avait vu : `make abuse` ne
pouvait pas fonctionner tel qu'il était écrit. Il lançait les tests depuis `evaluation/`, alors
qu'ils trouvent l'outil attaquant par `os.getcwd()/attacker/src`, c'est-à-dire depuis la racine :
« No module named isc_attacker ». Le Makefile est corrigé dans cette carte.

Un exécuteur n'est pas attaché maintenant pour deux raisons :
- sur un dépôt **public**, un exécuteur auto-hébergé exécute dans la VM le code de toute pull
  request, y compris celles des forks ;
- son inscription exige un jeton, qui est réservé à la propriétaire.

## Ce qui change

- `heavy.yml` ne se déclenche plus que par un lancement manuel (`workflow_dispatch`), plus sur
  pull request ni chaque nuit. Les pull requests ne restent donc plus en attente pour une porte
  fantôme.
- Les portes lourdes se vérifient **à la main dans la VM**, après chaque déploiement :

  ```
  make reset && make up            # seulement si init.sql a changé ; sinon make up
  make check-stack
  PATH=$HOME/.local/bin:$PATH make abuse
  ```

  Le résultat de chaque passage est consigné ici, avec la date et le commit déployé.

## Passages

### 2026-10-22 — main 9064e00 (A4 déployée), VM isc-vm

| Porte | Résultat |
|---|---|
| `make reset` puis `make up` | 6 conteneurs sains |
| `make check-stack` | VERT, au premier passage ; latence `/evaluate` à chaud : 32 ms |
| `tools/verify_attacker.sh` (AC-02 phase 1, sans couche) | VERT : solde vidé, OTP parti vers l'IMSI attaquant |
| AC-02 phase 2, essai manuel hors dépôt (avec couche) | VERT : solde intact (500 000 FCFA), aucune sortie, une décision FREEZE/PROBATION_VIOLATION, compte FROZEN, chaîne d'audit sans anomalie |
| `make abuse` tel qu'il était écrit | ROUGE : 2 échecs, « No module named isc_attacker » (défaut du Makefile, corrigé ici) |
| `make abuse` corrigé (depuis la racine) | **1 échec, 2 réussites** — voir ci-dessous |

**L'échec restant, non corrigé, en attente de décision.** `test_la_couche_active_ferait_echouer_la_phase1`
exige que la décision journalisée soit dans `("ALLOW", "STEP_UP", "FREEZE", "DENY")`. Or le contrat
`contracts/evaluate.yaml` dit `[ALLOW, STEP_UP, FREEZE, REJECTED]` : « DENY » n'existe pas, et
REJECTED, émis par le PDP depuis A4 (compte gelé, solde insuffisant, signature invalide), manque.
Le passage a constaté REJECTED, parce que l'essai manuel de la phase 2 venait de geler le compte de
la victime. Le gel dure TTL_FREEZE, et le test ne remet pas l'état du PDP à zéro. Corriger cet
ensemble revient à modifier un test existant : c'est hors de cette carte, et cela demande l'accord
de la propriétaire (CLAUDE.md §4).
