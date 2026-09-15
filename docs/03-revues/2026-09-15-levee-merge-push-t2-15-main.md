# Levée de la 8e interdiction — fusion de feat/T2-15-layerless-mode dans main et poussée

**Date :** 2026-09-15
**Portée :** cette action uniquement. La levée ne s'étend à aucune autre action distante.

## Motif (ordre du propriétaire)

Levée **conditionnelle** accordée en session : « Quand c'est fait et make check vert : je lève, fusionne feat/T2-15-layerless-mode dans main, et pousse. Carte vers Done. » Les additions demandées avant fusion sont faites :

- **Preuve inverse** ajoutée (`OperationTest#layeredModeAlwaysGoesThroughEvaluateExactlyOnce`) : en mode protégé, le contournement sans couche est INATTEIGNABLE — `pdp.evaluate` est appelé exactement une fois, le raccourci n'est pas emprunté.
- **Note « famille voisine »** ajoutée au document d'observation cold-start (premier appel serveur→cœur de la console), sans en faire une sixième occurrence (mécanisme distinct).
- **Règle d'architecture** : NON écrite, à dessein. ArchUnit exprime l'existence d'appels, pas la dominance de flot de contrôle (« tout chemin vers commit() passe par le garde »). Une condition « la méthode appelle aussi le garde » serait approximative (ni ordre, ni tous-chemins) ; conformément à l'ordre du propriétaire, je le signale plutôt que d'écrire une règle approximative. L'invariant est tenu par : `commit()` privé à point d'entrée unique (`operate`), et les deux tests (porte s'ouvre / porte se ferme).

`make check` VERT après ces additions.

## Ce qui est fait sur la forge — et rien d'autre

- Fusion locale `--no-ff` de `feat/T2-15-layerless-mode` dans `main` (commit de fusion).
- `make check` vérifié VERT sur `main` après la fusion et avant la poussée.
- `git push origin main`.
- Carte Kanban T2-15 déplacée vers Done.

## Ce qui n'est PAS fait

Aucune pull request, aucun `--force`, aucune suppression de branche, aucune modification de visibilité ni de réglages du dépôt.
