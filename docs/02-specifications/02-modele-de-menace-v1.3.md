# IdentiShield-CM — Modèle de menace

| Champ | Valeur |
|---|---|
| Version | **1.3** (25/09/2026) — Intégration de l'application mobile (ISC-AMD-02 v1.1) ; voir le journal v1.3 ci-dessous. La version 1.2.1 est conservée (`02-modele-de-menace-v1.2.md`). ~~1.2.1 — Refonte du modèle d'authentification (ISC-AMD-01, 07/09/2026) : AC-08 et AC-13 révisés, AC-14 à AC-16 ajoutés, T-02 chiffré par déclaration publique de l'opérateur. Correctif 1.2.1 : S-03, S-08, T-AC-03 et le paramètre CAR_ENROL citaient encore la logique renversée (écart trouvé par l'agent de codage en TACHE-04)~~ |
| Date | ~~20 août 2026~~ 25 septembre 2026 (v1.3) |
| Propriétaire | YENGUE MESSAH KATE YVANA |
| Méthode | STRIDE (Microsoft SDL) appliqué aux flux de données + scénarios d'attaque + cas d'abus |
| Statut | APPROVED (encadrement technique) — à présenter à Engr. Moune Daniel |
| Documents aval | SRS (§6ter, §7bis), SAD, protocole d'évaluation, suite de tests de sécurité |
| Documents liés (v1.3) | ISC-SRS-FR v1.4, ISC-SAD v1.3, ISC-MOB-CDC v1.2, ISC-MOB-SPEC v1.2, ISC-AMD-02 v1.1 |

**Journal v1.3 (25/09/2026, ISC-AMD-02 v1.1 §2.3).** Menaces propres à l'application mobile ajoutées en scénarios S-14 à S-21 (ex-MT-01 à MT-08 d'ISC-MOB-SPEC §16). AC-13 étendu à la fenêtre d'amorçage (AC-13 couvre déjà le premier enrôlement post-SIM swap : pas d'AC-17). Variante « canal application » ajoutée à AC-02. L'OTP n'est un contrôle qu'au premier enrôlement et sur USSD (D-3) ; la seule mention ambiguë (AC-01) est précisée. Ordre du ré-enrôlement aligné sur ES-19 v1.4 (PIN puis code de récupération, D-1). §11 : ES-25 à ES-28. AC-08, AC-14, AC-15, AC-16 et T-02 inchangés. Rien n'est supprimé : l'ancien texte est barré.

---

## 1. Périmètre du modèle

Le système modélisé est le **sandbox IdentiShield-CM** : cœur opérateur simulé, PDP, canaux app et USSD, console SOC, générateur. Le modèle décrit les menaces contre **l'utilisateur final du Mobile Money** telles qu'elles existent dans le monde réel, reproduites dans le sandbox. Il ne modélise pas les menaces contre le sandbox lui-même en tant qu'infrastructure de démonstration (voir §8).

---

## 2. Actifs à protéger

| ID | Actif | Pourquoi il compte |
|---|---|---|
| A-01 | Solde du compte Mobile Money | Actif financier direct de la victime |
| A-02 | Capacité d'autoriser une transaction sortante | Ce que l'attaquant cherche réellement à obtenir |
| A-03 | Lien MSISDN → titulaire légitime | L'identité telle que l'opérateur la conçoit |
| A-04 | Clé privée de dispositif (Keystore) | Cœur de la défense ; sa compromission annule le device binding |
| A-05 | PIN de l'utilisateur | Facteur de connaissance, extorquable |
| A-06 | OTP SMS | Facteur de possession du numéro, détourné par SIM swap |
| A-07 | Historique comportemental de l'utilisateur | Base du scoring ; sa corruption produit des faux négatifs |
| A-08 | Journal d'audit du PDP | Preuve a posteriori ; cible d'un insider |
| A-09 | Jeu de règles / modèle du PDP | Sa modification silencieuse désactive la défense |
| A-10 | Contacts de confiance enregistrés | Chemin de récupération et de STEP_UP |

---

## 3. Acteurs et capacités

### 3.1 Acteurs légitimes
- **Utilisateur** (canal app ou USSD)
- **Contact de confiance** (tiers désigné)
- **Analyste SOC** (console, actions limitées, quatre-yeux)
- **Agent KYC** (simulé ; ré-enrôlement physique)
- **Administrateur de politique** (déclaré, hors démo)

### 3.2 Attaquants

| ID | Attaquant | Contrôle | Ne contrôle PAS | Source |
|---|---|---|---|---|
| T-01 | **Fraudeur externe par ingénierie sociale** | Le PIN de la victime (extorqué) ; l'OTP si SIM swap réussi ; des comptes mules | Le téléphone physique de la victime ; la clé Keystore ; les contacts de confiance | FHNW 2025, 237 Actu, Cameroon Tribune |
| T-02 | **Fraudeur externe avec agent complice** | Tout T-01 + capacité de faire reprogrammer le MSISDN sur un nouvel IMSI sans la victime | Les systèmes internes du PDP ; le Keystore | Financial Afrik 2025, TechTrends Africa 2026, MINPOSTEL (reprogrammation « faillible ») |
| T-03 | **Attaquant mimétique** | Tout T-01 ou T-02 + connaissance du comportement habituel de la victime (observation, proximité) | Idem | Construit pour tester l'évasion |
| T-04 | **Analyste SOC malveillant** | Accès console, lecture du journal, capacité de lever un gel | Le Keystore ; la modification du journal (par conception) ; les règles | Hypothèse interne, Equity Bank Kenya (arXiv 2026) |
| T-05 | **Attaquant patient** | Tout T-02 + volonté d'attendre la fin d'une période de carence | Idem | Construit pour tester la probation |

