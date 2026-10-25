# Carte B1b · Défauts D5–D8, charte, D-B7-3 — ce qui est corrigé, ce qui est déclaré

Date : 2026-10-25. Couloir B (Mobile). Carte #44. Branche `mobile-25-b1b-defauts` (depuis `main`
9ea36dc). Référence : passe QA tablette du 2026-10-08 (`captures/RAPPORT_PASSE_2026-10-08.md`,
défauts D5–D8), passe B7 du 2026-10-25 (`captures/2026-11-B7/RAPPORT-PASSE-B7.md`, constat D-B7-3),
charte qualité mobile (CLAUDE.md §10). Décision du superviseur du 2026-10-25 : D-B7-3 dans B1b,
sans nouvelle route ni changement de contrat.

| Défaut | Constat 2026-10-08 | État au 2026-10-25 (passe B7) | Action B1b |
|---|---|---|---|
| **D5** espaces manquants aux jointures (« votreclé », « ÀJean-Paul », « Pour liercetéléphone », « bloqué.Vous ») | E-02, E-08, E-13, E-20, E-21 | **Déjà corrigé** : les morceaux portent leurs espaces (` `, `strings.xml` : `welcome_warning_*`, `recovery_intro_*`, `recovery_body_*`, `wait_not_blocked*`) ; E-02 et E-13 observés corrects sur l'appareil | Aucune (vérifié) |
| **D6** case vide supplémentaire après le dernier chiffre de l'OTP | E-05 | **Non reproductible** : le champ OTP est devenu un champ unique (`CodeField`, B-UI), pas une rangée de cases ; E-05 observé le 25/10 sans case surnuméraire | Aucune (champ redessiné) |
| **D7** écrans E-01 et E-04 invisibles quand le serveur répond vite | E-01, E-04 | E-01 capturé le 25/10 (0,4 s après le lancement) ; E-04 capturé ; **décision du propriétaire (B-UI, 17/10) : ne jamais rallonger E-01** | Aucune : **déclaré** (comportement voulu) |
| **D8** mise en page 360 dp étirée : grands vides, cases loin du libellé (E-09), numéro non groupé (E-03) | E-02, E-03, E-09, E-22 | Largeur de contenu déjà bornée (`Dimens.ContentMaxWidth = 600 dp`, colonne centrée) ; E-09 = `CodeField` sous son libellé (redessiné) ; **numéro non groupé : restait vrai** (E-03 et E-12 observés « 677000200 ») | **Corrigé** : `MsisdnTransformation` (affichage « 677 000 200 », valeur brute, curseur suivi) sur E-03 et E-12 ; test `MsisdnTransformationTest` |
| **D-B7-3** l'accueil n'affiche pas l'état FROZEN après « ce n'est pas moi » | E-11 (25/10) | E-24 l'affichait déjà ; l'accueil non | **Corrigé** : bandeau « Compte gelé pour votre sécurité · Les envois sont bloqués. Rendez-vous chez un agent MTN… Aucun code… » quand `account.state == FROZEN` (`GET /account`, état lu au PDP par le cœur) ; tests `e11_frozenAccount_showsTheFrozenBanner_trustedDoesNot`, `e11_trustedAccount_hasNoFrozenBanner` |

## Charte qualité mobile (§10) — ce qui a été observé sur l'appareil le 25/10

1 appareil réel (tablette SM-T515) : oui, passe B7 ; téléphone : non disponible. 2 champs : clavier
numérique, longueur imposée (9 chiffres ; 10e refusé), bouton désactivé tant que vide : observé.
3 messages : E-18 précis ; **D-B7-1** (générique sur 503) qualifié à part, correctif dans une PR
dédiée. 4 préconditions : verrou d'écran (E-07 « Déverrouillage local ✓ »). 5 état conservé :
non rejoué (B1a). 6 secrets : écrans FLAG_SECURE non capturés, OTP/PIN/clé jamais lus ni écrits
par l'automate. 7 session : 61 s d'arrière-plan et ~25 min d'inactivité → verrou serveur, observé.
8 mise en page : police 200 % et paysage sur tablette, observé. 9 rapports : « non observé » écrit
tel quel. 10 secrets dans les journaux : aucun (logcat système seul ; `-68` sans donnée).

## Limites déclarées

- **L-B1b-1** · D7 est voulu (pas de rallongement d'E-01) ; E-04 n'est visible que si le réseau
  est lent. Déclaré, pas corrigé.
- **L-B1b-2** · « Charte graphique » : au-delà de la largeur bornée et des bandeaux existants,
  aucune autre évolution visuelle n'a été demandée par écrit ; rien d'autre n'est changé.
- **L-B1b-3** · Vérification sur appareil de B1b : les deux changements (bandeau FROZEN, numéro
  groupé) sont couverts par des tests JVM ; l'observation sur la tablette suppose un nouvel APK
  (étape (b) de B7 à refaire : nouvelle empreinte) — **non observé** tant que l'APK n'est pas
  reconstruit et réinstallé.
- **L-B1b-4** · `make check` complet : impossible au moment du commit (réseau sortant coupé : l'étape
  build de la console télécharge des polices) ; tests JVM de l'application verts hors ligne. À
  rejouer avant push.
