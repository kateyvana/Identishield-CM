# L7 — Cœur en HTTPS pour le vrai APK : limites déclarées et garde-fous

**Date :** 2026-10-23
**Couloir :** A (part infrastructure de L7). La surface mobile du cœur (connecteur HTTPS, refus de
`/mobile/v1/*` en clair) reste au couloir B.
**Décision qui commande :** l'APK publié est le VRAI APK, connecté au vrai serveur. Les évaluateurs
rejoignent la VM par Tailscale, et le serveur est allumé le jour de la soutenance. Il n'y a pas de
mode démonstration.

## L7-1 — GRAVITÉ MAXIMALE, PORTE BLOQUANTE : la console et SSH sont joignables par tout le tailnet

**Constat (2026-10-23).** Tout membre du réseau Tailscale atteint la VM sur toutes ses interfaces :
- **SSH**, port 22, en écoute sur `0.0.0.0` ;
- **la console**, port 3000, publiée sur `0.0.0.0:3000` (`infra/docker-compose.yml`).

La console permet déjà de **désactiver la couche de sécurité** (`POST /api/mode`). Décision de la
propriétaire du 2026-10-23 : elle recevra aussi un onglet qui affiche les SMS simulés, et donc
**tous les codes OTP de tous les numéros**. Un évaluateur invité sur le tailnet sans restriction
pourrait éteindre la défense, puis lire l'OTP de n'importe quelle victime : c'est l'attaque AC-02
servie clé en main.

**Remède : une règle d'accès Tailscale** (ACL) qui limite les évaluateurs à `100.93.166.19:8443`,
et à rien d'autre. Ni le 22, ni le 3000, ni aucun autre port.

**La règle couvre le 3000 avec autant de soin que le 8443** (superviseur technique, 2026-10-23).
Un OTP lisible par tout le tailnet ne prouve plus la possession de la SIM : la défense d'AC-02
reposerait alors sur un code que n'importe quel invité peut lire. La preuve rouge/vert vaut donc
pour les deux ports : le 8443 OUVERT aux évaluateurs, le 3000 FERMÉ aux évaluateurs.

**Porte bloquante, et non conseil.** AUCUN évaluateur n'est invité sur le tailnet tant que :
1. la règle n'est pas en place. C'est un acte de la propriétaire, dans la console d'administration
   Tailscale ;
2. elle n'est pas prouvée rouge puis vert, depuis un nœud de la même classe qu'un évaluateur : le 8443
   répond, le 22 et le 3000 sont refusés.

## L7-2 — `make reset` régénère l'autorité de certification et casserait l'APK publié

**Constat.** `make reset` (`docker compose down -v`) supprime le volume `isc-certs`. Au démarrage
suivant, `infra/ca/gen-ca.sh` crée alors une **nouvelle** autorité. L'APK publié épingle
l'ancienne : tous les téléphones des évaluateurs refuseraient le serveur, avec un message qui
ressemble à une panne réseau.

**Décision du superviseur technique (2026-10-23).** Une fois l'APK publié, `make reset` doit
**refuser de tourner**, par un refus mécanique, pas par une note. La clé privée de l'autorité ne
sort PAS du volume pour être sauvegardée ailleurs : `gen-ca.sh` dit qu'aucune clé ne vit hors du
volume.

