# Amendements de spécification proposés — calibration T3-07 (ADR-016)

- **Date** : 2026-10-06
- **Statut** : **PROPOSÉ**, sauf ES-11 : **RATIFIÉ** (ratifié par la propriétaire le 2026-10-15). ~~Rien n'est ratifié.~~ L'agent de codage ne modifie jamais `docs/02-specifications/` (CLAUDE.md §2) ; la ratification, et la version des documents qui en découle, appartiennent au propriétaire.
- **Origine** : étude hors ligne T3-07 (`docs/03-donnees/calibration-t3-07/`) ; ADR-016 (PROVISOIRE) ; décisions du propriétaire du 2026-10-06.
- **Configuration correspondante** : `config/ruleset.yaml` rs-0.8.0 (rs-0.6.0 avant renumérotation à l'intégration), écrite **à titre provisoire** avec l'accord du propriétaire.

## Décisions du propriétaire consignées (2026-10-06)

1. **USSD = BLOQUER.** « Le cas où un renforcement (signature du téléphone) est impossible faute d'appareil lié ESCALADE vers CANCELLED, et non vers un report. » C'est le scénario « plafond » de T3-07, où le TPR sur AC-02 sans IMSI atteint 90,0 %.
2. **Écriture PROVISOIRE.** ADR-016 reste PROVISOIRE et n'est pas fermé. Les amendements ci-dessous sont rédigés au statut PROPOSÉ, pas ratifiés.

## Amendements proposés

| Réf. | Texte actuel | Texte proposé | Motif (mesure T3-07) |
|---|---|---|---|
| **ISC-TM §7** (ajout) | Les poids des règles ne sont pas nommés ; seul NIGHT +25 est cité en AC-06 | Ajouter les paramètres `SCORE` (bounded_sum, max 100, STEP_UP 40, FREEZE 70) et `W` (nouveau bénéficiaire 15, nouveau + THR_HIGH 45, VEL 70, NIGHT 25) | Le SAD §12 prévoit que « les poids des règles vivent dans une configuration versionnée » ; ADR-016 |
| **ISC-TM §7**, CAP_PROB | min(25 000 FCFA/jour ; 10 % du solde) | min(25 000 FCFA ; 10 % du solde **à l'entrée en PROBATION**), sur un **cumul de 24 h glissantes** | Lève les deux ambiguïtés : jour civil ou glissant, solde courant ou solde d'entrée |
| **ISC-TM §7**, fail-closed | « Activé pour toute sortie > CAP_PROB » | « Activé pour toute sortie > **CAP_FAILCLOSED** (50 000 FCFA) » | Avec CAP_PROB à 25 000, H5 est réfutée pour les salariés (2,67 % > 2 %). À 50 000 : 1,91 % pour les salariés, et moins de 2 % pour chaque persona public |
| **ES-11** — ~~PROPOSÉ~~ **RATIFIÉ** (ratifié par la propriétaire le 2026-10-15) | « …le cœur DOIT refuser toute sortie > CAP_PROB… » | « …le cœur DOIT refuser toute sortie > **CAP_FAILCLOSED**… » | idem. ~~**Le cœur n'est pas encore recâblé** : il lit `CAP_PROB.dailyXaf` tant que l'amendement n'est pas ratifié~~ Le cœur est recâblé : `FailClosedPolicy` lit `CAP_FAILCLOSED.xaf` (branche `t4-03-es11-fail-closed`) |
| **ES-06** | « Sur USSD, un STEP_UP DOIT se résoudre par une politique configurée (DEFER, plafond, contact de confiance)… » | « Sur USSD, un STEP_UP pour lequel aucun renforcement par signature du dispositif n'est possible DOIT passer à CANCELLED (politique `STEP_UP_POLICY.ussd = CANCEL`) ; JAMAIS par une question de connaissance. » | Décision du propriétaire du 2026-10-06. Le report (DEFER) ramène le TPR sur AC-02 sans IMSI à 23,7 % |
| **ED-04** | Horodatages UTC serveur ; NIGHT en heure serveur | Horodatages **toujours** UTC serveur ; la fenêtre NIGHT s'évalue en heure **locale** du fuseau `NIGHT_ZONE` (`Africa/Douala`, WAT = UTC+1) | La fenêtre nocturne est un fait de comportement local. Le fuseau du CSV réel est **supposé** WAT, ce qui est déclaré |

## Preuve exigée avant l'écriture : FPR sous la politique de blocage

Le FPR a été recalculé **sous la politique de blocage** (`verify_block.py`, avec les valeurs exactes de rs-0.8.0 (rs-0.6.0 avant renumérotation à l'intégration)). Aucun taux calculé sous l'hypothèse « renforcer » n'a été repris. Le pire cas public est ordinaire, à **0,813 %** des transactions USSD [0,75–0,88]. Le seuil EVAL (< 5 % par persona) est tenu. Le détail figure dans ADR-016 §3, qui déclare aussi la lecture par usager (un usager USSD ordinaire sur trois touché au moins une fois en 152 jours).

## Ce qui reste ouvert (propriétaire)

- Ratifier ou amender les ~~six~~ cinq lignes restantes ci-dessus (ES-11 ratifié par la propriétaire le 2026-10-15). ~~Il faudra ensuite recâbler ES-11 dans le cœur (`CAP_FAILCLOSED`).~~ ES-11 est recâblé dans le cœur.
- Décider de la place des agents (FPR 4,93 %) dans H2.
- Inscrire au protocole du mois 5 un rodage de 30 jours d'historique avant la mesure.
- Fermer ADR-016 après la confirmation du TPR au mois 5.