---

## 4. Frontières de confiance et flux

```
[Téléphone utilisateur]──(F1: app, TLS, signature)──►[Cœur opérateur]──(F3: /evaluate)──►[PDP]
[Téléphone basique]─────(F2: USSD, session réseau)──►[Cœur opérateur]                    │
[HLR simulé]────────────(F4: MSISDN→IMSI)──────────►[Cœur opérateur]                    │
[Console SOC]───────────(F5: actions analyste)──────────────────────────────────────────►[PDP]
[PDP]──────────────────(F6: journal chaîné)────────►[Audit store]
[Générateur]────────────(F7: trafic simulé)─────────►[Cœur opérateur]
```

Frontières : (a) dispositif ↔ opérateur ; (b) réseau télécom (HLR) ↔ opérateur ; (c) opérateur ↔ PDP ; (d) humains SOC ↔ PDP ; (e) PDP ↔ audit.

---

## 5. Analyse STRIDE par flux

| Flux | S (usurpation) | T (altération) | R (répudiation) | I (divulgation) | D (déni de service) | E (élévation) |
|---|---|---|---|---|---|---|
| F1 app | Attaquant se fait passer pour la victime via SIM swap + PIN | Modification du montant/bénéficiaire en transit | Utilisateur nie une transaction | Fuite du PIN | — | — |
| F2 USSD | Idem, sans aucune preuve de dispositif possible | Session USSD détournée | Idem | — | — | — |
| F3 /evaluate | Cœur contrefait appelant le PDP | Réponse ALLOW forgée | PDP nie une décision | Contexte de transaction exposé | Saturation du PDP → fail-open ? | — |
| F4 HLR | Reprogrammation MSISDN frauduleuse (T-02) | — | Agent nie la reprogrammation | — | — | Agent outrepassant la procédure |
| F5 SOC | Vol de session analyste | Levée de gel injustifiée | Analyste nie une action | Données clients exposées | — | Analyste modifiant les règles |
| F6 audit | — | Réécriture du journal (T-04) | — | — | Journal plein → PDP bloqué ? | — |

Décisions de conception qui en sortent (à reporter dans le SAD) :
- F3 : authentification mutuelle cœur↔PDP (mTLS ou jeton signé) ; **comportement en cas d'indisponibilité du PDP = fail-closed pour les sorties sensibles, configurable** (question de recherche secondaire : coût du fail-closed).
- F1 : la signature couvre un canonical de {montant, bénéficiaire, nonce serveur, identifiant de compte} — pas seulement une authentification de session.
- F6 : chaînage d'empreintes + rôle base de données en écriture seule + ancrage périodique.
- F5 : RBAC, quatre-yeux au-dessus d'un seuil, journalisation de chaque action.

---

## 6. Scénarios d'attaque (S-xx)

Chaque scénario est exécuté par l'outil attaquant CLI, injecté par le générateur, et devient un test d'intégration. « Attendu » = comportement du système avec la défense active.

