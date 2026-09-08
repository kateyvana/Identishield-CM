# Audit des trous — IdentiShield-CM, août 2026

| Champ | Valeur |
|---|---|
| Date | 30 août 2026 |
| Auteur | Encadrement technique |
| Objet | Recensement exhaustif des défauts, angles morts et questions ouvertes, avant le mois 4 |
| Méthode | Relecture croisée SRS v1.2, TM v1.1, SAD v1.1, EVAL, TEST, DEMO, contre les résultats des cinq spikes et les échanges d'encadrement |
| Règle appliquée | Aucun défaut n'est écarté parce qu'il serait gênant. Chacun porte une gravité et une position tranchée. |

Gravité : **G1** bloquant pour la soutenance · **G2** affaiblit un résultat · **G3** à traiter, sans urgence · **G4** hors périmètre, à déclarer.

---

## 1. Contacts de confiance et récupération

C'est la zone la plus faible du projet. Le device binding neutralise le SIM swap ; la récupération est la porte qu'il ouvre en retour.

| ID | Défaut | Gravité | Position |
|---|---|---|---|
| A-01 | La désignation d'un contact de confiance est **optionnelle** (EF-23 dit « DOIT permettre », pas « DOIT exiger »). Un compte sans contact n'a aucune voie de recours en cas de perte de téléphone : il ne reste que l'expiration de la probation. | **G1** | À corriger. Nouvelle exigence : au moins un contact de confiance DOIT être désigné et confirmé avant la première sortie sensible. Sans cela, la protection de secours du système est facultative. |
| A-02 | Rien ne dit ce qui se passe si le contact **ne répond jamais**. Relance ? Silence ? | G2 | À spécifier : une relance à mi-parcours de la probation, puis expiration silencieuse. |
| A-03 | La **durée de validité** d'une demande de confirmation n'est pas définie. | G2 | À spécifier : elle expire avec la probation qui l'a déclenchée. |
| A-04 | Rien ne dit si un contact peut **répondre par USSD**. EF-44 couvre la désignation, pas la réponse. | **G1** | À corriger. Un contact sans smartphone qui ne peut pas répondre rend P3 inapplicable — même défaut que D-03, à un autre endroit du parcours. |
| A-05 | Que se passe-t-il si **deux contacts répondent différemment** ? | G3 | À trancher : un seul NON suffit à verrouiller. Le refus l'emporte. |
| A-06 | Le contenu exact de ce que **voit le contact** n'est pas spécifié. | G3 | À spécifier dans le workflow : événement, compte concerné, action demandée, sans lien cliquable (ENF-17). |
| A-07 | Le champ **relation** (sœur, parent, associé) n'existe pas. | G4 | Écarté. N'influence aucune décision, ajoute une donnée personnelle contre EAR-10. Travaux futurs. |
| A-08 | La **révocation** d'un contact de confiance n'est pas spécifiée. Un contact devenu hostile reste actif indéfiniment. | G2 | À spécifier. La révocation doit elle-même être protégée, sinon un attaquant révoque les contacts avant d'agir. |
| A-09 | Le SMS de notification part vers l'**IMSI courant**. Si le contact a lui-même subi un swap, il arrive à l'attaquant. | G3 | À déclarer comme limite. Exige un double swap ou un agent complice ; hors du cas principal. |

---

## 2. Modèle de menace et scénarios

| ID | Défaut | Gravité | Position |
|---|---|---|---|
| B-01 | Le vol de téléphone **déverrouillé avec la SIM dedans** est hors modèle (ISC-TM §9), mais c'est le scénario le plus courant dans la vie réelle. | G2 | Exclusion assumée, à énoncer clairement en soutenance (acte 5). Ne pas la découvrir devant le jury. |
| B-02 | Aucun scénario ne couvre **la révocation malveillante des contacts** avant l'attaque. | G2 | Cas d'abus manquant. À ajouter comme AC-14 si A-08 est corrigé. |
| B-03 | AC-01 (victime consentante) n'a **aucune contre-mesure efficace**, ce qui est assumé — mais le taux d'abandon simulé qu'il propose de mesurer n'est défini nulle part. | G3 | À spécifier dans ISC-EVAL, ou à retirer de la liste des mesures. |
| B-04 | Le modèle exclut l'**insider technique** (DBA, développeur), et SPIKE-3 a confirmé que la clé d'ancre co-localisée ne résiste pas à cet adversaire. | G4 | Exclusion cohérente et déclarée. À énoncer, pas à corriger. |

