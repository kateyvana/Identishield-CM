# Amendement — frontière d'écriture/vérification du journal d'audit (T2-05 / T2-06)

**Date :** 2026-08-30
**Portée :** à intégrer dans **ISC-SKEL v1.1** (spécification d'encadrement du squelette).
**Statut :** amendement acté par le propriétaire ; ISC-SKEL v1.0 conservée.

## Constat — une contradiction interne de l'encadrement

Trois sources de l'encadrement se contredisaient sur *qui* construit le chaînage
du journal d'audit :

1. **ISC-SKEL v1.0, §2 (le fil), étape 5** : « Le PDP écrit une entrée d'audit
   **chaînée, index d'ordre assigné dans le verrou** » — attribue l'écriture
   chaînée in-lock à l'étape /evaluate, c'est-à-dire à **T2-05**.
2. **ISC-SKEL v1.0, obligation OB-2** : « le `chain_index` est assigné à
   l'intérieur du verrou **dès la première entrée d'audit** » ; et §6 qualifie
   l'écriture **hors verrou** d'anti-pattern coûteux (« OB-2 violée, le défaut
   réapparaîtra sous concurrence au mois 4 »).
3. **Backlog mois 2, carte T2-06** : « Journal d'audit : **chaînage, index dans
   le verrou**, vérification complète et incrémentale » — attribue le chaînage
   à **T2-06** ; le commentaire de `infra/init/init.sql` (T2-01) disait de même
   « `chain_index` assigné dans le verrou **par T2-06** ».

DÉDUIT : (1)+(2) exigent que la **première** entrée d'audit soit déjà chaînée
in-lock ; or la première entrée est écrite par le /evaluate de **T2-05**, qui
précède T2-06. Différer le mécanisme de chaînage à T2-06 laisserait donc les
premières entrées **non chaînées** — précisément l'anti-pattern qu'OB-2 interdit.
La carte T2-06 et le commentaire de T2-01 étaient donc en tort.

## Résolution actée — T2-05 écrit chaîné, T2-06 vérifie

- **T2-05** construit l'écriture d'audit **chaînée in-lock** (trigger BEFORE
  INSERT SECURITY DEFINER, `chain_index` assigné dans le verrou sur `chain_head`,
  `prev_hash`/`hash`), conforme à OB-2 dès la première entrée. Reprend SPIKE-3 /
  ADR-020.
- **T2-06** construit la **vérification** : complète et incrémentale (couche A =
  chaînage, ordonnée par `chain_index` et non `seq` — correction Q6 du spike),
  et la couche B (ancre `chain_head`) pour la suppression du dernier
  enregistrement. Détection **et localisation** d'altération (porte #4).

Cette répartition a été retenue et exécutée (T2-05 fusionné `631806c`, T2-06
fusionné `879d52e`).

## Corrections à porter dans ISC-SKEL v1.1

1. **Carte T2-06** : remplacer « chaînage, index dans le verrou, vérification »
   par « **vérification complète et incrémentale** du journal d'audit (couches
   chaînage + ancre), détection et localisation d'altération » — le chaînage
   in-lock appartient à T2-05.
2. **Carte T2-05** : mentionner explicitement qu'elle écrit l'entrée d'audit
   **chaînée in-lock** (pas seulement « idempotence par decisionId »).
3. **Commentaire de `init.sql`** (déjà corrigé en T2-05) : le chaînage est posé
   par T2-05, pas T2-06.
4. **§2 étape 5** : inchangée (elle était correcte) ; c'est la carte T2-06 qui
   s'y aligne.

## Limite assumée (rappel, sans changement)

L'**ancre signée Ed25519** n'est pas au mois 2 (ES-09 « partiel » dans ISC-SKEL
§4) : la réécriture complète de la chaîne n'est détectée qu'au mois 4. Les
couches A+B couvrent altération et suppression dans le modèle §4.