| ID | Scénario | Attaquant | Canal | Attendu |
|---|---|---|---|---|
| S-01 | Ingénierie sociale simple : PIN extorqué, transfert depuis le propre téléphone de la victime manipulée | T-01 | app/USSD | Hors du pouvoir du device binding ; dépend du comportemental (nouveau bénéficiaire, montant) → STEP_UP |
| S-02 | SIM swap + vidage immédiat (< 10 min) vers un bénéficiaire inconnu | T-02 | USSD | PROBATION (changement IMSI) → FREEZE (nouveau bénéficiaire / dépassement CAP_PROB, ES-04) |
| S-03 | SIM swap + vidage immédiat via l'app réinstallée sur le téléphone de l'attaquant | T-02 | app | Refus : absence de signature valide (ES-02) ; le ré-enrôlement exige le code de récupération, jamais l'OTP (ES-19) ; l'attaquant ne le détient pas |
| S-04 | SIM swap + attente 48 h puis vidage | T-05 | USSD | FREEZE si dans la carence, sinon STEP_UP (nouveau bénéficiaire + montant) ; mesure la durée de carence nécessaire |
| S-05 | SIM swap + vidage fractionné : n petits transferts vers m bénéficiaires | T-02 | USSD | PROBATION → FREEZE dès le premier bénéficiaire nouveau (ES-04) ; sans signal IMSI, détection par VEL (ES-05c) |
| S-06 | SIM swap + attaque de nuit (02h–05h) | T-02 | USSD | PROBATION → FREEZE (ES-04) ; règle horaire comme signal secondaire (ES-05d) |
| S-07 | Attaque mimétique : mêmes horaires, montants et bénéficiaires habituels de la victime, après swap | T-03 | USSD | PROBATION : perte plafonnée à CAP_PROB (bénéficiaire connu, montant habituel) ; sans signal IMSI, rappel attendu faible — **scénario de référence pour montrer les limites** |
| S-08 | Faux sinistre : « j'ai perdu mon téléphone », ré-enrôlement d'un dispositif attaquant | T-01/T-02 | app | PROBATION : sorties plafonnées 48–72 h ; confirmation par ancien dispositif ou contact de confiance requise pour lever |
| S-09 | Levée de gel par analyste malveillant | T-04 | console | Refus si montant > seuil sans second analyste ; action journalisée et chaînée |
| S-10 | Altération du journal d'audit | T-04 | base | Rupture de chaîne détectée par la console |
| S-11 | Rejeu d'une transaction signée | T-01 | app | Refus : nonce consommé |
| S-12 | Saturation du PDP pendant une attaque | T-02 | F3 | Fail-closed sur les sorties sensibles ; mesure du coût |
| S-13 | Premier enrôlement d'app après SIM swap sur un compte USSD-only | T-02 | app | Enrôlement refusé pendant CAR_IMSI ; PROBATION ensuite |
| S-14 | (ex-MT-01, v1.3) Téléphone lié volé et déverrouillé | voleur du téléphone lié (profil non décrit en §3.2) | app | Parade : déverrouillage local du téléphone exigé avant chaque signature ; PIN ; plafonds et PDP ; exécution différée (DEFER) sur STEP_UP. Résiduel : déclaré |
| S-15 | (ex-MT-02, v1.3) Capture ou enregistrement de la clé de récupération | T-01 | app | Parade : FLAG_SECURE ; pas de copie ; consigne papier. Résiduel : photo de l'écran par un autre appareil, déclaré |
| S-16 | (ex-MT-03, v1.3) Superposition d'écran pour faire valider une opération | application malveillante sur le téléphone (profil non décrit en §3.2) | app | Parade : anti-tapjacking. Résiduel : faible |
| S-17 | (ex-MT-04, v1.3) Rejeu d'une opération signée | T-01 | app | Parade : nonce unique ; horodatage borné. Résiduel : nul (voir aussi S-11) |
| S-18 | (ex-MT-05, v1.3) Téléphone rooté ou démarrage non vérifié qui tente d'extraire la clé | détenteur du téléphone (profil non décrit en §3.2) | app | Parade : clé non exportable ; attestation refusée si démarrage non vérifié. Résiduel : faible |
| S-19 | (ex-MT-06, v1.3) Énumération des noms de titulaires via ED-11 | T-01 | app | Parade : recherche freinée, jeton exigé, dans le parcours d'envoi seulement. Résiduel : faible |
| S-20 | (ex-MT-07, v1.3) Faux serveur (interception TLS) | attaquant réseau (profil non décrit en §3.2) | app | Parade : autorité épinglée ; aucune autorité utilisateur. Résiduel : nul |
| S-21 | (ex-MT-08, v1.3) Attaquant ayant SIM et PIN, avant tout enrôlement (fenêtre d'amorçage) | T-02 | app | Parade : OTP + PIN exigés ; aucune parade par liaison d'appareil. Résiduel : déclaré (QF-01 en option) — voir AC-13 |

---

## 7. Décisions de paramétrage (tranchées)

Chaque valeur est un **paramètre de configuration** du PDP, pas une constante. Les valeurs ci-dessous sont les valeurs initiales du jeu de règles v1 ; le protocole d'évaluation mesurera leur sensibilité.

| Paramètre | Valeur v1 | Justification | Sensibilité à mesurer |
|---|---|---|---|
| Carence post-changement d'IMSI (CAR_IMSI) | **72 h** en état PROBATION (pas FROZEN) : sorties ≤ CAP_PROB vers bénéficiaires connus autorisées ; tout le reste FREEZE ; levée anticipée par contact de confiance ou signature d'un dispositif ACTIVE (ADR-012) | Fenêtre observée des vidages : minutes à 24 h (Safaricom 2021 ; FHNW 2025). 72 h couvre l'attaquant patient à 48 h (S-04) avec marge ; Safaricom applique une suspension « pendant une période » non publiée ; Sayibu 2025 confirme l'efficacité des hold periods. | Courbe rappel/friction pour 24 h, 48 h, 72 h, 7 j |
| Carence post-ré-enrôlement (CAR_ENROL) | **RETIRÉ (v1.2)** — aucune carence propre à l'enrôlement | Le ré-enrôlement est authentifié par le code de récupération (ADR-029) ; une carence supplémentaire n'apporterait rien et pénaliserait l'utilisateur légitime. L'état transactionnel reste gouverné par CAR_IMSI (ES-01b). | — |
| Plafond de sortie en PROBATION (CAP_PROB) | **min(25 000 FCFA/jour ; 10 % du solde)** ; nouveaux bénéficiaires interdits | 25 000 FCFA ≈ ordre de grandeur d'un retrait courant ; limite la perte maximale à un montant absorbable sans bloquer la vie quotidienne. | Perte max vs % de transactions légitimes gênées |
| Seuil « montant élevé » (THR_HIGH) | **50 000 FCFA** ou **> 3× la médiane des 30 derniers jours** du compte (le plus bas) | Règle absolue + règle relative : protège le petit compte comme le gros. | FPR par persona |
| Seuil quatre-yeux SOC (THR_4EYES) | **100 000 FCFA** | Au-dessus, la levée d'un gel par un seul analyste est un risque d'insider (S-09). | — |
| TTL de gel (TTL_FREEZE) | **24 h**, puis annulation | Assez long pour qu'un utilisateur légitime réagisse ; assez court pour que la console ne s'encombre pas. Fail-closed. | Taux d'annulations de transactions légitimes |
| Fenêtre « nuit » (NIGHT) | **23 h – 05 h** heure serveur | Pic documenté des transferts frauduleux ; signal secondaire seulement (poids, pas veto). | Poids optimal dans le score |
| Fenêtre de vélocité (VEL) | **> 3 transferts sortants en 10 min** ou **> 3 bénéficiaires distincts nouveaux en 24 h** | Détecte le fractionnement (S-05). | FPR sur persona « commerçant » |
| Temporisation STEP_UP USSD (DEFER) | **4 h** | Plus long que la fenêtre d'action typique de l'attaquant ; plus court qu'une journée de travail. | Protection/friction (QR-2) |
| Nonce serveur | **128 bits aléatoires, usage unique, validité 120 s** | Anti-rejeu (S-11) ; 120 s laisse le temps de signer sur mobile. | — |
| Objectif de traitement SOC | **2 h ouvrées**, mesuré | ENF de qualité de service de la console. | — |
| Fail-closed PDP indisponible | **Activé pour toute sortie > CAP_PROB** ; sorties ≤ CAP_PROB autorisées (dégradé) | Un PDP en panne ne doit pas offrir une fenêtre de vidage (S-12), sans bloquer les micro-paiements. | Coût de disponibilité |

**Décision sur T-02 (QO-15, fermée) : l'agent complice est DANS le modèle.** C'est la voie réaliste du SIM swap au Cameroun (MINPOSTEL : reprogrammation « faillible et très accessible » ; Financial Afrik ; TechTrends). Un modèle qui l'exclurait décrirait un pays qui n'existe pas.

**Décision sur l'enrôlement (ADR-013 RENVERSÉ, ADR-029, v1.2) :** le serveur ne peut pas distinguer une reconduction de SIM légitime d'une frauduleuse ; refuser l'enrôlement pénalisait la victime dont le téléphone vient d'être volé. L'aiguillage repose désormais sur un seul critère : la présence d'une clé enregistrée pour le compte (ES-17b). Sans clé : premier enrôlement, OTP + PIN. Avec clé : ré-enrôlement, code de récupération + PIN, jamais d'OTP (ES-19). L'état de confiance transactionnel reste gouverné par le signal réseau (ES-01b), indépendamment du parcours. CAR_ENROL est retiré : aucune carence propre n'est attachée à l'enrôlement.

**Décision sur le poids du comportemental vs du cryptographique :** le changement d'IMSI est un **veto de transition** (il force PROBATION) ; la signature est un **veto d'exécution** ; la violation des restrictions de PROBATION est un **veto FREEZE** ; les règles comportementales produisent un **score** (0–100) avec seuils : < 40 ALLOW, 40–70 STEP_UP, > 70 FREEZE. Les vetos court-circuitent le score.

---

## 8. Cas d'abus (AC-01 à AC-16) — rédaction complète

Gabarit : Scénario | Acteur | Préconditions | Déroulé | Résultat sans contre-mesure | Contre-mesures | Sources | Test attendu.

### AC-01 — Ingénierie sociale sur la victime elle-même
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-01 / T-01 |
| Préconditions | La victime est au téléphone avec un faux « service client » ; elle tient son propre appareil |
| Déroulé | 1. L'attaquant lui dicte une séquence USSD ou des gestes dans l'app 2. La victime initie elle-même un transfert ou un retrait vers un compte/ATM de l'attaquant 3. ~~Elle communique l'OTP reçu~~ Sur USSD, elle communique l'OTP reçu (sur l'application, aucun OTP n'existe après l'enrôlement : D-3) |
| Sans contre-mesure | Transaction parfaitement authentifiée (bon appareil, bon PIN, bon OTP) → exécutée |
| Contre-mesures | ES-05 : nouveau bénéficiaire + montant > THR_HIGH → STEP_UP ; ES-06 : sur USSD, DEFER 4 h avec notification explicite « vous êtes peut-être victime d'une arnaque » ; sur app, écran d'interruption avec délai de réflexion |
| Limite assumée | Le device binding n'a **aucun** effet ici. Le comportemental ne fait que ralentir. C'est la limite structurelle de tout système technique face à une victime consentante ; à écrire dans le mémoire. |
| Sources | IntaSend (« M-Pesa tricks ») ; 237 Actu ; Cameroon Tribune 2020 ; FHNW 2025 |
| Test | T-AC-01 : attendu STEP_UP ; mesure du taux d'abandon simulé |

