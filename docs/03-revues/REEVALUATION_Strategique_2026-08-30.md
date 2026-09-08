# Réévaluation stratégique — IdentiShield-CM

| Champ | Valeur |
|---|---|
| Date | 30 août 2026 |
| Auteur | Encadrement technique |
| Objet | Confrontation du projet aux solutions réellement déployées, aux angles morts de conception, et aux situations de la vie réelle jamais analysées |
| Posture | Impartiale. Ce document cherche ce qui peut détruire le projet en soutenance, pas ce qui le rassure. |

---

## 1. Ce que la recherche a établi, et qui change le positionnement

Tu avais raison de me demander de vérifier. Voici ce qui est documenté au Ghana, et cela ne figure nulle part dans ton état de l'art.

**MTN Ghana bloque déjà la réinitialisation du code PIN pendant les 48 heures qui suivent une réattribution de SIM.** Mesure annoncée en 2021. Ton `CAR_IMSI` de 72 heures n'est donc pas une idée neuve : c'est une pratique industrielle confirmée depuis cinq ans, dans un pays voisin, chez le même opérateur.

**MTN Ghana propose un verrouillage anti-échange de SIM**, gratuit, activable par l'utilisateur lui-même. La presse spécialisée le décrit comme la défense la plus efficace disponible.

**L'application MTN MoMo prend en charge le déverrouillage biométrique.** L'argument avancé publiquement est exactement le tien : même en possession de la SIM, un voleur n'ouvre pas l'application.

**MTN Ghana a lancé l'eSIM fin 2024**, ce qui supprime la carte physique et rend l'échange traditionnel impossible.

**Et pourtant.** Entre janvier et septembre 2025, la Banque du Ghana a enregistré 1 847 cas d'échange de SIM liés au vol d'argent mobile, pour plus de 4,2 millions de cedis. La hausse des cas liés aux échanges de SIM est de 340 % entre 2022 et 2025. Les cas de fraude électronique chez les prestataires de paiement ont augmenté de 54 % sur un an, et la valeur exposée de 95 %.

**Toutes ces mesures existent, et la fraude explose quand même.** C'est le fait le plus important de cette recherche, et c'est aussi ce qui sauve ton projet — à condition de le formuler correctement.

---

## 2. Les quatre questions qu'un jury informé posera désormais

### Q1 — « MTN bloque déjà pendant 48 heures. Qu'apportez-vous ? »

**Réponse honnête** : la période de blocage n'est pas ma contribution, et je ne l'ai jamais présentée comme telle. Ce qui est bloqué au Ghana, c'est la **réinitialisation du code PIN**, pas la transaction. Un attaquant qui a obtenu le PIN par ingénierie sociale — ce qui est le cas dominant — n'a pas besoin de le réinitialiser. Il transige directement.

Ma couche évalue **chaque transaction**, pas seulement le parcours de réinitialisation. Et elle mesure ce que personne ne publie : le coût en friction de cette protection.

**Ce que ça impose** : ajouter cette distinction dans l'état de l'art. Sans elle, tu parais ignorer l'existant.

### Q2 — « Le verrouillage anti-échange empêche l'attaque. Pourquoi détecter au lieu d'empêcher ? »

C'est la question la plus dangereuse, et **le projet ne l'a jamais analysée**.

**Réponse honnête, en trois temps.**

D'abord, le verrouillage est **volontaire**. Il faut le connaître, le comprendre, l'activer. Les mesures optionnelles protègent ceux qui sont déjà informés, pas ceux qui se font voler.

Ensuite, il repose sur la procédure de l'opérateur. Les sources ghanéennes et camerounaises documentent des cas de **complicité interne** : un agent corrompu peut lever le verrou comme il peut effectuer l'échange. Une protection qui vit chez celui qu'on ne peut pas exclure du modèle de menace est une protection conditionnelle.

Enfin, et c'est le point structurant : **empêcher est un contrôle d'opérateur, détecter est un contrôle de système.** Un chercheur ne peut pas étudier le premier — il n'a accès ni aux procédures d'agence ni aux systèmes de reprogrammation. Il peut étudier le second. Ce n'est pas un choix par facilité, c'est le périmètre du possible.

