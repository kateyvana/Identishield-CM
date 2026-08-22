# Journal de défauts — Revue formelle ISC-SRS v1.1 (FR/EN)

| Champ | Valeur |
|---|---|
| Objet | ISC-SRS-FR v1.1 / ISC-SRS-EN v1.1 ; ISC-TM v1.0 (par ricochet) |
| Relecteur | Encadrement technique (revue hostile) |
| Date | 20 août 2026 |
| Méthode | Lecture exigence par exigence ; recherche de contradictions, termes non définis, exigences orphelines, mauvaise allocation, cas de bootstrap, populations oubliées |
| Résultat | 21 défauts : 3 CRITIQUES, 6 MAJEURS, 8 MINEURS, 4 OBSERVATIONS — tous traités |
| Conséquence | SRS v1.1 rejeté. Correctifs intégrés dans ISC-TM v1.1 et ISC-SRS v1.2 (FR/EN). Nouvelle revue requise avant APPROVED. |

Vocabulaire (ISO/IEC/IEEE 29148, revue d'exigences) : **INCOHÉRENCE** = deux énoncés ne peuvent être vrais ensemble ; **OMISSION** = exigence ou définition manquante ; **AMBIGUÏTÉ** = énoncé admettant plusieurs lectures ; **OBSERVATION** = remarque sans correction requise.

---

## CRITIQUES — tranchés par l'encadrement (décisions ADR-012, 013, 015)

### D-01 — INCOHÉRENCE — Contradiction entre ES-01 (veto FREEZE) et la machine à états (PROBATION plafonnée)
- **Où** : ES-01, ES-04, ISC-TM §7 (CAR_IMSI), AC-04, UC-04 politique P2.
- **Fait** : ES-01 impose FREEZE pour **toute** sortie sensible pendant CAR_IMSI (72 h). ISC-TM §7 et UC-04 P2 prévoient une exécution **plafonnée** à CAP_PROB. Les deux ne peuvent pas être vrais.
- **Conséquence** : un remplacement de SIM légitime (téléphone volé, SIM défectueuse) bloque **tout** retrait pendant trois jours. Friction réelle massive ; QR-4 prédit < 1 % de transactions gênées, ce qui est incompatible avec un blocage total.
- **Options** :
  - (a) IMSI change → compte **FROZEN** 72 h, zéro sortie (protection maximale, friction maximale, fidèle au TM v1.0).
  - (b) IMSI change → compte **PROBATION** 72 h : sorties ≤ CAP_PROB vers bénéficiaires connus autorisées ; tout le reste FREEZE. AC-02 (solde entier vers inconnu) reste bloqué par ES-04. AC-04 à 48 h avec nouveau bénéficiaire reste bloqué.
  - (c) (b) + levée anticipée par contact de confiance.
- **Recommandation** : (c). Elle est plus défendable devant un jury que (a), elle mesure quelque chose (QR-4), et elle reflète ce que fait réellement M-Pesa (suspension des transactions *significatives*, pas du service).
- **Correctif** : ES-01 devient « IMSI change → transition PROBATION_IMSI » ; ES-04 s'applique ; AC-02, 04, 05, 06 attendent FREEZE **via ES-04** (nouveau bénéficiaire ou dépassement de plafond), pas via un veto total. ISC-TM §7 et §8 mis à jour → v1.1. ADR-012.
- **Décision** : option (c). ADR-012. Après changement d'IMSI le compte entre en PROBATION (CAR_IMSI) : sorties ≤ CAP_PROB vers bénéficiaires connus autorisées ; tout le reste FREEZE ; levée anticipée par contact de confiance ou signature d'un dispositif ACTIVE.
- **Statut** : CLOS — intégré ISC-TM v1.1, ISC-SRS v1.2.

### D-02 — OMISSION — Problème de bootstrap : le premier enrôlement n'est pas protégé
- **Où** : UC-01 (non détaillé), EF-20, ES-02.
- **Fait** : rien ne dit dans quelles conditions le **premier** dispositif est accepté. Si un attaquant fait un SIM swap sur un compte qui n'a jamais enrôlé d'app (cas majoritaire au Cameroun), il installe l'app, passe PIN + OTP, et devient le **premier dispositif de confiance**. Le device binding se retourne contre la victime.
- **Correctif** : nouvelle exigence ES-17 : « Un premier enrôlement DOIT être refusé si un changement d'IMSI date de moins de CAR_IMSI ; il DOIT placer le compte en PROBATION pendant CAR_ENROL ; il DOIT notifier les contacts de confiance s'il en existe. » UC-01 détaillé obligatoire. Nouveau cas d'abus AC-13 « Premier enrôlement post-swap ».
- **Décision** : ADR-013. Premier enrôlement REFUSÉ si un changement d'IMSI date de moins de CAR_IMSI ; sinon accepté avec PROBATION de CAR_ENROL et notification des contacts de confiance existants. Nouveau cas d'abus AC-13.
- **Statut** : CLOS.

### D-03 — OMISSION — La population USSD-only ne peut pas désigner de contacts de confiance
- **Où** : EF-23 (app seulement), ES-06 politique P3, UC-06.
- **Fait** : ES-06 P3 (confirmation par contact de confiance) est la seule politique STEP_UP forte sur USSD. Or la désignation de contacts n'existe que dans l'app (EF-23). La majorité des utilisateurs ciblés n'aura donc jamais cette protection. ES-06 P3 est **inapplicable à sa population cible**.
- **Correctif** : EF-44 « Le canal USSD DOIT permettre la désignation et la révocation de contacts de confiance (menu dédié, confirmation par le contact sur son propre canal) » ; ou via agent KYC (UC-11 étendu). Le protocole d'évaluation DOIT mesurer P3 sur des agents USSD-only.
- **Décision** : ADR-015. Les deux : menu USSD dédié (confirmation par le contact sur son propre canal) et agent KYC simulé. Le protocole d'évaluation mesure P3 sur des agents USSD-only.
- **Statut** : CLOS.

---

## MAJEURS — corrigés dans la v1.2

### D-04 — OMISSION — « IMSI de référence » non défini
- **Où** : ES-01, glossaire.
- **Fait** : l'IMSI de référence est-il celui de l'enrôlement ? du dernier état TRUSTED ? du dernier changement expiré ? Sans définition, ES-01 est intestable.
- **Correctif** : glossaire : « IMSI de référence : dernier IMSI dont la carence est expirée sans incident. » Mise à jour automatique à la sortie de PROBATION.

### D-05 — AMBIGUÏTÉ — Facteurs d'authentification par canal non spécifiés
- **Où** : EF-03, EF-21, UC-02.
- **Fait** : sur le canal app, la transaction exige-t-elle PIN + OTP + signature, ou PIN + signature ? Si l'OTP reste requis sur app, il reste un vecteur d'ingénierie sociale inutile. Non tranché.
- **Correctif** : tableau « facteurs par canal » en §6 : USSD = PIN + OTP ; app = PIN + signature de dispositif, **sans OTP** (la signature remplace l'OTP, conformément à [N2]). ADR-013.

### D-06 — INCOHÉRENCE — Mauvaise allocation : ES-16 attribue la carence au cœur
- **Où** : ES-16 vs ES-03.
- **Fait** : ES-03 dit que le **PDP** applique la carence ; ES-16 dit que le **cœur** l'applique. Deux composants pour une même responsabilité.
- **Correctif** : ES-16 réécrite : « Le PDP DOIT appliquer la carence indifféremment au motif ; le cœur NE DOIT PAS transmettre le motif comme entrée de décision (il le journalise seulement). »

### D-07 — AMBIGUÏTÉ — Charge non définie pour ENF-01
- **Où** : ENF-01 « sous charge du générateur ».
- **Fait** : « charge » n'a pas de valeur. 5 tx/s ou 500 tx/s changent l'architecture.
- **Correctif** : scénario de qualité SQ-01 : « 50 transactions/s soutenues pendant 10 min, p95 < 500 ms, p99 < 800 ms (aligné sur le timeout EI-01) ». Valeur à confirmer par le protocole.

### D-08 — OMISSION — « Localité » non définie dans le sandbox
- **Où** : ES-05e, EF-10, EAR-01.
- **Fait** : aucune exigence ne dit ce qu'est une localité simulée (identifiant de cellule ? ville ? quartier ?) ni comment le générateur l'attribue.
- **Correctif** : ED-10 « Localité = identifiant de cellule simulée (entier) ; chaque agent possède 1 à 3 cellules habituelles ; le générateur modélise les déplacements. » Sinon ES-05e passe en W.

### D-09 — AMBIGUÏTÉ — Vocabulaire : « dispositif TRUSTED »
- **Où** : ES-08.
- **Fait** : TRUSTED est un état de **compte** ; un dispositif est PENDING / ACTIVE / REVOKED. ES-08 mélange les deux.
- **Correctif** : « signature d'un dispositif ACTIVE d'un compte en état TRUSTED ».

---

## MINEURS

### D-10 — MINEUR — Agrégation du score non spécifiée
- ES-05a dit « agrégeant » sans dire somme, max, ou pondération. Acceptable au niveau SRS ; **doit** être tranché dans le SAD (ADR-014) et versionné (EF-17).

### D-11 — MINEUR — FPR de H2 sans granularité
- « FPR < 5 % » : global, par canal, par persona ? Le commerçant de nuit (AC-06 limite) fera exploser un FPR global. Correctif : « FPR < 5 % par persona, reporté par canal ».

### D-12 — MINEUR — Deux « 10 minutes » différentes
- ENF-05 (installation < 10 min) et CR-04 (démo < 10 min) : à distinguer explicitement.

### D-13 — MINEUR — Langue de la console non spécifiée
- EF-26/27 couvrent l'app seulement. Ajouter EF-38 : console en français, anglais DEVRAIT.

### D-14 — MINEUR — UC-01 et UC-06 non détaillés
- UC-01 est le cas cryptographiquement critique (voir D-02). À détailler. UC-06 dépend de D-03.

### D-15 — MINEUR — EF-19 en priorité C alors que OBJ-4 est un objectif
- Cohérent seulement parce que EAR-08 permet la comparaison hors ligne. À écrire explicitement : « la comparaison règles/ML est hors ligne (EAR-08) ; le mode ombre (EF-19) est un bonus de démonstration ».

### D-16 — MINEUR — Plage MSISDN fictive non spécifiée
- CT-02 et ED-09 disent « plage fictive documentée » sans la donner. Correctif : ED-09 fixe la plage (ex. 6 99 000 000 – 6 99 009 999 hors plans réels — à vérifier contre le plan de numérotation ART avant adoption).

### D-17 — MINEUR — Notification : sémantique absente
- EF-09 et ES-13 exigent des notifications sans dire : contenu minimal, canal par destinataire, nombre de tentatives, expiration. Renvoyé au SAD mais une exigence de contenu minimal manque : « toute notification de sécurité DOIT contenir : type d'événement, horodatage, action possible, et NE DOIT PAS contenir de lien cliquable » (anti-hameçonnage).

---

## OBSERVATIONS

### D-18 — OBSERVATION — Volume ED-07
- 500 000 transactions / 1 000 agents / 180 jours ≈ 2,8 transactions par agent et par jour. Plausible avec des commerçants et agents dans la population ; à confirmer par EAR-06.

### D-19 — OBSERVATION — Rejeu sur USSD
- ES-10 ne s'applique qu'au canal app. Le rejeu d'une session USSD n'est pas une menace distincte (session synchrone). Aucun correctif ; à écrire dans ISC-TM §9 comme exclusion justifiée.

### D-20 — OBSERVATION — Bloc d'approbation
- « Encadrement technique — Rédigé » est honnête et doit le rester. ENF-15 couvre la déclaration.

### D-21 — OBSERVATION — RACI
- La colonne « Outillage IA » reste inhabituelle pour un jury. Elle est justifiée par ENF-15. Ne pas la retirer : la retirer serait une dissimulation.

---

## Plan de correction

1. Décisions D-01 à D-03 tranchées par l'encadrement (ADR-012, 013, 015) ; le candidat peut les contester par ADR.
2. ISC-TM → v1.1 : §7 (transition IMSI), §8 (AC-13, attendus AC-02/04/05/06 via ES-04), §9 (exclusion rejeu USSD), §11 (ES-17).
3. ISC-SRS-FR/EN → v1.2 : tous les correctifs ci-dessus ; historique des révisions mis à jour ; numéros d'exigences conservés (aucune suppression ; ES-01 et ES-16 passent en RETIRÉE et sont remplacées par ES-01b et ES-16b pour respecter la règle d'immuabilité).
4. Nouvelle revue par le candidat ; passage en APPROVED si zéro CRITIQUE et zéro MAJEUR ouvert.
