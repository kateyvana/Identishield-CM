# IdentiShield-CM — Plan du Mois 3 : Données de synthèse & calibration

**Étudiante :** YENGUE MESSAH KATE YVANA — ICTU20234409
**Date :** 2026-09-25
**Rédigé par :** supervision technique (encadreur senior / architecte / QA lead)

---

## 0. Où on en est / pourquoi ce mois

Mois 2 : la pile isolée tourne et l'attaque AC-02 se reproduit de bout en bout **avec la couche éteinte**. On ne peut pas régler ni évaluer un gardien (PDP) sans une **population de transactions réaliste et étiquetée**. Le Mois 3 fabrique ce socle, et — c'est le point de rigueur — **prouve qu'il est crédible** avant qu'on l'utilise pour quoi que ce soit.

## 1. Objectif du Mois 3 (une phrase)

Produire un **jeu de dev figé et reproductible** (~500 000 transactions) issu d'un **générateur de personas** dont les distributions sont **statistiquement plausibles au regard des 26 990 transactions réelles**, puis **calibrer** CAP_PROB / VEL / NIGHT et **trancher ADR-016** (agrégation du score) sur ce socle.

## 2. Règle méthodologique cardinale (à ne jamais violer)

Deux rôles séparés pour deux jeux de données :

- **Données réelles (26 990)** → *étalon de plausibilité* + *référence de friction* pour les seuils CAP_PROB / VEL / NIGHT (elles fixent combien de trafic **légitime** un seuil dérange dans le vrai monde). Elles ne contiennent **ni étiquette de fraude ni champ bénéficiaire**.
- **Jeu de dev synthétique (étiqueté)** → *terrain d'évaluation de la détection* et de l'**agrégation** (ADR-016), car lui seul porte la vérité-terrain (fraude vs légitime) et le bénéficiaire.

On ne règle jamais l'agrégation sur les 26 990 (échantillon trop petit → surapprentissage) ; on garde le réel comme juge externe de plausibilité et de friction.

Contraintes permanentes : MSISDN fictifs **699000000–699009999** uniquement ; sandbox isolée ; données fictives ; **figé = reproductible** (graine + manifeste + empreinte) ; rien n'est supprimé, tout est versionné ; **aucune action distante par la supervision** (push/PR/réglages = propriétaire seul).

## 3. Backlog Mois 3 (T3-01 → T3-08)

| # | Tâche | Sur chemin critique | Sortie attendue |
|---|---|---|---|
| **T3-01** | Profil de référence des 26 990 réelles | **oui** | Artefact figé de distributions (montant : quantiles ; heure du jour ; comptes/vélocité par usager ; taux de sortie) — l'étalon |
| **T3-02** | Spécifier le modèle de personas | oui | Doc de conception : archétypes paramétrés (fréquence, loi de montant, heures actives) qui, agrégés, reproduisent T3-01 |
| **T3-03** | Générateur légitime dans `isc-sim` | oui | Flux de transactions légitimes, **déterministe** (graine) |
| **T3-04** | Injection de fraude **étiquetée** | oui | Motif AC-02 (et scénarios étiquetés) à prévalence contrôlée, vérité-terrain jointe |
| **T3-05** | Jeu de dev **figé v1** (~500k) | oui | Jeu + **manifeste** (graine, versions, effectifs, empreinte SHA-256) |
| **T3-06** | Rapport de **plausibilité** | oui | Comparaison synthétique vs réel par dimension, **seuils d'acceptation** + verdict pass/fail |
| **T3-07** | **Calibration** CAP_PROB / VEL / NIGHT | oui | Points de fonctionnement choisis + justification (capture fraude vs friction légitime) |
| **T3-08** | Trancher **ADR-016** (agrégation) | oui | Décision (règles-d'abord + score ? veto ? somme pondérée ?) évaluée sur le dev étiqueté (ROC/PR), point de fonctionnement retenu |

Tout le mois est sur le chemin critique : chaque tâche alimente la suivante. Pas de parallélisme sérieux ici — c'est une chaîne.

## 4. Dettes / lacunes connues à porter

- **Pas de champ bénéficiaire dans le réel** → la règle « bénéficiaire » se calibre et s'évalue **uniquement** sur le dev synthétique ; à consigner comme limite de validité dans le rapport et le mémoire.
- Chiffres préliminaires déjà obtenus sur le réel (à confirmer/figer en T3-01) : CAP_PROB 25 000 FCFA ≈ **1,76 %** des sorties légitimes ; VEL > 3/10 min ≈ **10,2 %** des usagers (trop bas → à rediscuter) ; fenêtre NUIT 23h–05h ≈ **4,31 %**.
- Dette **cold-start `/evaluate`** (héritée du Mois 2) : à ne pas oublier avant le Mois 5, pas bloquante ici.

## 5. Définition de « fini » pour le Mois 3

1. Jeu de dev v1 **figé et rejouable** à l'identique depuis sa graine (empreinte identique).
2. Rapport de plausibilité **vert** (chaque dimension sous son seuil d'écart).
3. Seuils CAP_PROB / VEL / NIGHT posés **avec justification chiffrée**.
4. ADR-016 **tranché** et daté, point de fonctionnement documenté.
5. Documents de conception mis à jour (SRS/SAD si un paramètre bouge), rien supprimé.

## 6. Première action

**T3-01** — profil de référence des 26 990 réelles. C'est l'étalon ; tout le reste se mesure contre lui. Le prompt Claude Code est fourni séparément.