---

## 3. Évaluation et mesure

| ID | Défaut | Gravité | Position |
|---|---|---|---|
| C-01 | La matrice `traceability.csv` est **vide**. Le critère CR-06 en dépend, et la porte qui la vérifierait n'existe pas encore. | G2 | Structure faite, alimentation automatique au fil des tests du mois 2 à 4. À surveiller : une matrice vide au mois 5 serait un défaut majeur. |
| C-02 | La **fonction d'agrégation du score** (ADR-016) est la seule décision d'architecture ouverte. Elle se tranche sur données au mois 3. | G3 | Conforme au plan. Ne devient un risque que si le mois 3 déborde. |
| C-03 | Le **coût de friction** est défini comme « nombre de transactions retardées × durée », mais aucun seuil d'acceptabilité n'est fixé au-delà du 1 % de H4. | G3 | À préciser dans ISC-EVAL avant le gel du jeu. |
| C-04 | Les **notifications sont simulées instantanées**. Les résultats sur AC-04 (attaquant patient) en dépendent directement : l'information est la contre-mesure. | G2 | Dette D-02 déjà consignée. À énoncer comme limite dans le mémoire. |
| C-05 | Le calcul du **rappel sur AC-01** suppose que le STEP_UP dissuade la victime. Rien ne le mesure : une victime manipulée peut confirmer quand même. | G2 | À traiter : soit modéliser un taux de dissuasion explicite et déclaré comme hypothèse, soit exclure AC-01 du calcul de rappel. |

---

## 4. Performance et exploitation

| ID | Défaut | Gravité | Position |
|---|---|---|---|
| D-01 | Marge de **×2,6 seulement** sur SQ-30 en régime en ligne (133 écritures/s contre 50 exigées). | G2 | RA-08 consigné. À surveiller au mois 4 : chaque règle ajoutée réduit la marge. |
| D-02 | Le **préchauffage du client PDP** masque un premier appel dépassant 800 ms sur JVM froide. C'est un contournement, pas une correction. | G3 | Mesure demandée. Si le délai de 800 ms d'EI-01 est trop serré, c'est l'exigence qu'il faut amender, pas le code qu'il faut préchauffer. |
| D-03 | Le **bit d'exécution** ne survit pas à Git sous Windows. Deux occurrences : CI de TACHE-00, `gen-ca.sh` de T2-04. | G3 | Récurrent. Une porte doit le détecter, sinon il reviendra une troisième fois. |
| D-04 | La console **Next.js** a posé trois difficultés sur ce poste (prerender, build Docker, mémoire). | G2 | À investiguer. Si elle est structurellement coûteuse, mieux vaut le savoir avant le mois 6 où elle porte la démonstration. |
| D-05 | La VM est **sous-dimensionnée** : 4 vCPU / 8 Go contre 6 / 12 recommandés par ISC-DEPLOY. | G3 | Les mesures de SPIKE-5 ont été faites dessus. Toute publication doit déclarer la configuration. |
| D-06 | En **NAT**, les téléphones ne joignent pas la VM. La bascule vers le pont est dans la checklist du jour J. | G3 | Consigné. À vérifier la veille de la soutenance, pas le matin. |

---

## 5. Sécurité et obligations

