# Levée de la 8e interdiction — fusion de feat/T2-12-otp dans main et poussée

**Date :** 2026-09-15
**Portée :** cette action uniquement. La levée ne s'étend à aucune autre action distante.

## Motif (ordre du propriétaire)

Ordre explicite du propriétaire, en session : « je lève, fusionne feat/T2-12-otp dans main, et pousse ». T2-12 (OTP à usage unique, EF-03) est validée dans la VM (`verify_otp.sh` VERT — six assertions, dont la reproduction de la faiblesse réelle AC-02 : après réattribution par l'outil agent, l'OTP part vers le nouvel IMSI), `make check` VERT, carte en In Review.

## Ce qui est fait sur la forge — et rien d'autre

- Fusion locale `--no-ff` de `feat/T2-12-otp` dans `main` (commit de fusion).
- `make check` vérifié VERT sur `main` après la fusion et avant la poussée.
- `git push origin main`.
- Carte Kanban T2-12 déplacée vers Done.

## Ce qui n'est PAS fait

Aucune pull request, aucun `--force`, aucune suppression de branche, aucune modification de visibilité ni de réglages du dépôt.
