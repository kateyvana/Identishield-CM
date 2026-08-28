# Résultat du spike

## Question

Deux services conteneurisés peuvent-ils s'authentifier mutuellement par
certificat, avec une autorité générée automatiquement au démarrage, sans secret
versionné, sans intervention manuelle, et à un coût de latence compatible avec
ENF-01 ? → **OUI**, avec une obligation décisive sur le terminateur (voir ADR).

## Dispositif

- **VM (mesure publiable, §5.2)** : Intel i7-1185G7 @ 3,00 GHz, 4 vCPU, 7,8 Gio,
  Ubuntu 24.04.4, VirtualBox. Docker 29.7.2 / Compose v5.5.0. OpenSSL 3.3.7
  (alpine 3.20) pour la CA et le client ; nginx 1.27-alpine et
  `openssl s_server` comme terminateurs.
- **Certificats** : ECDSA P-256. CA racine auto-signée 10 ans ; certificats de
  service 1 an ; SAN + serverAuth/clientAuth. Générés au démarrage dans un
  volume Docker `certs` ; jamais dans le dépôt ni dans une image.
- **Composition** (propre au spike, `infra/` non touché) : `ca-init` (génère au
  démarrage), `pdp` (nginx mTLS), `pdp-strict` (openssl -Verify, = Spring Boot
  client-auth=need), `fakepdp` / `wrongpdp` (adversaires), `plain` (HTTP nu),
  `core` (client). Réseau `internal: true` (CT-02).
- Reproduction : `docker compose up -d --build` puis `core/tests.sh`,
  `core/bench.sh`, `core/lifecycle.sh`.

## Résultat

### Étape 1 — génération automatique (Q1, ENF-05)

Au premier `docker compose up`, `ca-init` génère la CA + tous les certificats
**sans aucune commande manuelle**. Les cinq services démarrent ; le PDP charge
son certificat. Clés en `-rw-------` dans le volume.

### Étape 2 — les quatre refus, avec niveau (Q2, Q3, Q4)

**Le niveau de refus est une propriété du TERMINATEUR, pas du mTLS.**

| # | Tentative | Terminateur nginx | Terminateur strict |
|---|---|---|---|
| 1 | sans certificat client | **HTTP 400 (applicatif)** `No required SSL certificate was sent` | **handshake** `alert certificate required` |
| 2 | autre autorité | **HTTP 400 (applicatif)** `The SSL certificate error` | **handshake** `alert unknown ca` |
| 3 | certificat expiré | **HTTP 400 (applicatif)** `The SSL certificate error` | **handshake** `alert certificate expired` |

Avec nginx, tentatives 1-3 refusées au niveau **applicatif** : la session TLS est
établie, la requête atteint le terminateur avant le 400. Avec le terminateur
strict (= comportement Spring Boot), refus au **handshake** : la requête
n'atteint jamais le service.

**Tentative 4 — sens mutuel, deux motifs distincts** (le cœur refuse un faux PDP) :
- **4a** faux PDP auto-signé, nom `pdp` correct → refus **handshake** sur la
  SIGNATURE : `curl (60) self-signed certificate`.
- **4b** serveur **valablement signé par la CA** mais nom `wrongpdp` → refus
  **handshake** sur le NOM : `curl (60) no alternative certificate subject name
  matches target hostname 'pdp'`.
Le cœur vérifie la chaîne ET le nom. Sans 4b, un service légitime du réseau
pourrait usurper le PDP.

### Étape 3 — acceptation, dans les deux sens

Appel légitime → `200 {"decision":"ALLOW","client":"CN=core","verify":"SUCCESS"}`
sur nginx ; `Verify return code: 0 (ok)` + « Client certificate » sur le strict.
Le PDP authentifie le cœur (DN client) ET le cœur authentifie le PDP
(`--cacert` valide le certificat serveur).

### Étape 4 — coût et cycle de vie (Q5, Q6, Q7, Q9)

**Latence** (1000 appels ; connexion-neuve mesurée contre le terminateur STRICT
= produit ; persistant contre nginx car s_server ne fait pas de keepalive — le
coût mTLS est une propriété du protocole) :

| Mode | contre | p50 | p95 | p99 |
|---|---|---|---|---|
| sans mTLS, persistant | plain | < 0,1 ms | 0,85 ms | 1,11 ms |
| mTLS, connexion-neuve | **STRICT** | 21,3 ms | 65,8 ms | 86,6 ms |
| mTLS, connexion-neuve | nginx | 43,6 ms | 77,3 ms | 106,1 ms |
| **mTLS, persistant** | nginx keepalive | < 0,1 ms | **2,54 ms** | 25,1 ms |

**Surcoût net (mode réel = persistant)** : p95 2,54 − 0,85 = **1,7 ms**, soit
**0,34 % du budget ENF-01 (500 ms p95)** → **sous 5 %**, ADR-021 confirmé sans
réserve (§8). En connexion-neuve (strict), p95 65,8 ms = **13,2 %** du budget :
sous les 20 %, mais montre que les connexions persistantes sont nécessaires.

