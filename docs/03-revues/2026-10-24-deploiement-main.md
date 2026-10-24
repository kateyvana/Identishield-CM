# Plan de déploiement du nouveau `main` dans la VM — écrit AVANT exécution

**Couloir A, 2026-10-24.** Ordre du superviseur technique (point 3 du carnet du 24/10). Ce plan est
écrit avant toute action ; il est exécuté **une seule fois**, après la fusion de la PR #88 (B4), pour
qu'il n'y ait qu'**une** migration de la base de la VM (002, 003 et 005 d'un coup), pas deux. Toute
preuve est **collée** dans le compte rendu d'exécution (section finale), jamais résumée.

## État constaté avant (2026-10-24, 16:3x UTC)

- VM `isc-vm` (100.93.166.19) : pile debout sur `main` **8929a3f** (5 conteneurs, `check-stack: VERT`
  à 15:41Z), aucune migration jamais appliquée (`infra/init/migrations/` absent de ce commit).
- Dépôt VM `~/identishield-cm` : `git ls-remote origin` répond (rc=0) → **`git pull` est possible**
  (le DNS de la VM répond aujourd'hui ; sinon, repli par `git bundle` comme le 16/10).
- `infra/.env` de la VM : **`ISC_MOBILE_BIND` absent**. `make up` lance `infra/gen-secrets.sh`, qui
  l'ajouterait avec le défaut **`127.0.0.1`** (`gen-secrets.sh`, ligne 22) : le 8443 serait alors lié
  à la boucle locale, invisible du tailnet. Il faut donc **écrire `ISC_MOBILE_BIND=100.93.166.19`
  avant `make up`** (décision de la propriétaire : APK réel par Tailscale).
- Volume `isc-certs` : CA et feuilles `pdp`, `core` de 2026-10-16 ; **pas de feuille `core-mobile`**.
- Images Docker présentes dans la VM (`docker images`, 17:34Z) : `postgres:16`, `eclipse-temurin:21-jdk-jammy`,
  `eclipse-temurin:21-jre-jammy`, `python:3.12-alpine`, `node:22-alpine`, `alpine:3.20`, `isc-*`.
  **`postgres:16-alpine` — l'image du service `migrate` — est ABSENTE** (signalé par le couloir B,
  CONSTATÉ), et le registre n'est pas joignable (`docker pull postgres:16-alpine` : expiration à 25 s,
  rc 124). Le service `migrate` passe donc sur **`postgres:16`**, l'image déjà présente et celle du
  serveur PostgreSQL lui-même (même `psql`, une image de moins) ; `tools/verify_migrations.sh`
  utilisait déjà `postgres:16`. Sans ce changement, `isc-migrate` ne démarrerait pas si le registre
  n'est pas joignable, et le cœur et le PDP non plus (ils l'attendent).
- Chaîne d'audit : 120 280 entrées à 15:48Z (export de référence : 120 276 entrées,
  sha `4ab894f0…c567`, `2026-10-24-export-chaine-audit.md`).
- Compte 677000001 **gelé** (VEL) jusqu'au **2026-10-25 15:48:31Z** ; `check-stack` ROUGE d'ici là.

## Préconditions (toutes vérifiées, sinon on n'exécute pas)

