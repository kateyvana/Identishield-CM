# ISC-AMD-01 — Spécification de changement : refonte du modèle d'authentification

| Champ | Valeur |
|---|---|
| Identifiant | ISC-AMD-01 |
| Version | 1.0 |
| Statut | À APPROUVER |
| Date | 31 août 2026 |
| Origine | Revue de conception des 30 et 31 août 2026 · vérification empirique de l'application MoMo sur trois appareils · confrontation aux référentiels OWASP |
| Documents impactés | ISC-SRS v1.2 → v1.3 · ISC-TM v1.1 → v1.2 · ISC-SAD v1.1 → v1.2 · ADR-010, 013, 015 · ADR-029 à 031 (nouveaux) · backlog du mois 4 |
| Règle appliquée | Rien n'est supprimé : toute exigence retirée conserve son identifiant avec la mention RETIRÉE et le motif. ISC-SRS §16. |

---

## 1. Pourquoi ce changement

Quatre constats ont invalidé la logique de ré-enrôlement de la version 1.2.

**Constat 1 — vérification empirique.** Un test mené le 31 août 2026 avec trois appareils sur un même compte MoMo a établi que les trois sessions restent actives simultanément, qu'aucune notification n'est émise vers les appareils existants lors de l'ajout d'un nouveau, que l'application demeure fonctionnelle sans carte SIM dans l'appareil, et que l'accès au solde comme l'initiation de transferts sont possibles après déverrouillage biométrique local sans re-authentification serveur.

Conclusion : l'application déployée ne pratique pas de liaison exclusive à un appareil vérifiée par le serveur. Le déverrouillage biométrique est un contrôle local, non opposable au serveur.

**Constat 2 — la confirmation par l'ancien appareil est une faille.** Si l'appareil précédent est volé, il est aux mains de l'attaquant. Un mécanisme où l'ancien appareil *confirme* le nouveau donne au voleur le pouvoir d'approuver son propre enrôlement.

**Constat 3 — l'expiration automatique vers la confiance est une faille.** Un attaquant patient attend la fin de la période et devient légitime sans intervention. C'est le profil T-05 du modèle de menace, que la logique de ré-enrôlement contredisait.

**Constat 4 — le refus du premier enrôlement après changement de SIM bloque la victime.** Le serveur ne peut pas distinguer une reconduction légitime d'une reconduction frauduleuse. Refuser l'enrôlement pénalise l'utilisateur dont le téléphone vient d'être volé et qui refait sa puce, cas de figure courant. Une protection qui bloque l'utilisateur légitime n'est pas une protection.

---

## 2. Le principe retenu

**Le serveur décide de l'aiguillage sur un seul critère : ce compte possède-t-il une clé publique enregistrée ?**

Il ne consulte ni le statut de la carte SIM, ni le motif déclaré, ni aucune donnée que l'attaquant contrôle ou qu'un agent saisit.

| Critère | Aiguillage |
|---|---|
| Aucune clé enregistrée | Premier enrôlement |
| Une clé enregistrée, appelant sans signature valide | Ré-enrôlement |

L'aiguillage se fait au tout premier échange, avant toute saisie de l'utilisateur autre que son numéro. L'application ne demande rien avant d'avoir reçu la réponse du serveur.

**Le signal est l'absence de signature.** Une application détenant la clé aurait signé. Une clé privée ne quitte jamais le Keystore — établi par SPIKE-1, tentative d'export refusée sur deux appareils avec exception citée. Il n'existe donc que deux états, mutuellement exclusifs.

**Un compte, un appareil actif.** Tout ré-enrôlement réussi révoque la clé précédente. Cette propriété est observable et distingue le système de l'application déployée.

---

## 3. Modifications du cahier des charges

### 3.1 Exigences retirées

| ID | Libellé v1.2 | Motif de retrait |
|---|---|---|
| **ES-14** | Révocation différée de l'ancienne clé jusqu'à la fin de la probation de ré-enrôlement | Constat 2. La conservation de l'ancienne clé n'a de sens que si elle sert à confirmer ou annuler. Ces deux voies sont supprimées. Remplacée par ES-14b. |
| **ES-17** | Premier enrôlement refusé si changement d'IMSI antérieur à CAR_IMSI | Constat 4. Bloque la victime légitime. Remplacée par ES-17b. |
| **EF-28** | L'application permet d'annuler un ré-enrôlement frauduleux depuis l'appareil précédent | Constat 2. Le parcours disparaît avec ES-14. |

