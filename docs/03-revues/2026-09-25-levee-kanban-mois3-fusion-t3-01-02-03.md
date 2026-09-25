# Note de levée — Kanban du Mois 3 et fusion de T3-01, T3-02, T3-03 (2026-09-25)

**Interdiction levée :** 8e interdiction de CLAUDE.md §4 (aucune action sur un dépôt distant ni sur une API de forge).
**Par :** le propriétaire, en session, le 2026-09-25 (consigne « MISE EN ORDRE DU MOIS 3 »).
**Texte de la levée :** « Je lève la 8e interdiction pour (1) créer et mettre à jour les cartes du Mois 3 sur GitHub Projects, et (2) fusionner et pousser t3-01, t3-02 puis t3-03 dans main. Rien d'autre. »

## Périmètre autorisé

1. **Kanban** (référence : plan `ISC_PLAN_Mois3_Kanban_v1.1`, versionné dans `docs/`) : jalon « Mois 3 — Données de synthèse & calibration », étiquettes du plan, cartes T3-01…T3-08, F-0, T3-00, T3-05a et cartes « dette », puis mise à jour de leur colonne au fil des fusions.
2. **Fusion puis push** de `t3-01-profil-reference`, `t3-02-personas`, `t3-03-generateur-legitime` dans `main`, dans cet ordre, avec `make check` VERT après chaque fusion.

## Hors périmètre (non levé)

- La branche `t3-00-adr035-identifiants-mtn` (ADR-035) : **ni fusionnée ni poussée**.
- Toute autre branche, pull request, modification de visibilité ou de réglage du dépôt.
- Tout push forcé.

## Conditions d'arrêt

Conflit de fusion ou porte rouge : arrêt immédiat, rapport, rien n'est forcé.

## Note de mise en œuvre

Les cartes du Mois 2 étaient des brouillons de projet (DraftIssue). Un brouillon ne peut porter ni étiquette ni jalon GitHub, alors que le plan v1.1 les exige. Les cartes du Mois 3 sont donc créées comme **issues du dépôt**, rattachées au projet n° 1, avec les champs Status et Étiquette (Tâche) du projet. Le reste du tableau (colonnes, limites WIP) est inchangé.
