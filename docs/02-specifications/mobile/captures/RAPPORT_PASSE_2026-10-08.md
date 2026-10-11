# Passe de vérification visuelle sur appareil réel — 2026-10-08 (révisé le 2026-10-11, carte B1a)

**Branche de la passe :** `mobile-08-fin-parcours` (9e20869), charte qualité `e32396a`. **Aucun code modifié pendant la passe** : les défauts ont été consignés. **Révision B1a (2026-10-11) :** captures renommées d'après ce que chaque image montre, statuts corrigés, défauts D10 à D13 ajoutés, D4 complété ; correctifs D2, D3, D4, D9, D11 et E-09 livrés sur la branche `mobile-10-defauts` (section 6).

**Appareil :** tablette Samsung **SM-T515** (Galaxy Tab A 10.1, 2019), **Android 11** (mise à jour faite pendant la passe depuis Android 10), **aucun capteur biométrique** (ni empreinte ni visage) : le déverrouillage local passe par le **code de verrouillage d'écran**, défini pendant la passe (aucun verrou au départ). Écran 1200 × 1920 px, 240 dpi, soit 800 × 1280 dp, contre 360 × 760 dp pour les maquettes.

**Montage :** APK **debug**, construit avec `-Pisc.apiBaseUrl=https://127.0.0.1:8089/mobile/v1/` et la CA de développement épinglée ; faux serveur HTTPS sur le PC (`make mobile-mock MOBILE_MOCK_DEMO=1`, journal non tamponné), joint par `adb reverse tcp:8089 tcp:8089`. Pilotage par `adb` (appuis, saisies, captures) ; le propriétaire saisit le code de verrouillage dans les fenêtres système et a noté les clés de récupération sur papier. Numéros fictifs 677… seulement.

**Captures :** `adb exec-out screencap`. Sur un écran protégé (`FLAG_SECURE`), Samsung ne rend pas une image noire : la capture est **refusée (0 octet)**. C'est la preuve attendue ; ces fichiers vides ne sont pas conservés, et le tableau note « capture refusée » avec les libellés lus dans l'arbre d'accessibilité, secrets masqués.

**Nettoyage de l'incident du 08/10 (étape 0 de B1a, 2026-10-11).** Sur le PC : dumps d'écran, `keepalive_proxy.py`, `uidump.py`, `fields.py` et journaux `qa-*` de la passe supprimés du dossier temporaire de la session (33 fichiers, liste rapportée au propriétaire). Sur la tablette : **non observé**, l'appareil n'a pas été détecté par `adb` pendant la carte B1a (`adb devices` vide, serveur relancé, aucun périphérique USB Samsung côté Windows) ; `/sdcard/ui.xml`, `svc power stayon false` et les redirections `adb reverse` restent à vérifier dès que la tablette est branchée. **Compte de test dont la clé de récupération a fui :** `acc-000200` / 677 000 200 (« Compte de test NEUF »), seul compte enrôlé pendant la passe ; il sera réenrôlé. Aucune valeur de clé n'est reproduite ici.

## 0. Référentiel : table officielle des écrans (ISC-MOB-SPEC v1.2 §10)

| N° | Nom | Rôle (résumé de la spécification) |
|---|---|---|
| E-01 | Lancement | Logo ; vérification locale de la clé |
| E-02 | Bienvenue | « Cet appareil va devenir la clé de votre compte » ; avertissement désinstallation |
| E-03 | Numéro | Saisie du numéro ; validation de la plage |
| E-04 | Attente | Réponse du serveur (aiguillage) |
| E-05 | OTP | 6 chiffres ; renvoi après 60 s |
| E-06 | PIN 🔒 | PIN MoMo |
| E-07 | Sécurisation | Déverrouillage local du téléphone ; progression |
| E-08 | Clé de récupération 🔒 | Clé affichée une fois ; consignes papier |
| E-09 | Confirmation 🔒 | Saisie de caractères de la clé |
| E-10 | Déverrouillage | Numéro mémorisé (masqué) ; PIN + déverrouillage local |
| E-11 | Accueil | Solde ; badge « Appareil protégé » ; alertes non lues ; opérations différées |
| E-12 | Bénéficiaire | Saisie du numéro |
| E-13 | Montant | Saisie du montant |
| E-14 | Confirmation 🔒 | Nom du titulaire, montant, badge « nouveau bénéficiaire » |
| E-15 | Autorisation 🔒 | PIN puis déverrouillage local du téléphone, qui autorise la signature |
| E-16 | Succès | Reçu |
| E-17 | Exécution différée | Message ; délai DEFER ; bouton Annuler |
| E-18 | Bloquée / Rejetée | FREEZE ou REJECTED |
| E-19 | Historique | Liste des opérations |
| E-20 | Clé de récupération 🔒 | Saisie de la clé ; lien « J'ai perdu ma clé » |
| E-21 | Attente imposée | Compte à rebours ; « Votre compte n'est pas bloqué » |
| E-22 | Réenrôlement réussi | Nouvelle clé créée ; ancienne révoquée |
| E-23 | Alertes | Liste |
| E-24 | Détail d'alerte | « C'est moi » / « Ce n'est pas moi » |
| E-25 | Sécurité | Appareil lié ; régénérer la clé ; délier ; mode démonstration |
| E-26 | Clé perdue | Orientation ; en attente de QM-05 |
| E-27 | Erreurs | Réseau, serveur, appareil incompatible, attestation refusée, clé révoquée |

