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

### Préconditions constatées (2026-10-24, 17:58Z → 18:28Z)

- P1 : #88 fusionnée (d4ff537, 17:03Z) ; `main` = **33979d2** (#91, #94), CI `success`.
- P2 : #93 fusionnée (a958f97). P5 : #92 fusionnée (a5121df). P7 : fichier présent (voir § État constaté).
- P3 — **posé par la propriétaire** (`sudo bash ~/isc-p3-docker-user.sh`, script déposé par A, une
  ligne à taper), sortie collée telle quelle :

```text
>>> 1. règles (sans doublon)
>>> 2. vérification : iptables -S DOCKER-USER
-N DOCKER-USER
-A DOCKER-USER -i br-isc-mobile -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN
-A DOCKER-USER -i br-isc-mobile -j DROP
>>> 3. persistance : unité systemd isc-docker-user.service
● isc-docker-user.service - IdentiShield-CM - DOCKER-USER : aucune sortie du sous-reseau isc-mobile (L7-3)
     Loaded: loaded (/etc/systemd/system/isc-docker-user.service; enabled; preset: enabled)
     Active: active (exited) since Sat 2026-10-24 18:27:16 UTC; 5ms ago
    Process: 283146 ExecStart=/bin/sh -c ... -j RETURN (code=exited, status=0/SUCCESS)
    Process: 283149 ExecStart=/bin/sh -c ... -j DROP (code=exited, status=0/SUCCESS)
>>> P3 : TERMINÉ.
```

  Écart avec la sortie annoncée au § P3 : **pas** de ligne finale `-A DOCKER-USER -j RETURN` — cette
  version de Docker ne la pose pas ; la fin de chaîne revient au même (retour à `FORWARD`). Les deux
  règles sont dans l'ordre exigé. **Incident annexe** : le mot de passe `sudo` de `isc`, donné
  oralement le 2026-08-29 (SPIKE-5), figurait dans les notes locales d'une session ; effacé le
  2026-10-24, absent du dépôt et de son historique (`git grep`, `git log -S`) ; à **changer** par la
  propriétaire (`passwd`).
- P4 : non fait (acte de la propriétaire dans la console Tailscale) ; non bloquant pour déployer,
  bloquant pour inviter un évaluateur.
- P6 : témoin du couloir B présent jusqu'à 19:05:43Z (preuves B3b sur bases jetables) : le
  déploiement attend son retrait.

### Exécution (2026-10-24, à partir de 18:56Z)

**Étape 1 — témoin** : `couloir: A / depuis: 2026-10-24T18:56:14Z / jusqu-a: 2026-10-24T19:56:14Z`
(retrait de celui de B à 18:5x, annoncé par B). Pile avant : 5 conteneurs `Up`, `main` 8929a3f.

**Étape 2 — export (rédacteur arrêté)** : `docker stop isc-core isc-pdp`, puis :

```text
export            : /home/isc/audit-exports/audit-export-2026-10-24T185639Z.csv
entrées exportées : 120280
sha256            : 975ee9ed12406fea2ea2d9b59384f3fc68f43b6377ca36803a07755e908ae405
ruptures (verify) : 0
ancre             : entry_count=120280 (réel 120280), last_seq=120280 (réel 120280), head_matches=true
export-audit: VERT (chaîne intacte, ancre conforme, export complet)
```

**Étape 3 — dépôt** : `git pull` → `fatal: unable to access … Could not resolve host: github.com`
(le DNS de la VM, qui répondait à 16:3x, ne répondait plus). **Repli prévu** : bundle depuis le
poste (`git bundle create … 8929a3f..origin/main`, `git bundle verify` OK des deux côtés), fetch +
`merge --ff-only` → `33979d28 Merge pull request #91`. `infra/init/migrations/` : 002, 003, 005.

**Étapes 4 / 4 bis — `.env`** : `6:ISC_MOBILE_BIND=100.93.166.19` ; `7:ISC_DEMO_SEED_CSV=/home/isc/.isc/seed-comptes-demo.csv`.

