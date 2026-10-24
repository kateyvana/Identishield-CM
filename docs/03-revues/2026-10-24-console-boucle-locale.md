# Console sur la boucle locale de la VM, accès par tunnel SSH — carte #66 (part couloir A)

**Date :** 2026-10-24. **Carte :** #66 (gravité maximale, bloquante pour la soutenance).
**Détail du risque :** `docs/03-revues/2026-10-23-limites-declarees-L7.md`, L7-1.

## Ce qui change

`infra/docker-compose.yml`, service `console` : la publication `3000:3000` (toutes les interfaces,
donc tout le tailnet) devient **`127.0.0.1:3000:3000`**. La console n'est plus joignable que depuis
la VM elle-même. Depuis le poste, on l'atteint par un tunnel SSH, authentifié par clé :

```
ssh -N -L 3000:127.0.0.1:3000 isc@100.93.166.19
```

puis `http://localhost:3000` dans le navigateur du poste. Le tunnel se ferme avec Ctrl-C.

## Ce qui ne change pas

- Les portes `verify_console`, `verify_console_attack`, `verify_console_integrity`, `verify_layerless`,
  `verify_attacker`, `verify_ac02_protected` appellent `http://localhost:3000` **depuis la VM**
  (`CONSOLE_URL` par défaut) : la boucle locale les sert comme avant.
- Le 8443 (surface mobile, L7-b) reste publié sur `ISC_MOBILE_BIND`.
- Le **remède principal** de #66 reste l'acte de la propriétaire : la règle d'accès Tailscale limitant
  les évaluateurs à `100.93.166.19:8443`. La boucle locale est la **seconde barrière** : même si la
  règle est mal écrite ou absente, un évaluateur n'atteint pas le 3000. Elle ne protège **pas** le 22
  (SSH), qui relève de la règle Tailscale seule.

## Preuve à faire au déploiement dans la VM (condition de clôture de cette part)

1. depuis le poste, `curl -m 5 http://100.93.166.19:3000/` → **connexion refusée** (CONSTATÉ, sortie
   collée) ;
2. depuis le poste, tunnel ouvert, `curl -m 5 http://localhost:3000/` → **200** ;
3. dans la VM, `make check-stack` → les portes console VERTES (dès que le gel VEL de 677000001 est
   levé, voir `2026-10-24-export-chaine-audit.md`).

La carte #66 elle-même ne se ferme que sur la preuve de la règle Tailscale depuis un nœud de la
classe « évaluateur » : 8443 répond, 22 et 3000 refusés.
