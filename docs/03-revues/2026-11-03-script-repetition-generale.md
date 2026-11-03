# Script de la répétition générale — compte 677001102 (jeudi 5 novembre 2026 au plus tard, de jour)

Date : 2026-11-03. Couloir B (Mobile). Ordre du superviseur technique du 2026-11-03. Références :
scénario B3b (`2026-10-24-scenario-demonstration-B3b.md`), comptes B8 (`2026-10-24-comptes-demonstration-B8.md`),
rapport de la passe B7 (`docs/02-specifications/mobile/captures/2026-11-B7/RAPPORT-PASSE-B7.md`), étape b du
2026-10-27 (ci-dessous §0), limites D-B7-1 (`2026-10-25-limites-declarees-D-B7-1.md`), charte qualité
mobile (CLAUDE.md §10). Comptes : **répétition 677001102**, **démonstration 677001103** (6/11),
**bénéficiaire 677001101**, chauffe 677001105. **Jamais** 677000200 (gelé, preuve B7).

## 0. État de départ constaté le 2026-11-03 00:39Z (lecture seule)

- Pile servie : `isc-pdp` et `isc-core` démarrés le **2026-10-25 20:16:06Z**, images de `a9e3486`
  (**sans #123** préchauffage PDP, **sans #124** ligne WARN du cœur) ; dépôt de la VM sur 84362e7 ;
  `main` = f3e560d. **La fenêtre 6 du couloir A n'a pas eu lieu** (témoin A du 27/10 00:32Z, échu à
  03:02Z, retiré par le couloir B le 3/11 00:39Z, contenu consigné dans le rapport).
- Comptes : 677001102 solde 290 000, HLR `624010677001102`, aucune clé ; 677001103 solde 275 000, aucune
  clé ; 677001105 solde 125 000, aucune clé ; 677001101 solde 225 000 ; 677001104 lié (clé ACTIVE
  d4b6c29a du 27/10, aucune transaction).
- Étape b du 27/10 : enrôlement de 677001104 fait (APK d6add23b), **aucun 503 observé** au premier
  `/enrolment/complete` du PDP froid ; **virement ALLOW non fait** (nuit, puis fenêtre A attendue).

## 1. Préalables (la veille ou le matin)

| # | Quoi | Qui | Preuve |
|---|---|---|---|
| P1 | Lire le témoin `~/VM-OCCUPEE` ; en poser un (couloir B, `jusqu-a` réaliste) | B | contenu du témoin |
| P2 | APK depuis `main` (SHA noté), `--offline`, `-Pisc.apiBaseUrl=https://100.93.166.19:8443/mobile/v1/ -Pisc.sandboxCa=<ca.crt VM>` ; `apksigner verify` ; SHA-256 ; CA épinglée `70:58:2E…BA:2F` | B | sorties brutes |
| P3 | Tablette : chargée, **Tailscale connecté** (vérifier `ip route get 100.93.166.19` → `tun0`, et `tailscale status` côté VM), débogage USB autorisé, **mot de passe de la tablette changé** après l'incident du 27/10 | propriétaire | `adb devices` = `device` |
| P4 | Preuve #66 depuis la tablette : `toybox nc -w 5 100.93.166.19 8443` rc=0 ; 22 et 3000 rc=1 | B | sortie brute |
| P5 | Jeu de règles : `config/ruleset.yaml` identique poste/VM (sha256) | B | empreintes |
| P6 | Aucun check-stack, aucun `make up`, aucun redémarrage du cœur/PDP **pendant** la répétition | A, B | témoin |

## 2. Plan de secours « PDP froid » (si la fenêtre 6 n'a pas eu lieu : cas constaté au 3/11)