**Étape 5 — premier `make up` (19:01Z) : ÉCHEC, construction de l'image du cœur** :

```text
profil demo : compose.b3a.yml inclus (ISC_DEMO_SEED_CSV dans infra/.env)
#40 0.341 Downloading https://services.gradle.org/distributions/gradle-8.14.3-bin.zip
#40 20.55 Exception in thread "main" java.net.UnknownHostException: services.gradle.org
target core: failed to solve: … "./gradlew --no-daemon clean bootJar" did not complete successfully: exit code: 1
FIN_MAKE_UP_RC=2
```

Cause : DNS de la VM. `isc-core`/`isc-pdp` étaient arrêtés (étape 2) → **service rétabli d'abord**
(`docker start isc-pdp isc-core`, anciens conteneurs, `isc-pdp (healthy)` à 19:0xZ). Diagnostic :
`resolvectl` → `Current DNS Server: 192.168.1.1` (routeur Wi-Fi du portable, injoignable depuis le
NAT VirtualBox : 5 résolutions sur 5 en échec à ~4 s), alors que `nslookup … 10.0.2.3` (DNS NAT
VirtualBox) et `1.1.1.1` répondent. **Correctif posé par la propriétaire** (`sudo bash
~/isc-dns-fix.sh`, script déposé par A) :

```text
>>> 1. DNS immédiat sur enp0s3 : 10.0.2.3 puis 1.1.1.1
       DNS Servers: 10.0.2.3 1.1.1.1
>>> 2. persistance (netplan, fichier 70-isc-dns.yaml, validé sans appliquer à chaud)
netplan generate : OK
>>> 3. vérification : 5 résolutions de services.gradle.org
essai 1 : 2606:4700::6810:4965 services.gradle.org (133 ms)
essai 2 : 2606:4700::6810:4965 services.gradle.org (2 ms)
essai 3 : 2606:4700::6810:4965 services.gradle.org (3 ms)
essai 4 : 2606:4700::6810:4865 services.gradle.org (2 ms)
essai 5 : 2606:4700::6810:4965 services.gradle.org (3 ms)
>>> DNS : 5/5 réussites.
```

Puis, depuis un **conteneur** (chemin réel de la construction) : `services.gradle.org`,
`plugins.gradle.org`, `repo.maven.apache.org` joignables en HTTPS (`CONTENEUR_OK`, `PLUGINS_OK`,
`MAVEN_OK`). Observation non bloquante : `curl -4` **depuis l'hôte** vers `services.gradle.org`
échoue encore ; `curl -6` : code 000 (IPv6 de la VM cassé, connu).

**Étape 2 refaite (19:22:08Z)** avant la reprise : `docker stop isc-core isc-pdp`, export
`audit-export-2026-10-24T192208Z.csv`, **120280 entrées, même sha `975ee9ed…ae405`** (aucune
écriture entre les deux), `export-audit: VERT`.

**Étape 5 — second `make up` (19:22:10Z) : ÉCHEC** aussi, cette fois sur le **délai** du wrapper :

```text
#41 103.0 Exception in thread "main" java.io.IOException: Downloading from https://services.gradle.org/distributions/gradle-8.14.3-bin.zip failed: timeout (10000ms)
#41 103.0 Caused by: java.net.SocketTimeoutException: Read timed out
target pdp: failed to solve: … "./gradlew --no-daemon clean bootJar" did not complete successfully: exit code: 1
FIN_MAKE_UP_RC=2
```

Service **rétabli** aussitôt (`docker start isc-pdp isc-core`, anciens conteneurs, `isc-pdp (healthy)`).
Un `wget` du même zip depuis un conteneur n'aboutit pas en 150 s : le lien réseau de la VM ne permet
pas une construction d'image. Constat annexe : Tailscale route poste→VM par un **relais** (« relay
sao ») — d'où les expirations SSH ; bascule de la session sur le chemin **host-only** direct
`192.168.56.10` (même clé, même utilisateur), sans effet sur la pile.

