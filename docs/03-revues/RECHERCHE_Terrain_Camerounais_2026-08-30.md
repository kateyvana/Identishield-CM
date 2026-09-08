# Recherche consolidée — Le terrain camerounais réel

| Champ | Valeur |
|---|---|
| Date | 30 août 2026 |
| Objet | Ce que MTN et Orange Cameroun déploient réellement, ce que les régulateurs imposent, et ce que cela change pour le positionnement du projet |
| Statut des sources | Sources primaires : MINPOSTEL, conférence de presse MTN Douala. Sources secondaires : presse camerounaise spécialisée. Les affirmations chiffrées issues de blogs commerciaux sont signalées comme telles. |

---

## 1. Ce que MTN Cameroun déploie réellement

Une conférence de presse tenue à Douala en septembre 2025, réunissant la presse, les associations de consommateurs et des officiers de police judiciaire, a exposé le dispositif.

**Les mesures annoncées :**

Un code de blocage des retraits. <cite index="36-1">MTN a donné aux clients la possibilité de bloquer les retraits non désirés avec le code *126*15#, empêchant quiconque d'initier des retraits sur le compte</cite>.

<cite index="41-1">La prévention repose sur des innovations technologiques : la sécurité des retraits, la sécurité biométrique sur la nouvelle application MoMo, et une sécurité bancaire protégeant les clients des réinitialisations frauduleuses de code PIN lorsque leur compte MoMo est lié à leur compte bancaire</cite>.

<cite index="36-1">La détection s'appuie sur la page fraud.mtn.cm et sur des outils de détection automatisés</cite>.

**L'ampleur du phénomène, selon MTN Cameroun elle-même :**

<cite index="38-1">Plus de 10 000 plaintes relatives à la fraude par an, soit au moins 1 000 par mois. Près de 90 % des tentatives proviennent de l'ingénierie sociale, 12 % de cyberattaques, et moins de 5 % sont initiées par des agents de MTN Cameroun. Les fraudeurs procèdent notamment par échange de SIM, par reconduction frauduleuse des cartes SIM et par hameçonnage. En réponse, 500 agents MTN ont été suspendus et plus de 2 000 comptes bloqués pour activités suspectes</cite>.

**Ce que ces chiffres établissent, et qu'il faut lire attentivement.**

L'opérateur reconnaît publiquement que **près d'un vingtième de la fraude vient de ses propres agents**. C'est la validation la plus forte possible du profil d'attaquant T-02 du modèle de menace — l'agent complice — que j'avais fait entrer dans le modèle par déduction, sans source chiffrée.

Et l'échange de SIM figure explicitement parmi les modes opératoires nommés par l'opérateur.

---

## 2. Le cas de mars 2026 : la validation la plus directe du projet

Un cas documenté par la presse camerounaise en mars 2026 décrit exactement le scénario du projet.

<cite index="40-1">Un individu se présentant comme agent du service client MTN demande à la victime de confirmer son identification. Des codes apparaissent sur l'écran du client, qu'il lit à voix haute en croyant participer à une procédure de mise à jour. Ces codes étaient des mots de passe à usage unique permettant de lier un nouveau téléphone au compte Mobile Money. En quelques secondes, le fraudeur connecte son propre appareil au compte de sa cible</cite>.

<cite index="40-1">Le compte a été vidé à 99 %. Le client appuyait sur « Annuler », et son refus a été ignoré par le système lui-même</cite>.

Et la conclusion de l'article, qui pourrait figurer telle quelle dans l'introduction d'un mémoire :

<cite index="40-1">Si les systèmes Mobile Money au Cameroun permettent qu'un compte soit vidé malgré des refus explicites, sans période de quarantaine, sans détection d'anomalie, alors la responsabilité des opérateurs est engagée dans la quasi-totalité des cas de fraude déclarés. La COBAC, l'ART et l'ANTIC sont aujourd'hui les institutions les mieux placées pour forcer un audit de sécurité complet de ces infrastructures</cite>.

**Pourquoi ce cas est décisif pour le projet.**

Il décrit **l'enrôlement frauduleux d'un appareil** — c'est le cas d'abus AC-13, celui que la revue du cahier des charges avait fait apparaître comme défaut D-02, et que personne n'avait vu dans la première version.

Il nomme les deux contrôles manquants dans les termes exacts du projet : **période de quarantaine** et **détection d'anomalie**. Ce sont ES-17 et ES-05.

Il montre que **le refus explicite de l'utilisateur a été ignoré** — ce qui est précisément ce que ES-14 corrige : l'ancienne clé n'est pas révoquée immédiatement, et une signature de l'ancien appareil peut annuler le ré-enrôlement.

