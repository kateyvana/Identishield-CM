# IdentiShield-CM — Modèle de menace

| Champ | Valeur |
|---|---|
| Version | 1.1 — Révisé suite à la revue du SRS (journal de défauts D-01, D-02, D-03, D-19) |
| Date | 20 août 2026 |
| Méthode | STRIDE (Microsoft SDL) appliqué aux flux de données + scénarios d'attaque + cas d'abus |
| Statut | APPROVED (encadrement technique) — à présenter à Engr. Moune Daniel |
| Documents aval | SRS (§6ter, §7bis), SAD, protocole d'évaluation, suite de tests de sécurité |

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
| S-03 | SIM swap + vidage immédiat via l'app réinstallée sur le téléphone de l'attaquant | T-02 | app | Refus : absence de signature valide (ES-02) ; enrôlement refusé (ES-17) |
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

---

## 7. Décisions de paramétrage (tranchées)

Chaque valeur est un **paramètre de configuration** du PDP, pas une constante. Les valeurs ci-dessous sont les valeurs initiales du jeu de règles v1 ; le protocole d'évaluation mesurera leur sensibilité.

| Paramètre | Valeur v1 | Justification | Sensibilité à mesurer |
|---|---|---|---|
| Carence post-changement d'IMSI (CAR_IMSI) | **72 h** en état PROBATION (pas FROZEN) : sorties ≤ CAP_PROB vers bénéficiaires connus autorisées ; tout le reste FREEZE ; levée anticipée par contact de confiance ou signature d'un dispositif ACTIVE (ADR-012) | Fenêtre observée des vidages : minutes à 24 h (Safaricom 2021 ; FHNW 2025). 72 h couvre l'attaquant patient à 48 h (S-04) avec marge ; Safaricom applique une suspension « pendant une période » non publiée ; Sayibu 2025 confirme l'efficacité des hold periods. | Courbe rappel/friction pour 24 h, 48 h, 72 h, 7 j |
| Carence post-ré-enrôlement (CAR_ENROL) | **48 h**, levée immédiate si confirmation par ancien dispositif ou contact de confiance | Le ré-enrôlement est un événement plus rare et mieux contrôlé que le swap ; 48 h suffit si la probation plafonne les sorties. | Idem |
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

**Décision sur le premier enrôlement (ADR-013) :** un premier enrôlement est REFUSÉ si un changement d'IMSI date de moins de CAR_IMSI ; sinon il place le compte en PROBATION pendant CAR_ENROL. Sans cette règle, le device binding se retourne contre la victime (AC-13).

**Décision sur le poids du comportemental vs du cryptographique :** le changement d'IMSI est un **veto de transition** (il force PROBATION) ; la signature est un **veto d'exécution** ; la violation des restrictions de PROBATION est un **veto FREEZE** ; les règles comportementales produisent un **score** (0–100) avec seuils : < 40 ALLOW, 40–70 STEP_UP, > 70 FREEZE. Les vetos court-circuitent le score.

---

## 8. Cas d'abus (AC-01 à AC-12) — rédaction complète

Gabarit : Scénario | Acteur | Préconditions | Déroulé | Résultat sans contre-mesure | Contre-mesures | Sources | Test attendu.

### AC-01 — Ingénierie sociale sur la victime elle-même
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-01 / T-01 |
| Préconditions | La victime est au téléphone avec un faux « service client » ; elle tient son propre appareil |
| Déroulé | 1. L'attaquant lui dicte une séquence USSD ou des gestes dans l'app 2. La victime initie elle-même un transfert ou un retrait vers un compte/ATM de l'attaquant 3. Elle communique l'OTP reçu |
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
| Contre-mesures | ES-01b (changement IMSI → PROBATION) ; ES-03 (CAR_IMSI 72 h) ; ES-04 (solde entier vers bénéficiaire inconnu viole le plafond et la règle de nouveau bénéficiaire → FREEZE) ; ES-05 en secours |
| Sources | Safaricom 2021 ; Rest of World 2023 ; FHNW 2025 ; MINPOSTEL |
| Test | T-AC-02 : FREEZE, latence p95 < 500 ms |

### AC-03 — Vidage post-SIM swap via l'application réinstallée
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-03 / T-02 |
| Préconditions | Comme AC-02 ; l'attaquant installe l'app sur son propre téléphone |
| Déroulé | 1. Tentative d'enrôlement du nouvel appareil 2. OTP + PIN acceptés par un système naïf 3. Transfert |
| Sans contre-mesure | Identique à AC-02 |
| Contre-mesures | ES-02 : la clé du nouvel appareil n'est pas la clé enrôlée → refus de signature ; l'enrôlement d'un second appareil déclenche S-08 (PROBATION) ; ES-17 refuse tout enrôlement sous CAR_IMSI ; ES-01b place le compte en PROBATION |
| Sources | NIST 800-63B §5.1.3.3 (liaison numéro ↔ appareil physique) ; SIM binding |
| Test | T-AC-03 : refus cryptographique (ES-02) et refus d'enrôlement (ES-17) |