**Ce que ça impose** : une section « prévention contre détection » dans le mémoire, qui reconnaît que la prévention est supérieure quand elle fonctionne, et qui explique pourquoi la détection reste nécessaire.

### Q3 — « L'application MoMo a déjà la biométrie. En quoi votre liaison à l'appareil est-elle différente ? »

**Le projet n'a jamais comparé les deux.** C'est un trou réel dans l'état de l'art.

**La différence est technique et elle est décisive.** Le déverrouillage biométrique est un **verrou local** : le téléphone décide si l'utilisateur peut ouvrir l'application. Le serveur ne voit rien de cette vérification. Il ne peut ni la vérifier, ni savoir si elle a eu lieu, ni distinguer une application ouverte par empreinte d'une application ouverte autrement.

La liaison cryptographique produit une **preuve vérifiable par le serveur** : la signature ne peut être produite que par la clé de cet appareil, et le serveur le vérifie mathématiquement. Un attaquant qui réinstalle l'application sur son propre téléphone franchit un verrou biométrique — le sien — mais ne produit aucune signature valide.

**Ce que ça impose** : ajouter cette comparaison à l'état de l'art. C'est un argument fort, et actuellement il n'est écrit nulle part.

### Q4 — « L'eSIM rend l'échange de SIM impossible. Votre projet ne devient-il pas obsolète ? »

**Réponse honnête** : l'eSIM supprime la carte physique, pas la réattribution de numéro. Le profil est provisionné à distance, donc l'échange devient une opération logicielle — potentiellement plus rapide et plus discrète.

Et sur le terrain camerounais, la base installée reste massivement composée d'appareils sans eSIM, dont beaucoup de téléphones basiques qui n'en auront jamais.

**Ce que ça impose** : une ligne dans les limites et les travaux futurs. Ne pas l'ignorer.

---

## 3. Le repositionnement qui s'impose

L'ancienne formulation disait que les briques existent mais ne sont pas assemblées. Elle reste vraie mais devient **insuffisante**, parce qu'un jury informé sait maintenant que plusieurs briques sont déployées et documentées.

**Nouvelle formulation, à substituer dans le dossier de cadrage et le mémoire :**

> Les opérateurs déploient des contre-mesures réelles — verrouillage volontaire de l'échange de SIM, blocage temporaire de la réinitialisation du code, déverrouillage biométrique de l'application, eSIM. Ces mesures partagent trois propriétés : elles sont **optionnelles**, donc protègent surtout les utilisateurs déjà informés ; elles sont **locales ou procédurales**, donc non vérifiables par le système d'autorisation ; et leur efficacité réelle **n'est publiée nulle part**. Pendant leur déploiement, les pertes déclarées ont continué de croître fortement.
>
> Ce travail apporte trois choses qui n'existent pas : une décision graduée par transaction plutôt qu'une protection par parcours ; une preuve vérifiable côté serveur plutôt qu'un verrou local ; et une mesure quantifiée du couple protection-friction, y compris sur le canal USSD où aucune cryptographie n'est possible.

Cette formulation est vraie, vérifiable, et elle résiste aux quatre questions.

---

## 4. Les workflows : le trou de conception le plus large

Tu as raison, et c'est le point le plus juste de ta remarque. Le projet a des **cas d'utilisation** — préconditions, flux principal, flux alternatifs — mais il n'a pas de **workflows**.

**La différence.** Un cas d'utilisation décrit un parcours. Un workflow décrit un **cycle de vie complet** : tous les états d'un objet, toutes les transitions, tous les acteurs, tous les déclencheurs, y compris les impasses et les expirations.

Le projet possède deux machines à états — celle du compte, celle de la transaction. Il en manque **six**.