**Écrans FLAG_SECURE (MOB-S-05, ISC-MOB-SPEC v1.2 §13) :** **E-06, E-08, E-09, E-14, E-15, E-20**. L'application protège aussi E-21 (non exigé).

### Captures : renommage d'après le contenu réel (2026-10-11)

| Fichier (nouveau nom) | Ancien nom | Ce que l'image montre | Remarque |
|---|---|---|---|
| `E01-lancement.png` | E01.png | page vide (fond seulement) | E-01 non observable (D7) |
| `E02-bienvenue.png` | E02.png | E-02 Bienvenue | « votreclé » (D5) ; grand vide (D8) |
| `E03-numero.png` | E03.png | E-03 Numéro, vide | bouton désactivé : conforme |
| `E03-numero-saisi.png` | E03-saisi.png | E-03 Numéro, « 677000200 » | numéro non groupé (D8) |
| `E04-attente.png` | E04.png | E-04 Attente | — |
| `E05-otp.png` | E05.png | E-05 OTP, vide, « Renvoyer le code (dans 54 s) » | — |
| `E05-otp-saisi.png` | E05-saisi.png | E-05 OTP, 6 chiffres + 7ᵉ case vide | D6 ; l'OTP visible est la valeur de test publique du faux serveur, pas un secret |
| `E07-securisation-erreur-sans-verrou.png` | E07-erreur-sans-verrou.png | E-07 Sécurisation, « Une erreur est survenue. Réessayez. » | D3 |
| `E10-deverrouillage.png` | E10.png | E-10 Déverrouillage, 677 ••• 200 | D10 |
| `E10-deverrouillage-erreur-connexion.png` | E10-erreur-connexion.png | E-10, « Connexion impossible… » | D9 |
| `E10-deverrouillage-paysage-apres-rotation.png` | E10-paysage-apres-rotation.png | E-10 en paysage | D4 (b) |
| `E10-deverrouillage-session-expiree.png` | E10-session-expiree.png | E-10, 677 ••• 123, « Votre session a expiré… » | D4 (c) |
| `E11-accueil.png` | E11.png | E-11 Accueil (Amina, 152 300 FCFA, SM-T515) | D12 |
| `E12-beneficiaire.png` | **E13.png** | **E-12 Bénéficiaire**, vide | mal nommée avant |
| `E12-beneficiaire-clavier.png` | **E13-saisi.png** | **E-12 Bénéficiaire**, « 677000456 », clavier système ouvert | **D13** : « Continuer » hors de l'écran |
| `E13-montant.png` | **E14.png** | **E-13 Montant**, 0 FCFA | mal nommée avant ; voir D11 |
| `E13-montant-saisi.png` | **E14-saisi.png** | **E-13 Montant**, 5 000 FCFA, solde après envoi | « ÀJean-Paul » (D5) |
| `E19-historique.png` | **E23.png** | **E-19 Historique**, « Aucune opération. » | D12 |
| `E19-historique-bis.png` | **E12.png** | E-19 Historique (doublon, même minute) | conservé tel quel |
| `E22-reenrolement-reussi.png` | E22.png | E-22 Réenrôlement réussi | — |
| `E23-alertes.png` | **E19.png** | **E-23 Alertes** (« Nouveau téléphone lié ») | D12 |
| `E26-cle-perdue.png` | E26.png | E-26 Clé perdue | — |

