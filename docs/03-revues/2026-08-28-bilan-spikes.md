# Bilan des cinq spikes de faisabilité

**Date :** 2026-08-29
**Objet :** clôture de la phase de conception. Pour le chapitre méthodologie du mémoire.

Cinq spikes ont été exécutés pour lever, avant l'écriture du code du mois 4, les
risques que la conception ne pouvait trancher par le raisonnement seul. **Aucun
n'a échoué.** Mais un spike réussi n'est pas un spike sans surprise : chacun a
révélé un fait qui n'était écrit dans aucun document, et c'est ce fait — non le
verdict — qui a modifié la conception. Ce bilan les rassemble, parce qu'ensemble
ils disent quelque chose sur la méthode : **la mesure voit ce que le raisonnement
suppose.**

---

## SPIKE-1 — Liaison cryptographique au dispositif (Keystore Android)

**Question.** Une clé EC P-256 du Keystore, non exportable, peut-elle signer un
canonical vérifiable par le serveur avec la seule clé publique ?

**Verdict.** RÉUSSI sur deux appareils. Signature vérifiée par Java 21, clé
privée non exportable, empreintes de canonical identiques appareil/serveur.

**Ce que la conception n'avait pas vu.** Que **la désinstallation de
l'application détruit la clé.** La conception traitait le ré-enrôlement comme un
cas limite. La mesure a montré qu'il est **fréquent** — tout changement ou
réinitialisation de téléphone l'impose — et qu'il devient donc la **principale
surface d'attaque résiduelle** du device binding. Conséquence gravée : OB-7,
l'ancienne clé n'est révoquée qu'à la fin de la probation de ré-enrôlement.

---

## SPIKE-2 — Abstraction temporelle injectable

**Question.** Peut-on évaluer des règles temporelles à un instant virtuel
arbitraire, de façon déterministe, sans que le mode d'avance de l'horloge change
les décisions ?

**Verdict.** RÉUSSI. Empreintes de décisions identiques quelle que soit la
granularité d'avance.

**Ce que la conception n'avait pas vu.** Que **l'invariance ne vient pas de
l'horloge, mais des règles.** On croyait tenir une propriété de l'abstraction
temporelle ; c'est en réalité une propriété de la forme des règles — pures,
recomptant leur fenêtre à chaque évaluation. Un compteur incrémental, la forme
naturelle et plus rapide, aurait rendu l'évaluation irreproductible sous
injection par lots **sans produire aucun symptôme visible**. Conséquence gravée :
OB-1.

---

## SPIKE-3 — Journal d'audit inaltérable

**Question.** Un journal en écriture seule au niveau du rôle base, chaîné et
ancré, résiste-t-il à l'analyste corrompu et détecte-t-il toute altération ?

**Verdict.** RÉUSSI AVEC RÉSERVES. Huit tentatives de modification refusées, six
altérations détectées et localisées, réécriture complète détectée par l'ancre
signée.

**Ce que la conception n'avait pas vu — deux fois.** D'abord, une **fonction
`SECURITY DEFINER` exposée par PUBLIC** : PostgreSQL accorde `EXECUTE` à PUBLIC
par défaut, et une telle fonction laissait l'adversaire écrire avec les droits de
l'administrateur — rendant inopérantes toutes les restrictions sur la table. Un
privilège présent dans **aucun** `GRANT` écrit (la troisième fois dans ce projet
qu'un privilège hérité de PUBLIC se cachait ainsi). Conséquence : OB-3. Ensuite,
sous concurrence, **4 589 fausses ruptures sur 5 000** : l'ordre d'insertion et
l'ordre de chaînage divergent quand l'index d'ordre est assigné hors du verrou.
Un mécanisme correct en séquentiel devient faux en concurrence, et **seule la
mesure le révèle.** Conséquence : OB-2. Un journal qui signale une altération 92
fois sur 100 cesse d'être lu.

---

## SPIKE-4 — Authentification mutuelle cœur↔PDP (mTLS)

**Question.** Deux services peuvent-ils s'authentifier mutuellement par
certificat, avec une autorité générée au démarrage, à un coût compatible avec
ENF-01 ?

**Verdict.** RÉUSSI AVEC RÉSERVES. Authentification mutuelle prouvée, surcoût
négligeable en connexions persistantes.

**Ce que la conception n'avait pas vu.** Que **le niveau de refus dépend du
terminateur, pas du mTLS.** Avec un terminateur, un adversaire sans certificat
valide **ouvre une session TLS** avec le PDP avant d'être rejeté au niveau
applicatif ; avec un autre, il est rejeté au handshake et **n'atteint jamais le
service.** Même résultat visible (refus), surface d'exposition différente.
Conséquence : OB-5, le terminateur DOIT rejeter au handshake. La mesure a aussi
séparé deux motifs de refus qu'on croyait un seul — signature invalide **et** nom
de serveur incorrect — révélant qu'un service légitime du réseau usurperait le
PDP si seule la chaîne était vérifiée. Conséquence : OB-6.

---

## SPIKE-5 — Débit d'injection du simulateur

**Question.** À quel débit peut-on injecter 500 000 transactions, et quel mode
permet de produire le jeu v1 en moins de 30 minutes ?

**Verdict.** RÉUSSI. Le jeu v1 se produit en ~3 minutes par le mode par lots,
avec des décisions prouvées identiques à l'injection unitaire.

**Ce que la conception n'avait pas vu.** **Le facteur 21 entre le débit
d'injection et le débit d'évaluation en ligne.** On aurait pu lire la marge sur
SQ-30 depuis les 2 822 transactions/s du mode par lots et conclure à une marge de
×56. Mais le régime réel de l'évaluation en production est unitaire, séquentiel,
durable : **≈ 133 écritures/s**, marge **×2,6**. Le mode le plus rapide n'est pas
le régime qui gouverne ES-09 ; confondre les deux aurait surestimé la marge d'un
facteur 21. Le plafond réel du journal d'audit est ce chiffre-là.

---

## Ce que ces cinq surprises ont en commun

Chacune est un écart entre ce que la conception supposait et ce que le système
fait réellement, et **aucune n'aurait produit de symptôme visible** si elle était
restée ignorée : une clé qu'on croit durable, une invariance qu'on attribue au
mauvais composant, un privilège qui n'apparaît dans aucun GRANT, un mécanisme
correct en séquentiel et faux en concurrence, un refus au mauvais niveau, une
marge de performance lue sur le mauvais régime. Toutes auraient été découvertes au
mois 4 sous charge réelle — trop tard pour être bon marché. Les sept obligations
du §1bis du SAD v1.1 sont la trace écrite de ces surprises : elles imposent, à
chaque fois, la forme correcte au prix de la forme rapide, précisément parce que
la forme rapide échoue en silence.
