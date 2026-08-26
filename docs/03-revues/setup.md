# Protection de la branche `main` — application manuelle par le propriétaire

Réglages GitHub à appliquer à la main (Settings → Branches → Branch protection
rules → `main`), conformément à ISC-DEPLOY §5.1 et ISC-DEV §3.2 :

1. **Require a pull request before merging** — poussée directe interdite.
2. **Require approvals : 1** — relecture explicite du candidat avant fusion
   (lecture intégrale du diff, ISC-DEV §5.2 étape 4).
3. **Require status checks to pass before merging** — cocher le workflow
   `ci / portes-legeres`. Ajouter `heavy / portes-lourdes` une fois
   l'exécuteur auto-hébergé `isc-vm` installé dans la machine virtuelle
   (ISC-DEPLOY §5.3).
4. **Do not allow bypassing the above settings** — la règle s'applique aussi
   aux administrateurs.

Rappel : le workflow `heavy` reste en attente tant que l'exécuteur `isc-vm`
n'est pas installé. C'est un comportement accepté (ADR-023), pas une panne.
