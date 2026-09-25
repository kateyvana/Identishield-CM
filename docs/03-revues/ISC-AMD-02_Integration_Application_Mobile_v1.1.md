# ISC-AMD-02 — Intégration de l'application mobile dans les documents de conception

**Version :** 1.1 — **Date :** 2026-09-25
**Étudiante :** YENGUE MESSAH KATE YVANA — ICTU20234409
**Statut :** À APPLIQUER par la session Claude Code du couloir Mobile, sur une seule branche, après fusion de T3-00 (ADR-035) dans `origin/main`.
**Origine :** décisions du propriétaire du 25/09/2026 ; ISC-MOB-CDC v1.1 ; ISC-MOB-SPEC v1.1 ; rapport de vérification de la session Mobile (21 points).

## Journal v1.0 → v1.1
La v1.0 avait été écrite de mémoire de projet. La session Mobile l'a vérifiée contre le dépôt et a relevé 21 écarts. **La conception du propriétaire ne change pas.** La v1.1 aligne les documents mobiles sur ce qui existe déjà dans le dépôt et ferme cinq trous préexistants. Retirés de la v1.0 : les nouveaux champs dans `/evaluate` (le contrat reste inchangé), l'état SUSPECT, la quarantaine après réenrôlement, le pouvoir d'action de l'ancien téléphone, le paramètre HOLD_DELAY (remplacé par DEFER existant), l'identifiant « G1 » comme point ouvert, l'exigence ES-27 telle qu'écrite (reformulée), la mention « ED-11 existant ».

**Règle :** rien n'est supprimé. Chaque document touché passe à la version suivante avec un journal des changements ; l'ancien texte est barré, jamais effacé. Tout renvoi cité ici est **vérifié dans le dépôt** avant d'être appliqué ; un renvoi introuvable est signalé, jamais inventé.

---

## 1. Décisions fixées (rappel)

| N° | Décision | Source |
|---|---|---|
| D-1 | Numéro puis PIN, puis le serveur décide. Au quotidien le numéro est mémorisé (PIN ou empreinte). | propriétaire, 25/09 |
| D-2 | L'application ne décide rien ; « le serveur » = cœur (API mobile, PIN, OTP, alertes) + PDP (DeviceRegistry, RecoveryCodeStore, EnrolmentThrottle, SignatureVerifier). | propriétaire + SAD §5 |
| D-3 | L'OTP n'existe qu'au premier enrôlement. | propriétaire |
| D-4 | Liaison après validation (OTP ou clé de récupération), jamais avant. | propriétaire |
| D-5 | Une seule clé active (ADR-031) ; réenrôlement = remplacement ; ancien téléphone **informé sans action** (EF-47, ADR-010). | propriétaire + dépôt |
| D-6 | Réenrôlement : tous les cas indistincts ; clé de récupération exigée. | propriétaire |
| D-7 | REENROL_ATTEMPTS (2) puis délai croissant sur l'origine (LOCK_BASE→LOCK_MAX, réglable, ex. 24 h) ; jamais de blocage du compte (ADR-030). | propriétaire + ADR-030 |
| D-8 | Pas de quarantaine après réenrôlement (ES-03c, SAD §7.5). | dépôt |
| D-9 | Charge signée = chaîne ADR-018 `accountId\|type\|amountXaf\|beneficiaryId\|nonce` ; signature vérifiée par le PDP (veto ES-02 → REJECTED) ; nonce selon ES-10. Contrat `/evaluate` inchangé. | dépôt (ADR-018 FERME) |
| D-10 | Réponses uniformes pour compte inexistant / PIN faux (ES-22). | dépôt |
| D-11 | STEP_UP sur le canal application = paramètre **DEFER** existant. | reco adoptée, réversible |
| D-12 | API mobile = routes du cœur ; **aucun service supplémentaire** ; exposition réseau restreinte (ADR-036). | reco adoptée, réversible |
| D-13 | Opérateur MTN, plage 677000000–677009999, IMSI 624-01 (ADR-035, branche t3-00). Horizon 6 mois. | propriétaire |
| D-14 | Limite Kanban « In Progress » = 2, une carte par couloir, dès maintenant. | propriétaire |
| D-15 | Proposition « confirmer sur le téléphone lié une opération USSD en STEP_UP » : **mise de côté**, non intégrée. | propriétaire |

