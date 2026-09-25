# Note de levée — fusion de T3-00 / ADR-035 (2026-09-25)

**Interdiction levée :** 8e interdiction de CLAUDE.md §4 (aucune action sur un dépôt distant ni sur une API de forge).
**Par :** le propriétaire, en session, le 2026-09-25, après relecture et acceptation de T3-00 (CLAUDE.md §1 et §4 compris).
**Texte de la levée :** « Je lève la 8e interdiction pour fusionner t3-00-adr035-identifiants-mtn dans main (--no-ff), make check VERT sur main, et pousser. Rien d'autre. »

## Périmètre autorisé

- Fusion `--no-ff` de `t3-00-adr035-identifiants-mtn` dans `main`, `make check` VERT sur la branche (CLAUDE.md §4) puis sur `main`, push de `main`.
- Mise à jour des cartes : #6 (T3-00) vers Done avec preuve, #7 (T3-04) vers In Progress.

## Hors périmètre (non levé)

Toute autre branche (dont `t3-04-injection-fraude` et `mobile-00-depot-docs`), toute pull request, tout réglage du dépôt, tout push forcé.

## Contenu fusionné

ADR-035 (opérateur simulé MTN : MSISDN 677000000–677009999, IMSI 624-01), porte `fictitious-msisdn` à deux règles, migration des identifiants, plan Kanban v1.1 du Mois 3, et la règle du propriétaire ajoutée à CLAUDE.md §4 : « une session = un worktree ; jamais deux sessions dans le même dossier de travail ».
