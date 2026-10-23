# Limites déclarées — carte B3a (premier enrôlement)

Date : 2026-10-22 — branche `mobile-12-b3a-premier-enrolement`.
Règle de la propriétaire (2026-10-22) : un risque que la conception ne couvre pas est DÉCLARÉ ici,
pas corrigé par un changement de conception.

## ⚠ CONSTAT HORS CARTE — la VM du banc tourne sous Hyper-V (mode NEM), pas en VT-x

À reprendre par le couloir A (décision de la propriétaire du 2026-10-22). Constaté par B3a, rien
n'a été modifié chez le couloir A.

Ligne exacte du journal de la VM en service, `UbuntuServer_24.04_VB_LinuxVMImages.COM`
(UUID 4368efcc-426e-4ca6-a795-80dafe432db9, 4 vCPU), fichier
`C:\Users\uiooi\Downloads\UbuntuServer_24.04_VB (1)\Logs\VBox.log` :

```
00:00:01.155461 HM: HMR3Init: Attempting fall back to NEM: VT-x is not available
```

Hyper-V garde VT-x (WSL2 / Docker Desktop actifs sur le poste, processus `vmmem`) : VirtualBox
7.2.4 se rabat sur l'API d'hyperviseur de Windows (NEM), plus lente et plus coûteuse. Les quatre
journaux conservés portent la même ligne : `VBox.log` (session démarrée le 2026-10-18 à 01 h 12),
`VBox.log.1` (2026-10-17), `VBox.log.2` (2026-10-16), `VBox.log.3` (2026-10-14).

Conséquences observées par B3a : `VBoxHeadless` consomme environ deux cœurs de l'hôte pour une VM
presque au repos ; une même dérivation PBKDF2 varie d'un facteur 2 à 3 d'une passe à l'autre.

DÉDUIT, non mesuré : toute mesure de latence faite dans cette VM depuis au moins le 2026-10-14 l'a
été sous NEM, **y compris le banc ENF-01 de la carte A4**, dont les chiffres figurent dans sa
levée. Ces chiffres décrivent une machine plus lente que le matériel en virtualisation normale.

**Corrigé le 2026-10-22 à 16 h 57** : Hyper-V désactivé sur le poste (`bcdedit /set
hypervisorlaunchtype off`, puis redémarrage), décision de la propriétaire. Le journal de la session
suivante porte « HM: HMR3Init: VT-x w/ nested paging and unrestricted guest execution hw
support ». Seules les mesures postérieures sont représentatives. Effet de bord : WSL2 et Docker
Desktop ne fonctionnent plus sur le poste tant qu'Hyper-V reste coupé
(`bcdedit /set hypervisorlaunchtype auto` pour revenir).

## L1 — Un code de récupération ne peut jamais être invalidé

`pdp.recovery_code` n'accorde que `SELECT, INSERT` au rôle `isc_pdp_app` (init.sql, section B3a).
Un code émis ne peut donc être ni invalidé, ni consommé, ni révoqué par l'application. Le seul
remède à un code compromis est le ré-enrôlement (flux D), qui émet un nouveau code pour la nouvelle
clé.

**Reste ouverte au mois 4.** `POST /recovery-code/rotate` (et `DELETE /device`) sont reportées au
mois 5 (décision de la propriétaire du 2026-10-23). Ce report LAISSE OUVERTE la limite L1 : un code
de récupération reste non invalidable, et le seul remède demeure le réenrôlement. Les deux routes
sont publiées dans `contracts/mobile-api.yaml` ; leur description y précise qu'elles ne sont pas
servies au mois 4.

## L2 — Aucun étranglement sur les routes publiques d'enrôlement

`/enrolment/start` est publique et dérive une empreinte PBKDF2 du PIN pour TOUT appelant, même
pour un numéro inconnu (temps constant, ES-22). `/recovery-code/confirm` (après PIN + OTP) fait
dériver le PDP à chaque essai, sans compte des échecs. Aucune limitation par origine n'existe dans
le cœur ni dans le PDP. Bornes actuelles : ENROL_TTL, l'OTP préalable pour confirm, et
l'enrôlement CONSOMMÉ après une confirmation réussie (toute reprise reçoit 410).

ENROL_TTL, seule borne temporelle de cette limite, n'est pas ratifié : voir L11.

