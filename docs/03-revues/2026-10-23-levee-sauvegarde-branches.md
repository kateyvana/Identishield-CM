# Levée de la 8e interdiction — sauvegarde des branches de travail (permanente)

**Date :** 2026-10-23
**Autorité :** la propriétaire, YENGUE MESSAH KATE YVANA, transmise par le superviseur technique le
2026-10-23.

Texte transmis, consigné tel quel :

> « la propriétaire accorde une levée permanente de sauvegarde (pousser une branche de travail,
> jamais main, jamais de PR, jamais de fusion). »

**Portée.** Une session peut pousser sa branche de travail vers `origin` pour la sauvegarder, à tout
moment, y compris avant que la carte soit prête. Cela ne couvre ni `main`, ni l'ouverture d'une pull
request, ni une fusion, ni `push --force`, ni la suppression d'une branche distante. Ouvrir une
pull request reste soumis à la 8e interdiction révisée (porte pré-poussée verte).

**Raison.** Le 2026-10-23, les résultats VT-x qui ont annulé une faiblesse déclarée du projet
n'existaient que dans un dossier de travail local. Un résultat non sauvegardé est un résultat qu'on
peut perdre.
