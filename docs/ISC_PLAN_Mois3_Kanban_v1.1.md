# IdentiShield-CM — Mois 3 : Kanban, dépendances et charge (v1.1)

**Date :** 2026-09-25 — **Remplace :** ISC_PLAN_Mois3 v1.0 (conservé, non supprimé)
**Rédigé par :** supervision technique

## Journal des changements v1.0 → v1.1
- Ajout de **T3-00 / ADR-035** : opérateur simulé = MTN, plage MSISDN 699 (préfixe Orange) → 677 (préfixe MTN), IMSI 624-01. Décision du propriétaire du 2026-09-25.
- Horizon : **30 jours → 6 mois**, ~1 000 usagers, ~520k transactions (PO-1, exigence ED-07). Décision du propriétaire du 2026-09-25.
- T3-04 élargie : **changements de SIM légitimes** (3 cas) + **300 rejeux AC-02 indépendants** (suréchantillonnage déclaré) au lieu d'une prévalence inventée.
- Ajout de **T3-05a** : correction de plausibilité PO-2 (agents trop « rafaleurs ») et PO-3 (montants ronds) avant le gel.
- Ajout d'une étape **F-0** : fusion de T3-01/02/03, préalable technique à ADR-035.
- Correction de processus : le Mois 3 a démarré sans cartes Kanban. Les cartes sont créées maintenant, avec leur état réel.

## 1. Le tableau (colonnes et règles inchangées depuis le Mois 2)
Colonnes : Backlog · Ready · In Progress · In Review · Blocked · Done
Limites WIP : Ready 8 · **In Progress 1** · In Review 3
Jalon GitHub : **« Mois 3 — Données de synthèse & calibration »**
Étiquettes : `mois-3`, `donnees`, `calibration`, `adr`, `chemin-critique`, `dette`
Une carte n'entre en **Done** que si : rapport rendu, relecture de la supervision faite, `make check` VERT, **fusion par le propriétaire**.

## 2. Les cartes, leur état réel et leur charge

Charge = effort de travail estimé d'après le rythme mesuré au Mois 2, en journées de travail. C'est une estimation, pas un calendrier imposé.

| Carte | Contenu | Dépend de | État réel | Charge |
|---|---|---|---|---|
| T3-01 | Profil de référence des 26 989 transactions réelles | — | **In Review** (accepté, à fusionner) | fait |
| T3-02 | Modèle de personas (conception) | T3-01 | **In Review** (accepté, à fusionner) | fait |
| T3-03 | Générateur légitime + solde par usager | T3-02 | **In Review** (accepté, à fusionner) | fait |
| **F-0** | Fusion T3-01 → T3-02 → T3-03 dans `main` (**propriétaire**) | T3-03 | Ready | < 0,5 j |
| **T3-00** | ADR-035 : identifiants MTN (699 → 677, IMSI 624-01), porte, tests, oracle | F-0 | Ready (prompt déjà donné) | 0,5 – 1 j |
| T3-04 | SIM légitimes (3 cas) + 300 rejeux AC-02 + test anti-fuite des labels | T3-00 | Backlog | 1,5 – 2 j |
| **T3-05a** | Plausibilité avant gel : PO-2 (λ agents hétérogène) + PO-3 (montants ronds) | T3-04 | Backlog | 0,5 – 1 j |
| T3-05 | Gel v1 : 6 mois, ~1 000 usagers, ~520k tx, manifeste + SHA-256 | T3-05a | Backlog | 0,5 j |
| T3-06 | Rapport de plausibilité complet (inclut la re-mesure PO-4) | T3-05 | Backlog | 1 j |
| T3-07 | Calibration CAP_PROB / VEL / NIGHT + sensibilité (marchand, taux de SIM légitime) | T3-06 | Backlog | 1,5 – 2 j |
| T3-08 | ADR-016 : agrégation (combinaison swap + nouveau bénéficiaire + montant) | T3-07 | Backlog | 2 – 3 j |

**Reste à faire : environ 7,5 à 11 journées de travail**, relectures comprises. Cela tient dans le Mois 3 avec de la marge.

## 3. Chemin critique (une seule chaîne, sans parallélisme de développement)

F-0 → T3-00 → T3-04 → T3-05a → T3-05 → T3-06 → T3-07 → T3-08

Tout est sur le chemin critique : chaque carte produit l'entrée de la suivante. Un retard sur l'une décale toutes les suivantes. C'est pour ça que la limite « In Progress 1 » est stricte.

## 4. Travaux hors développement (en parallèle, par le propriétaire)
- **Sondage** : ajouter la question « Avez-vous changé de carte SIM ces 12 derniers mois ? Pourquoi : perte / vol / casse / carte abîmée / passage 4G / autre », dans les versions FR et EN. Atteindre 100 réponses et publier les données sur GitHub. Cette question fournit l'ancrage réel du taux de changement de SIM légitime utilisé en T3-07.
- **Rédaction** : le Mois 3 alimente directement le chapitre 3 (méthodologie) : étalon, séparation calibré/modélisé, suréchantillonnage déclaré, reproductibilité.

## 5. Dettes portées (cartes étiquetées `dette`, hors chemin critique du Mois 3)
- Correction de l'énoncé OB-3 dans le SAD (E-01).
- Cold-start de `/evaluate` (EI-01), à résoudre avant le Mois 5.
- Points ouverts de la refonte d'authentification : clé de récupération perdue, contacts de confiance sur USSD (QO-19), portée de l'anti-brute-force du PIN.
- ED-11 (nom du titulaire affiché à la confirmation) ; traduction anglaise du SRS v1.3.

## 6. Où on va : la fin du Mois 3
À la fin du Mois 3, on dispose de :
1. un monde de synthèse **figé, rejouable et prouvé plausible** face au réel ;
2. 300 attaques AC-02 **étiquetées** et des changements de SIM **légitimes** ;
3. des seuils **réglés avec justification chiffrée** ;
4. une **décision d'agrégation** (ADR-016).

C'est exactement l'entrée du **Mois 4** : construire la vraie défense (les 7 obligations OB-1…OB-7) et la mesurer sur ce terrain.
