# Levée de la 8e interdiction — fusion de feat/T2-14-notifications dans main et poussée

**Date :** 2026-09-15
**Portée :** cette action uniquement. La levée ne s'étend à aucune autre action distante.

## Motif (ordre du propriétaire)

Ordre explicite en session : « je lève, fusionne feat/T2-14-notifications dans main, et pousse ». T2-14 (notifications simulées, EF-09, et garde-fou de contenu ENF-17) est validée : `make check` VERT, `verify_notification.sh` VERT dans la VM — SMS vers l'IMSI courant, notif suivant l'IMSI après réattribution, push stocké, persistance/consultation par numéro, notif émise lors d'un transfert exécuté, et les **trois refus** (lien, numéro, demande de code) prouvés par une tentative réelle avec leur message. Carte en In Review.

## Ce qui est fait sur la forge — et rien d'autre

- Fusion locale `--no-ff` de `feat/T2-14-notifications` dans `main` (commit de fusion).
- `make check` vérifié VERT sur `main` après la fusion et avant la poussée.
- `git push origin main`.
- Carte Kanban T2-14 déplacée vers Done.

## Ce qui n'est PAS fait

Aucune pull request, aucun `--force`, aucune suppression de branche, aucune modification de visibilité ni de réglages du dépôt.
