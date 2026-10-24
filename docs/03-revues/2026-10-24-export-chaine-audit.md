# Export et vérification de la chaîne d'audit de la VM — 2026-10-24

**Couloir A.** Remise en service du serveur (`isc-vm`, `main` = 8929a3f déployé, aucune migration),
puis export daté de la chaîne d'audit **hors** du volume `isc-pgdata`, comme l'exige
`infra/init/migrations/README.md` (précondition sur la vraie base) et la règle du 2026-10-17 (export
avant tout `make reset`). Outil livré : `tools/export_audit_chain.sh`.

## Remise en service

Le serveur était arrêté depuis mon `make down` du 2026-10-23 au soir (aucun conteneur ; volumes
`isc-certs` et `isc-pgdata` présents). Témoin posé (`couloir: A`, 15:40:06Z → 16:10:06Z), `make up`
sur 8929a3f (images en cache, `FIN_MAKE_UP_RC=0`), puis :

```
check-stack: VERT   (latence /evaluate à chaud : 11 ms)
```

## Export (CONSTATÉ)

Le seul rédacteur du journal est `isc-pdp` (rôle `isc_pdp_app`). Il est arrêté pendant l'export
(`docker stop isc-core isc-pdp`), puis relancé (`docker start`) ; l'outil refuse de s'exécuter si
`isc-pdp` tourne (refus vérifié : `[export-audit] ECHEC : isc-pdp tourne encore …`, code 2).

| | |
|---|---|
| Fichier | `/home/isc/audit-exports/audit-export-2026-10-24T154703Z.csv` (66 Mo, hors volume) |
| Format | `seq,chain_index,payload,prev_hash,hash,written_at` (identique à l'export du 2026-10-17) |
| Entrées | **120 276** (dont les écritures des bancs ENF-01 des 21–23/10) |
| `pdp.audit_verify()` | **0** rupture (`hash_mismatch` ou `chain_break`) |
| `pdp.audit_anchor_check()` | `entry_count` 120 276 = réel, `last_seq` 120 276 = réel, `head_matches` = true |
| SHA-256 | `4ab894f01edac23b4c7ea05a7c9ae096b50d24b42e5571330d9c1ae88720c567` |

**Reproductibilité.** Un premier export à 15:46:18Z (même procédure) a donné la **même empreinte**
sur les mêmes 120 276 entrées : aucune écriture entre les deux, export déterministe (tri par
`chain_index`). Le premier passage affichait un verdict ROUGE **à tort** : le script comparait le
booléen `head_matches` à `t` alors que la concaténation SQL le rend `true`. Corrigé avant le second
passage ; les quatre contrôles chiffrés étaient déjà conformes.

## Portée de cet export

Cet export **n'est pas encore** celui coché dans la liste du README : il précède la fusion du `main`
migré (PR #83 puis #82/#86/#84/#81/#87). Il prouve l'outil et la procédure, et fournit un état de
référence. **Il sera refait** avec le même outil juste avant le `make up` qui appliquera 002 et 003
sur la base de la VM (30 s, `isc-pdp` arrêté), et c'est cet export-là qui cochera la liste.

## Constat annexe : `check-stack` n'est pas rejouable dans la fenêtre VEL

Après redémarrage de `isc-core`/`isc-pdp`, un **second** `check-stack` 7 minutes après le premier
sort **ROUGE** sur `verify-ussd` (« Transfert REJECTED »). Cause lue dans le journal (entrées
120 278–120 279) : `verify-transfer` et `verify-ussd` émettent chacun une sortie du compte
677000001 ; deux passages en moins de 10 minutes font **4 sorties**, la règle **VELOCITY**
(`VEL.count: 3`, `VEL.window: PT10M`) déclenche (score 70 → FREEZE), et le compte est gelé pour
`TTL_FREEZE` (PT24H), jusqu'au **2026-10-25 15:48:31Z**. Pendant ce temps `check-stack` reste ROUGE
sur cette base (veto `ACCOUNT_FROZEN`).

Ce n'est **pas** un défaut du PDP : la règle fait ce que la spécification demande. C'est une
**limite de la porte** : elle n'est pas idempotente dans la fenêtre VEL et laisse l'état de la base
dégradé 24 h. `TrustStateMachine.release()` (levée explicite, quatre-yeux) existe mais n'a aucun
appelant (carte A3 : la vérification de la preuve appartient à l'appelant). Aucune donnée n'a été
modifiée pour « faire passer » la porte.

Options pour la propriétaire (décision à prendre, pas prise ici) :
1. attendre l'échéance du gel (25/10 15:48Z) ; `check-stack` redevient VERT seul ;
2. corriger la porte dans une carte dédiée : comptes distincts par `verify_*`, ou pré-contrôle qui
   annonce « compte gelé par VEL jusqu'à … » au lieu d'un ECHEC ambigu ;
3. `make reset` (autorisé : l'export ci-dessus est fait et VERT), au prix de la chaîne vivante de
   120 276 entrées (conservée dans l'export).

Le premier `check-stack` du jour (VERT) reste la preuve de remise en service.
