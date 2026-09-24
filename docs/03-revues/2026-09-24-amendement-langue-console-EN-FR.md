# Amendement — la console devient bilingue anglais/français (anglais par défaut)

**Date :** 2026-09-24
**Décidé par :** le propriétaire.
**Portée immédiate :** T2-17 (vue console de l'attaque AC-02).

## Décision

La console doit être **bilingue anglais et français**, avec l'**anglais par défaut** et un **sélecteur
de langue visible**.

**Motif :** le mémoire et la soutenance sont en anglais ; le jury lit l'anglais ; le Cameroun a deux
langues officielles (anglais et français).

## Ce qui est fait dans T2-17

- La vue de démonstration (chronologie de l'attaque EF-41, état du HLR EF-31/EF-32, en-tête, sélecteur,
  indicateur d'état) est **bilingue EN/FR**, anglais par défaut, avec un sélecteur `EN | FR` visible
  dans l'en-tête ; le choix est mémorisé par visiteur (`localStorage`, confort local).
- L'outil attaquant publie désormais chaque étape en **FR ET EN** vers isc-sim (sa sortie brute reste
  en français ; seule la publication est enrichie — conforme à « aucune modif hors publication »). La
  console affiche l'étape dans la langue choisie.

## Amendement du SRS À FAIRE (encadrement)

Deux exigences fixent aujourd'hui « en français » et doivent devenir « **en anglais et en français** » :

- **EF-26** — (interface / langue de la console) : « en français » → « en anglais et en français,
  anglais par défaut ».
- **EF-38** — (idem, second point de langue) : même amendement.

Tant que le SRS n'est pas amendé, il y a un écart formel assumé entre l'exigence (FR) et la livraison
(EN+FR). Cet amendement est un **acte du propriétaire** à porter au SRS (comme les corrections ES-11 et
SAD §5 déjà en attente).

## Dette signalée (NE PAS traiter maintenant — carte créée)

La **console livrée en T2-09** (le flux des transactions temps réel EF-30) et le **bandeau de mode
livré en T2-15** (EF-07/EF-36) sont **uniquement en français**. Ils restent en français dans T2-17 et
devront être **traduits (EN/FR)** dans une tâche dédiée — **carte Kanban créée** (Projet #1, item
`PVTI_lAHODKijec4Bi1SHzg8lpps`, statut Backlog) : « Traduire la console existante (flux T2-09 + bandeau
T2-15) en EN/FR ». Cette tâche posera aussi une base i18n partagée plutôt que le dictionnaire léger,
propre à la vue, introduit dans T2-17.
