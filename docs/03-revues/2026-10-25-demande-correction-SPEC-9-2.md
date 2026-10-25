# Demande de correction documentaire — ISC-MOB-SPEC §9.2 (texte de l'alerte « changement de SIM »)

Date : 2026-10-25. Couloir B (Mobile), carte B5 (#48). Décision du superviseur technique du
2026-10-25 : **L-B5-6 ACCEPTÉ**. Le document `docs/02-specifications/mobile/ISC-MOB-SPEC_*.docx`
n'est **pas** modifié par le couloir B : cette demande est adressée à la propriétaire pour la
version suivante de la spécification (v1.3 → v1.4 ou errata).

## Texte en vigueur (ISC-MOB-SPEC v1.3, §9.2 « Catalogue des signaux », ligne « Changement de SIM »)

> « La carte SIM de votre numéro a été changée à 14 h 02. C'est vous ? »

## Ce que le serveur fait (B5, `SecurityAlerts.TEXT_SIM_CHANGED`)

> « La carte SIM de votre numéro a été changée. C'est vous ? »

L'heure n'est pas dans le texte : l'alerte porte son instant `at` (UTC serveur, contrat
`Alert.at`, ED-04), que l'application affiche dans le fuseau du téléphone. Le serveur ne calcule
aucune heure locale (le fuseau `NIGHT_ZONE` n'est qu'un signal du moteur de règles, pas celui de
l'usager).

## Correction demandée

Dans §9.2, remplacer l'exemple « … changée à 14 h 02. C'est vous ? » par « … changée. C'est vous ? »
et ajouter : « L'heure de l'événement est portée par le champ `at` de l'alerte et affichée par
l'application dans le fuseau de l'appareil. » La règle d'or §9.3 (ni lien, ni code, ni saisie) est
inchangée et respectée.

## Trace

- Limite déclarée : `docs/03-revues/2026-10-25-limites-declarees-B5.md`, L-B5-6 (ACCEPTÉ).
- Test : `SecurityAlertsTest` (textes sans lien, code, saisie ni suite de chiffres).
