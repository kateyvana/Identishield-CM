# IdentiShield-CM — Guide du testeur (une page)

Version du 2026-11-14. Ce guide s'adresse à une personne qui reçoit l'application IdentiShield et l'essaie sur
son propre téléphone, sans câble et sans l'aide d'un informaticien. L'auteure du projet vous remet, **de vive
voix**, trois choses : votre **numéro de test** (de la forme 677 001 1xx), votre **PIN de test** et le **code
SMS** au moment de lier votre téléphone. Ne les écrivez dans aucun message.

Le serveur est un bac à sable : aucun vrai compte, aucun vrai argent, aucun vrai SMS. Les montants sont
fictifs.

## 1. Ce qu'il faut sur votre téléphone (relevé dans le code de l'application)

| Condition | Pourquoi | Si elle manque |
|---|---|---|
| **Android 11 ou plus récent** (`minSdk = 30`) | l'application ne s'installe pas en dessous | Android refuse l'installation : « application non compatible » |
| **Un verrouillage d'écran** : code, schéma ou mot de passe (`isDeviceSecure`) | la clé de votre compte est créée dans la puce du téléphone et chaque envoi demande ce déverrouillage | l'application affiche « Verrouillage d'écran requis » avec un bouton « Ouvrir les réglages » ; configurez-le, puis « C'est fait, continuer » |
| **Une puce de sécurité** (StrongBox ou environnement de confiance TEE ; une clé purement logicielle est refusée) | la thèse du projet : la clé ne sort jamais du matériel | message « Ce téléphone ne peut pas protéger votre compte. Vous pouvez continuer à utiliser MoMo par USSD. » Le test s'arrête là pour ce téléphone ; dites-le à l'auteure |
| **Tailscale installé et connecté** (§2) | le serveur n'est joignable que par ce réseau privé | « Connexion impossible. Vérifiez le réseau et réessayez. » |
| Une biométrie ou le code du téléphone pour **chaque envoi** | règle de l'application (`AUTH_BIOMETRIC_STRONG` ou `AUTH_DEVICE_CREDENTIAL`) | « Déverrouillage annulé. Réessayez. » |

Si vous **ajoutez une empreinte ou un visage** après avoir lié le téléphone, la clé est invalidée par la puce
(`setInvalidatedByBiometricEnrollment`) : l'application dira « La biométrie de votre téléphone a changé ; il
faut sécuriser à nouveau cet appareil ». C'est voulu.

## 2. Installer, puis rejoindre le réseau

1. **Tailscale.** Installez l'application Tailscale depuis le Play Store. Connectez-vous avec le compte que
   l'auteure vous a invité (elle vous dit lequel). Laissez Tailscale sur **« Connecté »** pendant tout le test ;
   dans ses réglages, mettez « VPN permanent » et, pour l'application Tailscale, la batterie sur « Non
   restreinte ». Vous n'atteindrez qu'une seule adresse : le serveur du projet. Rien d'autre.
2. **IdentiShield.** L'auteure vous donne le fichier `.apk` et son empreinte SHA-256. Ouvrez le fichier ;
   Android demande d'autoriser « l'installation d'applications inconnues » pour l'application qui l'ouvre
   (gestionnaire de fichiers ou navigateur) : acceptez, installez, puis vous pouvez retirer l'autorisation.
3. L'application n'a besoin que d'**Internet** (une seule permission). Elle ne lit pas vos SMS, ni votre SIM,
   ni vos contacts. Elle n'est pas sauvegardée dans le nuage.

## 3. Ce que vous verrez, écran par écran

**Lier votre téléphone (une seule fois).**

1. **Bienvenue** : « Cet appareil va devenir la clé de votre compte ». « Commencer ».
2. **Numéro** : tapez votre numéro de test ; il s'affiche « 677 001 1xx ». « Continuer ».
3. **Code PIN** : le PIN de test. L'écran refuse les captures d'écran : c'est normal.
4. **Code reçu par SMS** : le SMS est **simulé** ; l'auteure lit le code dans son réseau simulé et vous le
   dicte. Six chiffres. « Renvoyer le code » redevient actif après un court délai.
5. **Sécuriser cet appareil** : trois étapes cochées ; le téléphone vous demande **votre** déverrouillage
   (empreinte, visage ou code du téléphone). C'est le téléphone qui le demande, pas l'application : elle ne le
   voit jamais.
6. **Votre clé de récupération** : une suite de caractères affichée **une seule fois**. Écrivez-la sur papier.
   Ne la photographiez pas. Elle sert seulement si vous changez de téléphone.
