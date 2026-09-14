# Risque consigné — endpoint POST /hlr/reassign non authentifié

**Date :** 2026-09-14
**Origine :** revue de T2-11 (outil agent HLR). Mesure demandée par le propriétaire.
**Sévérité :** latente (pas encore exploitable) — devient réelle au mois 6 si non traitée.

## Mesure (état au 2026-09-14)

Inspection de `infra/docker-compose.yml` :

- Le service **`core` n'a AUCUN port publié** ; il n'est que sur `isc-net`, et `isc-net` est déclaré `internal: true` (aucun accès sortant, non joignable depuis l'hôte).
- **Seul port publié de toute la pile : `console` `3000:3000`.** postgres et pdp (8443) sont aussi internes, sans port publié.
- La console (publiée) ne relaie que ses propres routes (`/`, `/api/flux`) — elle ne proxie PAS `/hlr/reassign`.

**Conclusion : `/hlr/reassign` (core:8080) n'est PAS exposé hors de `isc-net` aujourd'hui.** Aucun chemin depuis l'hôte ou le réseau local vers l'endpoint. L'exposition n'est pas réelle à ce jour.

## Le risque (latent, mois 6)

L'endpoint `POST /hlr/reassign` **réattribue une carte SIM** et n'a **aucune authentification** (comme `/flux`). Il partage le port web du cœur (8080). Au mois 6, les téléphones (USSD/app) devront joindre le cœur ; si cette exposition se fait en **publiant 8080 tel quel** sur le réseau où vivent les téléphones, alors **n'importe qui sur ce réseau pourra réattribuer une SIM** — exactement ce qu'un pentesteur signalerait en premier.

## Ce qu'il faudra décider avant le mois 6 (tâche à part)

- **Ne jamais** exposer `/hlr/reassign` sur le réseau des téléphones. L'API de provisionnement de l'opérateur (agent HLR) est une surface d'administration, pas une surface publique.
- Options : segmentation réseau (endpoint agent sur un réseau distinct des canaux téléphone) ET/OU authentification — les **jetons distincts par outil** du SAD §5 (IR-08/09), différés au mois 4 (décision du propriétaire).
- À rattacher au découpage du mois 4 (auth) et du mois 6 (exposition démo).

Non corrigé ici, sur consigne : mesuré, rapporté, consigné.
