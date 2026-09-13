# Incident Docker Desktop — corruption du backend pendant le build de la console (2026-09-13)

## Résumé

En reprenant T2-09 (console v1), la construction de l'image console (`next build` complet dans le conteneur) a déclenché une corruption du stockage de Docker Desktop sur le poste Windows, rendant **toute** exécution de conteneur Linux impossible. Le poste n'a pas été réparé : la construction et la validation de la console ont été **basculées sur la VM `isc-vm`**, où tout est passé (voir [ADR-026] et la note de validation).

## Environnement

- Poste Windows 10 Pro, Docker Desktop 29.7.2, backend WSL2. Mémoire allouée à Docker : ~7,6 Gio, 8 CPU. Docker Desktop tournait normalement au départ.
- La panne **n'est pas** un manque de mémoire : le `next build` n'a jamais atteint la limite mémoire.

## Déroulé (constaté)

1. **Contexte de build énorme** : `console/` n'avait pas de `.dockerignore` ; le contexte expédié atteignait **444 Mo** (`node_modules`/`.next`). Correctif appliqué : ajout de `console/.dockerignore` (contexte réduit à l'essentiel).
2. **`read-only file system`** : premier build console échoue en écrivant un snapshot containerd — `write .../io.containerd.snapshotter.v1.overlayfs/snapshots/535/fs/...: read-only file system`.
3. **`input/output error`** : `docker system df` échoue — `snapshotter.Usage failed ... lstat .../snapshots/522/fs: input/output error`. Le stockage overlayfs est corrompu.
4. **Premier redémarrage** (arrêt process + `wsl --shutdown` + relance) : efface l'erreur I/O de `docker system df`, mais laisse le backend dans un état où il ne peut plus exécuter de binaire Linux.
5. **`exec format error` généralisé** : `docker run --rm alpine echo ok` → `exec /bin/echo: exec format error`, sur image amd64 / hôte x86_64 (pas un problème d'architecture). Même une image `node:22-alpine` re-téléchargée fraîche échoue.
6. **Deuxième redémarrage** (arrêt complet service + app, `wsl --shutdown`, délais plus longs, ~175 s de retour) : **inchangé**, `exec format error` persistant.

Conclusion : corruption du backend WSL2/containerd non récupérable par redémarrage. La remise en état demanderait un « Clean/Purge data » de Docker Desktop (destructif : efface images/volumes) ou une réparation/réinstallation WSL2.

## Ce qui a été fait à la place

Bascule sur la VM `isc-vm` (Linux natif, saine, Internet depuis le 30/08). Push de `feat/T2-09-console` (levée tracée), `git pull` dans la VM, `make up`, build console complet, `verify_console.sh` **VERT** (transaction affichée en ~310 ms, très en dessous des 2 s du critère #10). Le `next build` complet y prend ~86 s ; l'image console complète ~8 min. La console n'est donc **pas** structurellement trop coûteuse — elle l'est sur Windows, pas sur Linux.

## Conduite à tenir si cela se reproduit

**On ne répare pas le Docker du poste, on bascule sur la VM.** Raisons :
- Quatre occurrences du même couple Docker/Next.js sur ce poste (prerender cassé, EPERM `.next`, plantage mémoire, corruption containerd) : c'est un couple instable, pas un incident isolé.
- Réparer aujourd'hui ne dit rien sur demain ; une cinquième occurrence le jour de la soutenance ferait tomber la démonstration.
- ISC-DEPLOY §5.2 impose déjà la VM pour toute mesure publiable — `verify_console.sh` mesure un délai, il n'avait rien à faire sur Windows.

Procédure de bascule : pousser la branche (levée de la 8e interdiction, tracée), `git pull` dans la VM, `make up`, `verify_console.sh`. Le poste Windows garde uniquement la compilation légère (`make check`, mode compile), conformément à [ADR-026] désormais FERME.

[ADR-026]: ../01-decisions/ADR-026.md
