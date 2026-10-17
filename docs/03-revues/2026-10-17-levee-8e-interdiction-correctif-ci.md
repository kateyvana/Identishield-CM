# Levée de la 8e interdiction — correctif CI (shebang exécutable), 2026-10-17

Propriétaire : YENGUE MESSAH KATE YVANA
Objet : poussée de `fix/ci-shebang-executable` et ouverture de la pull request.

## Portée
Poussée de la branche `fix/ci-shebang-executable`, qui porte le commit
`63d4b37` (mode 100644 → 100755 sur `tools/profile_real.py`, AUCUN changement
de contenu) plus le présent commit de levée. Aucune écriture directe sur
`main` : la fusion relève de la propriétaire.

## Motif
La porte `portes-legeres` échoue depuis le mois 3 : sous Linux, ruff signale
EXE001 sur `tools/profile_real.py` (shebang présent, fichier non exécutable).
L'erreur est invisible sous Windows, d'où un `make check` local vert face à
une CI rouge.

## Preuves
- `make check` : VERT.
- Le commit ne change aucun contenu : seul le mode git change.
- Vérifié sur `origin/main` : `tools/profile_real.py` est le SEUL fichier
  Python portant un shebang sans bit exécutable. Les 30 autres fichiers dans
  ce cas sont des scripts shell et des `gradlew`, hors du champ de ruff.

## Limites déclarées
- 16 `tools/verify_*.sh` restent en 100644 alors que `tools/check_stack.sh`
  est en 100755. Sans effet fonctionnel (invoqués par `bash <script>`).
  Carte d'hygiène séparée.
- Les fichiers sous `spikes/` ne sont PAS modifiés : artefacts historiques
  gelés, leur mode fait partie de la trace.
- La porte `portes-lourdes` reste non exécutée (aucun exécuteur configuré).
  Ce correctif ne la concerne pas.
