# Note de levée — 8e interdiction — fusion de quatre branches dans main et poussée

**Date :** 2026-10-01
**Opération :** fusion `--no-ff` dans `main` de `fix-console-sora`, `t3-05a`, `t3-04b-harnais-evaluation` et `mobile-06-premier-enrolement` (M-05 + M-06), puis poussée de `main`.
**Exécutée par :** la session du couloir Mobile, dans le dossier principal. La session Données travaille dans `wt-t3`, qui n'est pas touché.

## Ordre du propriétaire

« Levée de la 8e interdiction (CLAUDE.md §4), pour cette opération uniquement :
- distant : git fetch, git pull --ff-only et git push origin main autorisés ;
- fusions dans main des branches fix-console-sora, t3-05a, t3-04b-harnais-evaluation et mobile-06-premier-enrolement (couloir Données inclus, D-14 levée pour ce merge) ;
- résous le conflit Makefile (ligne check:) par la ligne combinée : check: build format unit arch contract mobile check-docs secrets fictitious-msisdn named-params eval-tests ;
- lance make check ; pousse main UNIQUEMENT si tout est VERT, sinon STOP et colle-moi la sortie.
Consigne la note de levée dans docs/03-revues/ comme d'habitude. Rien d'autre. »

Il s'agit d'une levée explicite de la 8e interdiction de CLAUDE.md §4 et, pour cette seule fusion, de la séparation des couloirs (D-14).

## Contexte

- `fix-console-sora` (cedc9e1) répare le build de la console. Google sert désormais Sora comme police variable : un même fichier pour toutes les graisses, sur lequel Turbopack échouait. Le correctif retire le tableau `weight` de `Sora(...)`, sous l'exception cadrée accordée par le propriétaire le 2026-10-01.
- `mobile-06-premier-enrolement` contient M-05 (échafaudage), M-06 (flux A), la fusion de `fix-console-sora` et le correctif M-06 (fe82ef8). Le ruleset y passe en rs-0.5.0.

## Déroulé

1. `git fetch origin`, `git switch main`, `git pull --ff-only` : `main` = `origin/main` = f44194c, sans changement.
2. Fusions `--no-ff`, dans cet ordre :

| Fusion | Commit | Résultat |
|---|---|---|
| `fix-console-sora` | f0bd0e6 | sans conflit |
| `t3-05a` | 195b265 | sans conflit |
| `t3-04b-harnais-evaluation` | 1522989 | sans conflit |
| `mobile-06-premier-enrolement` | 0e64e9d | un conflit, prévu, dans le Makefile (ligne `check:`) |

   Les trois premières fusions et leur absence de conflit avaient été prévues par une simulation `git merge-tree`, faite en lecture seule avant la levée.

3. Le conflit est résolu par la ligne combinée fixée par le propriétaire. Aucun autre marqueur ne reste.
4. Les fichiers ont été ajoutés par chemins explicites, jamais par `git add -A` : `tools/mobile-mock/.tls/` (CA de développement et sa clé) n'était pas encore ignoré sur `main` avant la fusion de M-06.

## Validation

`make check` VERT sur `main` fusionné (0e64e9d), puis de nouveau après le commit de cette note. Toutes les portes sont VERTES :

| Porte | Résultat |
|---|---|
| build | la console compile |
| format | VERT |
| unit | core, pdp, Python |
| arch | VERT |
| contract | `evaluate.yaml` et `mobile-api.yaml` |
| mobile | lint, tests JVM, APK debug |
| check-docs | VERT |
| secrets | VERT |
| fictitious-msisdn | VERT |
| named-params | 16 valeurs surveillées |
| eval-tests | 7 tests passent |

## Geste

`git push origin main`, et seulement si tout est VERT. Aucune autre action distante.