| # | Workflow manquant | Pourquoi c'est grave |
|---|---|---|
| W-1 | **Cycle de vie du compte** : comment un compte naît-il ? Qui l'ouvre, avec quelle vérification d'identité ? | Le projet suppose des comptes existants. L'ouverture est le premier moment de vérification d'identité, donc la première surface d'attaque. Jamais discutée. |
| W-2 | **Cycle de vie du code PIN** : définition, changement, oubli, blocage après échecs | Deux trous déjà identifiés (E-05, F-03). Le parcours d'oubli de PIN est une voie de récupération, donc une porte d'entrée. Un attaquant qui a le numéro peut forcer le PIN : rien ne l'en empêche dans le système actuel. |
| W-3 | **Cycle de vie du contact de confiance** : désignation, acceptation, réponse, révocation, expiration | Neuf défauts recensés dans l'audit. C'est la protection de secours du système, et elle est optionnelle, incomplète et révocable sans protection. |
| W-4 | **Cycle de vie de l'appareil** : enrôlement, ré-enrôlement, annulation, révocation, appareils multiples | Partiellement couvert. Le cas « plusieurs appareils » n'est ni interdit ni spécifié. |
| W-5 | **Cycle de vie du gel** : création, notification, levée, expiration, escalade, contestation | La levée est spécifiée. La **contestation** ne l'est pas : que fait un utilisateur qui estime le gel injustifié ? |
| W-6 | **Parcours de réclamation** : un utilisateur affirme avoir été victime. Que se passe-t-il ? | Totalement absent. C'est pourtant ce qui suit chaque fraude réelle, et ce que les victimes ghanéennes décrivent comme le plus douloureux. |

**Ce que ça impose** : un document ISC-FLOW, à produire avant le mois 4, décrivant ces six cycles de vie avec leurs états et leurs transitions. Ce n'est pas de la documentation d'agrément — c'est ce qui empêche de découvrir au mois 4 qu'un parcours n'a pas d'issue.

---

## 5. Situations de la vie réelle jamais analysées

Voici ce que le système ferait aujourd'hui, dans des situations courantes au Cameroun. Certaines réponses sont inconfortables.

| # | Situation | Ce que le système fait aujourd'hui | Verdict |
|---|---|---|---|
| S-1 | **L'utilisateur n'a ni smartphone ni contact de confiance.** C'est le cas majoritaire. | Aucune preuve de possession, aucune voie hors-bande. Il ne reste que la probation qui expire et le score comportemental. | **Trou majeur.** C'est la population la plus exposée, et c'est celle qui bénéficie le moins du dispositif. À énoncer comme résultat, pas à cacher. |
| S-2 | **Le contact de confiance est l'attaquant.** La fraude par un proche est très fréquente au Cameroun. | Le contact confirme le ré-enrôlement frauduleux. Le système lui fait confiance par construction. | **Trou réel.** Jamais discuté. Atténuation possible : exiger deux contacts, ou empêcher un contact d'être aussi bénéficiaire. À trancher. |
| S-3 | **Téléphone partagé** entre plusieurs membres d'une famille. Très courant. | Un seul appareil enrôlé, une seule clé. Les autres utilisateurs n'ont pas de preuve d'appareil. | Non spécifié. À déclarer hors périmètre ou à traiter. |
| S-4 | **L'utilisateur change de numéro** volontairement. | Le compte est lié au MSISDN. Rien n'est prévu. | À déclarer hors périmètre. |
| S-5 | **Transfert entre opérateurs** (MTN vers Orange). | Hors périmètre implicite, jamais déclaré. | À déclarer. |
| S-6 | **L'agent Mobile Money est complice** au moment du retrait en espèces. | Le modèle couvre l'agent complice au moment de l'échange de SIM, pas au moment du retrait. | Angle mort. Le retrait est le dernier maillon : c'est là que l'argent sort. |
| S-7 | **Pas de réseau de données.** L'USSD fonctionne sans données, l'application non. | L'application est inutilisable hors couverture données. Le canal protégé est donc le moins disponible. | **Ironie structurelle** à énoncer : le canal le mieux protégé est celui qui fonctionne le moins souvent. |
| S-8 | **L'utilisateur perd son téléphone ET sa SIM** au même moment (vol du sac). | Il doit refaire une SIM chez l'opérateur — donc déclencher lui-même l'événement que le système traite comme suspect. Puis probation. | Cohérent, mais la friction pour la victime réelle est maximale au pire moment. À mesurer. |

---

## 6. Le paradoxe de l'application mobile

Tu l'as senti sans le nommer. Je le formule.

**Le sujet de ta thèse est le point de décision.** L'application n'en fait pas partie : dans un déploiement réel, MTN intégrerait ta couche dans **son** application existante. Tu construis donc une application dont le projet n'a pas besoin en soi.

