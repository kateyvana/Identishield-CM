# Carte B7 · Préparation écrite de l'étape « vrai téléphone » (6 novembre 2026)

Date : 2026-10-25. Couloir B (Mobile). Carte #50. **Aucun code** : liste exacte de ce qu'il faut,
qui le fait, et ce qui dépend de la propriétaire. Référence : ADR-036 (Tailscale, amendement du
2026-10-23 à signer), ADR-038 (autorité épinglée), L7 (`2026-10-23-limites-declarees-L7.md`),
scénario B3b (`2026-10-24-scenario-demonstration-B3b.md`), comptes B8
(`2026-10-24-comptes-demonstration-B8.md`), charte qualité mobile (CLAUDE.md §10).

## 1. Réseau — le téléphone atteint le cœur, et rien d'autre

| Besoin | Qui | État |
|---|---|---|
| Tailscale installé sur le téléphone de démonstration, connecté au **tailnet de la propriétaire** (le téléphone devient un nœud ; aucune autre exposition) | **propriétaire** (compte Tailscale, invitation du nœud) | non fait |
| Le cœur écoute 8443 sur l'adresse Tailscale de la VM (`isc-vm`, `100.93.166.19`) avec la feuille `core-mobile` (SAN `isc-core`, `isc-vm`, `isc-vm.tail3824f1.ts.net`, `100.93.166.19`) ; `CORE_MOBILETLS_ENABLED=true` ; 8080 refuse `/mobile/v1` | couloir A (déployé le 2026-10-24 20:00Z, ADR-036 point 5 validé) | fait, à revérifier le jour J |
| ACL Tailscale : le téléphone ne voit que la VM, port 8443 (pas le poste, pas 8080) | **propriétaire** (ACL du tailnet) | non fait |
| Hotspot / 4G du téléphone (pas le Wi-Fi du poste : Tailscale relaie ; une connexion directe est préférable mais le relais suffit à l'application) | couloir B le jour J | — |

## 2. Confiance — l'application ne parle qu'à notre cœur

| Besoin | Qui | État |
|---|---|---|
| Autorité locale (SPIKE-4) épinglée dans l'APK (`network_security_config`, ADR-038) ; **aucune autorité utilisateur** | couloir B (déjà dans l'APK ; vérifier que l'autorité embarquée est celle qui a signé `core-mobile`) | à vérifier contre `infra/certs` de la VM |
| Nom attendu par l'application = un SAN de la feuille (`isc-vm.tail3824f1.ts.net` ou `100.93.166.19`) | couloir B | à fixer avec l'adresse choisie |

## 3. APK — construit contre la VM

| Besoin | Qui | État |
|---|---|---|
| `API_BASE_URL = https://<adresse Tailscale de la VM>:8443/mobile/v1` dans le BuildConfig (saveur de démonstration, jamais la saveur du simulateur) | couloir B | à construire après « VM à toi » |
| Paramètres nommés dans le BuildConfig lus de `config/ruleset.yaml` (POLL_FOREGROUND, APP_NETWORK_TIMEOUT, APP_CONN_KEEPALIVE, LOCK_*, QUICK_AMOUNTS) : même version de ruleset que la VM | couloir B | vérifier `rs-0.10.0` déployé |
| APK **debug** signé par la clé debug du poste (voie C, apksigner ; décision fixe) ; installé par `adb install` ; Android ≥ version requise ; verrou d'écran configuré ; attestation matérielle réelle (TEE : SPIKE-1 OK sur 2 appareils) | couloir B | — |
| Si le PDP refuse la vraie attestation : **ARRÊT**, rien n'est assoupli (ordre de la propriétaire) | couloir B | — |

## 4. Données — comptes de démonstration

| Besoin | Qui | État |
|---|---|---|
| Les 20 comptes B8 amorcés (CSV hors dépôt dans `/home/isc/.isc/`, `ISC_DEMO_SEED_CSV` dans `.env` VM) ; PIN connus de la propriétaire seule | couloir A (déploiement) + propriétaire (CSV) | 25 noms constatés le 2026-10-24 |
| Compte de démonstration du scénario : **677000200** (Hervé ESSOMBA) ; bénéficiaire tiers **677001101** ; HLR renseigné (IMSI `624010…`) pour que `/evaluate` ne refuse pas (A5-00) | couloir A | à confirmer |
| Aucun enrôlement préalable du compte de démonstration dans le PDP (décision : flux A réel, pas d'amorçage) | — | acquis |
| **Solde de 677000200 : 0 FCFA aujourd'hui** (constat A, 2026-10-25). **Décision du superviseur technique (2026-10-25)** : ni cash-in d'opérateur (aucune route ne l'expose), ni CSV (le seeder pose le solde à la création seulement), ni SQL. Dotation par un **virement P2P réel de 20 000 FCFA depuis 677000123** (compte technique, 152 300 FCFA, hors portes automatiques), exécuté par le couloir A sous témoin par le simulateur USSD de la VM, de jour, avec son entrée d'audit | couloir A (sous témoin) | non fait |
| PIN de 677000200 : celui des fixtures de démonstration (rien à demander à la propriétaire) | — | acquis |
| HLR : 677000200 → `624010677000200`, 677001101 → `624010677001101` (seeder B8) : A5-00 satisfait | couloir A (constaté) | fait |
| IMSI attaquant d'AC-02 réel **DISTINCT** de celui des portes automatiques (décision du 2026-10-25) : par exemple `624010000000667` (`--attacker-imsi`), 677000007 garde `624010000000666` ; plus aucune contrainte d'exclusion avec `check-stack` / `verify_attacker` | couloir A | décidé |

## 5. Scénario du 6 novembre (dans cet ordre, chaque étape observée)

1. **Flux A** (premier enrôlement) : numéro → PIN → OTP (lu dans la console `/sms`, côté serveur) → attestation réelle acceptée par le PDP → code de récupération affiché une fois → confirmation → accueil (état TRUSTED, solde).
2. **Flux D** (ré-enrôlement) sur le même téléphone après désinstallation : numéro → PIN → code de récupération → nouvelle clé, ancienne révoquée ; alerte `DEVICE_REBOUND` informative (B5) visible si un second téléphone est disponible.
3. **Virement ALLOW** vers 677001101 : nonce → signature → `/evaluate` → EXECUTED ; historique et solde mis à jour ; preuve `proofType=SIGNATURE` dans le journal d'audit (console). **Contrainte (superviseur, 2026-10-25) : UN seul virement, de jour (hors 23:00–05:00 heure de Douala, `NIGHT` / `NIGHT_ZONE`), montant strictement inférieur à 50 000 FCFA** — au-delà, ou de nuit, le score entre en bande STEP_UP et ce n'est plus l'étape (e).
4. **AC-02 refusé** : réattribution de la SIM du 677000200 par l'outil HLR (couloir A, console) → alerte `SIM_CHANGED` sur le téléphone lié ; tentative de virement depuis l'application de l'attaquant (second téléphone ou émulateur avec la SIM « volée ») → ré-enrôlement exigé → sans code : refus uniforme, délai croissant, alerte `REENROL_ATTEMPT` ; « ce n'est pas moi » sur le téléphone lié → compte FROZEN (**dépend de la route PDP de A, L-B5-1**) ; USSD de l'attaquant → STEP_UP bloqué. **Oracle : aucun débit.**
5. Vérifications charte §10 : rotation, mort du processus (retour des Réglages), police 200 %, tablette, arrière-plan 61 s → verrou.

## 6. Ce qui dépend de la propriétaire

- Tailscale sur le téléphone et ACL du tailnet (§1).
- CSV des comptes et PIN de démonstration (§4) ; compte retenu avec le couloir A : **677000200**
  (victime d'AC-02 = le compte lié au vrai téléphone) ; **solde de 677000200 à doter** (cash-in
  d'opérateur ou CSV) avant le virement ALLOW.
- Signature des amendements : ADR-036 (Tailscale), ADR-041 (routes `nonce`/`verify`, `GET /trust`,
  `not-me`), ADR-042 (PBKDF2 44 000).
- Décision sur la fenêtre de la démonstration (durée, présence du couloir A pour l'outil HLR).
