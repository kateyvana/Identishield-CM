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
- Chaîne d'audit : 120 280 entrées à 15:48Z (export de référence : 120 276 entrées,
  sha `4ab894f0…c567`, `2026-10-24-export-chaine-audit.md`).
- Compte 677000001 **gelé** (VEL) jusqu'au **2026-10-25 15:48:31Z** ; `check-stack` ROUGE d'ici là.

## Préconditions (toutes vérifiées, sinon on n'exécute pas)

| # | Précondition | Qui | Comment on le voit |
|---|---|---|---|
| P1 | PR #88 (B4) fusionnée, CI de `main` verte | propriétaire | `gh pr view 88 --json state` = MERGED ; `gh run list --branch main` = success |
| P2 | **Feuille `core-mobile` créée sur une CA existante.** `infra/ca/gen-ca.sh` (main 15a1808, lignes 25–28) sort en « REUTILISATION » dès que `ca.crt` existe, **sans signer la feuille manquante**. Or le cœur **ne démarre pas** sans `/certs/core-mobile.crt` (`core/src/main/resources/application.yml`, lignes 63–69, `MobileTlsConfig`). Sur la VM, le déploiement tel quel **casserait le cœur**. Défaut de L7-b (couloir A), trouvé en écrivant ce plan. Correctif : signer toute feuille **absente** avec la CA existante (la CA, épinglée par les téléphones, n'est pas touchée) ; preuve rouge/vert sur un volume jetable ; PR fusionnée **avant** le déploiement. | couloir A | PR `t4-l7-b-feuille-manquante` fusionnée ; preuve collée |
| P3 | Règle `DOCKER-USER` coupant la sortie du sous-réseau `isc-mobile` (L7-3) | propriétaire | `sudo iptables -S DOCKER-USER` (sudo exige son mot de passe : `isc` ne l'a pas sans mot de passe) |
| P4 | Règle d'accès Tailscale (L7-1) : évaluateurs → `100.93.166.19:8443` seulement | propriétaire | preuve depuis un nœud « évaluateur » (carte #66) — **non bloquante pour ce déploiement**, bloquante pour inviter un évaluateur |
| P5 | PR #66 (console sur la boucle locale) fusionnée, pour la preuve « 3000 refusé » | propriétaire | `gh pr view` = MERGED ; sinon la preuve 3000 est reportée |
| P6 | Témoin `~/VM-OCCUPEE` absent, puis posé par A avec heure de fin | couloir A | `cat ~/VM-OCCUPEE` |

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
5. **Migrations + démarrage = `make up`** (il n'existe **pas** de cible `make migrate` : retirée par
   #86 ; `isc-migrate` s'exécute à chaque `make up`, `docker-compose.yml` service `migrate`, et
   `isc-core`/`isc-pdp` l'attendent en `service_completed_successfully`). Lancé en
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

## Ce qui n'est pas dans ce plan

- `make reset` : non (export fait, mais rien ne l'exige ; la chaîne vivante est conservée).
- Publication de l'APK (`make apk-publie`) : après la preuve 8443, acte séparé de la propriétaire.
- L'invitation d'un évaluateur sur le tailnet : interdite tant que #66 (P4) n'est pas close.

## Compte rendu d'exécution

_À remplir au moment de l'exécution, preuves collées._