### 3.2 Exigences remplaçantes

**ES-14b — priorité M.** Le PDP DOIT révoquer la clé de dispositif précédente immédiatement après enregistrement d'une nouvelle clé. Une seule clé est active par compte à tout instant. La révocation est journalisée.

**ES-17b — priorité M.** Le PDP DOIT déterminer la nature de l'opération d'enrôlement uniquement à partir de la présence ou de l'absence d'une clé publique enregistrée pour le compte. Aucun signal réseau, aucun motif déclaré, aucune donnée fournie par l'appelant n'entre dans cette détermination.

### 3.3 Exigences nouvelles

**ES-18 — code de récupération — priorité M.** Le PDP DOIT générer un code de récupération lors du premier enrôlement réussi. Ce code est transmis une seule fois à l'application. Le PDP n'en conserve qu'une empreinte cryptographique à sel unique, jamais le code en clair. Un code consommé est invalidé et un nouveau code est généré à chaque ré-enrôlement réussi.

**ES-19 — facteurs de ré-enrôlement — priorité M.** Le PDP DOIT exiger, pour tout ré-enrôlement, la présentation successive du code de récupération puis du code PIN. Il NE DOIT PAS émettre ni accepter de mot de passe à usage unique sur ce parcours. L'échec de l'un des deux facteurs interrompt le parcours sans révéler lequel a échoué.

**ES-20 — unicité de l'appareil — priorité M.** Le PDP DOIT garantir qu'un compte ne possède jamais plus d'une clé de dispositif active. Toute transaction signée par une clé révoquée DOIT être refusée.

**ES-21 — limitation sans déni de service — priorité M.** Le PDP DOIT limiter les tentatives de ré-enrôlement échouées par un délai croissant appliqué à l'appareil demandeur et à l'origine réseau, jamais au compte visé. Le compte ciblé NE DOIT JAMAIS être rendu indisponible à son titulaire du fait de tentatives échouées émanant d'un tiers. Le délai croissant est plafonné par le paramètre nommé `LOCK_MAX`.

*Fondement : OWASP recommande le délai croissant plutôt que le verrouillage fixe, et avertit explicitement du risque de déni de service par verrouillage du compte d'autrui. OWASP API Security précise que les points de récupération d'identifiants doivent être traités comme des points d'authentification en matière de limitation.*

**ES-22 — réponses uniformes — priorité M.** Le PDP DOIT retourner une réponse identique en contenu, en code de statut et en temps de réponse, que le compte existe ou non, qu'il soit ralenti ou non, et que le facteur échoué soit le code de récupération ou le PIN. Aucune réponse ne DOIT permettre d'inférer l'existence d'un compte ni l'état de sa limitation.

**ES-23 — protection de l'affichage — priorité M.** L'application DOIT interdire la capture d'écran et l'enregistrement d'écran sur toute vue affichant le code de récupération, par activation du drapeau système prévu à cet effet.

**ES-24 — alerte sur tentatives — priorité S.** Le PDP DOIT comptabiliser les tentatives de ré-enrôlement échouées par compte et émettre une alerte vers la console au-delà du seuil `ALERT_REENROL`. Ce comptage NE DOIT produire aucune restriction sur le compte.

**EF-45 — priorité M.** L'application DOIT afficher le code de récupération une seule fois, accompagné d'un avertissement indiquant qu'il doit être conservé hors de l'appareil et qu'aucun agent ne le demandera jamais. L'utilisateur DOIT confirmer explicitement l'avoir noté avant de poursuivre.

**EF-46 — priorité M.** L'application DOIT présenter le parcours de ré-enrôlement sans jamais demander de mot de passe à usage unique.

**EF-47 — priorité S.** Le cœur DOIT notifier l'appareil légitime de toute tentative de ré-enrôlement échouée sur son compte. Cette notification est informative : elle NE DOIT comporter aucune action à confirmer ni à refuser.

