# Correction de spécification — ES-11 : le fail-closed ne s'applique qu'aux sorties

**Date :** 2026-09-15
**Décision de référence :** T2-13 (types d'opération, EF-04) ; ADR-030 (verrouillage de compte écarté).
**Nature :** correction de spécification — à porter par l'encadrement dans une version du SRS. `docs/02-specifications/` n'est pas modifié par l'agent (CLAUDE.md §2) ; la décision est **appliquée dans le code** (T2-13) et **signalée** ici pour remontée.

## Décision du propriétaire

Le fail-closed (ES-11) — quand le PDP est indisponible, refuser au-dessus de CAP_PROB — **ne s'applique qu'aux sorties** (P2P, cash-out, paiement marchand, facture, achat de crédit). Une **entrée** (`CASH_IN`) **passe toujours**, en mode dégradé et journalisée, **quel que soit le montant**.

## Motif

Refuser un dépôt quand le PDP est indisponible **ne protège personne** : ça bloque un client légitime devant un agent, sans gêner l'attaquant. C'est exactement le défaut du verrouillage de compte écarté en **ADR-030** — une protection qui gêne le légitime sans gêner l'attaquant n'est pas une protection. ES-11 parle de **sorties sensibles** ; une entrée n'en est pas une, il n'y a aucune sortie à retenir.

## Origine

Formulation fautive relevée pendant T2-13 : la spec de la tâche disait « chacun passant par /evaluate avant commit **comme le transfert P2P** », raccourci non examiné qui étendait le fail-closed à `CASH_IN`. L'agent a implémenté d'abord le comportement uniforme (littéral de la consigne) **en signalant la nuance** ; le propriétaire a tranché pour la règle correcte ci-dessus.

## Correction demandée (SRS, ES-11)

Préciser qu'ES-11 (politique fail-closed du cœur sur PDP indisponible) porte sur les **sorties sensibles** uniquement. Les **entrées** (`CASH_IN`) ne sont jamais refusées pour cause de fail-closed ; elles s'exécutent en mode dégradé et sont journalisées.

## État

Appliqué dans le code T2-13 (`core/.../transfer/TransferService.java`, `operate()`), avec test dédié (`OperationTest#cashInNeverFailsClosedAndRunsDegradedWhenPdpUnavailable` : CASH_IN 100 000 FCFA, PDP indisponible → crédité, EXECUTED_DEGRADED, jamais rejeté). En attente de la version du SRS portant la précision d'ES-11.