### AC-02 — Vidage immédiat post-SIM swap (USSD)
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-02 / T-02 |
| Préconditions | MSISDN réassigné à l'IMSI attaquant via agent complice ; PIN obtenu ; mule prête |
| Déroulé | 1. OTP reçu sur l'appareil attaquant 2. Authentification USSD 3. Transfert du solde vers bénéficiaire inconnu 4. Cash-out sous 30 min |
| Sans contre-mesure | Perte totale, irréversible |
| Contre-mesures | ES-01b (changement IMSI → PROBATION) ; ES-03c (CAR_IMSI 72 h) ; ES-04 (solde entier vers bénéficiaire inconnu viole le plafond et la règle de nouveau bénéficiaire → FREEZE) ; ES-05 en secours |
| Sources | Safaricom 2021 ; Rest of World 2023 ; FHNW 2025 ; MINPOSTEL |
| Test | T-AC-02 : FREEZE, latence p95 < 500 ms |
| Variante canal application (v1.3) | L'attaquant a la SIM et le PIN. Il installe l'application, saisit le numéro puis le PIN ; le PDP trouve une clé enregistrée sans signature valide et exige le code de récupération (ES-17b, ES-19, ES-26) ; aucun OTP n'est émis. L'attaquant échoue ; après REENROL_ATTEMPTS échecs, délai croissant sur son appareil et son origine réseau, jamais sur le compte (ES-21) ; le téléphone actuellement lié reçoit l'alerte avec réponse signée « ce n'est pas moi » (ES-27). Toute opération applicative non signée par la clé active est refusée (ES-02). **Oracle : aucun débit.** |