### 3.4 Paramètres nommés ajoutés

| Paramètre | Rôle | Valeur de départ |
|---|---|---|
| `REENROL_ATTEMPTS` | Tentatives avant déclenchement du délai croissant | 2 |
| `LOCK_BASE` | Durée initiale du délai croissant | 30 s |
| `LOCK_MAX` | Plafond du délai croissant | 24 h |
| `ALERT_REENROL` | Seuil d'alerte console, par compte et par heure | 5 |
| `CODE_LENGTH` | Longueur du code de récupération | à fixer par l'opérateur |

La valeur de `LOCK_MAX` reprend la durée de 24 heures retenue en revue, non plus comme durée fixe mais comme plafond du délai croissant.

---

## 4. Modifications du modèle de menace

### 4.1 Cas d'abus révisés

**AC-08 — ré-enrôlement frauduleux.** Résultat révisé : l'attaquant détient le numéro, la carte SIM, le code PIN et reçoit les messages à usage unique. Le serveur lui oppose la demande du code de récupération, qu'il ne détient pas. Le parcours échoue au premier facteur. Le mot de passe à usage unique dont il dispose n'est jamais sollicité.

**AC-13 — premier enrôlement après reconduction.** Résultat révisé : l'enrôlement n'est plus refusé. Il est accepté, et le compte demeure dans l'état de confiance déterminé par le signal réseau au niveau transactionnel — probation plafonnée si l'IMSI a changé récemment, conformément à ES-01b. L'accès est préservé, l'exposition est bornée.

### 4.2 Cas d'abus nouveaux

**AC-14 — déni de service par tentatives de ré-enrôlement.** Un attaquant soumet des codes de récupération erronés dans le seul but de rendre le compte indisponible à son titulaire. Contre-mesure : ES-21. La limitation porte sur l'appareil demandeur, jamais sur le compte.

**AC-15 — force brute sur le code de récupération.** Un attaquant tente des codes successifs depuis des appareils ou des origines multiples. Contre-mesures : ES-21 pour le délai croissant, ES-22 pour l'absence de fuite d'information, ES-24 pour l'alerte.

**AC-16 — énumération de comptes.** Un attaquant soumet une liste de numéros pour déterminer lesquels sont liés à un appareil. Contre-mesure : ES-22.

### 4.3 Périmètre inchangé

Le hameçonnage du code de récupération n'est pas traité. Il relève de la même famille que le hameçonnage du code PIN, déjà exclu du périmètre et traité par les campagnes de sensibilisation de l'opérateur. Cette exclusion est déclarée, non omise.

---

## 5. Modifications de l'architecture

### 5.1 Décisions renversées

**ADR-010 — révocation différée.** RENVERSÉE. La révocation devient immédiate. Motif : la conservation de l'ancienne clé ne servait qu'aux voies de confirmation et d'annulation, toutes deux supprimées. Une clé conservée sans usage est une surface d'attaque sans contrepartie.

**ADR-013 — refus du premier enrôlement après changement d'IMSI.** RENVERSÉE. Motif : constat 4.

**ADR-015 — contacts de confiance.** PÉRIMÈTRE RÉDUIT. Les contacts de confiance ne sont plus un facteur du parcours de ré-enrôlement applicatif. Leur rôle sur le canal USSD demeure et doit être tranché séparément — voir §8.

### 5.2 Décisions nouvelles

**ADR-029 — le code de récupération comme facteur de ré-enrôlement.** Décision : un secret à affichage unique, généré par le serveur, stocké en empreinte seule, présenté avant le PIN. Alternatives rejetées : confirmation par l'ancien appareil, qui donne au voleur le pouvoir d'approuver ; expiration automatique, qui récompense l'attaquant patient ; contact de confiance obligatoire, qui suppose une désignation préalable que la majorité des utilisateurs n'effectue pas. Conséquence : l'utilisateur ayant perdu son code doit passer par la voie physique — voir §8.

