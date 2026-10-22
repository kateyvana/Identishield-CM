LEVÉE DE LA 8e INTERDICTION — CARTE A4

Autorité : superviseur technique du projet, 17 octobre 2026.

Objet : la 8e interdiction, révisée, interdit à une session d'ouvrir une
pull request ou de pousser une branche sans levée écrite et nominative. La
levée est accordée pour la carte A4, et pour elle seule.

Portée : les commits c4a1167 (carte A4), ab44b64 (correction), et le présent
commit de levée. La levée ne couvre AUCUN autre commit, AUCUNE autre branche,
AUCUNE autre carte. Elle ne se reconduit pas.

Autorisé : pousser t4-04-branchement-evaluate, ouvrir la pull request,
attendre la CI.

Interdit : merger. Le merge appartient au propriétaire du dépôt.

Porte de fusion (inchangée, FUSION RENFORCÉE) : make check VERT ET CI VERTE.
Une CI rouge n'est pas un détail d'environnement : la porte locale est plus
faible que la porte CI, c'est démontré (ruff EXE001 invisible sous Windows).

Précondition de déploiement, déclarée : init.sql a changé. Une ligne
trust_state en PROBATION antérieure à A4 a probation_entered_at et
probation_entry_balance_xaf à NULL ; Trust.probationEntryBalanceXaf est un
Long déboîté en long à l'appel du véto, donc une telle ligne produit une NPE
et un 500. Les nouveaux CHECK ne protègent qu'une base RECRÉÉE. make reset
est donc une précondition dure du déploiement d'A4, journal d'audit exporté
et chaîne confirmée intacte d'abord — fait le 17 octobre 2026
(audit-export-2026-10-17.csv, 8 entrées, 0 rupture, ancre conforme).

Défaut connu, NON corrigé par A4, consigné pour ne pas être oublié :
TransferService:48, PLACEHOLDER_IMSI = "624010000000000", envoyé par orElse
quand le HLR ne connaît pas le numéro. Ce placeholder n'existe que dans le
cœur ; le PDP ne peut pas le distinguer d'un vrai IMSI et, depuis A4, le
persiste comme IMSI de référence. Un HLR renseigné après la première
transaction produit alors une PROBATION de 72 h sur un utilisateur légitime,
sans aucun SIM-swap. Ce n'est pas un trou de sécurité (l'attaquant ne choisit
pas currentImsi, et un vrai swap produit toujours une ligne
hlr_reassignment, donc imsiChangedAt, donc PROBATION) : c'est un faux
positif. Il est atteignable sur la base de démonstration, puisque make reset
vide hlr_subscriber. Carte A5-00, BLOQUANTE avant la démonstration de
soutenance.

---

AJOUT DU 22 OCTOBRE 2026 — sur ordre du superviseur technique (messages des 21
et 22 octobre 2026). Ces sections ne faisaient pas partie du texte de levée du
17 octobre ; elles sont ajoutées ici, après lui.

Portée étendue par le superviseur technique (ordre du 22 octobre 2026 : « make
check, pousse, CI ») : le commit 6d8d334 (banc de latence) et le présent
commit. L'interdit est inchangé : merger appartient au propriétaire du dépôt.

