# Limites déclarées du correctif D-B7-1 (PR #124, fusionnée le 2026-10-25)

Date : 2026-10-25. Couloir B (Mobile). Référence : qualification
`2026-10-25-qualification-D-B7-1.md`, PR #122 (reproduction), PR #124 (correctif application + ligne
WARN du cœur + outil `tools/device_register_cold/`), PR #123 (préchauffage du PDP, couloir A).
Décisions du superviseur technique du 2026-10-25 (délai `core.device.timeout-ms = 1000` inchangé).

| # | Limite | Pourquoi elle est acceptée | Où c'est écrit |
|---|---|---|---|
| L-DB71-1 | **Flux D : pendant la fenêtre de rejeu après un 503, la requête mémorisée (`pendingComplete`) porte l'ancien code de récupération** (il part avec l'attestation à `/enrolment/complete`, ADR-041). | Mémoire vive seulement : **jamais persisté** (ni `SavedStateHandle` ni disque) ; **effacé au succès, au refus, à la connexion perdue, à l'abandon** (`begin`, qui l'efface depuis a19495c) **et par la mort du processus** (D4 : retour au PIN). Depuis l'état 503 il **n'existe pas d'autre sortie** : seul « Réessayer » (`onRestart` ne sert qu'à DEVICE_REFUSED et SESSION_EXPIRED) ; le retour arrière est inactif pendant la liaison ; `beginWithNumber` n'est pas joignable pendant un enrôlement. Le secret existe en mémoire exactement le temps où l'usager peut appuyer sur « Réessayer », comme `pendingRecoveryCode` le faisait déjà avant le correctif. Compté par `holdsSecrets()`. Accepté au titre de la **charte §10.5** (relecture ligne à ligne du couloir A, décision du superviseur). | `EnrolmentViewModel.kt`, commentaire de `pendingComplete` |
| L-DB71-2 | **« Connexion perdue »** (`ApiResult.Unreachable` : client hors délai ou réseau tombé pendant `/enrolment/complete`) **reste tel quel** : clé locale détruite, l'usager recommence. | Le PDP absorbe une éventuelle réinscription par la règle « une seule clé EN ATTENTE » (décision du superviseur, point 4 du 2026-10-25). | PR #124, corps |
| L-DB71-3 | La requête mémorisée pour le rejeu ne survit pas à la mort du processus. | D4 : rien n'est persisté ; retour au PIN, numéro conservé. | PR #124, corps |
| L-DB71-4 | La ligne WARN du cœur porte le **délai configuré**, pas une durée mesurée. | Le cœur ne lit pas d'horloge hors `Clock` (ADR-019) ; la route et le délai suffisent à lire la cause. | `DeviceClient.java` |
| L-DB71-5 | Sur les écrans à saisie (E-06, E-05, E-20, E-09), le rejeu après un 503 est la **resoumission de la même saisie** par l'usager ; seul E-07 rejoue sans saisie. | Les saisies (PIN, OTP, code) ne sont pas conservées entre deux envois : la saisie est le rejeu. | PR #124, corps |
| L-DB71-6 | Correctif **non observé sur appareil** à ce jour ; le 503 n'a pas été rejoué **à travers le cœur** (la mesure #122 porte sur le PDP directement, composant visé par le délai). | Répétition générale ≈ 3/11 (compte 677001102) ; preuve du préchauffage PDP par le couloir A (fenêtre 6 : trois redémarrages, premier `/device/register` complet < 1 000 ms, outil de la PR #124). | ce document |