**Décision A de la propriétaire, sur avis du superviseur (19:4xZ), cinq conditions :**

1. *Retour arrière possible* : `docker tag isc-core:latest isc-core:avant-33979d2` et idem `isc-pdp`
   — fait, `0aed2c5d9761` / `2fdb042bd851` (images du 22/10).
2. *Images depuis les jars du poste* : jars compilés par le `make check` du poste à 8ffe543
   (sources `core/`, `pdp/`, `config/` **identiques** à 33979d2 : `git diff --stat` vide), copiés
   par host-only, **empreintes identiques** des deux côtés :
   `f41071ce76d39c0f5c51a7d4a2745c3cca759eed4390c6164aed63def0142aee` (cœur, 23 431 690 o),
   `4139e755f6d46425720d6d37052ebdc78e3c35bb2e3a35049b5e9a58626a7d3f` (PDP, 35 231 599 o).
   Dockerfiles minimaux = étage final du dépôt à l'identique (`FROM eclipse-temurin:21-jre-jammy`,
   `WORKDIR /app`, `COPY app.jar`, `EXPOSE 8443` pour le PDP, même `ENTRYPOINT`). Images :
   `isc-core:latest 75f284a923aa`, `isc-pdp:latest aab7c4a50d72`. `isc-console` et `isc-tools`
   **inchangés** : `git diff --stat 8929a3f 33979d2 -- console/ infra/ca/Dockerfile.tools` vide
   (`gen-ca.sh` est monté en volume, pas dans l'image).
3. *Démarrage avec exactement les fichiers et l'environnement de `make up`* : export final d'abord
   (`audit-export-2026-10-24T195132Z.csv`, 120280 entrées, **même sha `975ee9ed…ae405`**, VERT), puis
   `docker compose -f docker-compose.yml -f compose.b3a.yml up -d --wait` **sans `--build`**
   (`up-sansbuild-20261024T195137Z.log`) → `FIN_UP_RC=0`, tous `Healthy`.

**Preuves de l'étape 5 (CONSTATÉ, 19:5xZ)** :

```text
$ docker logs isc-migrate
[apply-migrations] 002-b3a-premier-enrolement.sql
[apply-migrations] 003-holder-name.sql
[apply-migrations] 005-b4-session-liveness.sql
[apply-migrations] 3 migration(s) appliquee(s) depuis /opt/isc/migrations
$ docker logs isc-ca-init
[ca-init] 2026-10-24T19:51:38Z CA deja presente dans le volume -> REUTILISATION (Q9), pas de regeneration
[ca-init] empreinte CA : 70:58:2E:19:59:A6:C3:47:5D:46:87:B6:94:ED:2B:44:32:74:0A:2F:06:07:FC:1D:01:B2:85:39:C3:1A:BA:2F
[ca-init] feuille core-mobile ABSENTE -> signee avec la CA existante (CA inchangee)
[ca-init] 1 feuille(s) ajoutee(s) ; empreinte CA apres : 70:58:2E:19:…:1A:BA:2F
$ docker logs isc-core | grep amorçage
… DemoAccountSeeder : amorçage démo : 5 comptes techniques, 20 comptes de démonstration, noms des titulaires alignés
$ docker port isc-core          → 8443/tcp -> 100.93.166.19:8443
$ docker port isc-console       → 3000/tcp -> 127.0.0.1:3000
$ docker port isc-pdp           → (rien)
$ docker network inspect isc-mobile → br-isc-mobile 172.21.0.0/16 ; ip link : br-isc-mobile UP
$ docker exec isc-core bash -c '</dev/tcp/1.1.1.1/443' → SORTIE_BLOQUEE   (P3 efficace)
```

**Étape 6 — depuis le poste** : `curl http://100.93.166.19:8080/` → `Could not connect` (8080 non
publié) ; `curl http://100.93.166.19:3000/` → `Could not connect` ; tunnel
`ssh -L 3000:127.0.0.1:3000` → `http=200` ; `curl http://192.168.56.10:3000/` → expiré (boucle locale
seule). **8443 : NE RÉPOND PAS** — ni par Tailscale, ni depuis la VM (`s_client` sur
`100.93.166.19:8443`). Cause lue : le cœur n'écoute que sur 8080 (`Tomcat started on port 8080`) car
`core.mobile-tls.enabled: false` dans `application.yml` (L7-a, livré opt-in, « noms à confirmer avec
l'infra ») et la composition publie le port sans l'activer. **Câblage manquant A↔B**, pas une panne.

**Activation du connecteur (19:58Z)**, interrupteur confirmé par le couloir B dans son code
(`MobileTlsProperties`, `@ConfigurationProperties("core.mobile-tls")` → variable
**`CORE_MOBILETLS_ENABLED=true`**, liaison souple Spring ; `CORE_MOBILE_TLS_ENABLED` ne lierait pas) :
prérequis vérifiés (`core-mobile.key` 600 root, processus du cœur uid 0 → lisible ; `.crt` 644) ;
variable ajoutée à `infra/.env` (ligne 8) pour ce soir, et au service `core` de
`docker-compose.yml` par la PR du compte rendu ; recréation par `docker compose … up -d --wait` sans
`--build` → `isc-core Healthy`. Effet de bord constaté : **`isc-postgres` a été recréé aussi**
(`env_file: .env` partagé : tout changement de `.env` recrée tous les services qui le lisent) ; données
intactes (volume), chaîne d'audit vérifiée après (étape 7). À retenir : poser toutes les variables
`.env` **avant** le premier `up`.

```text
$ docker logs isc-core
… TomcatWebServer : Tomcat initialized with ports 8080 (http), 8443 (https)
… NioEndpoint.certificate : Connector [https-jsse-nio-8443], TLS virtual host [_default_] …
… TomcatWebServer : Tomcat started on ports 8080 (http), 8443 (https) with context path '/'
… DemoAccountSeeder : amorçage démo : 5 comptes techniques, 20 comptes de démonstration, noms des titulaires alignés
```

**Étape 6 — preuves 8443 (CONSTATÉ, depuis le poste par le tailnet ET depuis la VM)** :

```text
$ openssl s_client -connect 100.93.166.19:8443 -servername isc-vm.tail3824f1.ts.net -CAfile ca.crt
subject=CN=isc-core
issuer=CN=IdentiShield-CM Local CA
Verify return code: 0 (ok)                      (depuis la VM : New, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384)
X509v3 Subject Alternative Name:
    DNS:isc-core, DNS:isc-vm, DNS:isc-vm.tail3824f1.ts.net, IP Address:100.93.166.19
sha256 Fingerprint=2C:1B:E8:8E:2C:CD:77:39:00:C0:7C:87:97:F6:C4:F2:11:E4:40:9A:5B:48:CA:43:91:1B:0E:ED:7F:90:67:DE
notAfter=Oct 24 19:51:38 2027 GMT
```

CA servie = CA du volume (empreinte `70:58:2E:19…1A:BA:2F`, inchangée depuis le 16/10 : l'épinglage
futur des téléphones ne sera pas affecté).

**Étape 6 bis — 25 comptes et rejouabilité (avec le couloir B, sous le témoin A)** : ligne
d'amorçage identique aux deux démarrages du cœur (19:51:55Z et 19:58:49Z) ;
`SELECT count(*) FROM core.account WHERE holder_name IS NOT NULL` (isc_admin) → **25**. Aucun PIN, aucune
empreinte, aucun nom au rapport. Validation de B : voir sa réponse au message de 20:0xZ.

**Étape 8 — ADR-036 point 5, rejoué sur le cœur déployé (CONSTATÉ)** :

```text
8443 (tailnet, poste) :  GET  /health                       -> 403    (ONLY_MOBILE_HERE)
                         GET  /flux                         -> 403
                         POST /mobile/v1/enrolment/start {} -> 400    (route servie, corps hors contrat)
8080 (isc-net, interne): POST /mobile/v1/enrolment/start {} -> 403 « Refuse : les routes /mobile/v1/* ne sont servies qu'en HTTPS »
                         GET  /flux                         -> 200
                         GET  /account?msisdn=677000001     -> 200
                         GET  /health                       -> 404    (le cœur n'a pas de /health : route du PDP)
```

Rouge/vert tenu : une route hors `/mobile/v1` est refusée sur le 8443 ; une route `/mobile/v1` y est
servie ; le 8080 refuse `/mobile/v1` et sert le reste, et n'est pas publié.

**Validation du couloir B (20:1xZ, sur ces sorties)** : étape 6 bis — « rejouabilité CONSTATÉE sur le
serveur réel, cohérente avec ma preuve en pile isolée (27 lignes identiques dont 25 amorcées) » ;
point 5 d'ADR-036 — tenu (L7-a + L7-b), **ce qui clôt la condition L7 de
`docs/03-revues/2026-10-22-limites-declarees-B3a.md`** ; le couloir B référencera ce document depuis
ses limites B3a à sa prochaine PR docs. Prérequis constaté tel quel : `core-mobile.key` **600 root**
et processus du cœur **uid 0** → lisible ; **si un jour le cœur passe non-root, ce prérequis casse**
(à traiter avec les droits posés par `gen-ca.sh`). Reste pour L7 : **L7-c**, le téléphone de bout en
bout (étape du vrai téléphone), et la condition ADR-030 de #67 (adresse réelle de deux téléphones).

**Étape 7 — second export (20:03:43Z)** : `audit-export-2026-10-24T200343Z.csv`, **120280 entrées**,
0 rupture, ancre conforme, VERT ; `sha256` des 120281 premières lignes du second =
`975ee9ed12406fea2ea2d9b59384f3fc68f43b6377ca36803a07755e908ae405` = sha du premier export
(19:51:32Z) : **chaîne ancienne intacte, aucune entrée perdue ni altérée** ; nombre d'entrées ≥ avant
(égal : aucune transaction pendant le déploiement). Cœur et PDP relancés, `isc-pdp (healthy)`.

**Condition 5 de la décision A** : carte **#96** « make up doit reconstruire seul dans la VM
(--build compris) » créée, « À faire », condition de clôture = un `make up` complet réussi une fois
dans la VM, journal collé.

**État final (20:04Z)** : `main` 33979d2 déployé ; 5 conteneurs `Up`, `isc-pdp` `(healthy)` ; images
`isc-core:latest 75f284a923aa`, `isc-pdp:latest aab7c4a50d72` (retour arrière : `avant-33979d2`) ;
migrations 002/003/005 appliquées ; 8443 servi avec `core-mobile` ; console sur la boucle locale ;
sortie Internet du sous-réseau mobile bloquée ; 25 comptes amorcés. **Non fait** : P4 (ACL Tailscale,
propriétaire) ; `check-stack` (gel VEL jusqu'au 25/10 15:48:31Z) ; condition ADR-030 de #67 (adresse
réelle de deux téléphones : exige des téléphones sur le tailnet).

### Fenêtre 3 (2026-10-25, 09:02Z → 09:21Z) — `main` fbdaf3b + dotation du compte de démonstration

Ordre du superviseur technique du 25/10 : après la fusion de #109 et #110, « témoin, export, git pull,
make up (construction), preuves courtes, puis DOTATION » de 677000200 par un virement P2P réel de
20 000 FCFA depuis le compte technique 677000123, par le simulateur USSD de la VM, de jour.

**Porte constatée (09:02Z = 10:02 Douala)** : #110 fusionnée 08:55:01Z ; CI `main` fbdaf3b `success` ;
aucune PR ouverte ; `origin/main` = fbdaf3b (#110 ordre du gel après `SOFTWARE_KEY`, #111 outil
T-AC-16). Témoin A posé 09:02:58Z (`jusqu-a` 10:02:58Z), couloir B prévenu avant et après.

**Export 1 (09:06:34Z, cœur et PDP arrêtés)** : `audit-export-2026-10-25T090634Z.csv`, 120280
entrées, `sha256 975ee9ed12406fea2ea2d9b59384f3fc68f43b6377ca36803a07755e908ae405` = référence des
fenêtres 1 et 2, 0 rupture, ancre conforme (`head_matches=true`) : chaîne intacte, rien ne s'est écrit
depuis la fenêtre 2. `git pull --ff-only` : 0d7a952 → fbdaf3b.

**`make up` avec construction (`~/deploy-step5.sh`, journal `up-20261025T090706Z.log`)** : 09:07:06Z →
09:12:43Z (5 min 37 s), `FIN_MAKE_UP_RC=0`, « profil demo : compose.b3a.yml inclus ». Preuves :

- `isc-migrate` : `002-b3a-premier-enrolement`, `003-holder-name`, `004-b3b-reenrolement`,
  `005-b4-session-liveness`, `006-b5-alertes` — « 5 migration(s) appliquee(s) » (rejouées, `IF NOT
  EXISTS`, aucune erreur) ;
- `isc-ca-init` : « CA deja presente dans le volume -> REUTILISATION », 0 feuille ajoutée, empreinte
  `70:58:2E:19:59:A6:C3:47:5D:46:87:B6:94:ED:2B:44:32:74:0A:2F:06:07:FC:1D:01:B2:85:39:C3:1A:BA:2F`
  avant = après (même valeur que `openssl x509` sur `/certs/ca.crt`) ;
- cœur : « Tomcat started on ports 8080 (http), 8443 (https) », connecteur 8443 sur
  `core-mobile.key`/`core-mobile.crt` ;
- 8443 : depuis la VM `http=403 verify=0` ; depuis le poste par Tailscale avec `vm-ca.crt`
  `http=403 verify=0` (`--ssl-no-revoke` : le `curl` Windows échoue sinon sur la révocation, artefact
  du poste) ; 8080 et 3000 depuis le poste : connexion refusée ;
- comptes : **29** = 25 comptes de démonstration (amorcés le 24/10 à 19:51:55Z par le seeder B8, dont
  677000123 à 152 300 et 677000200 à 0) + 4 comptes techniques des portes (677000001, 677000002,
  677000007, 677000008). Aucun compte créé par cette fenêtre ; les « 25 comptes » des fenêtres 1 et 2
  comptaient les seuls comptes amorcés.

**Dotation (09:16:46Z = 10:16 Douala, `~/dotation-677000200.sh`, mode USSD scripté du cœur sur
`isc-net`, chemin `/evaluate` avant commit, ni SQL ni nouvelle route)** :

```text
avant : 677000123|152300   677000200|0
USSD> Transfert P2P. Montant en FCFA ?            USSD< 20000
USSD> MSISDN du beneficiaire ?                   USSD< 677000200
USSD> Confirmer 20000 FCFA vers 677000200 ? 1=Oui USSD< 1
USSD_FINAL=Transfert EXECUTED_DEGRADED. Reference 2b0f90e2-4d96-4c00-929a-80dbc5d346cb
après : 677000123|132300   677000200|20000
core.transaction da4914fa-02f0-4a36-af78-cff28aebdbf7 | EXECUTED | 20000 | USSD | 2026-10-25 09:16:46.875Z
pdp.audit_entry seq 120281 (chain_index 120280), written_at 09:16:47.868Z :
  decision=ALLOW score=15 triggeredRules=[NEW_BENEFICIARY +15] vetoApplied=NONE requiredAction=NONE
  ruleSetVersion=rs-0.10.0 decisionId=2b0f90e2-4d96-4c00-929a-80dbc5d346cb
```

**Référence de décision** : `2b0f90e2-4d96-4c00-929a-80dbc5d346cb` ; **entrée d'audit** : seq 120281.
Soldes exacts, une seule transaction, une seule entrée d'audit.

**Écart constaté, non masqué** : le message USSD dit `EXECUTED_DEGRADED` alors que le PDP a décidé
`ALLOW`. Le code (`TransferService.operate`) ne rend `EXECUTED_DEGRADED` que si le cœur juge le PDP
**indisponible**. Journal du PDP : le `/health` de préchauffage à 09:16:46.503Z n'initialise que le
`DispatcherServlet` ; le pool Hikari ne s'ouvre qu'au premier `/evaluate` (« HikariPool-1 -
Starting... » 09:16:47.463Z, « Start completed » 09:16:47.766Z), `evaluatedAt` 09:16:47.868Z : le PDP
a répondu ≈ 1,0 s après la requête, au-delà du délai du client du cœur (`core.pdp.timeout-ms: 800`,
`application.yml`). 20 000 < `CAP_FAILCLOSED.xaf` = 50 000 → le cœur a appliqué la voie **dégradée**
(ES-11 : refus seulement au-dessus du plafond). Conséquences : (1) la décision `ALLOW` existe dans
l'audit mais **n'a pas été consommée** par le cœur ; (2) l'état persisté est `EXECUTED` (le caractère
dégradé n'est pas persisté dans `core.transaction`, il n'est que dans la réponse et le journal) ;
(3) le virement n'a **pas** été refait (un second = 40 000). C'est la **sixième occurrence** de
l'observation du 15/09 (`2026-09-15-observation-recurrente-evaluate-a-froid.md`), la première sur un
**mouvement réel** et non une mesure : la dette « à trancher avant le mois 5 » est maintenant visible
sur la démonstration elle-même.

**Défaut d'outillage trouvé en cherchant la cause** : `core/Dockerfile` porte `ENTRYPOINT ["java",
"-jar", "/app/app.jar"]` ; or `tools/verify_ussd.sh` (et `~/dotation-677000200.sh`, copié dessus)
lancent `docker run … isc-core java -Dspring.main.web-application-type=none -Dcore.pdp.timeout-ms=5000
-jar /app/app.jar` : tout ce qui suit le nom de l'image devient **argument du programme**, les options
`-D` n'atteignent jamais la JVM. Le délai effectif a toujours été 800 ms et Tomcat démarre dans chaque
conteneur jetable. Même motif dans `bench_mtls.sh`, `verify_console.sh`, `verify_hlr.sh`,
`verify_hlr_agent.sh`, `verify_mtls.sh`, `verify_notification.sh`, `verify_otp.sh`,
`verify_transfer.sh`, `verify_ussd.sh` (9 outils). **Non corrigé seul** : rendre les 5 000 ms effectifs
ferait disparaître le symptôme à froid de `check-stack`, c'est-à-dire un contournement de plus de la
dette ci-dessus ; la correction (`--entrypoint java`) attend la décision sur l'observation du 15/09.

**Export 2 (09:19:08Z, cœur et PDP arrêtés)** : `audit-export-2026-10-25T091908Z.csv`, **120281**
entrées (+1 = la dotation), `sha256 8c8b0a34eabcfc7f9b0910f7103c4a2844177dbadc34b9a378724738ae6af206`,
0 rupture, ancre conforme, VERT. Après le redémarrage qui suit l'export, le PDP a été **réchauffé par
une lecture seule** (`GET /trust/{accountId}` en mTLS depuis le conteneur du cœur : 286 ms à froid,
pool ouvert 09:22:10Z, puis 51 ms) pour que le premier appel du couloir B soit nominal.

**État final (09:22Z)** : `main` fbdaf3b déployé ; 5 conteneurs `Up`, `isc-pdp (healthy)` ; témoin
retiré 09:21Z, « VM à toi » au couloir B. **Non fait** : P4 (ACL Tailscale, propriétaire) ;
`check-stack` (une seule exécution, à partir de 15:48:31Z, levée du gel VEL) ; condition ADR-030 de
#67 ; `passwd` de `isc` (incident du 24/10) ; signature des amendements PROPOSÉS d'ADR-030 et ADR-041.
