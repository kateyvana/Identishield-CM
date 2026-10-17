# Levée de la 8e interdiction — carte B-UI, 2026-10-17

Propriétaire : YENGUE MESSAH KATE YVANA
Objet : poussée de la branche `mobile-13-refonte-ui` (refonte du design et du thème de
l'application mobile), autorisée par la carte B-UI, section « Porte avant poussée ».

## Portée
Poussée de la branche `mobile-13-refonte-ui` vers `origin`, et d'elle seule
(`git push -u origin mobile-13-refonte-ui`). Base : `c8dcac8`. Elle porte cinq
commits de contenu, le commit de levée initial, la fusion de `origin/main` et le présent
commit de correction :

- `a5acd10` — thème, composants, écrans et mise en page adaptative ;
- `7963cb4` — logo animé d'E-01 et finitions de l'audit ;
- `5eb25ca` — cadenas « capture d'écran bloquée » dans le bandeau ;
- `cef9780` — ADR-037, amendement B-UI (texte de la propriétaire) ;
- `317ad47` — corrections de la passe sur tablette ;
- `b9a119a` — levée initiale (poussée le 2026-10-17, avant la décision sur l'ordre de fusion) ;
- `81c7ecc` — fusion de `origin/main` (`6305b7e` : correctif CI du shebang, PR #52, et carte A3),
  ordonnée par la propriétaire le 2026-10-17 ;
- le présent commit de correction (ce fichier et les écarts nommés de `mobile/README.md`).

Cette rédaction remplace celle du commit `b9a119a`, dont la portée s'arrêtait à la levée
initiale et qui ne tenait pas compte des décisions de clôture de la propriétaire.

Aucune écriture sur `main`, aucun `--force`, aucune réécriture d'historique, aucune
suppression de branche ni d'étiquette, aucun réglage du dépôt : la fusion relève de la
propriétaire.

## Preuves
- `make check` : VERT sur chaque commit de contenu, VERT après la fusion `81c7ecc`, et relancé
  après l'ajout de ce correctif.
- Fusion `81c7ecc` VÉRIFIÉE, non prédite : `git merge --no-ff origin/main` sans conflit
  (`git diff --diff-filter=U` vide) ; 29 fichiers entrés (correctif CI et carte A3).
- Porte de fusion renforcée : `make check` VERT **et** CI GitHub `portes-legeres` VERTE
  sur la branche poussée (vérifiée après la poussée).
- Tests JVM : 174, tous verts. Les 161 tests existants sont inchangés, sauf un seul,
  modifié par décision de la propriétaire du 2026-10-16 (« cadenas discret permanent ») :
  `EnrolmentScreensTest.secureScreens_showTheCaptureBlockedBadge_likeTheMockups` vérifie
  la description du cadenas au lieu du texte de la pastille. FLAG_SECURE est vérifié
  partout comme avant (`SecureScreensTest` vert).
- Nouveaux tests : `AdaptiveLayoutTest` (8) et `PresentationTest` (5). Sept mutations
  tuées : rail jamais affiché, colonne non bornée, page sans défilement, anciennes chaînes,
  onde du logo retirée, réglage « animations coupées » ignoré, cadre du champ sans focus.
- Dépendances : `androidx.window:window-core` 1.5.0 déclaré à la version déjà résolue ;
  graphe d'exécution identique avant et après (154 coordonnées).
- Appareil réel : tablette Samsung SM-T515, Android 11, 1200 × 1920 à 240 dpi.
  Format téléphone simulé par `wm size 720x1520` et `wm density 320` (360 × 760 dp),
  puis remise à zéro prouvée (`wm size reset`, `wm density reset` : « Physical size:
  1200x1920 », « Physical density: 240 », aucune ligne « Override »). Rotation remise
  (`accelerometer_rotation` = 1, `user_rotation` = 0). Captures en tablette et téléphone,
  portrait et paysage, de E-02, E-03, E-05, E-10, E-11, E-12, E-13, E-16, E-17, E-18,
  E-19, E-22, E-23, E-24, E-26. Écrans protégés E-06, E-08, E-09, E-14, E-15, E-20,
  E-21 : capture de 0 octet dans les quatre formats (FLAG_SECURE tient à la rotation et
  au changement de taille). Limite déclarée : aucun téléphone physique.
- Audit avant poussée : aucun secret en dur ; PIN, OTP et clé de récupération jamais
  affichés, journalisés ni capturés (saisis par un outil qui lit les fixtures sans les
  imprimer, ou par la propriétaire) ; aperçus et banc de rendu avec des données fictives
  et la clé réduite à un masque ; MSISDN fictifs seulement (677000000–677009999) ; 0 CRLF ;
  aucun fichier hors de `mobile/` sauf `docs/01-decisions/ADR-037.md` (demandé) et ce
  fichier.

## Non observé sur l'appareil (rendu montré par la galerie avant/après seulement)
Chaque écran est nommé avec son motif (même liste dans `mobile/README.md`) :
- **E-04** — transitoire : le contrôle dure moins d'une seconde.
- **E-07** — transitoire, et recouvert par la fenêtre système de déverrouillage.
- **E-07-verrou** — difficile à déclencher : exige de retirer le verrouillage d'écran de la tablette.
- **E-27a** — difficile à déclencher : le faux serveur ne révoque jamais une clé.
- **E-27b** — difficile à déclencher : le faux serveur ne vérifie pas l'attestation, il ne
  refuse donc jamais l'appareil.
- **Tous les écrans** — pas de téléphone physique : format téléphone simulé sur la tablette.
- **E-01, animation** — EXIGENCE RESTANTE (décision de la propriétaire du 2026-10-17) :
  une animation ne se juge pas sur une image fixe ; un enregistrement vidéo sur la tablette
  (`adb shell screenrecord`) est exigé. Non encore fait au moment de ce correctif : tablette
  verrouillée. Seule la première image est vérifiée (barre d'état transparente, fond
  #F4F7FB). La vidéo sera ajoutée par un commit de documentation dédié.

## Réserves consignées
1. Indicateur d'étape : ABANDONNÉ, nulle part (décision de la propriétaire du 2026-10-17).
   E-03 et E-06 servent aux flux A et D ; un compteur propre au flux violerait ES-26 et
   serait une fuite d'énumération (« ce numéro est nouveau » / « ce numéro est déjà lié ») ;
   un compteur identique serait faux dans l'un des flux. Écart justifié, consigné.
2. HORS CARTE, invariant D4 : après une sortie par le bouton d'accueil pendant E-08,
   l'application a rouvert sur E-10 (constaté le 2026-10-17). DÉDUIT : l'état sauvegardé
   a été perdu (processus et état nettoyés par le système) alors que la clé existait déjà
   dans la puce ; l'application l'a traitée comme liée. La garde côté serveur (clé en
   attente jusqu'à la confirmation, prévue par la carte B3a, dont l'ADR n'est pas encore
   écrit) n'existe pas encore dans le faux serveur. Aucune logique n'a été touchée par B-UI.
3. HORS CARTE : une session expirée pendant l'autorisation d'un envoi s'affiche « Code PIN
   incorrect » (réponse unique du serveur, ES-22) ; le message induit l'usager en erreur.
4. Deux volets pour les alertes sur tablette : abandonnés (un volet vide « choisissez une
   alerte » ne mènerait nulle part) ; abandon accepté par la propriétaire le 2026-10-17.
5. Numéro masqué « 677 ••• 200 » : peut passer à la ligne au milieu en police agrandie
   (texte vérifié par des tests de parcours, laissé tel quel).
6. États vides (« Aucune opération. », « Aucune alerte. ») sans illustration.