Vérification « aucune capture ne montre un secret » (2026-10-11, 22 images relues) : aucune clé de récupération, aucun PIN (points vides), aucun jeton. Seul l'OTP de démonstration du faux serveur (valeur de test publique de `fixtures.json`) apparaît sur `E05-otp-saisi.png`.

## 1. Tableau écran par écran

Verdict « conforme » seulement pour ce qui a été observé (charte, point 9).

| Écran | Capture | Verdict | Description | Gravité |
|---|---|---|---|---|
| E-01 Lancement | `E01-lancement.png` (fond vide) | **Non observable** | La vérification de la clé passe sous la première image : six captures rapprochées, toutes déjà sur E-02 ou E-10. | D7, mineure |
| E-02 Bienvenue | `E02-bienvenue.png` | Écart | Contenu conforme ; **« votreclé »** sans espace ; mise en page téléphone étirée sur tablette. | D5, D8 |
| E-03 Numéro | `E03-numero.png`, `E03-numero-saisi.png` | Écart | Conforme (titre, +237, aide, bouton désactivé tant que vide). Numéro non groupé. | D8 |
| E-06 PIN 🔒 | capture refusée (0 octet) | Conforme | Libellés lus : « 🔒 capture bloquée », « Votre code PIN », « Compte 677 000 200 », pavé 0-9, « Continuer ». | — |
| E-04 Attente | `E04-attente.png` | Conforme | Observé une seule fois, pendant une attente réseau. | — |
| E-05 OTP | `E05-otp.png`, `E05-otp-saisi.png` | Écart | Conforme (numéro masqué, compte à rebours qui décroît). Une **case vide supplémentaire** après le 6ᵉ chiffre. | D6 |
| E-07 Sécurisation | `E07-securisation-erreur-sans-verrou.png` | **Écart majeur** | Sans verrou d'écran : « Une erreur est survenue. Réessayez. » à l'étape « Clé créée dans la puce ». Cause établie côté système (`CredentialType: None`) ; l'application ne dit pas quoi faire. Avec un code défini : clé créée, fenêtre système, liaison OK. | D3 |
| E-08 Clé de récupération 🔒 | capture refusée | **Écart majeur** | **La clé est lisible dans l'arbre d'accessibilité** (`uiautomator`). Texte collé « maintenant.Elle ne serajamaisaffichée ». | D2, D5 |
| E-09 Confirmation 🔒 | capture refusée | Écart | Bouton désactivé tant que les cases ne sont pas remplies : conforme. Cases de 72 px tout à droite, loin du libellé. Caractères tapés lisibles dans l'arbre d'accessibilité. Saisie par `adb` non reproduite au second passage. | D1 (non reproduit), D2, D8 |
| E-10 Déverrouillage | `E10-deverrouillage.png` | **Écart** | Numéro masqué, pas de nom (décision M-07), pavé, « Continuer ». Texte « empreinte, visage ou code » : voir D10. Paysage : voir D4. | D10 |
| E-11 Accueil | `E11-accueil.png` | Écart | Titulaire, solde, badge « Appareil protégé · SM-T515 », quatre tuiles, barre du bas. « Sécurité » inactif sans aspect désactivé net. | D12 |
| E-12 Bénéficiaire | `E12-beneficiaire.png`, `E12-beneficiaire-clavier.png` | **Écart** | Champ et libellé conformes ; dès que le clavier système s'ouvre, **« Continuer » est caché sous le clavier** (la page ne défile pas). | D13 |
| E-13 Montant | `E13-montant.png`, `E13-montant-saisi.png` | Écart | Montants rapides du jeu de règles, « Solde après envoi » : conformes. « ÀJean-Paul NKOA » sans espace. | D5 |
| E-14 Confirmation 🔒 | — | **Non observé** | Jamais atteint pendant la passe (D9 a interrompu le flux C). Aucune capture de cet écran n'existe : le fichier auparavant nommé `E14.png` montrait E-13. | — |
| E-15 Autorisation 🔒 | — | Non observé | Idem. | — |
| E-19 Historique | `E19-historique.png` | Écart | « Aucune opération. » ; barre du bas : **Alertes inactif** depuis cet écran. | D12 |
| E-20 Clé de récupération 🔒 | capture refusée | **Écart majeur** | Clé saisie lisible dans l'arbre d'accessibilité (champ non marqué « mot de passe »). | D2 |
| E-21 Attente imposée 🔒 | capture refusée | Conforme | Compte à rebours, « Votre compte n'est pas bloqué ». | — |
| E-22 Réenrôlement réussi | `E22-reenrolement-reussi.png` | Conforme | « Ce téléphone est maintenant votre clé », ancien délié / ce téléphone lié, avertissement, « Afficher ma nouvelle clé ». | — |
| E-23 Alertes | `E23-alertes.png` | Conforme | « Nouveau téléphone lié · 8 oct. à 17:25 ». | — |
| E-24 Détail d'alerte | — | Non observé (capture) | Réponse « ce n'est pas moi » exercée depuis le PC (incident 6), écran non capturé. | — |
| E-26 Clé perdue | `E26-cle-perdue.png` | Conforme | Orientation en trois étapes, encadré QM-05, « Retour ». | — |
| E-25, E-27b | — | Non observé | Hors M-08 ; appareil compatible. | — |