C'est un cas réel, daté, camerounais, qui décrit une attaque que le projet neutralise par conception. Il doit figurer au chapitre de la problématique.

---

## 3. Le cadre réglementaire, que le projet n'avait pas traité

**Ce qui régule quoi.**

La BEAC régule le mobile money, pas l'ART. <cite index="44-1">Tous les opérateurs Mobile Money au Cameroun sont régulés par la BEAC et le Ministère des Finances. L'argent des utilisateurs est déposé dans des comptes de cantonnement auprès de banques agréées</cite>. L'ART régule les télécommunications, et c'est auprès d'elle que se signalent les incidents.

**Les obligations de sécurité.** Selon une source commerciale à traiter avec prudence, <cite index="43-1">la réglementation BEAC impose la conformité PCI DSS pour les données de paiement, des procédures de lutte contre le blanchiment et le financement du terrorisme, et la déclaration des transactions suspectes</cite>. Cette affirmation provient d'un blog de prestataire de paiement et devrait être vérifiée contre les textes BEAC eux-mêmes avant citation dans le mémoire.

**Le KYC est obligatoire à l'ouverture.** <cite index="50-1">Les clients s'identifient à l'ouverture du portefeuille, et les plafonds de transaction varient selon le niveau de vérification du compte</cite>.

**Ce que cela change pour le projet.** L'ouverture de compte — le workflow W-1 identifié comme manquant — n'est pas un trou de conception mais un périmètre légitimement externe : le KYC est une obligation réglementaire de l'opérateur, antérieure au système. À déclarer comme tel, avec la source.

En revanche, la déclaration des transactions suspectes est une obligation réglementaire qu'un point de décision gradué sert directement. C'est un argument que le projet n'a jamais utilisé, et il est fort : **le dispositif ne fait pas que protéger l'utilisateur, il outille l'opérateur pour une obligation qu'il a déjà.**

---

## 4. Le marché, en chiffres

<cite index="44-1">Le Cameroun compte plus de 15 millions de comptes Mobile Money actifs en 2026 pour 28 millions d'habitants, soit 54 % d'adoption. Le volume annuel des transactions dépasse 5 000 milliards de FCFA, en croissance de 25 % par an. Le réseau compte plus de 45 000 agents enregistrés. L'accessibilité est un facteur clé : un simple téléphone basique suffit, pas besoin de smartphone</cite>.

<cite index="43-1">Le taux de pénétration est l'un des plus élevés d'Afrique centrale : 43 % de la population adulte utilise activement le mobile money en 2026 contre 27 % en 2020</cite>.

<cite index="44-1">L'interopérabilité entre MTN et Orange est en cours de déploiement, permettant bientôt des transferts directs entre les deux réseaux</cite>.

**Deux points à retenir.** La phrase « un simple téléphone basique suffit » confirme que la population USSD n'est pas résiduelle : c'est un argument de vente de l'écosystème. Et l'interopérabilité en cours signifie que la surface d'attaque s'élargit pendant la durée du projet — à mentionner dans les travaux futurs.

Une source commerciale avance <cite index="39-1">un taux de fraude inférieur à 0,08 % des transactions</cite>. Ce chiffre provient d'un blog de prestataire et contraste avec les 10 000 plaintes annuelles déclarées par MTN. Les deux peuvent coexister — un faible pourcentage sur un très grand volume — mais le chiffre ne doit pas être cité sans sa source ni sans cette réserve.

---

## 5. Un risque pratique découvert, qui touche la soutenance

Une mesure sans rapport avec la fraude peut affecter la démonstration.

<cite index="46-1">Dans un communiqué du 20 août 2026, le ministre des Finances annonce le démarrage effectif du blocage des téléphones non dédouanés à compter du 1er septembre 2026</cite>. <cite index="45-1">La douane avait enjoint aux opérateurs de couper l'accès au réseau local à environ 700 000 téléphones et tablettes non dédouanés entrés au Cameroun depuis le 1er avril 2026, par blocage IMEI</cite>.

**Deux conséquences.**

La première est pratique : si l'un des deux téléphones de démonstration est concerné, il pourrait perdre l'accès réseau. La démonstration n'en dépend pas — les appareils se connectent en Wi-Fi à la machine virtuelle, sans carte SIM — mais le point mérite d'être vérifié avant la soutenance.

La seconde est intellectuelle, et elle est intéressante : l'État camerounais déploie **une infrastructure de contrôle par IMEI au niveau opérateur**. C'est la preuve que l'identification de l'appareil est techniquement praticable chez les opérateurs camerounais. Un jury pourrait demander pourquoi ne pas s'appuyer dessus plutôt que sur une clé cryptographique.