### AC-04 — Attaquant patient (48 h après le swap)
| Champ | Contenu |
|---|---|
| Scénario / acteur | S-04 / T-05 |
| Préconditions | Comme AC-02 ; l'attaquant sait qu'une carence existe |
| Déroulé | 1. Swap 2. Silence 48 h (la victime n'a pas remarqué, ou pense à une panne réseau) 3. Transfert |
| Sans contre-mesure | Carence de 24 h : attaque réussit. Carence de 72 h : bloquée. |
| Contre-mesures | ES-03 CAR_IMSI = 72 h ; ES-05 nouveau bénéficiaire + montant → STEP_UP même après la carence ; **notification à la victime sur un canal alternatif (contact de confiance) dès le swap** — l'information est la meilleure arme contre la patience |
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
| Déroulé | 1. Demande de ré-enrôlement 2. OTP + PIN 3. Nouvelle clé enregistrée 4. Transfert |
| Sans contre-mesure | Le device binding est contourné par sa propre procédure de récupération |
| Contre-mesures | ES-04 : compte → PROBATION (CAR_ENROL 48 h, CAP_PROB, nouveaux bénéficiaires interdits) ; ES-03 ; levée anticipée seulement par ancien dispositif (signature) ou contact de confiance ; notification à tous les contacts de confiance ; l'ancienne clé est révoquée **à la fin** de la probation, pas au début (l'ancien appareil, s'il réapparaît, peut annuler le ré-enrôlement) |
| Sources | Principe « la récupération ne doit pas être plus faible que l'authentification » (NIST 800-63B §6.1.2 : changement de numéro = liaison d'un nouvel authentifiant) ; Apple recovery contacts (récupération sociale) |
| Test | T-AC-08 : transfert > CAP_PROB pendant la probation → FREEZE ; nouveau bénéficiaire → FREEZE ; après confirmation par contact → TRUSTED |

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
| Contre-mesures | ES-17 : premier enrôlement refusé si changement d'IMSI < CAR_IMSI ; sinon PROBATION CAR_ENROL + notification des contacts de confiance (désignés via USSD, EF-44) |
| Sources | Construit à partir de D-02 ; principe NIST [N2] §6.1.2 |
| Test | T-AC-13 : enrôlement refusé à t+1 h après swap ; accepté en PROBATION à t+73 h ; transfert > CAP_PROB pendant la probation → FREEZE |

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
| ES-03 | Carences CAR_IMSI (72 h) et CAR_ENROL (48 h) | AC-04, 08 | Test |
| ES-04 | Machine à états TRUSTED / PROBATION / FROZEN / LOCKED avec CAP_PROB | AC-04, 08 | Test + inspection du SAD |
| ES-05 | Score comportemental (bénéficiaire, montant, VEL, NIGHT) → seuils 40/70 | AC-01, 05, 06, 07 | Test + évaluation |
| ES-06 | Politiques STEP_UP USSD (DEFER, plafond, contact de confiance) | AC-01, 02, 04, 05 | Test + évaluation (QR-2) |
| ES-07 | Gel avec TTL 24 h et annulation à expiration | AC-02, 12 | Test |
| ES-08 | Levée de gel : hiérarchie de preuves, quatre-yeux > THR_4EYES | AC-09 | Test |
| ES-09 | Journal chaîné, écriture seule, ancrage, vérification en console | AC-09, 10 | Test + démonstration |
| ES-10 | Anti-rejeu par nonce serveur | AC-11 | Test |
| ES-11 | mTLS cœur↔PDP, fail-closed > CAP_PROB, rate limiting | AC-12 | Test |
| ES-12 | RBAC console, authentification analyste, justification obligatoire | AC-09 | Test |
| ES-13 | Notification hors-bande (contacts de confiance) à tout événement IMSI ou ré-enrôlement | AC-04, 08 | Test |
| ES-14 | Révocation différée de l'ancienne clé (fin de probation) | AC-08 | Test |
| ES-17 | Premier enrôlement : refus si changement d'IMSI < CAR_IMSI ; sinon PROBATION CAR_ENROL | AC-13 | Test |

---

## 12. Décision de validation

Révision 1.1 : la revue du SRS (D-01) a révélé qu'un veto FREEZE total pendant 72 h était indéfendable en friction ; il est remplacé par une transition PROBATION dont les violations sont les vrais vetos. La revue (D-02) a révélé un cas d'abus manquant, AC-13, qui retournait le device binding contre la victime. Ces deux corrections rendent le modèle plus faible en apparence et plus vrai en substance.

Le modèle couvre les cinq attaquants plausibles du contexte camerounais, inclut un scénario de limite (AC-07) et un scénario de victime consentante (AC-01) pour empêcher toute sur-affirmation, et fixe des paramètres initiaux justifiés et mesurables. Il est **approuvé par l'encadrement technique** comme base du SRS partie III et du protocole d'évaluation.

Actions de suivi :
1. Présentation à Engr. Moune Daniel avec le dossier de cadrage ; recueillir ses objections par écrit.
2. Toute modification ultérieure passe par un ADR et incrémente la version.
3. Les ES sont reprises dans le SRS v1.2 ; les AC-01 à AC-13 deviennent les scénarios du module d'injection du générateur et les tests d'intégration `T-AC-01` à `T-AC-13`.
