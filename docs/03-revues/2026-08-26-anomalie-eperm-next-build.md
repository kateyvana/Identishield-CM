# Anomalie d'environnement — EPERM transitoire sur le build Next.js (Windows)

| Champ | Valeur |
|---|---|
| Date de première observation | 26 août 2026, ~17h30 (heure locale) |
| Fréquence observée | 1 occurrence sur ~8 exécutions de `make check` ce jour |
| Statut | DOCUMENTÉ, NON CORRIGÉ — décision d'encadrement |

## Symptôme

`make check` échoue à la porte `build` (étape console) avec :

```
errno: -4048, code: 'EPERM', syscall: 'open',
path: '...\console\.next\required-server-files.js'
```

La relance immédiate de `make check` passe au VERT sans aucune modification.
Le fichier `.next/required-server-files.js` est ensuite resté verrouillé en
suppression (`rm: Permission denied`) pendant quelques minutes, puis a pu être
recréé normalement par le build suivant.

## Constaté au moment de l'incident

- Aucun processus `node.exe` vivant (`tasklist` vide) : le verrou n'était pas
  tenu par un build précédent.
- Le dossier `.next/` est non suivi par git (ignoré) : aucune conséquence sur
  le dépôt.

## Cause supposée

Verrouillage de fichier opportuniste par un scanner (antivirus/Windows
Defender) pendant que `next build` réécrit `.next/`. SUPPOSÉ : non prouvé —
aucun journal Defender n'a été corrélé.

## Correctifs écartés, et pourquoi

- `rm -rf console/.next` systématique avant le build dans le Makefile :
  masquerait le symptôme et ferait perdre l'information de récurrence, sans
  traiter la cause. Écarté par l'encadrement (TACHE-02).
- Exclusion antivirus du répertoire du projet : réglage système hors du
  périmètre de l'agent ; à l'appréciation du propriétaire si le phénomène
  devient fréquent.

## Conduite à tenir si récurrence

Relancer `make check` une fois. Si l'échec persiste ou devient fréquent
(> 1/10 exécutions), consigner ici la date et la fréquence, puis envisager
l'exclusion antivirus ou le déplacement du build console dans un conteneur
(cohérent avec ISC-DEPLOY §6.2 — la construction en conteneur est de toute
façon la cible).
