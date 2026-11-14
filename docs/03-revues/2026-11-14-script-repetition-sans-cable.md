# Script de la répétition générale — variante SANS câble (carte M5-B1)

Date : 2026-11-14. Couloir B (Mobile). Ordre du superviseur technique du 2026-11-14, mois 5, objectif n°1 :
**une démonstration sans câble**. Ce document est la variante « sans câble » du script #131
(`2026-11-03-script-repetition-generale.md`, fusionné) : mêmes étapes R0 à R12, mêmes comptes, mais la
propriétaire fait tout à la main sur la tablette et le couloir B ne lit que des preuves **côté serveur, en
lecture seule**. **Aucune commande `adb`** dans ce script. Documentation seule : rien n'a été exécuté sur la
tablette ni sur la VM pour l'écrire.

Comptes : **répétition 677001102** (la veille de la démonstration, sans chauffe : #123 est en service depuis la
fenêtre 6 du 14/11, `main` 4f3ef12 puis 963b6c3), **démonstration 677001103**, **bénéficiaire 677001101**.
**Jamais** 677000200 (gelé). La tablette SM-T515 est aujourd'hui **liée à 677001104** (clé ACTIVE d4b6c29a du
27/10 ; virement ALLOW du 14/11 fait, `pdp.audit_entry` 120298).

## 0. Ce que le câble faisait, et ce qui le remplace

| Usage du câble aujourd'hui | Remplacé par | Reste impossible (§6) |
|---|---|---|
| Installer l'APK (`adb install`) | Rien : l'APK déjà installé est conservé (§1, P2) | Oui : nouvel APK |
| Lire le code SMS dans la VM et le taper (`tap_digits.py`) | La propriétaire lit le code dans l'onglet **SMS de la console** (#65, couloir A) et le tape elle-même (R4) | Tant que #65 n'est pas livré |
| Captures `screencap`, relevés `uiautomator` | Captures par les **boutons de la tablette**, facultatives (§4) ; aucun relevé | Relevés d'arbre d'accessibilité |
| Vérifier la route Tailscale, le port 8443, la batterie | Application Tailscale « Connecté » sur la tablette + `tailscale status` lu dans la VM par B ; navigateur de la tablette sur `https://100.93.166.19:8443/` (P3b) | Preuve #66 par `nc` depuis la tablette |
| Désinstaller / réinstaller (R0, R11) | **Effacer les données** de l'application dans les Réglages (§2) | Réinstallation |

## 1. Préalables (la veille ou le matin), sans câble

