# Demande de correction de spécification — SAD §5 : outils CLI en bibliothèque standard

**Date :** 2026-09-14
**Décision de référence :** ADR-033 (outils CLI Python, bibliothèque standard uniquement)
**Nature :** correction de spécification — à porter par l'encadrement, PAS par l'agent de codage.

## Pourquoi cette note existe

CLAUDE.md §2 interdit à l'agent de codage de modifier tout document de `docs/02-specifications/`. Le SAD y vit (`ISC-SAD_Architecture_Logicielle_v1.2.docx`). La correction ci-dessous est donc **signalée**, pas exécutée : elle doit être portée par une nouvelle version du SAD livrée par l'encadrement (comme le TM v1.2.1 l'a été pour ISC-AMD-01).

## La correction demandée (SAD §5, composants outils)

Dans le tableau des composants (§5), la colonne « Technologie » nomme actuellement **Python (Typer)** pour deux lignes. La remplacer par **Python, bibliothèque standard uniquement** :

| Composant | Avant | Après |
|---|---|---|
| isc-hlr-agent | Python (Typer) | Python, bibliothèque standard uniquement |
| isc-attacker | Python (Typer) | Python, bibliothèque standard uniquement |

La colonne « dépendances » (« aucune ») reste inchangée — elle est désormais littéralement vraie.

## Motif (voir ADR-033)

Typer n'apporte rien que `argparse`/`urllib` ne fassent pour ces CLI triviaux ; chaque dépendance en moins sert ENF-05 (environnement opérationnel en < 10 min) et la sobriété (CLAUDE.md §8). Une seule convention pour tous les outils. `isc-hlr-agent` (T2-11) est déjà livré en stdlib ; `isc-attacker` (T2-16) le sera aussi.

## Statut

ADR-033 est PROPOSÉ FERME (clôture par le propriétaire). La correction du SAD est en attente de la nouvelle version du document.