Sans #123, le premier `/device/register` après un redémarrage du PDP peut dépasser le délai cœur de
1 000 ms (#122 : 2,25 s et 2,58 s sur pile isolée ; non reproduit le 27/10 sur la pile servie). Pour que
la répétition et la démonstration ne tombent pas sur ce premier appel :

1. **Enrôlement de chauffe** sur un compte libre, **677001105**, le matin de la répétition (flux A complet
   jusqu'à l'accueil, PIN tapé par la propriétaire, OTP lu dans la VM). Il parcourt attestation, PBKDF2 et
   insertion dans le PDP servi : les appels suivants durent 0,15–0,33 s (#122).
2. **Aucun redémarrage** du cœur ni du PDP ensuite, jusqu'à la démonstration du 6/11 incluse. Si un
   redémarrage est inévitable (A, déploiement), la chauffe est **refaite** sur le compte libre suivant
   (677001106, …) avant toute démonstration.
3. Si un 503 apparaît malgré tout sur E-07 : noter la valeur « Réessayez dans N s », appuyer sur
   « Réessayer » (#124 : même clé, même requête), continuer ; c'est l'observation manquante de L-DB71-6.

Si la fenêtre 6 a eu lieu (images avec #123) : pas de chauffe ; noter le SHA déployé et la ligne
« chemin d'enrôlement prêt à l'initialisation » du journal du PDP.

## 3. Déroulé de la répétition (677001102), étape par étape, de jour (05:00–23:00 Douala)

| Étape | Action | Attendu (observé = conforme) | Preuve |
|---|---|---|---|
| R0 | `adb shell pm list packages \| grep identishield` (listing) puis `adb uninstall cm.identishield.mobile` (commande seule) ; `adb install -r <APK>` | `Success`, `versionName=0.1.0`, `lastUpdateTime` du jour | sortie brute |
| R1 | Lancer ; E-01 → E-02 « Cet appareil va devenir la clé… » | E-02 | `E-02-bienvenue-677001102.png` |
| R2 | « Commencer » → E-03 ; numéro **677001102** | affiché **« 677 001 102 »** (D8) | `E-03-numero-677001102.png` |
| R3 | « Continuer » → E-06 PIN ; **PIN tapé par la propriétaire** ; « Continuer » | E-05 OTP, « 677 ••• 102 » ; **aucune capture** (protégé) | horodatage |
| R4 | OTP : `docker exec isc-sim python3 … /inbox?imsi=624010677001102` (lu, jamais affiché) ; 6 chiffres via `tap_digits.py` ; « Continuer » | E-07 « Sécuriser cet appareil » | horodatage |
| R5 | Invite système : **mot de passe de la tablette tapé par la propriétaire** (ne pas « Afficher ») ; **aucun relevé uiautomator pendant l'invite** (incident du 27/10) | E-07 trois coches, puis E-08 | horodatage |
| R6 | E-08 clé de récupération : **notée sur papier par la propriétaire** ; « Je l'ai notée » ; E-09 : retapée ; « Confirmer » ; **aucun relevé ni capture E-08/E-09** | E-11 accueil, « Appareil protégé · SM-T515 », solde 290 000 | `E-11-accueil-677001102.png` |
| R7 | Serveur (lecture seule) : `pdp.device` du compte (attendu **une** ligne ACTIVE ; une REVOKED en plus = réponse perdue + rejeu), `core.enrolment` stage, `pdp.trust_state` TRUSTED | conforme | SQL brut |
| R8 | **Virement ALLOW** : « Envoyer » → bénéficiaire **677001101** → montant **< 50 000** (ex. 5 000) → déverrouillage local → EXECUTED | E-13/E-14 (capturables si non protégés), solde 285 000 ; `core.transaction` P2P 5000 APP EXECUTED ; `pdp.audit_entry` ALLOW, `proofType=SIGNATURE` (#119) si image ≥ 4952d06, sinon absent (à noter) | captures + SQL |
| R9 | Historique E-12 ; Alertes vides ; Sécurité | conforme | `E-12-historique-677001102.png` |
| R10 | Charte §10 : rotation (paysage/portrait) sur l'accueil, police 200 %, arrière-plan 61 s → verrou, mort du processus (Réglages) → reprise | conforme / non observé | captures nommées |
| R11 | **Flux D** (optionnel si le temps le permet) : désinstaller, réinstaller, 677001102 → RECOVERY → clé de récupération → nouvelle clé ; ancienne REVOKED | conforme | SQL |
| R12 | Témoin retiré (listing puis rm), « VM libre » à A | — | heure |

Le passage AC-02 (réattribution HLR par A, « ce n'est pas moi », gel) n'est **pas** rejoué à la
répétition sur 677001102 : il gèlerait le compte avant la démonstration. Il reste réservé à la
démonstration du 6/11 sur 677001103 selon le scénario B3b/B7, avec le couloir A à l'outil HLR.

## 4. Ce qui est déclaré d'avance

- L-DB71-6 : le 503 réel n'a pas été observé le 27/10 ; la chauffe du §2 vise à ne pas le rencontrer
  en démonstration, pas à le prouver.
- Secrets : PIN, OTP, clé de récupération, mot de passe de la tablette ne sont jamais affichés,
  relevés ni capturés ; les captures se limitent aux écrans non protégés (E-02, E-03, E-11, E-12,
  E-13/E-14 si autorisés).
- Un « non observé » se rapporte tel quel, sans second essai forcé.