### AC-03 — Vidage post-SIM swap via l'application réinstallée
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-03 / T-02 |
| Préconditions | Comme AC-02 ; l'attaquant installe l'app sur son propre téléphone |
| Déroulé | 1. Tentative d'enrôlement du nouvel appareil 2. OTP + PIN acceptés par un système naïf 3. Transfert |
| Sans contre-mesure | Identique à AC-02 |
| Contre-mesures | ES-02 : la clé du nouvel appareil n'est pas la clé enrôlée → refus de signature ; la tentative d'enrôlement d'un second appareil est un ré-enrôlement (ES-17b) qui exige le code de récupération (ES-19) ; ES-01b place le compte en PROBATION du fait du changement d'IMSI |
| Sources | NIST 800-63B §5.1.3.3 (liaison numéro ↔ appareil physique) ; SIM binding |
| Test | T-AC-03 : refus cryptographique (ES-02) ; tentative de ré-enrôlement sans code de récupération → ~~refusée au premier facteur~~ refusée au facteur « code de récupération » (ES-19 v1.4 : PIN d'abord), aucun OTP émis (ES-19) |

### AC-04 — Attaquant patient (48 h après le swap)
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-04 / T-05 |
| Préconditions | Comme AC-02 ; l'attaquant sait qu'une carence existe |
| Déroulé | 1. Swap 2. Silence 48 h (la victime n'a pas remarqué, ou pense à une panne réseau) 3. Transfert |
| Sans contre-mesure | Carence de 24 h : attaque réussit. Carence de 72 h : bloquée. |
| Contre-mesures | ES-03c CAR_IMSI = 72 h ; ES-05 nouveau bénéficiaire + montant → STEP_UP même après la carence ; **notification à la victime sur un canal alternatif (contact de confiance) dès le swap** — l'information est la meilleure arme contre la patience |
| Sources | Logique M-Pesa ; construit |
| Test | T-AC-04 : exécuté à t+24 h, t+48 h, t+72 h, t+8 j ; attendu FREEZE (ES-04, nouveau bénéficiaire en PROBATION) / FREEZE / STEP_UP / STEP_UP |

### AC-05 — Vidage fractionné
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-05 / T-02 |
| Préconditions | Comme AC-02 ; l'attaquant connaît les seuils de montant |
| Déroulé | n transferts de 15 000 FCFA vers m bénéficiaires distincts en 20 min |
| Sans contre-mesure | Chaque transfert passe sous THR_HIGH → tous exécutés |
| Contre-mesures | ES-01b (PROBATION) + ES-04 (bénéficiaires nouveaux interdits, plafond) ; ES-05 règle VEL (> 3 sorties / 10 min ; > 3 nouveaux bénéficiaires / 24 h) → FREEZE cumulatif |
| Sources | arXiv 2026 (layering pour épuiser les seuils) ; Flutterwave |
| Test | T-AC-05 : avec et sans signal IMSI ; attendu FREEZE dès la 4e sortie au plus tard |

### AC-06 — Attaque de nuit
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-06 / T-02 |
| Déroulé | Comme AC-02, exécuté entre 02 h et 04 h |
| Contre-mesures | ES-01b + ES-04 ; ES-05 NIGHT ajoute +25 au score (signal secondaire) |
| Limite | Les commerçants de nuit et les travailleurs postés existent : NIGHT ne doit jamais être un veto. FPR à mesurer par persona. |
| Test | T-AC-06 : avec/sans IMSI ; sans IMSI, attendu STEP_UP au minimum si nouveau bénéficiaire |

### AC-07 — Attaque mimétique (scénario de limite)
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-07 / T-03 |
| Préconditions | Swap effectué ; l'attaquant connaît horaires, montants, bénéficiaires habituels de la victime |
| Déroulé | Transferts vers un bénéficiaire habituel, à l'heure habituelle, au montant habituel |
| Sans contre-mesure | Indistinguable du comportement légitime |
| Contre-mesures | ES-01b + ES-04 **uniquement** : le bénéficiaire habituel n'est pas nouveau, donc seul le plafond CAP_PROB limite la perte pendant la PROBATION. Sans le signal IMSI, rappel attendu proche de zéro. |
| Rôle dans l'évaluation | **Scénario de référence négatif** : prouve que le comportemental seul est insuffisant et que le signal réseau/cryptographique est indispensable. À présenter au jury comme résultat, pas comme échec. |
| Test | T-AC-07 : avec IMSI → ALLOW si ≤ CAP_PROB vers bénéficiaire habituel (perte plafonnée), FREEZE au-delà ; sans IMSI → ALLOW documenté |

### AC-08 — Faux sinistre et ré-enrôlement frauduleux
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-08 / T-01 ou T-02 |
| Préconditions | L'attaquant contrôle le numéro (swap) ou manipule la victime ; il déclare « téléphone perdu » |
| Déroulé | 1. L'attaquant installe l'application, saisit le numéro 2. Le serveur trouve une clé enregistrée → ré-enrôlement 3. Le serveur demande le code de récupération 4. L'attaquant ne le détient pas ; son OTP n'est jamais sollicité |
| Sans contre-mesure | Le device binding est contourné par sa propre procédure de récupération. **Occurrence réelle documentée** : mars 2026, Cameroun — un tiers a lié son appareil au compte d'une victime au moyen d'OTP lus au téléphone ; compte vidé à 99 % ; les refus explicites de la victime ont été ignorés par le système ([237online](https://www.237online.com/fraude-mtn-mobile-money-failles-securite-plainte-cameroun/)). Vérification empirique du 31/08/2026 : l'application déployée accepte plusieurs appareils simultanés sur un compte, sans notification ni quarantaine, et fonctionne sans SIM. |
| Contre-mesures | ES-17b : aiguillage sur la seule présence d'une clé ; ES-19 : ~~code de récupération puis PIN~~ PIN puis code de récupération (SRS v1.4, D-1), jamais d'OTP ; ES-18 : code à affichage unique, stocké en empreinte ; ES-14b : ancienne clé révoquée immédiatement ; ES-20 : une seule clé ACTIVE ; ES-21 : délai croissant sur l'appareil demandeur ; EF-47 : notification informative à l'appareil précédent |
| Sources | Principe « la récupération ne doit pas être plus faible que l'authentification » (NIST 800-63B §6.1.2) ; modèle des clés secrètes à affichage unique (AWS IAM, codes de secours TOTP) ; OWASP Authentication Cheat Sheet (délai croissant, déni de service par verrouillage) |
| Test | T-AC-08 : ré-enrôlement sans code de récupération → ~~refusé au premier facteur~~ refusé au facteur « code de récupération » (le PIN est demandé en premier, ES-19 v1.4 ; réponse uniforme ES-22) ; OTP jamais émis sur ce parcours ; deux échecs → délai croissant sur l'appareil, compte toujours utilisable par le titulaire ; après ré-enrôlement légitime, signature par l'ancienne clé → refusée (ES-20) |

### AC-09 — Levée de gel par analyste malveillant
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-09 / T-04 |
| Préconditions | L'analyste est complice de T-02 ; une transaction de 300 000 FCFA est gelée |
| Déroulé | L'analyste tente de lever le gel seul |
| Sans contre-mesure | Transaction exécutée ; l'insider est la faille |
| Contre-mesures | ES-08 : montant > THR_4EYES → second analyste distinct requis ; ES-12 : RBAC, authentification, chaque action journalisée (ES-09) avec identité, horodatage serveur, justification obligatoire ; alerte si le même analyste lève des gels du même bénéficiaire |
| Sources | arXiv 2026 (Equity Bank, staff facilitation) ; FITC Nigeria |
| Test | T-AC-09 : levée seule refusée ; levée à quatre yeux acceptée et tracée |

### AC-10 — Altération du journal d'audit
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-10 / T-04 (ou admin base) |
| Déroulé | Modification ou suppression d'une ligne du journal pour effacer une levée de gel |
| Contre-mesures | ES-09 : chaînage SHA-256 ; rôle base en INSERT seul ; ancrage horaire de l'empreinte de tête signée par le PDP et exportée ; vérification d'intégrité affichée en console |
| Test | T-AC-10 : altération manuelle en démo → rupture détectée et localisée |

### AC-11 — Rejeu d'une transaction signée
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-11 / T-01 |
| Préconditions | L'attaquant a capturé une requête signée (proxy, journal fuité) |
| Déroulé | Renvoi de la même requête |
| Contre-mesures | ES-10 : nonce serveur 128 bits, usage unique, 120 s ; le canonical signé inclut le nonce et l'identifiant de compte ; TLS |
| Test | T-AC-11 : second envoi refusé |

### AC-13 — Premier enrôlement post-SIM swap (bootstrap)
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-13 (nouveau) / T-02 |
| Préconditions | Compte jamais enrôlé sur l'app (utilisateur USSD-only) ; swap effectué ; PIN obtenu |
| Déroulé | 1. L'attaquant installe l'app 2. PIN + OTP acceptés 3. Sa clé devient la première clé du compte 4. Il signe ses propres transferts |
| Sans contre-mesure | Le device binding légitime l'attaquant : pire que l'absence de device binding |
| Contre-mesures | L'enrôlement n'est plus refusé (ADR-013 renversé : le refus pénalisait la victime légitime). Il est accepté ; l'attaquant obtient une clé, mais le compte demeure en PROBATION par le signal réseau (ES-01b, ES-04) : sorties plafonnées à CAP_PROB, aucun nouveau bénéficiaire, toute violation → FREEZE. L'accès est préservé pour la victime, l'exposition est bornée pour l'attaquant. Résultat révisé : **perte plafonnée**, non plus refus. |
| Sources | Construit à partir de D-02 ; principe NIST [N2] §6.1.2 ; ISC-AMD-01 constat 4 |
| Test | T-AC-13 : enrôlement accepté à t+1 h après swap ; compte en PROBATION ; transfert > CAP_PROB → FREEZE ; nouveau bénéficiaire → FREEZE ; transfert ≤ CAP_PROB vers bénéficiaire connu → ALLOW (perte plafonnée documentée) |
| Extension v1.3 — fenêtre d'amorçage (S-21, ex-MT-08) | Si le SIM-swap est antérieur à tout enrôlement **et** que CAR_IMSI est expiré, le compte n'est plus en PROBATION : l'attaquant qui détient SIM et PIN lie son propre téléphone au premier enrôlement. Parade : OTP + PIN exigés, liaison seulement après validation (ES-25) ; **aucune parade par liaison d'appareil**. Résiduel **déclaré**. Option non retenue par défaut : quarantaine après le premier enrôlement (QF-01). |

### AC-14 — Déni de service par tentatives de ré-enrôlement (nouveau, v1.2)
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-14 / T-01 |
| Préconditions | L'attaquant connaît le numéro d'une cible ; il n'a aucun secret |
| Déroulé | 1. Installation de l'application 2. Saisie du numéro → ré-enrôlement 3. Codes de récupération erronés, répétés 4. Objectif : rendre le compte indisponible au titulaire |
| Sans contre-mesure | Un verrouillage de compte après N échecs transforme la protection en arme : le titulaire, sur son téléphone légitime, se retrouve bloqué par un tiers. Défaut du même type remonté en production chez un éditeur SaaS par le candidat (expérience QA). |
| Contre-mesures | ES-21 : le délai croissant porte sur l'appareil demandeur et l'origine réseau, jamais sur le compte ; ES-24 : alerte console sans restriction ; EF-47 : notification informative au titulaire |
| Sources | OWASP Authentication Cheat Sheet : « care must be taken to prevent [lockout] from being used to cause a denial of service by locking out other users' accounts » ; CWE-645 |
| Test | T-AC-14 : dix codes erronés depuis un appareil → délai croissant sur cet appareil ; en parallèle, le titulaire signe et exécute une sortie depuis son appareil ACTIF → ALLOW. Assertion négative : le compte n'entre jamais en état restreint du fait des tentatives. |

### AC-15 — Force brute sur le code de récupération (nouveau, v1.2)
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-15 / T-03 |
| Préconditions | L'attaquant dispose de plusieurs appareils ou origines réseau |
| Déroulé | 1. Tentatives de ré-enrôlement distribuées 2. Codes candidats successifs 3. Réinstallation pour réinitialiser l'identifiant d'installation |
| Sans contre-mesure | Un code court sans limitation est énumérable ; une limitation par appareil seul est contournée par réinstallation |
| Contre-mesures | ES-21 : délai croissant cumulé sur l'appareil ET sur l'origine réseau ; CODE_LENGTH dimensionné pour rendre l'énumération impraticable dans la fenêtre ; ES-22 : aucune fuite d'information ; ES-24 : alerte au-delà d'ALERT_REENROL par compte |
| Sources | OWASP API Security Top 10, API2:2023 : les points de récupération d'identifiants sont des points d'authentification en matière de force brute |
| Test | T-AC-15 : campagne distribuée simulée → délai croissant observé sur chaque origine ; alerte console déclenchée ; aucun succès dans la fenêtre d'évaluation |

### AC-16 — Énumération de comptes (nouveau, v1.2)
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-16 / T-03 |
| Préconditions | L'attaquant dispose d'une liste de numéros |
| Déroulé | 1. Soumission de chaque numéro au premier échange d'enrôlement 2. Observation de la réponse pour déterminer quels comptes sont liés à un appareil, lesquels sont ralentis |
| Sans contre-mesure | Une réponse différenciée révèle quels comptes utilisent l'application — cibles de choix pour AC-08 |
| Contre-mesures | ES-22 : réponse identique en contenu, code et temps dans tous les cas |
| Sources | OWASP Authentication Cheat Sheet, section « Authentication and Error Messages » |
| Test | T-AC-16 : numéros existant / inexistant / ralenti → réponses byte-identiques et temps de réponse dans la même fenêtre statistique |

### AC-12 — Saturation du PDP pendant une attaque
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-12 / T-02 |
| Déroulé | Flood de /evaluate pour provoquer des timeouts côté cœur, espérant un fail-open |
| Contre-mesures | ES-11 : fail-closed pour sorties > CAP_PROB ; rate limiting par source ; mTLS cœur↔PDP ; circuit breaker avec état explicite en console |
| Test | T-AC-12 : PDP arrêté → sortie de 60 000 FCFA refusée, sortie de 5 000 FCFA acceptée et journalisée comme « mode dégradé » |

---

## 9. Hors modèle (déclaré explicitement)

| Exclusion | Justification |
|---|---|
| Compromission du téléphone physique de la victime (vol avec PIN, malware, root, hooking) | L'attaquant de référence n'a pas l'appareil. Une clé Keystore matérielle reste non exportable même sur appareil rooté. Extension : attestation d'application (Play Integrity). |
| Compromission du cœur opérateur ou du PDP par un insider technique (DBA, développeur) | Hors de portée d'une couche de décision ; relève de la sécurité interne de l'opérateur. T-04 (analyste) est dans le modèle ; l'insider technique ne l'est pas. |
| Interception SS7 / OTP sans swap | Menace réelle mais le projet traite la réassignation légitime du numéro, pas son interception. |
| Blanchiment aval (réseau de mules) | Traçabilité hors périmètre (sous-problème 6). |
| Sécurité du sandbox comme infrastructure | Non pertinent pour la question de recherche. |
| Rejeu d'une session USSD | Session synchrone côté réseau ; pas de message signé à rejouer. ES-10 ne s'applique qu'au canal app. |
| Victime consentante jusqu'au bout (AC-01 poussé à l'extrême) | Aucun système technique n'arrête un utilisateur qui confirme à chaque étape ; le système ralentit et informe, il ne peut pas interdire. |

---

## 10. Hypothèses de confiance

- H-T1 : le Keystore matériel du dispositif enrôlé n'est pas compromis.
- H-T2 : l'horloge serveur est fiable ; les horodatages clients ne sont pas des entrées de décision.
- H-T3 : le canal cœur↔PDP est authentifié mutuellement.
- H-T4 : au moins un contact de confiance enregistré n'est pas complice.
- H-T5 : le journal d'audit est en écriture seule au niveau base de données.
- H-T6 : le HLR simulé expose fidèlement l'événement « changement d'IMSI » (équivalent de l'API CAMARA Sim Swap) ; dans le monde réel, cette hypothèse dépend de l'opérateur.

---

## 11. Correspondance scénarios → exigences de sécurité (IDs alloués)

| ES | Intitulé | Couvre | Vérification |
|---|---|---|---|
| ES-01 | RETIRÉE (D-01) — remplacée par ES-01b | — | — |
| ES-01b | Changement d'IMSI → transition PROBATION pendant CAR_IMSI | AC-02, 03, 04, 05, 06, 07 | Test |
| ES-02 | Signature de dispositif obligatoire pour toute sortie sur le canal app | AC-03, 11 | Test |
| ES-03 | RETIRÉE (v1.2) — remplacée par ES-03c | — | — |
| ES-03c | Carence CAR_IMSI après changement d'IMSI ; aucune carence propre à l'enrôlement | AC-02, 04 | Test |
| ES-04 | Machine à états TRUSTED / PROBATION / FROZEN / LOCKED avec CAP_PROB | AC-04, 08 | Test + inspection du SAD |
| ES-05 | Score comportemental (bénéficiaire, montant, VEL, NIGHT) → seuils 40/70 | AC-01, 05, 06, 07 | Test + évaluation |
| ES-06 | Politiques STEP_UP USSD (DEFER, plafond, contact de confiance) | AC-01, 02, 04, 05 | Test + évaluation (QR-2) |
| ES-07 | Gel avec TTL 24 h et annulation à expiration | AC-02, 12 | Test |
| ES-08 | Levée de gel : hiérarchie de preuves, quatre-yeux > THR_4EYES | AC-09 | Test |
| ES-09 | Journal chaîné, écriture seule, ancrage, vérification en console | AC-09, 10 | Test + démonstration |
| ES-10 | Anti-rejeu par nonce serveur | AC-11 | Test |
| ES-11 | mTLS cœur↔PDP, fail-closed > CAP_PROB, rate limiting | AC-12 | Test |
| ES-12 | RBAC console, authentification analyste, justification obligatoire | AC-09 | Test |
| ES-13 | RETIRÉE (v1.2) — remplacée par ES-13b | — | — |
| ES-13b | Notification au titulaire à tout changement d'IMSI ; notification informative à l'appareil précédent à tout ré-enrôlement | AC-04, 08 | Test |
| ES-14 | RETIRÉE (v1.2) — remplacée par ES-14b | — | — |
| ES-14b | Révocation immédiate de l'ancienne clé ; une seule clé ACTIVE | AC-08 | Test |
| ES-17 | RETIRÉE (v1.2) — remplacée par ES-17b | — | — |
| ES-17b | Aiguillage enrôlement / ré-enrôlement sur la seule présence d'une clé | AC-13 | Test |
| ES-18 | Code de récupération : affichage unique, empreinte seule, consommé et régénéré | AC-08 | Test |
| ES-19 | Ré-enrôlement : ~~code de récupération puis PIN~~ PIN puis code de récupération (SRS v1.4), jamais d'OTP | AC-08, 13 | Test |
| ES-20 | Unicité de la clé ACTIVE ; signature par clé révoquée refusée | AC-08 | Test |
| ES-21 | Délai croissant sur l'appareil demandeur, jamais sur le compte | AC-14, 15 | Test |
| ES-22 | Réponses uniformes, aucune inférence d'existence ou d'état | AC-16 | Test |
| ES-23 | Capture d'écran interdite sur la vue du code de récupération | AC-08 | Test + démonstration |
| ES-24 | Alerte console au-delà d'ALERT_REENROL, sans restriction de compte | AC-15 | Test |
| ES-25 | Liaison d'un appareil seulement après validation de la preuve (OTP au premier enrôlement, code de récupération au ré-enrôlement) (v1.3) | AC-02, 13 | Test |
| ES-26 | Aucune logique d'aiguillage dans l'application ; le PDP décide (v1.3) | AC-02 (variante app) | Test + inspection |
| ES-27 | Alerte avec réponse signée sur l'appareil actuellement lié ; « ce n'est pas moi » → FROZEN + gel des ré-enrôlements ; appareil révoqué : information seule (v1.3) | AC-02, 15 | Test |
| ES-28 | Alerte de sécurité sans lien, code ni demande de saisie (v1.3) | AC-16 | Inspection + test |

---

## 12. Décision de validation

Révision 1.2 (07/09/2026) : la revue de conception des 30 et 31 août a démonté la logique de ré-enrôlement de la v1.1 sur quatre constats — vérification empirique de l'application déployée (trois appareils simultanés, aucune notification, fonctionnement sans SIM), faille de la confirmation par l'ancien appareil, faille de l'expiration automatique vers la confiance, pénalisation de la victime par le refus du premier enrôlement. Le remplacement par un code de récupération à affichage unique (ADR-029), une limitation sans déni de service (ADR-030) et l'unicité de la clé (ADR-031) est fondé sur les référentiels OWASP et sur les pratiques établies des clés à affichage unique. Le profil T-02 (agent complice), introduit en v1.0 par déduction, est désormais chiffré par déclaration publique de l'opérateur : moins de 5 % des fraudes sont initiées par ses propres agents (conférence de presse MTN Cameroun, Douala, septembre 2025). Le cas de mars 2026 constitue l'occurrence réelle documentée d'AC-08. Les contacts de confiance sont retirés du parcours applicatif et DIFFÉRÉS pour le canal USSD (QO-19).

Révision 1.1 : la revue du SRS (D-01) a révélé qu'un veto FREEZE total pendant 72 h était indéfendable en friction ; il est remplacé par une transition PROBATION dont les violations sont les vrais vetos. La revue (D-02) a révélé un cas d'abus manquant, AC-13, qui retournait le device binding contre la victime. Ces deux corrections rendent le modèle plus faible en apparence et plus vrai en substance.

Le modèle couvre les cinq attaquants plausibles du contexte camerounais, inclut un scénario de limite (AC-07) et un scénario de victime consentante (AC-01) pour empêcher toute sur-affirmation, et fixe des paramètres initiaux justifiés et mesurables. Il est **approuvé par l'encadrement technique** comme base du SRS partie III et du protocole d'évaluation.

Actions de suivi :
1. Présentation à Engr. Moune Daniel avec le dossier de cadrage ; recueillir ses objections par écrit.
2. Toute modification ultérieure passe par un ADR et incrémente la version.
3. Les ES sont reprises dans le SRS v1.2 ; les AC-01 à AC-16 deviennent les scénarios du module d'injection du générateur et les tests d'intégration `T-AC-01` à `T-AC-16`.