## 2. Défauts (numérotation du propriétaire)

| Id | Gravité | Écran | Constat | Preuve | Carte |
|---|---|---|---|---|---|
| D1 | **non reproduit** | E-09 | Cases non saisissables par `adb` au premier passage ; saisissables au second. Le premier passage a coïncidé avec une rotation. | arbre d'accessibilité, deux passages | — |
| D2 | **majeure, sécurité** | E-08, E-09, E-20 | Secrets lisibles par les services d'accessibilité : clé affichée (E-08), caractères de confirmation (E-09), clé saisie (E-20). `FLAG_SECURE` bloque les pixels, pas le texte. Scénario S-16. | `uiautomator dump` | **B1a, corrigé** |
| D3 | **majeure** | E-07 | Sans verrou d'écran, la création de clé échoue avec un message générique ; la précondition n'est pas vérifiée avant d'agir (charte 3 et 4). | `E07-securisation-erreur-sans-verrou.png`, `dumpsys lock_settings` | **B1a, corrigé** |
| D4 | **majeure** | flux A, flux C | Perte de progression : **(a)** processus recréé après un passage dans les Réglages → retour à E-02 ; **(b)** rotation après la création de la clé et avant la confirmation → saut à **E-10**, la copie de la clé n'est jamais confirmée ; **(c) complété le 2026-10-11 :** à l'expiration de session, la saisie en cours (numéro du bénéficiaire, montant) est perdue : retour à E-10 puis à l'accueil, champs vides. | `E10-deverrouillage-paysage-apres-rotation.png`, `E10-deverrouillage-session-expiree.png` | **B1a, corrigé** |
| D5 | mineure | E-02, E-08, E-13, E-20, E-21 | Espaces manquants aux jointures de chaînes : « votreclé », « maintenant.Elle ne serajamaisaffichée », « ÀJean-Paul », « Pour liercetéléphone », « bloqué.Vous ». | captures, arbre | B1b |
| D6 | mineure | E-05 | Case vide supplémentaire après le dernier chiffre. | `E05-otp-saisi.png` | B1b |
| D7 | mineure | E-01, E-04 | Écrans de lancement et d'attente invisibles tant que le serveur répond vite. | rafale de captures | B1b |
| D8 | mineure | E-02, E-03, E-09, E-22 | Mise en page pensée pour 360 dp : grands vides, cases loin du libellé, numéro non groupé. | captures | B1b |
| D9 | **majeure** | E-10 (et toute route après une attente) | **« Connexion impossible » alors que le serveur est joignable** : après le défi, la connexion est gardée en réserve par OkHttp alors que le serveur l'a fermée (`CLOSE_WAIT` côté tablette) ; la requête suivante la réutilise et échoue, et l'application ne rejoue jamais. Reproduit 4 fois ; passe quand plus de 5 min séparent les deux requêtes (éviction du pool). | journal serveur, `netstat` tablette et PC | **B1a, corrigé** |
| D10 | mineure (conception) | E-10, E-07, E-15 | Texte « empreinte, visage ou code » sur l'écran de déverrouillage (et dans les fenêtres système E-07/E-15) : contredit la décision « biométrie hors conception » (ISC-MOB-SPEC v1.2 §1 : le déverrouillage local n'est pas un facteur). | `E10-deverrouillage.png` | B1b (consigné seulement) |
| D11 | **requalifié** | E-14 (liste FLAG_SECURE) | Signalé comme « écran listé FLAG_SECURE mais capture réussie » d'après le fichier `E14.png`. **Relecture du 2026-10-11 : cette image montre E-13 Montant**, qui n'est pas dans la liste ; aucune capture d'un écran de la liste n'a réussi (E-06, E-08, E-09, E-20, E-21 : 0 octet ; E-14, E-15 jamais atteints). Le défaut venait du nom du fichier. Un test table des six écrans de la liste verrouille `FLAG_SECURE` sur chacun. | images renommées | **B1a** (test de garde ; preuve tablette : non observée, appareil absent) |
| D12 | mineure | E-11, E-19, E-23 | Barre du bas : « Alertes » inactif depuis Historique (E-19) ; « Sécurité » inactif partout, sans aspect désactivé (seule la couleur grise change, pas de mention « bientôt »). | `E19-historique.png`, `E11-accueil.png` | B1b (consigné seulement) |
| D13 | majeure (usage) | E-12 | Bouton « Continuer » caché sous le clavier système ; la page ne défile pas et le clavier ne se ferme pas sur « OK ». | `E12-beneficiaire-clavier.png` | B1b (consigné seulement) |

