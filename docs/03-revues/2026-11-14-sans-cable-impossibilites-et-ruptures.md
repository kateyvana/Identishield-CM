# Sans câble : ce qui reste impossible, et ce qui casse un APK déjà distribué (I-5 c)

Date : 2026-11-14. Couloir B (Mobile). Ordre du superviseur technique du 2026-11-14 (intégration complète du
mois 5 : tester sans aucun branchement, donner l'APK à un tiers). Lecture seule ; sources : `mobile/app/build.gradle.kts`
(`SandboxTrustTask`, `buildConfigField`), `mobile/README.md`, limites L13 et L7-2
(`2026-10-22-limites-declarees-B3a.md`, `2026-10-23-limites-declarees-L7.md`), `tools/apk_publie.sh`.

## 1. Ce qui reste impossible sans câble

| # | Impossible sans câble | Pourquoi | Ce qui le remplace, ou rien |
|---|---|---|---|
| 1 | **Installer ou mettre à jour l'APK** sur la tablette du projet | `adb install` était la seule voie utilisée ; l'APK de publication (signature de la propriétaire) ne peut pas s'installer par-dessus l'APK de débogage (signatures différentes) : il faut d'abord désinstaller | Un tiers installe le fichier `.apk` reçu (navigateur ou gestionnaire de fichiers, « applications inconnues »). Pour la tablette : fichier servi depuis le poste sur le tailnet et ouvert dans son navigateur, **à décider** ; non couvert |
| 2 | **Lire le code SMS** pour un enrôlement | le code est dans `isc-sim`, lu jusqu'ici par `docker exec` puis tapé par `adb` | L'onglet SMS de la console (#65) : l'auteure lit et dicte (L14). Sans #65, aucun enrôlement sans câble |
| 3 | **Désinstaller puis réinstaller** (flux D du script #131) | plus de réinstallation possible | « Effacer les données » dans les réglages (script sans câble, §2) |
| 4 | La **preuve #66 depuis le téléphone** (`nc` sur 22, 3000, 8443) | commande shell du téléphone | `tailscale status` lu dans la VM ; navigateur du téléphone sur `https://100.93.166.19:8443/` (le port répond ou non) |
| 5 | Les **relevés d'arbre d'accessibilité** (`uiautomator`) : formatage exact d'un numéro, état d'un bouton, texte d'une erreur | outil `adb` | La lecture de la personne qui tient le téléphone ; captures par les boutons (jamais sur un écran protégé, E-10 inclus) |
| 6 | Les **mesures** : durée d'un 503 (« Réessayez dans N s »), niveau de batterie, état de l'écran | `dumpsys`, `screencap` horodatés | La valeur N lue à l'écran, sans chronomètre |
| 7 | La **mort du processus par le système** (`am kill`) | commande `adb` | « Forcer l'arrêt » dans les réglages : un autre cas (l'état sauvegardé de l'activité est vidé) ; D4 reste « non observé » |
| 8 | Le **transfert des captures** vers le poste | `adb pull` | Elles restent dans la Galerie du téléphone |
| 9 | Le **journal de l'application** (`logcat`) | `adb` | Rien : un plantage ne laisse que ce que l'écran a montré |
| 10 | Vérifier **quel APK** est installé (`dumpsys package`, empreinte) | `adb` | Paramètres → Applications → IdentiShield → version ; l'empreinte du fichier avant installation |
| 11 | **Saisir à la place** de l'usager (`input text`, `tap_digits.py`) | `adb` | Tout est tapé à la main ; le PIN, le code et la clé ne passent par personne d'autre |

## 2. Ce qui casse un APK déjà distribué

L'APK porte, **figés à la construction**, l'adresse du serveur, l'autorité TLS acceptée et les paramètres du
jeu de règles. Tout changement de l'un d'eux côté serveur laisse les téléphones déjà équipés avec un APK
périmé ; le symptôme est le même pour l'usager : « Connexion impossible. Vérifiez le réseau et réessayez. »
alors que le réseau fonctionne.

| # | Ce qui change côté serveur | Ce qui casse | Signe à l'écran | Remède |
|---|---|---|---|---|
| 1 | **L'autorité de certification** du bac à sable (`make reset` = `down -v` supprime `isc-certs` ; `gen-ca.sh FORCE=1`) | la poignée de main TLS : l'APK n'accepte **que** cette CA (`network_security_config.xml` généré, `<trust-anchors>` = `res/raw/sandbox_ca.pem` ; aucune autorité système ni utilisateur) | « Connexion impossible » | **Ne plus remettre la pile à zéro** une fois l'APK distribué (L13, décision 1) ; la sentinelle `/certs/APK-PUBLIE` posée par `make apk-publie APK_VERSION=…` fait **refuser** `make reset` et `FORCE=1` (L7-2, #68). Sinon : nouvel APK, redistribué à tous |
| 2 | **Le certificat feuille** du cœur réémis **sous la même CA** avec les mêmes noms (SAN `IP:100.93.166.19`, `DNS:isc-vm.tail3824f1.ts.net`, …) | rien | — | aucun |
| 3 | **Le certificat feuille sans le nom utilisé** (SAN sans `IP:100.93.166.19`) | la vérification du nom d'hôte | « Connexion impossible » | réémettre la feuille avec le SAN (L7-b) |
| 4 | **L'adresse ou le port** : `API_BASE_URL = https://100.93.166.19:8443/mobile/v1/` figée (`buildConfigField`) ; nouvelle adresse Tailscale de la VM (nœud réenregistré), autre `ISC_MOBILE_BIND`, autre port | la connexion | « Connexion impossible » | garder le nœud Tailscale `isc-vm` et l'adresse `100.93.166.19` ; sinon nouvel APK |
| 5 | **Le chemin ou le contrat** `/mobile/v1/*` (`mobile-api.yaml`) : champ renommé, code de réponse nouveau | la lecture stricte des réponses | erreurs « Une erreur est survenue » ou refus inattendus | nouvel APK ; ne pas changer le contrat servi tant qu'un APK est distribué |
| 6 | **Les paramètres du jeu de règles copiés dans l'APK** : `OTP_RESEND_DELAY`, `APP_NETWORK_TIMEOUT`, durées de maintien des connexions, `QUICK_AMOUNTS_XAF`, `REENROL_ATTEMPTS`, `LOCK_BASE`, `LOCK_MAX`, `CODE_LENGTH`, `POLL_FOREGROUND` (`config/ruleset.yaml` lu à la construction) | rien ne casse ; l'application et le serveur **divergent** (délais affichés faux, montants rapides périmés, longueur de clé) | comportements incohérents, pas de message | ne pas changer ces valeurs tant qu'un APK est distribué ; sinon nouvel APK |
| 7 | **La règle d'accès Tailscale** (#66) ou le retrait du testeur du tailnet | l'atteinte du 8443 | « Connexion impossible » | acte de la propriétaire dans l'administration Tailscale |
| 8 | **La signature de l'APK** : nouvel APK signé avec un autre magasin de clés que le précédent | la mise à jour en place est refusée par Android | « Application non installée » | désinstaller d'abord (perte de la clé locale → flux D avec la clé de récupération) ; garder le même magasin de clés |
| 9 | **La version d'Android** du téléphone revenue sous 11 | — | installation refusée | aucun |

Deux disciplines en découlent, à décider par la propriétaire avant la première distribution (L13) : **figer**
CA, adresse, contrat et paramètres copiés tant que l'APK circule ; ou **republier** un APK à chaque changement,
avec une nouvelle version, et prévenir chaque testeur.