**ADR-030 — limitation par délai croissant, jamais par verrouillage de compte.** Décision : le délai porte sur l'appareil demandeur et l'origine réseau, il croît géométriquement, il est plafonné par `LOCK_MAX`. Le compte n'est jamais rendu indisponible. Alternative rejetée : verrouillage de compte après N échecs, qui transforme la protection en arme de déni de service. Fondement : OWASP.

**ADR-031 — unicité de la clé active.** Décision : un compte, une clé. Tout enrôlement réussi révoque le précédent. Alternative rejetée : appareils multiples, retenue par l'application déployée et vérifiée le 31 août 2026 sur trois appareils simultanés. Conséquence : propriété observable en démonstration.

### 5.3 Contrat

Deux opérations s'ajoutent au contrat :

`POST /enrolment/resolve` — reçoit un numéro, retourne l'aiguillage. Réponse uniforme conformément à ES-22.

`POST /enrolment/complete` — reçoit les facteurs selon le chemin, retourne le résultat et, pour le premier enrôlement comme pour le ré-enrôlement réussi, le code de récupération à usage unique.

Le mot de passe à usage unique reste une opération du cœur, jamais du PDP, et n'apparaît que sur le chemin du premier enrôlement.

### 5.4 Modèle de données

Table `device_key` : ajout d'un état actif ou révoqué, d'un horodatage de révocation, et d'une contrainte garantissant au plus une clé active par compte.

Table `recovery_code` : identifiant de compte, empreinte à sel unique, horodatage de génération, horodatage de consommation. Jamais le code en clair.

Table `enrolment_attempt` : identifiant d'installation, origine réseau, compte visé, horodatage, résultat. Sert au délai croissant et à l'alerte, jamais au blocage du compte.

---

## 6. Le parcours, dans l'ordre

**Ouverture.** L'application inspecte le Keystore. Aucune clé.

**Premier échange.** L'application envoie le numéro, sans signature. Le serveur consulte sa base et retourne l'aiguillage.

**Chemin A — premier enrôlement.** Mot de passe à usage unique, puis PIN, puis génération de la paire de clés, puis transmission de la clé publique, puis affichage unique du code de récupération sur vue protégée contre la capture.

**Chemin B — ré-enrôlement.** Code de récupération, puis PIN. Aucun mot de passe à usage unique. En cas d'échec, délai croissant appliqué à l'appareil. En cas de succès : nouvelle paire de clés, révocation immédiate de l'ancienne, consommation du code, génération et affichage unique d'un nouveau code.

**Dans les deux chemins**, l'état de confiance du compte au niveau transactionnel reste déterminé par le signal réseau selon ES-01b, indépendamment du parcours d'enrôlement.

---

## 7. Ce qui ne change pas

Le point de décision, son contrat d'évaluation, le moteur de règles, le journal d'audit chaîné, l'authentification mutuelle entre services, le canal USSD, les cinq spikes et leurs conclusions, les sept obligations du SAD, la question de recherche.

Aucune tâche du mois 2 n'est affectée. Le développement en cours se poursuit sans interruption.

---

## 8. Points laissés ouverts, à trancher par le propriétaire

**Point 1 — le code perdu.** L'utilisateur qui a perdu son code de récupération ne dispose d'aucune voie autonome. Reste la vérification physique au comptoir avec pièce d'identité. Faut-il l'inscrire comme exigence, avec ou sans validation par deux agents distincts ?

**Point 2 — le canal USSD.** Le mécanisme du code de récupération ne s'applique qu'aux utilisateurs de l'application : un utilisateur USSD n'a ni clé, ni enrôlement, ni code. La protection de ce canal repose entièrement sur le signal réseau, le score comportemental et la temporisation. Les contacts de confiance, retirés du parcours applicatif, doivent-ils y demeurer comme unique voie hors-bande, ou être retirés du projet ?

**Point 3 — la force brute sur le code PIN.** Le défaut E-05 de l'audit du 30 août reste ouvert. Le mécanisme de délai croissant d'ES-21 s'y applique-t-il également, ou faut-il une exigence distincte ?

Ces trois points doivent être tranchés avant la production des versions v1.3 du cahier des charges et v1.2 du modèle de menace et de l'architecture.