- **Q6 reprise de session** : **active** (nginx `ssl_session_cache` + tickets
  TLS 1.3). Ticket de 1831 octets ; 2e connexion `Reused, TLSv1.3`. (Le premier
  test avait échoué : en TLS 1.3 le ticket arrive après la poignée de main.)
- **Q7 expiration** : certificat serveur valide 12 s → avant : `http=200` ;
  après : `curl (60) SSL certificate problem: certificate has expired`. Message
  compréhensible. Conduite à tenir : porte de validité dans `make check-stack`
  qui échoue si un certificat expire dans moins de N jours.
- **Q9 redémarrage — MESURÉ, pas déduit** : `down` puis `up` (volume conservé) →
  ca-init « RÉUTILISATION », empreinte CA **identique** (F2=F1). `down -v` puis
  `up` (volume détruit) → « génération », empreinte **différente** (F3≠F1),
  certificats antérieurs invalidés.
- **Démarrage à froid** : `up` avec génération = **13,6 s** ; avec réutilisation
  = **10,3 s** ; la génération de la CA ajoute ~3,3 s. Très en dessous des
  10 min d'ENF-05.
- **Q8 clés privées** : absentes du **dépôt** (`git ls-files` : aucune) ET des
  **images** (les seuls `.pem` sont le magasin public de l'OS ; `grep BEGIN
  PRIVATE KEY` vide dans `spike4-tools` et `nginx:1.27-alpine`). Vérifié, pas
  supposé. Les clés ne vivent que dans le volume runtime.

### Réponses Q1–Q9

- Q1 : oui, génération au premier `up`, sans action manuelle.
- Q2 : dépend du terminateur — nginx = applicatif (400), strict = handshake.
- Q3 : oui, certificat d'une autre autorité refusé.
- Q4 : oui, le cœur refuse un faux PDP, pour deux motifs séparés (signature 4a,
  nom 4b).
- Q5 : surcoût persistant 1,7 ms p95 = 0,34 % du budget ; connexion-neuve
  65,8 ms = 13,2 %.
- Q6 : reprise de session active (Reused, TLSv1.3).
- Q7 : `certificate has expired`, message clair ; remède = porte check-stack.
- Q8 : aucune clé dans le dépôt ni les images.
- Q9 : réutilisation au `down`/`up`, régénération au `down -v` — les deux mesurés.

## Décision (ADR)

Proposition : **ADR-021 passe en FERME** (l'acte appartient au propriétaire),
avec ces OBLIGATIONS :

1. mTLS cœur↔PDP retenu ; le jeton partagé reste rejeté (n'authentifie qu'un
   sens ; surcoût mTLS négligeable, donc pas de motif de l'adopter).
2. **Le terminateur TLS du PDP DOIT rejeter au niveau de la poignée de main** ;
   un refus applicatif (nginx 400) est un défaut, pas une variante acceptable.
   Le PDP Spring Boot (`server.ssl.client-auth=need`) satisfait cette exigence.
3. **Vérification obligatoire au mois 4** : rejouer les quatre tentatives sur le
   PDP Spring Boot réel, avec le même protocole et le même tableau de niveaux.
4. **Le client DOIT vérifier la chaîne ET le nom**, testés séparément (4a/4b).
5. Autorité et certificats générés automatiquement au démarrage, dans un volume ;
   aucune clé dans le dépôt ni dans une image.
6. Le cœur DOIT utiliser des connexions PERSISTANTES vers le PDP (surcoût 0,34 %
   du budget vs 13,2 % en connexion-neuve).
7. Certificats ECDSA P-256 ; CA 10 ans, feuilles 1 an ; **porte de validité dans
   `make check-stack`** qui échoue si un certificat expire dans moins de N jours
   (prévention de l'incident du jour J).
8. Comportement au redémarrage documenté : `down`/`up` réutilise l'autorité ;
   `down -v` la régénère et invalide les certificats antérieurs.

## Statut

**RÉUSSI AVEC RÉSERVES.**

- ES-11 tient : authentification mutuelle prouvée (chaîne + nom, deux sens),
  génération automatique, aucun secret versionné.
- ENF-01 : surcoût de latence en mode réel (persistant) = 1,7 ms p95 = 0,34 %
  du budget de 500 ms.
- ENF-05 : `make up` avec génération = 13,6 s, très en dessous de 10 min.
- Réserves / obligations : (1) le niveau de refus dépend du terminateur → le
  produit DOIT rejeter au handshake, à re-tester sur Spring Boot au mois 4 ;
  (2) porte de validité de certificat dans check-stack pour le jour J ; (3)
  connexions persistantes obligatoires.
- Jour de la soutenance : mTLS n'ajoute pas de risque perceptible (surcoût
  invisible en persistant) ; le seul risque est l'expiration, couvert par la
  porte check-stack.
