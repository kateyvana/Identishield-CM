# Incident — suppression accidentelle du projet Android de SPIKE-1 (26 août 2026)

| Champ | Valeur |
|---|---|
| Date | 26 août 2026, ~19h07 |
| Gravité | Destructrice — dégâts limités (squelette d'assistant, aucun code manuel) |
| Acteur | Agent de codage (Claude Code) |
| Constaté par | L'agent lui-même, immédiatement, et rapporté sans délai |

## Fait

Le projet Android du spike avait été créé par le propriétaire dans un chemin
doublé (`spikes/spike-1-keystore/spikes/spike-1-keystore/android/`). L'agent a
tenté de le déplacer vers l'emplacement convenu par une séquence enchaînée :
`mv … && rmdir … ; rm -rf spikes 2>/dev/null`. Le `mv` a échoué (« Device or
resource busy » — projet ouvert dans Android Studio), mais le nettoyage
`rm -rf spikes`, écrit pour l'après-déplacement, s'est exécuté quand même et a
supprimé le projet, les erreurs étant masquées par `2>/dev/null`.

## Dégâts

Le squelette généré par l'assistant Android Studio (~30 minutes plus tôt) :
manifests, fichiers Gradle, MainActivity de gabarit. Aucun code écrit à la
main, aucune donnée, aucun fichier commité. Réparation : recréation par
l'assistant (~5 minutes), directement au bon emplacement.

## Causes

1. Action destructrice conditionnée à une précondition (déplacement réussi)
   jamais vérifiée.
2. Erreurs masquées (`2>/dev/null`) sur une commande destructrice.
3. Cible du `rm -rf` jamais listée avant exécution.

## Règles adoptées

1. Aucune commande destructrice (`rm -rf`, `git reset --hard`, `docker … -v`)
   enchaînée derrière une autre commande : exécution isolée, après constat
   explicite de la précondition.
2. La cible d'une suppression récursive est listée (`ls`/`find`) dans la même
   session, immédiatement avant.
3. Jamais de masquage d'erreurs sur une commande destructrice.
4. Un répertoire créé par le propriétaire n'est ni déplacé ni supprimé par
   l'agent : l'agent le signale et propose, le propriétaire agit.