---

## 2. Changements par document

### 2.1 ISC-SRS-FR v1.3 → v1.4

| Action | Où | Quoi |
|---|---|---|
| Réécrire (barré + nouveau) | ES-19 | Ordre au réenrôlement : ~~clé de récupération puis PIN~~ → **PIN puis clé de récupération** (D-1). |
| Réécrire | UC-01 | ~~OTP puis PIN~~ → **numéro, PIN, puis OTP** (D-1, D-3). |
| Vérifier | ES-03c, ES-13b, ES-14b, ES-17b, ES-18…ES-24, EF-45/46/47 | Confirmer qu'aucune n'exige un OTP hors premier enrôlement ; si c'est le cas, barrer avec motif D-3. Confirmer EF-47 (ancien appareil : information seule) — **inchangé**. |
| Ajouter | **ES-25** | « La liaison d'un appareil n'est effectuée qu'après validation par le serveur de la preuve exigée (OTP au premier enrôlement, clé de récupération au réenrôlement). Aucune clé n'est enregistrée avant. » (D-4) |
| Ajouter | **ES-26** | « L'application mobile ne contient aucune logique distinguant enrôlement, réenrôlement et ouverture de session ; le PDP détermine l'étape suivante à partir de la présence d'une clé enregistrée et de la validité de la signature reçue. » (D-2) |
| Ajouter | **ES-27** (reformulée) | « Sur le téléphone actuellement lié, une alerte de changement de SIM ou de tentative de réenrôlement offre une réponse signée "c'est moi / ce n'est pas moi". "Ce n'est pas moi" passe le compte en FROZEN et gèle les réenrôlements pendant REENROL_FREEZE. Un téléphone révoqué ne reçoit que des notifications informatives (EF-47). » |
| Ajouter | **ES-28** | « Une alerte de sécurité ne contient jamais de lien, de code ni de demande de saisie ; elle n'offre qu'une réponse binaire signée. » |
| Ajouter | **ED-11** | « L'écran de confirmation d'un envoi affiche le nom du titulaire du bénéficiaire, avec une recherche freinée. » (audit du 30/08, point A-xx à citer) |
| Ajouter | Section « Application mobile » | Renvoi vers ISC-MOB-CDC v1.1 (MOB-F, MOB-S, MOB-NF). Le SRS référence, il ne recopie pas. |
| Paramètres | §Paramètres nommés | **Corriger le renvoi « définis dans ISC-TM §7 »** pour REENROL_ATTEMPTS, LOCK_BASE, LOCK_MAX (ils n'y sont pas) : les définir ici. Ajouter REENROL_FREEZE, ENROL_TTL, APP_SESSION_TTL, POLL_FOREGROUND, POLL_BACKGROUND ; vérifier OTP_ATTEMPTS, OTP_RESENDS, OTP_TTL ; nommer la validité du nonce d'ES-10 si elle n'a pas de nom. Annoter LOCK_MAX « réglable par le propriétaire ». Barrer CAR_ENROL s'il subsiste. |
| Plage | ED-09 | Confirmer 677000000–677009999 (déjà fait par T3-00 ; sinon l'appliquer ici). |
| Horizon | ED-07 | Confirmer « ≥ 6 mois », décision du 25/09. |
| Points ouverts | §Points ouverts | QM-05 (clé perdue, = audit G1), QM-14 (anti-force-brute PIN), QF-01 (quarantaine après premier enrôlement, non par défaut). |
| Propriétaire | En-tête | Harmoniser : **YENGUE MESSAH KATE YVANA** (comme le PowerPoint). |

### 2.2 ISC-SAD v1.2 → v1.3

| Action | Où | Quoi |
|---|---|---|
| Ajouter | §5 Composants | **Application mobile (Android, Kotlin)** : garde la clé privée dans le Keystore, signe, affiche les décisions, reçoit les alertes. **Routes API mobile** `/mobile/v1/*` dans le cœur : **pas de service supplémentaire**, le SAD garde ses deux services applicatifs. Responsabilités inchangées : DeviceRegistry, RecoveryCodeStore, EnrolmentThrottle, SignatureVerifier restent dans le PDP. |
| Ajouter | Déploiement | Téléphone physique → exposition réseau restreinte des routes mobiles du cœur (Wi-Fi local fermé, aucune sortie Internet, TLS avec la CA du bac à sable épinglée) → cœur → PDP. Renvoi ADR-036. |
| Ajouter | Séquences | Les 6 diagrammes `.mmd` v1.1 livrés (aiguillage, premier enrôlement, session, envoi, réenrôlement, alerte SIM) sous `docs/02-specifications/mobile/diagrams/` ; les référencer, ne pas les redessiner. |
| Corriger | §1bis OB-3 | ~~ALTER DEFAULT PRIVILEGES … REVOKE EXECUTE FROM PUBLIC~~ → « REVOKE EXECUTE explicite par fonction + contrôle de pile » (mesure T2-01, E-01). |
| Confirmer | §8.2, ADR-018 | La chaîne signée du canal application est **la même** que celle déjà définie : `accountId|type|amountXaf|beneficiaryId|nonce`. Aucun format nouveau. |
| Confirmer | Contrat `/evaluate` | **Inchangé.** Diff vide exigé. Le PDP calcule lui-même le changement d'IMSI et le nouveau bénéficiaire. |
| Ajouter | Machine à états §7 | Aucun état nouveau. « Ce n'est pas moi » (téléphone lié) → FROZEN. Le réenrôlement ne déclenche pas PROBATION (§7.5, inchangé). |
| Ajouter | Événements d'audit | Liste d'ISC-MOB-SPEC §14, dans le format de chaîne existant. |
| Index ADR | — | ADR-035, ADR-036 (PROPOSÉ), ADR-037 (PROPOSÉ, versions à fixer à l'échafaudage). |
| Propriétaire | En-tête | Harmoniser : YENGUE MESSAH KATE YVANA. |

### 2.3 Modèle de menace v1.2.1 → v1.3

| Action | Quoi |
|---|---|
| Ajouter | Les menaces MT-01…MT-08 d'ISC-MOB-SPEC §16, **renumérotées S-xx** (nomenclature des scénarios du TM), avec parade et résiduel. |
| Ajouter | **AC-17 — Fenêtre d'amorçage** : attaquant ayant SIM + PIN **avant** tout enrôlement lie son propre téléphone. Parade : OTP + PIN ; aucune parade par liaison ; QF-01 en option. Résiduel **déclaré**. (Vérifier d'abord si AC-13 le couvre déjà ; si oui, l'étendre au lieu de créer AC-17.) |
| Réviser | AC-02, variante « canal application » : l'attaquant installe l'app, numéro + PIN, tombe sur la clé de récupération, échoue, est freiné, le téléphone lié est alerté. Oracle : **aucun débit**. |
| Réviser | Toute ligne où l'OTP est cité comme contrôle **après** enrôlement : barrer, motif D-3. |
| Confirmer | AC-08/13/14/15/16, T-02 : inchangés. |
| Propriétaire | Harmoniser : YENGUE MESSAH KATE YVANA. |

### 2.4 ADR

| ADR | Action |
|---|---|
| ADR-010, 018, 029, 030, 031 | Inchangés. Ajouter une ligne « Confirmé par ISC-AMD-02 v1.1 le 25/09/2026 ». Sur ADR-030 : « LOCK_MAX réglable par le propriétaire (ex. 24 h) ». |
| ADR-035 | Déjà sur t3-00 ; **préalable** à cet amendement. |
| **ADR-036 (nouveau, PROPOSÉ)** | **Exposition réseau restreinte du cœur pour le téléphone physique.** Contexte : isc-net internal, aucune sortie ; un téléphone doit atteindre `/mobile/v1/*`. Décision : publication restreinte de ces seules routes sur un point d'accès Wi-Fi local sans Internet ; TLS avec la CA du bac à sable épinglée ; **aucun conteneur ni service supplémentaire**. Conséquences : pas de FCM (interrogation périodique), pas de vérification de révocation d'attestation ; limites déclarées. |
| **ADR-037 (nouveau, PROPOSÉ)** | **Pile mobile.** Kotlin, Jetpack Compose, Android Keystore, AndroidX Biometric, OkHttp/Retrofit, kotlinx.serialization, DataStore, JUnit/Turbine/MockWebServer, outil de faux serveur ; côté serveur, bibliothèque de lecture d'attestation à nommer. **Versions exactes : à fixer à l'échafaudage (M-05)** — l'ADR est créé maintenant pour que les documents qui le citent passent `check-docs`, et sera complété. Aucune de ces bibliothèques n'est utilisée avant la clôture de l'ADR. |

### 2.5 Outils et configuration

| Fichier | Action |
|---|---|
| `config/ruleset.yaml` | Ajouter REENROL_ATTEMPTS, LOCK_BASE, LOCK_MAX, ALERT_REENROL, CODE_LENGTH (cités par le SRS, absents : trou préexistant fermé). Ajouter REENROL_FREEZE, ENROL_TTL, APP_SESSION_TTL, POLL_FOREGROUND, POLL_BACKGROUND avec des valeurs proposées, marquées « à calibrer ». **Retirer CAR_ENROL** (retiré du SRS en v1.3). La porte `named-params` doit rester verte. |
| `tools/gen_traceability.py` + CLAUDE.md §5 | Étendre la plage acceptée de AC-01…AC-13 à **AC-01…AC-17** (AC-14 à AC-16 existent déjà dans le TM : trou préexistant fermé). |
| `CLAUDE.md` | §1 : plage 677 (si T3-00 ne l'a pas fait). Ajouter la règle « make check VERT sur la branche, prouvé dans le rapport, avant toute fusion ». Ajouter la règle des deux couloirs (chaque session sur ses branches, jamais main ; limite In Progress 2). Propriétaire : YENGUE MESSAH KATE YVANA. |
| Plan Mois 3 | v1.1 → v1.2 : limite In Progress 2 dès maintenant (D-14) ; ajouter le couloir Mobile (cartes M-01…M-10). |

### 2.6 Nouveaux documents à versionner

| Document | Emplacement (confirmé) |
|---|---|
| ISC-MOB-CDC v1.1 (.docx) et ISC-MOB-SPEC v1.1 (.docx) | `docs/02-specifications/mobile/` (le `.docx` fait référence ; `check-docs` le lit directement ; un export `.md` est facultatif) |
| 6 `.mmd` + 6 `.png` v1.1 | `docs/02-specifications/mobile/diagrams/` |
| ISC-AMD-02 v1.1 (ce fichier) | `docs/03-revues/`, à côté d'AMD-01 |
| Les v1.0 des documents mobiles | **Ne pas les déposer** : elles n'ont jamais été committées ; la v1.1 est la première version versionnée. Le journal des versions à l'intérieur des documents garde la trace de la v1.0. |

### 2.7 Kanban

- Couloir **Mobile** (étiquette `mobile`), cartes **M-01 à M-10** d'ISC-MOB-CDC §13 ; carte « AMD-02 : appliquer » en In Progress (couloir Mobile).
- Limite In Progress **2** (D-14).

---

## 3. Vérifications à exécuter (résultats dans le rapport)

1. `git log origin/main --oneline | head` contient la fusion de T3-00 (ADR-035, plage 677). **Sinon STOP.**
2. `grep -rn "OTP" docs/` : chaque occurrence classée « premier enrôlement / USSD (EF-03, conservé) / autre (barré, motif D-3) ».
3. `grep -rn "699" docs/ core/ simulator/ attacker/ console/ tools/ CLAUDE.md` : aucune plage active résiduelle.
4. `git diff origin/main -- contracts/evaluate.yaml` : **vide**.
5. `grep -rn "SUSPECT\|HOLD_DELAY\|TS_WINDOW\|PROBATION_DAYS\|ACCOUNT_NOT_FOUND" docs/02-specifications/mobile/` : rien (termes retirés en v1.1).
6. `grep -n "CAR_ENROL" config/ruleset.yaml` : rien.
7. Les 6 `.mmd` se rendent (`npx -y @mermaid-js/mermaid-cli` ou `mmdc` si installé) ; sinon, le signaler et comparer les PNG livrés (SHA-256) aux images du `.docx`.
8. `python tools/gen_traceability.py` accepte AC-17.
9. `make check` VERT, sur la branche, **avant** toute demande de fusion.
10. Tableau « renvoi cité → trouvé / introuvable / différent », sans exception.

---

## 4. Prompt pour la session Mobile

```
TÂCHE AMD-02 v1.1 — Appliquer l'intégration de l'application mobile (couloir Mobile)

PRÉCONDITION (vérifie, sinon STOP et attends) :
  git fetch origin && git log origin/main --oneline | head -5
  → la fusion de T3-00 (ADR-035, plage 677) doit y être.

CONTEXTE
Ta vérification (21 points) a été traitée : ISC-MOB-CDC v1.1, ISC-MOB-SPEC v1.1,
6 diagrammes v1.1 et ISC-AMD-02 v1.1 sont dans C:\Users\uiooi\Downloads\.
La conception du propriétaire ne change pas ; les documents sont alignés sur le
dépôt (ADR-018, ES-02, ES-03c, ES-22, EF-47, ADR-010, DEFER, états réels).
Tes trois questions sont réglées : ADR-036 et ADR-037 s'écrivent dans CETTE
branche (PROPOSÉ) ; emplacements confirmés ; In Progress = 2.

RÈGLES
- Abandonne mobile-00-depot-docs sans commit. Une seule branche :
  `git checkout -b amd-02-integration-mobile origin/main` ; `git branch --show-current`.
- Tu ne touches jamais simulator/, data/, docs/03-donnees/, ni une branche t3-*.
- Rien n'est supprimé : version suivante + journal ; ancien texte barré.
- Chaque renvoi d'ISC-AMD-02 v1.1 est VÉRIFIÉ avant d'être touché ; introuvable
  ou différent → dans le rapport, jamais inventé. Toute contradiction que tu
  découvres : tu la décris, tu ne tranches pas.
- Pas de push, pas de PR, pas de création de cartes : le propriétaire le fait
  après relecture.

À FAIRE, dans cet ordre
1. Déposer les documents v1.1 selon §2.6 (les v1.0 ne sont pas déposées).
2. ADR-036 et ADR-037 (PROPOSÉ) selon §2.4.
3. ruleset.yaml, gen_traceability.py, CLAUDE.md selon §2.5.
4. SRS v1.3 → v1.4 (§2.1). 5. SAD v1.2 → v1.3 (§2.2). 6. TM v1.2.1 → v1.3 (§2.3).
7. Lignes de confirmation sur ADR-010/018/029/030/031.
8. Plan Mois 3 v1.2 (§2.5).
9. Les 10 vérifications de §3, résultats collés.
10. make check VERT sur la branche, prouvé.

RAPPORT ATTENDU
- `git branch --show-current` ; `tree docs/ -L 2`
- Tableau « renvoi cité → trouvé / introuvable / différent »
- Résultats des 10 vérifications
- Fichiers modifiés avec leur nouvelle version
- Contradictions rencontrées, décrites, non tranchées

STOP après le rapport : pas de fusion, pas de push.
```
