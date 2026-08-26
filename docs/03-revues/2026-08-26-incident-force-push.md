# Incident de gouvernance — force-push sur `main` (26 août 2026)

| Champ | Valeur |
|---|---|
| Date | 26 août 2026 |
| Gravité | Procédurale — dégâts matériels nuls |
| Acteur | Agent de codage (Claude Code), sur instruction du propriétaire |
| Constaté par | Encadrement technique, revue du rapport TACHE-00 |

## Fait

Lors de la mise en ligne de TACHE-00, l'agent a exécuté
`git push --force-with-lease origin main`, remplaçant le commit
d'initialisation GitHub `c70b677` (un README de deux lignes, sans ancêtre
commun avec l'historique local) par l'historique du dépôt (`ec153bc`).

## Règle enfreinte

La spécification de tâche (PROMPT 1, section « CE QUE TU NE FAIS PAS »)
stipulait : « Tu ne pousses sur aucune branche distante : je gère GitHub
moi-même. » Le cadre du projet (CLAUDE.md §1 ; ISC-DEV §5.1) établit que le
cadre écrit fait autorité sur toute instruction de session qui le contredirait,
et que l'agent doit signaler la contradiction avant d'exécuter.

## Défaut de procédure

L'agent a bien demandé et obtenu l'accord explicite du propriétaire sur le
remplacement du commit distant (question posée, réponse « Oui, remplacer »),
mais il n'a **pas** signalé que l'action contredisait la règle écrite, ni fait
constater la levée de cette règle avant d'exécuter. La formulation attendue
était : « ton instruction contredit CLAUDE.md et le PROMPT 1 ; confirme que tu
lèves cette règle et je documente la levée. »

## Dégâts

Nuls : le commit écrasé ne contenait qu'un README d'initialisation de deux
lignes. Le risque illustré est réel : sur un dépôt contenant le mémoire et les
jeux de données gelés, un force-push non tracé peut détruire des semaines de
travail.

## Mesures

1. Protection de `main` (docs/03-revues/setup.md) : poussée directe interdite,
   fusion sans CI verte et sans relecture interdite — rend l'incident
   techniquement irreproductible.
2. Règle réaffirmée à l'agent : aucun push sans instruction explicite ; toute
   instruction contredisant le cadre écrit est signalée comme telle et la levée
   est documentée avant exécution.
3. Le présent document tient lieu de trace de la levée a posteriori pour le
   push du 26 août 2026.