Pire cas chiffré, au nombre retenu de 44 000 itérations (ADR-042 ; mesure du 2026-10-23, VT-x,
régime chaud, 4 vCPU) :
- environ **3 600 dérivations par minute saturent les 4 vCPU** (3 582 à 4 fils concurrents, 3 648
  à 8 fils) : quatre appelants concurrents suffisent à occuper toute la machine qui porte aussi
  le PDP, PostgreSQL et le cœur ;
- **un seul appelant séquentiel** force environ 1 240 dérivations par minute (une toutes les
  48 ms) et prend **un vCPU sur quatre, soit 25 %** de la machine.

Point de comparaison à 600 000 itérations (même coût unitaire mesuré, 1,124 ms pour 1 000
itérations) : environ 670 ms par dérivation, soit environ 260 dérivations par minute pour saturer
les 4 vCPU. Ce que la décision a fait gagner : environ 14 fois moins de calcul par requête. Ce
qu'elle n'a pas fait : borner le nombre de requêtes. Sans étranglement, la saturation reste
atteignable ; elle demande seulement 14 fois plus de requêtes. Les mesures des 21 et 22 octobre,
faites sous NEM, sont écartées.

Cartes nommées (à créer par la propriétaire) :
- **Couloir A** — étranglement par origine des routes publiques d'enrôlement, sur le motif ES-21
  (délai croissant sur l'origine, jamais sur le compte) : paramètre nommé, donc `ruleset.yaml`.
- **B4** — comptage des échecs de `/recovery-code/confirm` et limitation des essais du PIN (P-11).

## L3 — Le 410 d'une course perdue n'est pas indiscernable d'une expiration (R1)

`/enrolment/complete` répond 410 ENROLMENT_EXPIRED, même code et même corps, que l'enrôlement ait
expiré ou qu'une autre liaison l'ait devancé. L'appelant peut néanmoins distinguer les deux :
il connaît son heure de début et ENROL_TTL, et le `start` suivant répond RECOVERY. Limite R1, déjà
déclarée ; aucun plancher de durée n'est ajouté.

## L4 — La relecture sur réponse perdue ne dit pas QUELLE clé est active

Sur expiration de `/device/activate`, le cœur relit `GET /device/state` : ACTIVE → il poursuit,
PENDING ou NONE → 503. Le fait ACTIVE ne nomme pas la clé. Dans une course de premier enrôlement
où un tiers (SIM + PIN + OTP) a enregistré puis activé SA clé pendant que la réponse de l'usager
se perdait, le cœur ouvrirait à l'usager une session sur une clé RÉVOQUÉE. Aucun débit possible :
toute opération signée par une clé révoquée est refusée (ES-20) ; l'usager constate l'échec à sa
première opération.

## L5 — Parties du flux hors de B3a

- `start` sur un compte lié répond RECOVERY sans `enrolmentId` : `/enrolment/recover` est la
  carte B3b.
- La branche SESSION de `start` (signature de l'appareil lié) est la carte B4.
- Le refus 403 ACCOUNT_FROZEN de `start` n'est pas traité en B3a.

## L6 — Comptes de démonstration

- Les fixtures (`tools/mobile-mock/fixtures.json`, source unique) ne portent pas d'IMSI :
  l'amorçage `demo` dérive un IMSI de structure MTN, `624010` + numéro (15 chiffres).
- Le compte lié des fixtures (`boundDeviceId`, `recoveryCode`) n'a pas de clé au PDP : son
  amorçage côté PDP relève de B3b.
- L'activation du profil `demo` et le montage du fichier de fixtures dans le conteneur relèvent
  d'une carte d'infrastructure.

## L7 — Le PIN voyage en clair entre le téléphone et le serveur

Le contrat publie `https://isc-core:8443/mobile/v1`, mais le cœur sert ses routes mobiles en HTTP
8080. Aujourd'hui, le PIN de l'usager voyage EN CLAIR entre le téléphone et le serveur. Sur le
réseau interne de la pile c'est tolérable ; dès que l'application tourne sur un vrai téléphone face
à la VM, ça ne l'est plus. C'est la première question qu'un jury posera.

Carte nommée, **PRIORITAIRE** — carte d'infrastructure : TLS de la surface mobile du cœur (8443,
conforme au contrat), à régler AVANT la démonstration de soutenance.

## L8 — Valeurs de transport

- `Retry-After` du 503 SERVICE_UNAVAILABLE = délai de refroidissement du disjoncteur PDP
  (`core.pdp.breaker.cooldown-ms`), arrondi à la seconde supérieure.
- Un échec de remise du SMS d'OTP répond aussi 503 SERVICE_UNAVAILABLE (dépendance transitoire).

## L9 — Avant tout enrôlement, le PIN est la seule barrière, et rien ne compte ses essais

Un attaquant qui a dupliqué la SIM possède l'OTP. La conception l'admet : avant tout enrôlement,
la puce et le PIN suffisent (AC-13, premier enrôlement post-SIM swap ; ES-17b : l'étape ne dépend
que de la présence d'une clé enregistrée). Le PIN est donc la SEULE barrière restante dans ce
scénario. Or aucun comptage des essais du PIN n'existe : aucun paramètre nommé dans
`ruleset.yaml` ne le porte (le brief du 11/10 annonce « 5 échecs → 15 min », rien ne
l'implémente). Sans comptage, le PIN tombe en quelques minutes d'essais en ligne. Les itérations
PBKDF2 n'y changent rien (ADR-042).

Carte nommée, **PRIORITÉ HAUTE, BLOQUANTE avant la démonstration de soutenance** — couloir A :
comptage des essais du PIN et verrou temporaire, sur le motif ES-21 (sur l'origine, jamais sur le
compte). Paramètres nommés, donc `ruleset.yaml`. Non implémentée dans B3a.

Note : l'ordre de travail citait ES-17 ; ES-17 est RETIRÉE (v1.3, ISC-AMD-01), remplacée par
ES-17b, citée ici.

## L10 — Le serveur n'impose ni limite ni délai sur l'émission des OTP

OTP_RESENDS = 2 est déclaré dans `ruleset.yaml` et n'est LU PAR AUCUN CODE. OTP_RESEND_DELAY
(60 s) est lu par l'APPLICATION, pas par le serveur. Conséquence exacte : le serveur n'impose ni
limite ni délai sur l'émission des OTP ; un appelant qui n'utilise pas l'application n'a aucun
frein.

Ce que ça coûte, sans l'exagérer : un envoi de SMS en rafale vers le téléphone d'une victime, et
son coût. PAS une prise de compte : pour atteindre l'OTP il faut d'abord passer le PIN
(`/enrolment/start` vérifie le PIN avant toute émission), et un attaquant qui a la SIM et le PIN
entre dès le premier OTP. C'est un défaut d'abus et de coût, pas d'authentification. L'OTP ne sert
qu'au PREMIER enrôlement ; le réenrôlement passe par le code de récupération.

Pourquoi non câblé dans B3a : la règle n'est pas définie. Le SRS (v1.4 et v1.5) cite OTP_RESENDS
par son nom seulement, et `ruleset.yaml` dit « renvois d'OTP autorisés ; à calibrer ». Rien ne fixe
sur quoi porte le compte, sur quelle fenêtre, ni la réponse au-delà. Or un renvoi rappelle
`/enrolment/start`, qui ouvre un NOUVEL enrôlement : compter par enrôlement ne borne rien ;
compter par compte irait contre le motif ES-21 (jamais sur le compte : un tiers pourrait priver la
victime d'OTP). Le câbler aurait demandé d'inventer la règle.

Carte nommée — compteur et délai de renvoi des OTP CÔTÉ SERVEUR : portée, fenêtre et réponse
(429 THROTTLED avec Retry-After, comme ES-21) à définir, OTP_RESEND_DELAY appliqué par le serveur.

## L11 — ENROL_TTL n'est pas ratifié

Dans `config/ruleset.yaml`, ENROL_TTL porte la mention « (proposé : 2 × OTP.ttl) », jamais
ratifiée ; c'est pourtant aujourd'hui la seule borne temporelle de la limite L2. Même mention
« (proposé) » sur APP_SESSION_TTL. La ratification appartient à la propriétaire ; B3a ne change
aucune valeur.

## L12 — L'attestation prouve qu'une chaîne est BIEN FORMÉE, pas qu'elle vient d'un vrai téléphone

Le PDP ne compare pas la racine de la chaîne d'attestation aux racines matérielles de Google
(`AttestationVerifier`, limite « Racine simulée » de l'amendement M-09 d'ADR-018). Il ne distingue
donc PAS une vraie puce de sécurité d'une chaîne fabriquée par ordinateur : une chaîne construite
de bout en bout par un attaquant, avec sa propre racine auto-signée, une extension KeyDescription
bien formée déclarant « TEE » et le bon défi, passe la vérification. Le refus DEVICE_UNSUPPORTED
(clé logicielle) ne s'applique qu'à une chaîne qui AVOUE être logicielle. La garantie « clé
matérielle », au cœur de la thèse, repose aujourd'hui sur une hypothèse.

Ce qu'il faudrait pour la fermer :
1. **Racines matérielles de Google épinglées** dans la configuration du PDP (publiques, possible
   hors ligne), et refus de toute autre racine ; les tests gardent une racine de test injectée.
2. **RootOfTrust** lu dans KeyDescription : démarrage vérifié (`verifiedBootState`) et chargeur
   d'amorçage verrouillé (`deviceLocked`), demandés par ISC-MOB-SPEC §12.1.
3. **Révocation** : liste d'état publiée par Google, qui exige Internet (QM-06) ; le bac à sable
   n'a aucune sortie réseau.
4. **Validité temporelle** des certificats, lue par l'horloge injectée (autre limite déclarée de
   M-09).

Statut documentaire : la limite est déclarée dans ADR-018 (amendement M-09, FERMÉ par la
propriétaire le 2026-10-11, ligne « LIMITE DÉCLARÉE — Racine simulée »).

**Incohérence de documentation, à corriger par la carte qui touchera le vérificateur** (celle de
L12) : la Javadoc d'`AttestationVerifier` (lignes 35 à 38) cite encore l'amendement M-09 comme
« PROPOSÉ », alors qu'il est FERMÉ depuis le 2026-10-11. Elle n'est pas modifiée par B3a : le
vérificateur n'y est pas touché.

Carte nommée — **Attestation : racines Google, RootOfTrust** (couloir B). Tenabilité au mois 4,
évaluée : points 1 et 2 TENABLES (de l'ordre de deux à trois jours avec les tests), à condition de
disposer d'une VRAIE chaîne de fabricant comme vecteur de test — celle du téléphone de la
propriétaire, obtenue à l'étape 4 du test de contrat de B3a. Point 3 NON tenable dans le bac à
sable (aucune sortie réseau) : il resterait déclaré. Point 4 : dépend d'une décision sur
l'horloge injectée.

## Deux cartes pour le couloir A (à nommer, pas à faire dans B3a)

**(a) Durées de session.** La conception en fixe trois (décision du 2026-10-10) : 5 min
d'inactivité, 60 s en arrière-plan, 30 min au maximum. Seule APP_SESSION_TTL existe. Les deux
autres n'ont aucun paramètre nommé ; si B4 les écrit en dur, named-params ne le verra pas. À
ajouter AVANT que B4 implémente la session.

**(b) La porte named-params ne vérifie qu'un sens.** Elle vérifie qu'une valeur du jeu de règles
n'est pas écrite en dur dans le code ; elle ne vérifie jamais qu'un paramètre DÉCLARÉ est
effectivement LU. C'est ce trou qui a laissé vivre sans lecteur OTP_RESENDS, THR_4EYES et
POLL_BACKGROUND (vérifié le 2026-10-22 : aucun fichier de production ne les nomme). Ajouter le
sens inverse, avec une liste d'exemptions explicites pour les paramètres de mois ultérieurs :
exemptions NOMMÉES, jamais une exclusion silencieuse.

## Cartes nommées (à créer par la propriétaire)

| Priorité | Carte | Couloir | Limite |
|---|---|---|---|
| HAUTE, bloquante avant soutenance | Comptage des essais du PIN et verrou temporaire (motif ES-21) | A | L9 |
| PRIORITAIRE, avant soutenance | TLS de la surface mobile du cœur (8443) | infrastructure | L7 |
| avant B4 | Paramètres de session : arrière-plan 60 s, durée maximale 30 min | A | (a) |
| — | Sens inverse de named-params (paramètre déclaré = paramètre lu), exemptions nommées | A | (b) |
| — | Compteur et délai de renvoi des OTP côté serveur | à désigner | L10 |
| à décider (tenable au mois 4 pour les points 1-2) | Attestation : racines Google épinglées, RootOfTrust | B | L12 |
| mois 5 | `/recovery-code/rotate` et `DELETE /device` (une seule carte) | B | L1 |
| — | Étranglement par origine des routes publiques d'enrôlement (motif ES-21) | A | L2 |
| — | Comptage des échecs de `/recovery-code/confirm`, limitation P-11 | B4 | L2 |
| — | Amorçage PDP du compte lié des fixtures ; `/enrolment/recover` | B3b | L5, L6 |
| — | Profil `demo` et montage des fixtures dans le conteneur | infrastructure | L6 |