| # | Quoi | Qui | Preuve |
|---|---|---|---|
| P1 | Lire le témoin `~/VM-OCCUPEE` (commande seule ; s'il existe, attendre). **Poser le témoin seulement quand la propriétaire est prête et présente** ; jamais de `jusqu-a` dépassé : si l'attente redevient humaine, le retirer et le reposer (règle du superviseur, 14/11) | B | contenu du témoin |
| P2 | **APK : celui déjà installé**, `versionName 0.1.0`, installé le 2026-10-27 01:19Z (SHA-256 du fichier `d6add23b…962e`, construit depuis `main` f3e560d, ordre du 26/10). Écart avec `main` 963b6c3, côté `mobile/` : 3e55aa8 (KDoc), 8d9bfb0 (`begin()` efface tous les secrets en mémoire à l'abandon d'un enrôlement), 8787f5d (test). **Aucun effet sur le déroulé** ; l'écart est déclaré, pas corrigé (gel) | B | `git log f3e560d..main -- mobile/` |
| P3 | Tablette : **chargée** (le 14/11 elle était à 12 % sur USB ; sans câble, prévoir le chargeur secteur), mot de passe de la tablette changé (incident du 27/10), Tailscale **VPN permanent** et **batterie non restreinte** pour Tailscale (chemins dans #131 P3) | propriétaire | déclaration |
| P3b | **Juste avant chaque passage** (R0, R8, virement) : sur la tablette, l'application Tailscale affiche « Connecté » ; dans la VM, B lit `tailscale status` → `galaxy-tab-a` **active** (le 14/11 : `active; relay "sao"`), jamais `offline`. Si doute : la propriétaire ouvre `https://100.93.166.19:8443/` dans le navigateur de la tablette ; un avertissement de certificat ou une page d'erreur HTTP prouve que le port répond ; « connexion impossible » prouve le contraire | propriétaire + B | sortie brute de `tailscale status` |
| P4 | Preuve #66 (22 et 3000 refusés depuis la tablette) : **non faisable sans câble** ; reste celle de la règle Tailscale (couloir A / propriétaire) | — | non observé |
| P5 | Jeu de règles : `config/ruleset.yaml` identique poste et VM (sha256) | B | empreintes |
| P6 | Aucun `check-stack`, aucun `make up`, aucun redémarrage du cœur ou du PDP **pendant** la répétition | A, B | témoin |
| P7 | **Onglet SMS de la console (#65) livré et déployé** ; la propriétaire y accède depuis le navigateur du poste par le tunnel SSH `ssh -N -L 3000:127.0.0.1:3000 isc@192.168.56.10` puis **`http://localhost:3000/sms`** (`2026-10-24-console-boucle-locale.md` ; si le port 3000 est déjà pris sur le poste, changer seulement le port local, par exemple `-L 3300:127.0.0.1:3000` et `http://localhost:3300/sms` ; précision du couloir A, relecture de #139). B ouvre le tunnel si on le lui demande, **ne regarde pas** l'onglet et ne relève rien du poste | A, propriétaire | déclaration |

## 2. R0 — Repartir de zéro à la main : « Effacer les données »

**Pourquoi.** La tablette est liée à 677001104 ; la répétition enrôle 677001102 par le flux A (premier
enrôlement). Sans câble, on ne réinstalle pas : on **vide les données de l'application**.

**Comment (Samsung SM-T515, Android 11, One UI 3).** Paramètres → Applications → IdentiShield → Stockage →
**« Effacer les données »** (confirmer ; « Vider le cache » est sans effet utile). **Ne jamais désinstaller** :
sans câble, l'APK ne peut pas être réinstallé (§6).

**Ordre imposé.** Le **virement de jour 677001104 → 677001101 (§5) se fait AVANT** d'effacer les données :
l'effacement détruit la clé privée du compte 677001104 sur la tablette, et plus aucune signature n'est possible
pour ce compte ensuite.

**Ce que l'effacement détruit (DÉDUIT du comportement Android, non observé sur cette tablette) :**

1. Tout le stockage privé de l'application : numéro mémorisé, identifiant d'appareil, compteur et échéance du
   délai local (ES-21), préférences. Au relancement, l'application se comporte comme à la première
   installation (E-01 → E-02).
2. Les **clés du Keystore appartenant à l'application** : Android efface les entrées du Keystore de l'UID
   quand il efface les données de l'application (`clearApplicationUserData` → nettoyage du Keystore). La clé
   privée de l'appareil d4b6c29a est donc **détruite, sans retour**. C'est l'analogue, côté données, de ce que
   SPIKE-1 (Q6) a constaté à la **désinstallation** sur itel A70 et Redmi Pad SE (`getKey → null`) ; **le cas
   « effacer les données » n'a jamais été observé sur la SM-T515**.

**Ce que l'effacement ne touche pas :**

- côté serveur, **rien** : `pdp.device` d4b6c29a reste **ACTIVE** pour 677001104, `core.enrolment` reste
  DONE, `pdp.trust_state` inchangé, les soldes inchangés. Le serveur ne sait pas que le téléphone a perdu la
  clé. Le compte 677001104 devient un compte lié à une clé **dont aucun téléphone ne dispose plus** : il ne
  peut être repris que par le **flux D** (clé de récupération notée sur papier par la propriétaire le 27/10 ;
  décision sur sa qualité toujours attendue) ;
- le verrouillage d'écran de la tablette, Tailscale, les autres applications ;
- le compte 677001102, qui n'a aucune clé : le flux A y est possible.

**Ce que B vérifie (lecture seule) et ce qui n'est pas vérifiable :**

| Vérifié par B avant/après R0 | Non vérifiable sans câble |
|---|---|
| `pdp.device` de 677001104 : toujours **une** ligne ACTIVE d4b6c29a (l'effacement est invisible au serveur) | que la clé locale a bien disparu (`getKey → null`) : seule la propriétaire voit E-01 au relancement |
| aucune nouvelle ligne `core.enrolment`, aucune transaction, aucune entrée d'audit pendant R0 | l'absence de fichier résiduel dans le stockage privé |
| 677001102 : aucune clé, aucun enrôlement (état de départ du flux A) | la version exacte de l'APK conservé (dumpsys) : on se fie à P2 |

La **première exécution** de ce R0 est donc elle-même une observation : si l'application ne revient pas à E-01,
ou si elle se souvient du numéro, c'est un écart à consigner tel quel.

## 3. Déroulé de la répétition (677001102), la veille de la démonstration, de jour (05:00–23:00 Douala)

Chaque ligne : ce que fait la propriétaire, ce que B lit côté serveur (SQL en lecture seule, jamais la boîte
`isc-sim` qui contient le code), capture facultative (§4). « Conforme » ne s'écrit que pour ce que B a lu ou
que la propriétaire déclare avoir vu, et le rapport dit lequel des deux.

| Étape | Propriétaire (à la main) | Attendu à l'écran (déclaré par la propriétaire) | B lit côté serveur (lecture seule) | Capture (facultative) |
|---|---|---|---|---|
| R0 | §2 : Effacer les données ; relancer IdentiShield | E-01 puis E-02 « Cet appareil va devenir la clé… » | `pdp.device` 677001104 inchangé (ACTIVE) ; 677001102 sans clé | E-02 |
| R1 | « Commencer » | E-03 | — | — |
| R2 | Numéro **677001102**, « Continuer » | « 677 001 102 » affiché (D8) | — | E-03 |
| R3 | **PIN tapé par la propriétaire**, « Continuer » | E-05 OTP, « 677 ••• 102 », compte à rebours | `core.enrolment` du compte : une ligne, étape « OTP envoyé », `created_at` | aucune (E-06 protégé) |
| R4 | Lit le **code dans l'onglet SMS de la console** (#65, P7) sur le poste, **après R3 seulement** (avant, l'onglet dit « Aucun SMS reçu par cette SIM ») ; vérifie que l'heure « Remis à » est celle de la demande (seul le **dernier** code par SIM est gardé, L-65-2) ; le **tape elle-même** sur la tablette, « Continuer ». Si l'onglet dit « Aucun SMS » après R3 : isc-sim a été redémarré (L-65-1, boîtes vidées) → « Renvoyer le code » dans l'application, puis relire | E-07 « Sécuriser cet appareil » | `core.enrolment` étape suivante ; `otp_failures` = 0 attendu | aucune (E-05 porte le code en clair à la saisie) |
| R5 | Invite système : **mot de passe de la tablette** (ne pas « Afficher ») | E-07 trois coches, puis E-08 | `pdp.device` 677001102 : **une** ligne PENDING puis ACTIVE (si une REVOKED apparaît en plus : réponse perdue + rejeu #124, à consigner) | aucune (invite système) |
| R6 | E-08 : clé **notée sur papier** ; « Je l'ai notée » ; E-09 : retapée ; « Confirmer » | E-11 accueil, « Appareil protégé · SM-T515 », solde 290 000 | `core.enrolment` DONE ; `pdp.trust_state` TRUSTED ; une entrée d'audit d'enrôlement | aucune sur E-08/E-09 ; E-11 oui |
| R7 | — | — | Récapitulatif SQL : `pdp.device` (attendu une ACTIVE), `core.enrolment`, `pdp.trust_state`, `max(seq)` d'audit | SQL brut |
| R8 | **Virement ALLOW** : « Envoyer » → **677001101** → **5 000** → confirmation (E-14) → PIN + déverrouillage (E-15) | E-16 reçu, puis accueil à 285 000 avec la ligne « Envoi · - 5 000 » | `core.transaction` P2P 5000 APP EXECUTED avec `decision_id` ; `pdp.audit_entry` ALLOW `proofType=SIGNATURE` ; soldes 285 000 / bénéficiaire +5 000 | E-12, E-13, E-16, E-11 ; **jamais E-14/E-15** |
| R9 | Historique, Alertes, Sécurité | Historique : une opération ; Alertes : vide | — | E-19 |
| R10 | Charte §10 : rotation paysage/portrait sur l'accueil ; Paramètres → Affichage → Taille de police au maximum ; arrière-plan ≥ 61 s puis retour → verrou ; Paramètres → Applications → IdentiShield → **Forcer l'arrêt** puis relancer → retour au PIN avec le numéro mémorisé | conforme selon la propriétaire | aucune preuve serveur, sauf le verrou si la session expire côté cœur | E-11 pivotée, E-11 à 200 % |
| R11 | **Flux D** (optionnel) : §2 à nouveau (Effacer les données), relancer, 677001102, PIN → le serveur répond RECOVERY → clé de récupération retapée → nouvelle clé | E-20 puis E-22 « Ce téléphone est maintenant votre clé » | `pdp.device` 677001102 : l'ancienne **REVOKED**, une nouvelle **ACTIVE** | E-22 ; **jamais E-20** |
| R12 | — | — | Témoin retiré (listing puis `rm`), « VM libre » à A, heure | — |

Le passage AC-02 (réattribution HLR, « ce n'est pas moi », gel) n'est **pas** rejoué à la répétition
(il gèlerait 677001102) ; il reste réservé à la démonstration sur 677001103 avec le couloir A à l'outil HLR.

**« Forcer l'arrêt » n'est pas la mort du processus par le système** (`am kill` du script #131) : c'est un arrêt
franc, qui vide aussi l'état sauvegardé de l'activité. Le cas « reprise après mort du processus avec état
sauvegardé » (D4) **n'est pas observable sans câble** et sera écrit « non observé ».

## 4. Captures : par la propriétaire, facultatives, jamais sur un écran protégé

- Geste SM-T515 : **Marche/Arrêt + Volume bas** en même temps ; la capture va dans la Galerie de la tablette.
- Les écrans **E-06, E-08, E-09, E-14, E-15, E-20, E-21** portent le drapeau anti-capture : la tablette refuse
  (« Impossible de faire une capture d'écran, politique de sécurité »). Ce refus est normal ; ne pas insister.
- **E-10 Déverrouillage (PIN quotidien) est capturable** alors qu'il reçoit le PIN (constat du 14/11, fenêtre
  sans `FLAG_SECURE` ; non corrigé, gel). **Il est ajouté à la liste des écrans où l'on ne capture jamais**, par
  règle humaine, comme l'invite système de déverrouillage.
- Écrans capturables : E-01, E-02, E-03, E-07 (hors invite système), E-11, E-12, E-13, E-16, E-19, E-22, E-23.
- Les captures **restent sur la tablette** (pas de câble, pas de partage réseau prévu) : elles se montrent à
  l'écran si on les demande ; le rapport les nomme « sur la tablette, non transférées ».

## 5. Virement de jour 677001104 → 677001101 sans câble (feuille adaptée)

Déclencheur **unique** : la propriétaire écrit **« je fais le virement »**. Ordre : **avant** le R0 de §2.
De jour (05:00–23:00 Douala). Montant **< 50 000**, 5 000 conseillé. État au 14/11 après le premier virement :
677001104 = 75 000, 677001101 = 230 000 ; attendus après : 70 000 / 235 000.

| # | B (poste et VM, lecture seule) | Propriétaire (tablette) |
|---|---|---|
| V1 | Lire le témoin (commande seule) ; s'il existe, attendre et le dire | — |
| V2 | Poser le témoin (couloir B, `jusqu-a` = maintenant + 30 min) | — |
| V3 | **Avant** : `docker inspect` (StartedAt, image) ; soldes des deux comptes ; `max(seq)` d'audit ; `count(*)` de `core.transaction` ; `pdp.device` du compte ACTIVE ; `tailscale status` → tablette active. Écrire « allez-y » | Tailscale « Connecté » ; lancer IdentiShield |
| V4 | — (ne regarde pas l'écran, ne lit rien) | E-10 : PIN + déverrouillage → E-11 ; « Envoyer » → 677001101 → 5 000 → E-14 confirmation → E-15 PIN + déverrouillage → E-16 ; écrire **« fait »**. Si l'écran dit « service indisponible, réessayez dans N s » : le dire **tel quel** avec N, appuyer **une fois** sur « Réessayer », dire l'issue |
| V5 | **Après** : `core.transaction` (type P2P, 5000, canal APP, état EXECUTED, `decision_id`) ; `pdp.audit_entry` nouvelle entrée, décision ALLOW, `proofType=SIGNATURE`, score et règles ; soldes 70 000 / 235 000 | — |
| V6 | Témoin : listing (seul) puis `rm` (seul) ; « VM libre » à A avec l'heure | — |

Rapport : SHA déployé, sorties brutes SQL, « conforme » pour ce que B a lu, « déclaré par la propriétaire »
pour l'écran, « non observé » pour le reste. **Jamais de PIN, d'OTP ni de clé de récupération**, nulle part.

## 6. Ce qui reste impossible sans câble

La liste complète, avec ce qui casse un APK déjà distribué (autorité TLS, adresse, contrat, paramètres
copiés), est tenue dans **`2026-11-14-sans-cable-impossibilites-et-ruptures.md`** (une seule liste). Les deux
points qui commandent ce script : **aucun nouvel APK** sur la tablette (elle garde celui du 27/10, P2) et
**aucune lecture du code SMS** sans l'onglet SMS de la console (#65) : B ne dicte jamais un code par le fil
de session (secret dans un journal). Sans #65, R4 est **bloqué**.

## 7. Déclarations

- Rien de ce document n'a été exécuté : les chemins Samsung (Réglages, geste de capture) et l'effet de
  « Effacer les données » sur le Keystore sont **déduits**, à confirmer à la première exécution.
- L-DB71-6 (correctif 503 non observé sur appareil) reste ouverte ; sans câble, une occurrence ne sera
  connue que par la lecture de la propriétaire (« réessayez dans N s »).
- Règle de relevé du superviseur (3/11) : sans câble, elle devient « **aucun relevé** » ; la règle des
  captures (§4) la remplace, avec E-10 ajouté.
- Dépendances : #65 (couloir A) pour R4 ; décision sur la clé de récupération de 677001104 (flux D éventuel) ;
  date de la démonstration (la répétition se tient la veille, sans chauffe).