**Garde-fou proposé (NON écrit, en attente d'accord) :**
- **Une sentinelle DANS le volume `isc-certs`**, à côté de l'autorité qu'elle protège :
  `/certs/APK-PUBLIE`, avec l'empreinte SHA-256 de la CA épinglée, la date et la version de l'APK.
  Elle est posée par une cible explicite, `make apk-publie`, que la propriétaire lance au moment de
  publier.
- **`make reset` lit d'abord la sentinelle** (conteneur jetable, volume monté en lecture seule). Si
  elle existe, il **refuse**, avec un code de sortie non nul et un message qui nomme la cause et
  l'empreinte de la CA épinglée. Il ne supprime rien.
- **`gen-ca.sh` refuse `FORCE=1`** si la sentinelle est présente.
- **Une cible `make reset-db`** supprime la base (`isc-pgdata`) et JAMAIS `isc-certs`. Elle sert aux
  changements d'`init.sql` après publication, par exemple pour A5-00. La précondition reste la même :
  exporter le journal d'audit et le confirmer intact d'abord.

**Pourquoi dans le volume, et non dans le dépôt ou le dossier de la VM.** La sentinelle vit et meurt
avec la CA qu'elle protège. Une VM neuve, sans volume, n'a pas de CA à protéger, et rien ne la
bloque. Une sentinelle versionnée dans le dépôt bloquerait le reset sur toutes les machines (poste,
CI), même là où aucun APK n'épingle la CA.

**Limite du garde-fou, déclarée.** Une commande lancée à la main (`docker volume rm isc-certs`,
`docker compose down -v`) le contourne. Il arrête l'accident, pas un acte délibéré.

## L7-3 — Sortie vers Internet du cœur, si `isc-mobile` n'est pas fermé

Publier le 8443 exige un réseau non interne (`isc-mobile`). Un pont Docker non interne donne aussi
au conteneur une **sortie vers Internet**, contraire à ADR-035, point 5. **Prérequis de la
propriétaire** : une règle de la chaîne `DOCKER-USER` qui refuse toute nouvelle connexion sortante
depuis le sous-réseau d'`isc-mobile`, et qui survit au redémarrage de la VM. L'utilisateur `isc` n'a
pas les droits administrateur. Preuve attendue : depuis le cœur, une connexion vers l'extérieur
échoue.

## L7-4 — Le port lié à l'adresse Tailscale : échec explicite, jamais un démarrage partiel

Le 8443 se lie à l'adresse Tailscale par une variable du `.env` (arbitrage du 2026-10-23), et non à
`0.0.0.0`. Si Tailscale n'est pas démarré, l'adresse n'existe pas sur la VM, et Docker démarrerait
les autres services avant d'échouer sur le cœur. Exigence : **un contrôle avant `docker compose
up`**, dans `make up`, qui vérifie la présence de l'adresse sur la VM. Sinon il s'arrête **avant de
démarrer quoi que ce soit**, avec un message qui nomme la cause (« adresse … absente : Tailscale
n'est pas démarré ; la pile n'est PAS démarrée »).

## L7-5 — Certificat serveur du cœur pour le téléphone

Feuille SERVEUR distincte, `core-mobile`. Elle ne reprend **pas** `CN=core`, parce que le PDP accepte
tout porteur de `CN=core` comme étant le cœur (trou relevé par le couloir B). Son SAN, dans cet
ordre : `DNS:isc-vm.tail3824f1.ts.net` (l'adresse de l'APK), `DNS:isc-vm`, `IP:100.93.166.19`,
`DNS:isc-core` (le contrat). Les feuilles `pdp` et `core` ne changent pas.

## Onglet SMS de la console : question de frontière, AVANT d'écrire

L'onglet demandé affiche, par numéro : numéro, **nom du titulaire**, message, heure. Constats :
- **Le nom du titulaire n'existe nulle part.** `infra/init/init.sql` n'a aucune colonne de nom
  (`core.account` : numéro, solde, date). Le nom affiché, c'est la dette ED-11 (#18), jamais
  tranchée.
- **isc-sim range les SMS par IMSI, et non par numéro** : `POST /deliver {imsi, code}`,
  `GET /inbox?imsi=` (`simulator/src/isc_simulator/network.py`). Passer au numéro demande la
  correspondance du HLR, tenue par le cœur.
- **Après un échange de SIM, le numéro de la victime pointe vers l'IMSI de l'attaquant.** Une vue
  « par numéro » doit dire à quel IMSI chaque SMS a été remis, sinon elle montrerait les OTP
  interceptés comme reçus par la victime.

Ces trois points sont à trancher avant d'écrire l'onglet. La frontière de code (console Next.js,
lecture d'isc-sim comme la chronologie T2-17) est acceptée.