| ID | Défaut | Gravité | Position |
|---|---|---|---|
| E-01 | **OB-3 est mal formulée** dans le SAD v1.1. T2-01 a mesuré que `ALTER DEFAULT PRIVILEGES` n'enregistre rien pour ce cas : la garantie réelle est un `REVOKE` explicite par fonction, plus la porte automatique. | **G1** | À corriger dans le SAD. Telle qu'elle est écrite, l'obligation donne un faux sentiment de sécurité. |
| E-02 | La **clé de signature de l'ancre** vit sur la même machine que la base. | G4 | RA-09. Limite du modèle, non du dispositif. Déclarée. |
| E-03 | Le **faux positif de la porte secrets** s'est produit deux fois. À la troisième, c'est le détecteur qu'il faut corriger. | G3 | Seuil fixé. À surveiller. |
| E-04 | **TA-CLOCK-01 reste une liste noire**. Un dix-neuvième chemin non nommé resterait invisible. | G3 | Limite structurelle, déclarée. La vraie défense est OB-1. |
| E-05 | Aucune exigence ne couvre le **verrouillage après tentatives de PIN répétées**. Un attaquant qui a le numéro mais pas le PIN peut le forcer. | G2 | Trou réel. À trancher : ajouter une exigence, ou déclarer explicitement que le PIN brute-force est hors périmètre. |

---

## 6. Fonctionnel non couvert

| ID | Défaut | Gravité | Position |
|---|---|---|---|
| F-01 | Le **dégel par l'utilisateur** existe (ES-08), mais rien ne dit ce qu'il voit ni comment il agit depuis USSD. | G2 | À spécifier avec le workflow des contacts de confiance. |
| F-02 | Aucune exigence ne couvre le cas où l'utilisateur **change de numéro** volontairement (portage, nouveau numéro). | G3 | À déclarer hors périmètre : le compte est lié au MSISDN. |
| F-03 | Le **PIN oublié** n'est couvert par aucune exigence. C'est pourtant un parcours de récupération, donc une surface d'attaque. | G2 | Trou réel. À trancher : hors périmètre déclaré, ou exigence à ajouter. |
| F-04 | Rien ne couvre **plusieurs appareils enrôlés simultanément** pour un même compte. Le SAD implique un seul appareil actif. | G3 | À déclarer explicitement : un seul appareil actif par compte, c'est une décision, pas un oubli. |

---

## 7. Ce que je ne retiens pas comme défaut

Pour être complet, voici ce que j'ai examiné et écarté.

Le champ relation des contacts (A-07) : n'influence aucune décision.
L'absence d'email : délibérée, peu de comptes camerounais en ont un.
L'absence de biométrie : hors périmètre, et le device binding la remplace fonctionnellement.
L'absence de rotation de clé : dette D-04 déjà consignée, travaux futurs.
L'absence de chiffrement au repos de la base : le sandbox n'héberge aucune donnée réelle.

---

## 8. Synthèse

| Gravité | Nombre | Traitement |
|---|---|---|
| G1 — bloquant | **3** | A-01, A-04, E-01 — à corriger avant le mois 4 |
| G2 — affaiblit un résultat | 12 | À traiter au mois 4, ou à déclarer comme limite |
| G3 — à traiter | 12 | Backlog, sans urgence |
| G4 — hors périmètre | 3 | À déclarer en soutenance |

**Les trois G1 sont les seuls qui exigent une décision immédiate.** Deux concernent les contacts de confiance — la protection de secours du système est optionnelle et partiellement inapplicable sur USSD. Le troisième est une obligation d'architecture mal formulée qui donne un faux sentiment de sécurité.

Aucun de ces trois ne remet en cause l'architecture ni la question de recherche. Ils se corrigent par des exigences et une reformulation, pas par une refonte.

---

## 9. Ce que cet audit ne couvre pas

Il porte sur la conception et les exigences. Il ne porte pas sur la qualité du code écrit au mois 2 — cela relève de la revue de code, tâche par tâche.

Il ne porte pas non plus sur le mémoire, dont le plan dépend du format imposé par l'université, encore inconnu.
