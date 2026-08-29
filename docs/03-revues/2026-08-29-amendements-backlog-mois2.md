# Amendements au backlog du mois 2 (ISC-SKEL v1.0)

**Date :** 2026-08-29
**Objet :** consigner quatre décisions issues de la vérification de cohérence du backlog contre `docs/requirements-index.csv`, et ajouter la tâche T2-19.
**Portée :** ISC-SKEL n'est pas modifié (document d'encadrement). Ces amendements seront intégrés à la version 1.1 de la spécification.

---

## Décision 1 — comptage des exigences complètes (§4)

Le tableau du §4 marque **seize** exigences « complet » ou « complet en forme » ; le texte en annonce **quinze**. C'est une erreur de comptage de l'encadrement, rattrapée par la vérification. **Correction en v1.1 :** le texte lira « dont seize complètes » (ou le tableau sera ajusté, au choix de l'encadrement).

## Décision 2 — ED-02 : défaut d'étiquetage à corriger

**ED-02** (le cœur DOIT persister : Compte, AbonnéHLR avec historique, Transaction, Session) est **réalisée** au mois 2 par T2-01 (schémas), T2-07 (comptes, transferts), T2-08 (session USSD) et T2-10 (HLR), mais **n'est citée dans aucune tâche**. Sans cet étiquetage, la matrice de traçabilité montrerait une exigence obligatoire sans couverture, et la porte de traçabilité échouerait **à tort** au mois 4.

**Correction en v1.1 :** rattacher **ED-02** à **T2-01** (schéma) et **T2-07** (entités). Les marqueurs `@implements ED-02` seront posés dans le code de ces deux tâches.

## Décision 3 — EF-26 et EF-38 : français de la console, dès le premier écran

**EF-26** et **EF-38** (l'interface / la console DOIT être disponible en français) ne sont rattachées à aucune tâche, alors que la console est construite dès le mois 2 (T2-09, T2-17). Deux raisons de les rattacher maintenant : la console est bâtie ce mois-ci, et **traduire une interface après coup coûte bien plus cher que l'écrire dans la bonne langue dès le premier écran**.

**Correction en v1.1 :** rattacher **EF-26** et **EF-38** à **T2-09** (console v1).

## Décision 4 — EF-34 et EF-37 sont du mois 2, non du mois 4

Décision de l'encadrement, contre la prudence de la vérification qui les avait laissées « à trancher » :

- **EF-34** — vue d'intégrité du journal d'audit avec localisation d'une rupture : c'est **l'acte 4 de la démonstration au jury**.
- **EF-37** — boîte de notifications émises (SMS / push simulés).

Les deux sont des **vues de lecture** sur des données que **T2-06** (journal chaîné) et **T2-14** (notifications) produisent **déjà**. Les reporter au mois 4 les mettrait en concurrence avec le PDP complet, l'application Android et les treize cas d'abus, sur le mois le plus chargé du projet. **On les fait pendant que la console est simple** → nouvelle tâche T2-19 ci-dessous.

---

## Ajout au backlog — tâche T2-19

À insérer au §9 d'ISC-SKEL v1.1, après T2-18 (avant le jalon), au gabarit ISC-PROC §4.2 :

| ID | Tâche | Exigences | Durée | Dépend de |
|---|---|---|---|---|
| **T2-19** | Console : vue d'intégrité du journal d'audit (état de la chaîne, localisation d'une rupture) et boîte de notifications émises (SMS / push simulés). Vues de lecture seule ; aucune écriture, aucune logique métier. | EF-34, EF-37 | 2 j | T2-06, T2-14 |

**Conséquence sur le total :** le backlog passe de 18 à 19 tâches, de 27 à **29 jours** de travail effectif. L'écart avec les ~20 jours ouvrés reste absorbé par la parallélisation et la marge du mois 7 (comme noté au §9 d'ISC-SKEL).

**Conséquence sur le jalon :** inchangé — le jalon du mois 2 (AC-02 réussit, scriptée, visible) ne dépend pas de T2-19 ; T2-19 renforce la partie console de la démonstration (actes de lecture) sans être sur le chemin critique de l'attaque.

---

## Récapitulatif des rattachements d'exigences (pour la v1.1)

| Exigence | Priorité | Rattachée à | Motif |
|---|---|---|---|
| ED-02 | M | T2-01, T2-07 | persistance déjà réalisée, non étiquetée |
| EF-26 | M | T2-09 | français de la console, dès le premier écran |
| EF-38 | M | T2-09 | idem (anglais recommandé) |
| EF-34 | M | **T2-19 (nouvelle)** | vue d'intégrité — acte 4 du jury |
| EF-37 | M | **T2-19 (nouvelle)** | boîte de notifications |

Après ces rattachements, la couverture des exigences EF+ED de priorité M du mois 2 est complète ; les autres non couvertes relèvent légitimement du mois 3 (générateur : ED-07, ED-08, ED-10) ou du mois 4 (sécurité comportementale, device binding, application Android).