**La réponse à préparer** : l'IMEI est un identifiant déclaratif, transmis par l'appareil et falsifiable. Il n'est pas une preuve cryptographique. Il identifie un terminal, il ne prouve pas la possession d'un secret. Le blocage par IMEI sert une politique fiscale, pas une authentification.

---

## 6. Ce que tout ceci change pour le positionnement

**Ce qui reste vrai.** Les mesures camerounaises sont réelles mais partielles. Le code de blocage des retraits est un interrupteur binaire, activé par l'utilisateur, qui bloque tous les retraits — pas une décision graduée par transaction. La sécurité biométrique de l'application est un verrou local, non vérifiable par le serveur. La protection contre la réinitialisation frauduleuse de PIN ne s'applique qu'aux comptes liés à un compte bancaire, donc à une minorité.

**Ce qui devient faux.** Affirmer que le problème n'est pas traité au Cameroun. Il l'est, publiquement, avec des mesures nommées et une conférence de presse. Un jury peut le vérifier en composant `*126*15#`.

**Ce qui devient beaucoup plus fort.** Le cas de mars 2026 montre qu'**avec ces mesures en place**, un compte a été vidé à 99 % par un enrôlement d'appareil frauduleux, malgré les refus explicites de l'utilisateur, sans période de quarantaine et sans détection d'anomalie. Et l'opérateur lui-même déclare 10 000 plaintes par an et moins de 5 % de fraude interne.

**Formulation révisée de la contribution, à substituer partout :**

> MTN Cameroun déploie des contre-mesures nommées et publiques : blocage des retraits par code court, sécurité biométrique applicative, protection de la réinitialisation du PIN pour les comptes liés à une banque, outils de détection automatisée. Ces mesures partagent trois propriétés : elles sont **binaires ou optionnelles** — on bloque tout ou rien, et il faut le demander ; elles sont **locales ou partielles** — le verrou biométrique n'est pas vérifiable par le serveur, la protection du PIN ne couvre que les comptes bancarisés ; et leur efficacité **n'est mesurée nulle part**.
>
> Un cas documenté de mars 2026 montre un compte vidé à 99 % par enrôlement frauduleux d'appareil, malgré les refus répétés de l'utilisateur, en l'absence de période de quarantaine et de détection d'anomalie. L'opérateur déclare pour sa part plus de 10 000 plaintes annuelles et reconnaît que moins de 5 % de la fraude est initiée par ses propres agents.
>
> Ce travail apporte : une décision graduée par transaction au lieu d'un interrupteur global ; une preuve vérifiable côté serveur au lieu d'un verrou local ; une période de probation appliquée à l'enrôlement d'appareil, précisément le contrôle absent du cas de mars 2026 ; et une mesure quantifiée du couple protection-friction, y compris sur le canal USSD que l'écosystème présente lui-même comme sa force d'accessibilité.

---

## 7. Ce qu'il faut ajouter aux documents

| # | Ajout | Où |
|---|---|---|
| 1 | Les mesures MTN Cameroun de septembre 2025, avec leurs limites analysées | État de l'art |
| 2 | Le cas de mars 2026 comme cas d'étude principal de la problématique | Introduction, problématique |
| 3 | La comparaison verrou biométrique local contre preuve cryptographique vérifiable | État de l'art |
| 4 | Les mesures ghanéennes du même groupe, et l'échec constaté malgré elles | État de l'art |
| 5 | Le cadre réglementaire BEAC, et l'argument de la déclaration des transactions suspectes | Cadrage, section réglementaire |
| 6 | Le KYC comme périmètre externe déclaré, avec sa base réglementaire | Périmètre |
| 7 | L'IMEI comme identifiant déclaratif, et pourquoi il ne remplace pas une clé | État de l'art, questions du jury |
| 8 | L'interopérabilité MTN-Orange en cours, comme élargissement futur de la surface | Travaux futurs |
| 9 | Les 5 % de fraude interne déclarés par MTN, comme validation chiffrée de T-02 | Modèle de menace |

Les points 1, 2, 3 et 9 sont les plus importants. Le point 2 seul vaut plusieurs pages de justification abstraite.

---

## 8. Réserve de méthode

Plusieurs sources de ce document sont journalistiques ou commerciales. Avant citation dans le mémoire, il faut :

- retrouver le communiqué officiel de MTN Cameroun de septembre 2025 plutôt que sa reprise de presse ;
- vérifier les textes BEAC eux-mêmes pour les obligations de sécurité ;
- traiter le taux de fraude de 0,08 % comme non vérifié ;
- confirmer que `*126*15#` est toujours actif, en le composant.

Ce dernier point prend trente secondes et vaut mieux que n'importe quelle citation.
