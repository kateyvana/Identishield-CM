# Levée de la 8e interdiction (actions de forge) — création du tableau Kanban

**Date de la levée :** 2026-09-07 (ordre du propriétaire)
**Date d'exécution sur la forge :** 2026-09-08
**Portée :** cette tâche uniquement. La levée ne s'étend à aucune autre action distante.

## Motif (ordre du propriétaire)

« Création du tableau Kanban ISC-PROC §4.1 par l'agent, sur ordre du propriétaire, 07/09/2026. »

Le propriétaire a levé, pour cette seule tâche, la 8e interdiction de CLAUDE.md (aucune action sur un dépôt distant ni sur une API de forge), afin que l'agent crée et configure le tableau de projet GitHub.

## Ce qui a été fait sur la forge — et rien d'autre

Authentification `gh` en tant que **kateyvana** (flux « device », portées `project` et `repo`), puis, via `gh` / l'API GraphQL Projects v2 :

1. **Création d'un GitHub Project (v2)** appartenant à l'utilisateur kateyvana, nommé **« IdentiShield-CM »**, vue tableau — n° 1, URL https://github.com/users/kateyvana/projects/1.
2. **Liaison au dépôt** kateyvana/Identishield-CM (`gh project link`).
3. **Six colonnes** dans l'ordre exact d'ISC-PROC §4.1, via reconfiguration du champ à sélection unique *Status* : **Backlog · Ready · In Progress · In Review · Blocked · Done**.
4. **Limites de travail en cours documentées dans la description** du projet (l'outil ne les bloque pas mécaniquement ; elles sont tenues par discipline et vérifiées en revue hebdomadaire) : Ready 8 max · In Progress 1 (stricte, un seul contributeur) · In Review 3.
5. **Champ d'étiquette** « Étiquette » (sélection unique : Tâche / Jalon), pour marquer les cartes de jalon.
6. **Dix-neuf cartes T2-01 à T2-19**, chacune portant ses sept champs (Titre + Exigences couvertes, Comportement attendu, Critère de vérification, Composant, Jalon, Risque de sécurité) **copiés verbatim depuis `docs/backlog-mois2.md`**, non résumés.
7. **Six cartes de jalon M2 à M7**, étiquetées « Jalon ».
8. **Placement** selon l'état réel : T2-01 à T2-08 → Done (fusionnées sur main, vérifié) ; T2-09 → Ready ; T2-10 à T2-19 → Backlog ; M2 à M7 → Backlog.

## Vérification (constatée par l'API)

Nombre de cartes par colonne : **Backlog 16 · Ready 1 · In Progress 0 · In Review 0 · Blocked 0 · Done 8** (total 25) — conforme à l'attendu. Les 19 cartes T2 portent chacune leurs sept champs (vérifié en relisant le corps des draft issues via GraphQL).

## Ce qui n'a PAS été fait

Aucune poussée de code, aucune pull request, aucune modification de la visibilité ni des réglages du dépôt, aucune protection de branche, aucun autre appel à l'API de forge. La seule écriture distante est le tableau de projet ci-dessus et sa liaison au dépôt.