## 3. Incidents de passe (outillage, pas des défauts de l'application)

1. Clé de test partiellement exposée dans mes sorties (un groupe de 4 caractères sur E-08, puis les 4 caractères saisis sur E-09) : fichiers supprimés, filtre corrigé, **clé du compte acc-000200 / 677 000 200 considérée compromise**, faux serveur redémarré ; le compte sera réenrôlé.
2. APK de l'émulateur réinstallé par erreur à 16:50 (`make check` reconstruit `app-debug.apk` avec `10.0.2.2` et sans CA) : les échecs réseau de 16:56 viennent de là, pas de l'application. APK reconstruit avec les bons paramètres et réinstallé à 17:15.
3. Journal du premier faux serveur perdu (tampon non vidé à l'arrêt) ; relancé avec `PYTHONUNBUFFERED=1`.
4. Enrôlement expiré (`ENROL_TTL` 10 min) pendant la confirmation E-09 : `410` légitime côté serveur, affiché « Connexion impossible » par l'application (rattaché à D9).
5. La commande du README (`-Pisc.sandboxCa=../tools/...`) résout le chemin depuis `mobile/app/` : défaut de documentation, contourné par un chemin absolu.
6. Alerte « carte SIM changée » : non déclenchable depuis l'extérieur du faux serveur (`raise_sim_change` hors contrat) ; remplacée par l'alerte « tentative de liaison d'un autre téléphone », obtenue par cinq clés fausses depuis le PC.
7. **Captures mal nommées** (E12, E13, E14, E19, E23) : corrigées le 2026-10-11, voir la table du §0.

## 4. Non observé

E-14, E-15, E-16, E-17, E-18 (flux C interrompu par D9), E-24 (capture), E-25 (hors M-08), E-27b (l'appareil a une puce matérielle), interrogation des alertes en arrière-plan (premier plan seulement, décision du propriétaire), taille de police 200 %, tablette en paysage sur les autres écrans.

## 5. Décisions et points à trancher relevés par B1a

- **E-09 refondu (décision du propriétaire du 2026-10-10)** : code de récupération de **6 caractères** (lettres et chiffres, alphabet sans I, L, O, 0, 1), saisie **complète** du code sur E-09, masqué sauf les 2 derniers caractères. `CODE_LENGTH` passe de 20 à 6 dans `config/ruleset.yaml`. ISC-MOB-SPEC v1.2 §10 (E-09 : « 4 caractères tirés au hasard ») et le contrat (`confirmPositions`, `characters` : 4 éléments) ne décrivent plus ce comportement : **ISC-MOB-SPEC v1.3 à mettre à jour par le propriétaire** ; le contrat `contracts/mobile-api.yaml` est mis à jour (6 éléments).
- **D10** : la chaîne « empreinte, visage ou code » est celle de la fenêtre système `BiometricPrompt` (biométrie OU code de l'appareil). La décision « biométrie hors conception » demande un libellé ne nommant que le déverrouillage du téléphone : B1b.

## 6. Correctifs B1a (branche `mobile-10-defauts`, base origin/main d710eb3)

Complété en fin de carte : voir la section « Commits » ci-dessous.