7. **Confirmation** : recopiez les caractères demandés. « Confirmer ».
8. **Accueil** : votre nom de test, votre numéro, le solde fictif, « Appareil protégé · <votre modèle> ».

**Au quotidien.**

- À chaque ouverture : **Déverrouillage** (numéro masqué, PIN, puis déverrouillage du téléphone).
- **Envoyer** : numéro du bénéficiaire → montant (ou un montant rapide) → **Confirmation** (nom du titulaire,
  montant ; une mention « nouveau bénéficiaire » la première fois) → **Autorisation** (PIN puis déverrouillage
  du téléphone) → **Reçu**. Le solde baisse à l'accueil et l'opération apparaît dans « Dernières opérations ».
- Une opération jugée inhabituelle peut être **différée** : « Envoi programmé … exécuté le … », avec un bouton
  « Annuler cet envoi » jusqu'à l'heure dite. Ce n'est pas une panne.
- Une opération peut être **bloquée** pour votre sécurité : l'écran le dit et vous renvoie à un agent.
- **Historique**, **Alertes** (un nouveau téléphone lié déclenche une alerte : « C'est moi » / « Ce n'est pas
  moi »), **Sécurité**.
- Après 5 minutes sans toucher l'écran, ou 60 secondes en arrière-plan, l'application se **verrouille** :
  « Votre session a expiré. Déverrouillez à nouveau. » C'est voulu.

## 4. En cas d'erreur : ce que dit l'écran et quoi faire

| Message | Cause | Quoi faire |
|---|---|---|
| « Connexion impossible. Vérifiez le réseau et réessayez. » | Tailscale déconnecté, ou le serveur éteint, ou l'application ne reconnaît pas le certificat du serveur | Vérifiez Tailscale (« Connecté »). Si cela persiste, prévenez l'auteure : le serveur est peut-être éteint |
| « Service momentanément indisponible. Réessayez dans N s. » | le serveur démarre ou est surchargé | Attendez N secondes, « Réessayer » : la même demande est renvoyée, rien n'est perdu |
| « Numéro invalide » | numéro hors de la forme attendue | Retapez les 9 chiffres |
| « Code PIN incorrect » / « Identifiants incorrects » | PIN faux | Retapez. Après plusieurs essais : « Trop d'essais. Réessayez dans … » : attendez |
| « Code incorrect » / « Code expiré : demandez un nouveau code. » | SMS mal recopié ou trop ancien | Retapez, ou « Renvoyer le code » et redemandez-le à l'auteure |
| « Verrouillage d'écran requis » | aucun code, schéma ni mot de passe sur le téléphone | « Ouvrir les réglages », configurez-le, revenez |
| « Ce téléphone ne peut pas protéger votre compte. … » | pas de puce de sécurité utilisable | Le test s'arrête pour ce téléphone ; signalez le modèle |
| « Déverrouillage annulé. Réessayez. » | vous avez fermé la fenêtre de déverrouillage | Relancez l'action |
| « Clé incorrecte. Vérifiez votre copie papier. » | recopie fausse de la clé de récupération | Relisez votre papier |
| « Le téléphone a fermé l'application pendant la liaison. … » | Android a tué l'application | Retapez le PIN : la liaison reprend où elle était |
| « Cet appareil n'est plus la clé de votre compte. » | un autre téléphone a été lié | Normal si vous avez changé de téléphone ; sinon, prévenez |
| « Votre compte est gelé pour votre sécurité. … » | une action « Ce n'est pas moi » ou une décision du serveur | Prévenez l'auteure ; c'est le comportement attendu dans le scénario d'attaque |
| « Solde insuffisant » / « Aucun compte MoMo pour ce numéro. » | montant trop élevé / bénéficiaire inconnu | Changez le montant ou le numéro (bénéficiaire de test : celui donné par l'auteure) |
| « Une erreur est survenue. Réessayez. » | cause inconnue | Notez l'heure et l'écran, réessayez une fois, puis signalez |

Ce qu'il est utile de rapporter : le modèle du téléphone, la version d'Android, l'heure, le message exact,
et ce que vous faisiez. **Jamais** votre PIN, le code SMS ni la clé de récupération.

## 5. Ce que ce test ne couvre pas

- Lier votre téléphone **sans** l'auteure : impossible, le réseau SMS est simulé (limite déclarée L14 du
  projet, écrite sur la page de publication).
- Un vrai paiement : tout est fictif.
- Le changement de serveur : si l'auteure remet le serveur à zéro ou le déplace, l'application installée ne le
  reconnaîtra plus et dira « Connexion impossible » ; il faudra un nouvel `.apk` (voir
  `2026-11-14-sans-cable-impossibilites-et-ruptures.md`).