**Pourquoi elle est nécessaire quand même** : il faut un client capable de détenir une clé et de signer, sinon la liaison à l'appareil ne peut pas être démontrée. L'application est un **support de démonstration**, pas une contribution.

**La conséquence, et elle est importante** : son périmètre doit rester minimal, et il l'est — neuf exigences, quelques écrans. Toute heure passée à embellir cette application est une heure prise sur le point de décision, qui est le vrai sujet.

**À écrire dans le mémoire** : l'application est un client de démonstration. Elle n'est ni une contribution, ni un produit.

---

## 7. Le cahier des charges est-il complet ?

Structurellement, oui : il suit ISO 29148, il porte 117 exigences tracées, priorisées, vérifiables.

**Fonctionnellement, non.** Il lui manque des exigences sur cinq parcours :

- l'ouverture de compte et la vérification d'identité initiale (W-1) ;
- le cycle de vie du code PIN, y compris le blocage après échecs (W-2, E-05) ;
- l'oubli du code PIN (F-03) ;
- la révocation d'un contact de confiance (A-08) ;
- la réclamation après fraude (W-6).

Trois d'entre eux peuvent légitimement être déclarés hors périmètre — mais **déclarés**, pas oubliés. Un jury distingue immédiatement une exclusion assumée d'un angle mort.

Les deux autres — le blocage après échecs de PIN et la révocation de contact — sont des surfaces d'attaque directes. Elles doivent devenir des exigences.

---

## 8. Le cadrage est-il complet ?

Il l'était. Il ne l'est plus, pour deux raisons.

**L'état de l'art est incomplet.** Il ignore ce qui est déployé au Ghana par le même opérateur, et il ne compare pas la liaison cryptographique au déverrouillage biométrique. Ces deux manques sont corrigibles en une journée de lecture et deux paragraphes.

**Le cadre réglementaire n'est pas traité.** Le dossier cite la COBAC, l'ART et l'ANTIC comme parties prenantes contextuelles, mais aucune exigence ne découle d'une obligation réglementaire. Pour un travail sur un service financier, c'est une lacune qu'un jury académique relèvera.

---

## 9. Verdict

**Le projet n'est pas invalidé.** La question de recherche — que reste-t-il à protéger quand aucune cryptographie n'est possible, et à quel coût de friction — reste ouverte et non publiée pour le contexte camerounais.

**Mais trois choses doivent changer avant la soutenance.**

Le positionnement, parce qu'il repose actuellement sur une méconnaissance de l'existant qu'un jury informé détectera.

Les workflows, parce que six cycles de vie n'existent pas et que trois d'entre eux portent des surfaces d'attaque.

Les situations réelles, parce que deux d'entre elles — l'utilisateur sans smartphone ni contact, et le contact de confiance malveillant — touchent le cœur du dispositif et ne sont pas traitées.

**Aucun de ces trois points n'exige de refonte architecturale.** Ils exigent des exigences supplémentaires, des workflows écrits, et un état de l'art honnête.

**Ce qui serait fatal, en revanche**, c'est d'arriver en soutenance en affirmant que personne n'a traité le problème. C'est faux, c'est vérifiable en trois minutes, et cela discréditerait tout le reste — y compris ce qui est solide.

---

## 10. Ce que je recommande, dans l'ordre

1. Compléter l'état de l'art : mesures déployées au Ghana, comparaison biométrie contre liaison cryptographique, eSIM. Une journée.
2. Réécrire la formulation de la contribution selon le §3.
3. Produire ISC-FLOW : les six cycles de vie manquants. Avant le mois 4.
4. Ajouter les exigences sur le blocage après échecs de PIN et la révocation de contact.
5. Déclarer explicitement hors périmètre : ouverture de compte, oubli de PIN, changement de numéro, interopérabilité, téléphone partagé, réclamation.
6. Traiter S-1 et S-2 comme des questions de recherche secondaires, pas comme des défauts à masquer.
7. Ajouter une section réglementaire au cadrage.

Rien de tout cela ne touche au code du mois 2. Le développement peut continuer pendant que ces corrections sont produites.