Limite fermée. L'écriture de la décision et son effet sont désormais
dans une seule transaction. Le gel concurrent du même compte (Refused de
frozenByDecision) n'annule que son propre point de sauvegarde : l'entrée
d'audit est conservée et la réponse reste FREEZE. Le mécanisme repose sur
PROPAGATION_NESTED, tenu par les mutations M7, M8 et M9, toutes trois
tuées sur vraie base (M8 échoue en UnexpectedRollbackException —
rollback-only, le mode d'échec exact prédit avant écriture). Retirer le
point de sauvegarde rouvre la perte d'une entrée d'audit sans aucun
signal.

Correction du superviseur technique : nestedTransactionAllowed n'est PAS à
false par défaut ; DataSourceTransactionManager le met à true dans son
constructeur. Aucune configuration n'était nécessaire et aucune n'a été
ajoutée : le seul réglage est setPropagationBehavior(PROPAGATION_NESTED) dans
le constructeur de TrustStateMachine.

Latence. Budget : ENF-01 gouverne /evaluate (p95 < 500 ms, p99 < 800 ms sous
SQ-30), arbitrage ratifié par le superviseur technique le 22 octobre 2026. Les
800 ms d'EI-01 sont le délai d'attente du cœur sur l'appel, une borne
d'interface, pas un objectif de performance. T3-07 ne mesure pas ce tronçon
(T307ReplayTest appelle RuleEngine seul, sans contrôleur, journal ni
transaction) : la mesure vient d'un banc du contrôleur réel sur base jetable,
exécuté DANS la VM, versionné avec sa méthode et ses résultats bruts dans
tools/bench_evaluate/ (commit 6d8d334). Deux passages, les 21 et 22 octobre
2026, commits c4a1167 et ab44b64 alternés.

  - Séquentiel, médiane : ALLOW 33 → 38 ms (21/10), 35 → 42 ms (22/10) ;
    FREEZE 66 → 70 ms, 71 → 81 ms. L'écart imputable au commit 1 est de 4 à
    10 ms selon le passage.
  - Saturation (16 fils) : débit de 28 à 37 req/s avec c4a1167, de 23 à 32 req/s
    avec ab44b64 ; soit −15 à −37 % selon le tour.
  - À 50 req/s : p95 de 6 à 10 s sur LES DEUX commits.

Réserve : le mélange mesuré (50 % de FREEZE, comptes neufs) n'est pas celui de
la spécification, donc AUCUN verdict ENF-01 n'est rendu. Seul est établi que
NI c4a1167 NI ab44b64 ne tiennent 50 req/s sur ce mélange.

Défaut de spécification, consigné comme tel. SQ-30 (ISC-ADD v1.0, §5.4) dit :
« Le générateur soumet 50 transactions/s pendant 10 min sur la machine de
démonstration, PDP nominal ; mesure : /evaluate p95 < 500 ms, p99 < 800 ms,
aucune décision perdue. » Il chiffre un débit sans énoncer la charge : ni part
de FREEZE, ni état des comptes, ni canal. ISC-EVAL, ISC-TEST et le SRS ne la
donnent pas davantage (la prévalence de 0,5 % d'ISC-EVAL §3.4 porte sur les
campagnes d'évaluation, pas sur SQ-30). En l'état, ENF-01 n'est pas vérifiable.

Carte débit, à créer (critère : ENF-01, une fois le mélange de SQ-30 défini).
Premier levier à EXAMINER, très avant la transaction du commit 1 : une requête
FREEZE écrit trois entrées chaînées (TRUST_PROBATION_ENTERED, FREEZE,
TRUST_FROZEN), et chacune prend le verrou FOR UPDATE sur pdp.chain_head, le
point de sérialisation global du PDP ; un ALLOW n'en écrit qu'une. Avec 50 % de
FREEZE, c'est probablement l'essentiel de la saturation mesurée. À examiner,
pas à corriger dans A4. Le commit 1 reste inchangé (décision du superviseur
technique du 22 octobre 2026).

Dérive du contrat, consignée : l'exemple de contracts/evaluate.yaml annonce
latencyMs: 18, alors qu'un ALLOW séquentiel mesure 33 à 42 ms côté serveur.
À corriger par le couloir A dans une carte ultérieure ; evaluate.yaml n'est pas
modifié ici, il fait partie de la pull request ouverte.

Ordre après la fusion : (1) CI-01, portes-lourdes sans exécuteur (cause de
l'état UNSTABLE sur GitHub) ; (2) make reset, puis AC-02 de bout en bout sur la
pile ; (3) A5-00.