| # | Précondition | Qui | Comment on le voit |
|---|---|---|---|
| P1 | PR #88 (B4) fusionnée, CI de `main` verte | propriétaire | `gh pr view 88 --json state` = MERGED ; `gh run list --branch main` = success |
| P2 | **Feuille `core-mobile` créée sur une CA existante.** `infra/ca/gen-ca.sh` (main 15a1808, lignes 25–28) sort en « REUTILISATION » dès que `ca.crt` existe, **sans signer la feuille manquante**. Or le cœur **ne démarre pas** sans `/certs/core-mobile.crt` (`core/src/main/resources/application.yml`, lignes 63–69, `MobileTlsConfig`). Sur la VM, le déploiement tel quel **casserait le cœur**. Défaut de L7-b (couloir A), trouvé en écrivant ce plan. Correctif : signer toute feuille **absente** avec la CA existante (la CA, épinglée par les téléphones, n'est pas touchée) ; preuve rouge/vert sur un volume jetable ; PR fusionnée **avant** le déploiement. | couloir A | PR `t4-l7-b-feuille-manquante` fusionnée ; preuve collée |
| P3 | Règle `DOCKER-USER` coupant la sortie du sous-réseau `isc-mobile` (L7-3) | propriétaire | commandes exactes en § P3 ci-dessous ; `sudo iptables -S DOCKER-USER` montre les deux règles (sudo exige son mot de passe : `isc` ne l'a pas sans mot de passe) |
| P4 | Règle d'accès Tailscale (L7-1) : évaluateurs → `100.93.166.19:8443` seulement | propriétaire | règle ACL exacte en § P4 ci-dessous ; preuve depuis un nœud « évaluateur » (carte #66) — **non bloquante pour ce déploiement**, bloquante pour inviter un évaluateur |
| P5 | PR #92 (#66, console sur la boucle locale) fusionnée, pour la preuve « 3000 refusé » | propriétaire | **fusionnée** (a5121df, 17:03Z) |
| P6 | Témoin `~/VM-OCCUPEE` absent, puis posé par A avec heure de fin | couloir A | `cat ~/VM-OCCUPEE` |
| P7 | **Les 20 comptes de démonstration** (carte #76, PR #90) : `~/.isc/seed-comptes-demo.csv` déposé par la propriétaire dans la VM, dossier **700**, fichier **600**. On vérifie la présence et les droits par `ls -l`, **jamais le contenu** (il porte des PIN). | propriétaire (dépôt), couloir A (vérification) | CONSTATÉ 2026-10-24 16:3x : `drwx------ 2 isc isc 4096 Oct 24 16:32 /home/isc/.isc` ; `-rw------- 1 isc isc 909 Oct 24 16:32 /home/isc/.isc/seed-comptes-demo.csv` ; 21 lignes (en-tête + 20) |

## Étapes, dans l'ordre (chaque commande seule ; aucune suppression enchaînée)

1. **Témoin** : `couloir: A / depuis / jusqu-a (+60 min) / déploiement main <sha>` ; prévenir B.
2. **Export de la chaîne d'audit** (précondition du README des migrations) :
   `docker stop isc-core isc-pdp` → `bash tools/export_audit_chain.sh ~/audit-exports` → **VERT**
   exigé (0 rupture, ancre conforme) ; **sha consigné** ici. Les conteneurs restent arrêtés : le
   `make up` de l'étape 5 les recrée.
3. **Mise à jour du dépôt** : `cd ~/identishield-cm && git pull --ff-only origin main` → `git log -1`
   = le `main` attendu (sha collé). Fichiers hors suivi présents (`tools/bilan_mois2.sh`,
   `verif-t2-16.txt`) : laissés tels quels.
4. **`ISC_MOBILE_BIND`** : `grep -n ISC_MOBILE_BIND infra/.env || echo "ISC_MOBILE_BIND=100.93.166.19" >> infra/.env`
   puis `grep -n ISC_MOBILE_BIND infra/.env` (collé).
4 bis. **`ISC_DEMO_SEED_CSV`** (P7) : `grep -n ISC_DEMO_SEED_CSV infra/.env || echo "ISC_DEMO_SEED_CSV=/home/isc/.isc/seed-comptes-demo.csv" >> infra/.env`
   puis `grep -n ISC_DEMO_SEED_CSV infra/.env` (collé).
5. **Migrations + démarrage + comptes de démonstration = `make up`, et rien d'autre.** Il n'existe
   **pas** de cible `make migrate` (retirée par #86) : `isc-migrate` s'exécute à chaque `make up`
   (`docker-compose.yml`, service `migrate`), et `isc-core`/`isc-pdp` l'attendent en
   `service_completed_successfully`. **Une seule commande de démarrage** (tranché avec le couloir B,
   ordre du superviseur du 24/10) : `make up` inclut **automatiquement** la surcouche
   `infra/compose.b3a.yml` (profil Spring `demo`, montage du fichier des comptes) dès que
   `ISC_DEMO_SEED_CSV` est défini dans `infra/.env` — c'est le cas sur le serveur après l'étape 4 bis —
   et l'annonce (`profil demo : compose.b3a.yml inclus`). La commande manuelle
   `docker compose -f docker-compose.yml -f compose.b3a.yml up -d` n'est plus utilisée. Lancé en
   `nohup setsid sh -c "make up > ~/up-<date>.log 2>&1; echo FIN_MAKE_UP_RC=\$? >> ~/up-<date>.log"`,
   attendre la **ligne de fin**. Migrations **attendues, exactement** (journal `docker logs isc-migrate`) :
   ```
   [apply-migrations] 002-b3a-premier-enrolement.sql
   [apply-migrations] 003-holder-name.sql
   [apply-migrations] 005-b4-session-liveness.sql
   [apply-migrations] 3 migration(s) appliquee(s) depuis /opt/isc/migrations
   ```
   (004 n'existe pas encore : elle sera appliquée au `make up` qui suivra sa fusion — mécanisme de
   rejeu intégral, `infra/init/migrations/README.md`, § Application.) `docker logs isc-ca-init` doit
   montrer la feuille `core-mobile` (P2). `docker ps` : 5 conteneurs `Up`, `isc-pdp`/`isc-core` `healthy`.
   **Si le cœur ne démarre pas à cause du fichier des comptes** (fichier invalide : le cœur refuse
   **par conception**, `DemoSeedCsv`) : `docker logs isc-core` nomme le **fichier, la ligne, le champ
   et la cause** (jamais un PIN) ; la propriétaire corrige **son** fichier sur son poste, le redépose
   (`scp`, puis `chmod 600`), et on relance `make up`. On n'affiche **jamais** le fichier, ni par
   `cat`, ni par `docker exec`, ni dans un rapport ; on ne le modifie jamais depuis une session.
6. **Preuves, depuis le poste (tailnet)** :
   - `openssl s_client -connect 100.93.166.19:8443 -servername isc-vm.tail3824f1.ts.net </dev/null 2>/dev/null | openssl x509 -noout -subject -issuer -ext subjectAltName`
     → `subject=CN=isc-core`, `issuer=CN=IdentiShield-CM Local CA`, SAN avec `DNS:isc-vm.tail3824f1.ts.net`
     et `IP:100.93.166.19` ; puis `openssl s_client … -CAfile <ca.crt copié depuis la VM>` →
     `Verify return code: 0 (ok)` ;
   - dans la VM, `docker port isc-core` → une seule ligne, `8443/tcp -> 100.93.166.19:8443` ; depuis le
     poste `curl -m 5 http://100.93.166.19:8080/` → refusé (**8080 non publié**) ;
   - `curl -m 5 http://100.93.166.19:3000/` → refusé ; tunnel `ssh -N -L 3000:127.0.0.1:3000 isc@100.93.166.19`
     puis `curl -m 5 -o /dev/null -w '%{http_code}' http://localhost:3000/` → `200` (si P5) ;
   - dans la VM : `verify_attacker` et `verify_ac02_protected` (comptes 677000007/677000008, **non
     gelés**), `verify_console*`, `verify_layerless` ; **pas** `check-stack` (voir ci-dessous).
6 bis. **Preuve des 25 comptes** (5 techniques + 20 de démonstration), avec le couloir B, sous **un
   seul témoin** (celui de A, élargi) : `docker logs isc-core | grep "amorçage démo"` → la ligne
   `amorçage démo : 5 comptes techniques, 20 comptes de démonstration, noms des titulaires alignés`
   (collée) ; puis la **preuve de rejouabilité** de B (`2026-10-24-comptes-demonstration-B8.md`) :
   second démarrage du cœur (`docker restart isc-core` ou `make up`), **même ligne**, et la requête
   de comptage de B sous `isc_admin` donne le même nombre — **sans** afficher d'empreinte de PIN ni
   de PIN ; seuls les MSISDN, les noms et les soldes peuvent figurer au rapport.
7. **Second export** : `docker stop isc-core isc-pdp` → `export_audit_chain.sh` → `docker start isc-pdp isc-core`.
   Exigé : VERT, **nombre d'entrées ≥ celui de l'étape 2**, et les N premières lignes identiques au
   premier export (`head -n <N+1> second.csv | sha256sum` = sha du premier) : la chaîne ancienne est
   intacte, seules des entrées ont été ajoutées.
8. **ADR-036 point 5**, rejoué avec le couloir B sur le cœur déployé : relever les routes exposées
   sur 8443 (`/mobile/v1/*` seulement) et prouver qu'aucune autre route du cœur n'est joignable
   depuis le tailnet (rouge/vert : une route hors `/mobile/v1` → refusée ; une route `/mobile/v1` → servie).
9. **Retrait du témoin** ; compte rendu collé ci-dessous ; cartes déplacées.

## `check-stack` et le gel VEL

`check-stack` n'est **pas** lancé avant le **2026-10-25 15:48:31Z**. Le gel se lève **seul, sans
acte** : `TrustStateMachine.settle()` (appelé par `observed()` à chaque `/evaluate` et par
`current()` à chaque lecture) applique `TTL_FREEZE_EXPIRED` dès que `now ≥ freeze_until`, écrit la
transition dans le journal et sauve l'état. Le premier `/evaluate` de 677000001 après 15:48:31Z le
remet TRUSTED ; le `check-stack` qui suit est VERT. Aucune commande, aucune modification en base.
(`release()` existe mais n'a aucun appelant : il n'y a pas d'acte possible, et aucun n'est nécessaire.)

## P3 — règle `DOCKER-USER`, à poser par la propriétaire (sudo à mot de passe)

But (L7-3) : **aucune sortie** du sous-réseau `isc-mobile` vers l'extérieur, tout en laissant passer
les **réponses** aux connexions entrantes des téléphones sur 8443. Le pont de `isc-mobile` porte un
nom **fixe**, `br-isc-mobile` (`docker-compose.yml`, `driver_opts`), pour que la règle s'écrive une fois.
Le réseau n'existe qu'après le premier `make up` du nouveau `main` ; les règles peuvent être posées
avant (elles ne font référence qu'au nom du pont).

Dans la VM, une ligne à la fois :

```
sudo iptables -I DOCKER-USER 1 -i br-isc-mobile -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN
sudo iptables -I DOCKER-USER 2 -i br-isc-mobile -j DROP
sudo iptables -S DOCKER-USER
```

Sortie attendue de la vérification (l'ordre compte : RETURN avant DROP) :

```
-N DOCKER-USER
-A DOCKER-USER -i br-isc-mobile -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN
-A DOCKER-USER -i br-isc-mobile -j DROP
-A DOCKER-USER -j RETURN
```

Persistance au redémarrage de la VM (`iptables-persistent` n'est **pas** installé ; on n'ajoute pas
de paquet : une unité systemd rejoue les deux règles après Docker, sans doublon grâce à `-C`) :

```
sudo tee /etc/systemd/system/isc-docker-user.service >/dev/null <<'UNIT'
[Unit]
Description=IdentiShield-CM - DOCKER-USER : aucune sortie du sous-reseau isc-mobile (L7-3)
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/bin/sh -c '/usr/sbin/iptables -C DOCKER-USER -i br-isc-mobile -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN 2>/dev/null || /usr/sbin/iptables -I DOCKER-USER 1 -i br-isc-mobile -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN'
ExecStart=/bin/sh -c '/usr/sbin/iptables -C DOCKER-USER -i br-isc-mobile -j DROP 2>/dev/null || /usr/sbin/iptables -I DOCKER-USER 2 -i br-isc-mobile -j DROP'

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable --now isc-docker-user.service
sudo systemctl status isc-docker-user.service --no-pager
```

Sortie attendue : `Active: active (exited)`. Après un redémarrage de la VM, `sudo iptables -S DOCKER-USER`
redonne les quatre lignes ci-dessus.

Preuve fonctionnelle, une fois la pile démarrée (étape 5) : dans la VM,
`docker exec isc-core bash -c 'timeout 3 bash -c "</dev/tcp/1.1.1.1/443" && echo SORTIE_POSSIBLE || echo SORTIE_BLOQUEE'`
→ `SORTIE_BLOQUEE` ; et, depuis le poste, le 8443 répond toujours (étape 6). Le cœur garde son
chemin vers le PDP et PostgreSQL : il passe par `isc-net` (interne), pas par `br-isc-mobile`.

## P4 — règle d'accès Tailscale (ACL), à coller par la propriétaire dans la console Tailscale

PROPOSÉ (non testable depuis une session : seule la propriétaire a la console). Principe : les
appareils des évaluateurs portent l'étiquette `tag:isc-evaluateur` et ne peuvent joindre **que**
`100.93.166.19:8443` ; les appareils de la propriétaire gardent tout. Aucune règle SSH Tailscale.

```json
{
  "tagOwners": {
    "tag:isc-evaluateur": ["autogroup:admin"]
  },
  "acls": [
    { "action": "accept", "src": ["autogroup:member"], "dst": ["*:*"] },
    { "action": "accept", "src": ["tag:isc-evaluateur"], "dst": ["100.93.166.19:8443"] }
  ],
  "ssh": []
}
```

Mise en œuvre : *Access controls* → remplacer le fichier par ce JSON → *Save*. Chaque appareil
d'évaluateur rejoint le tailnet avec une **clé de pré-authentification étiquetée** `tag:isc-evaluateur`
(*Settings → Keys → Generate auth key → Tags*), générée par la propriétaire ; un appareil étiqueté n'a
pas d'identité d'utilisateur, il n'a que les droits de l'étiquette.

Preuve, depuis un appareil étiqueté `tag:isc-evaluateur` (collée dans ce document) :

```
nc -vz -w 3 100.93.166.19 8443   # attendu : succeeded / open
nc -vz -w 3 100.93.166.19 22     # attendu : timed out ou refused
nc -vz -w 3 100.93.166.19 3000   # attendu : timed out ou refused
```

Et depuis un appareil de la propriétaire : le 22 répond (SSH), le 3000 refuse (boucle locale, #66),
le 8443 répond. Tant que cette preuve n'est pas faite, **aucun évaluateur n'est invité** (carte #66).

## Ce qui n'est pas dans ce plan

- `make reset` : non (export fait, mais rien ne l'exige ; la chaîne vivante est conservée).
- Publication de l'APK (`make apk-publie`) : après la preuve 8443, acte séparé de la propriétaire.
- L'invitation d'un évaluateur sur le tailnet : interdite tant que #66 (P4) n'est pas close.

## Compte rendu d'exécution

_À remplir au moment de l'exécution, preuves collées._
