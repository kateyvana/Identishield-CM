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
