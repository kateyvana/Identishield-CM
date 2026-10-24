# Demande de correction documentaire — ISC-MOB-SPEC v1.3 §6 et §12.4 bis (renouvellement du jeton)

Date : 2026-10-24. Couloir B (Mobile), carte B4 (#47). Décision de la propriétaire du 2026-10-24,
point 2. Le document de spécification n'est **pas** modifié par la session : cette demande est
adressée à la propriétaire pour une version future.

## Ce que dit le document

- ISC-MOB-SPEC v1.3, §6 (flux B), tableau des cas : « Jeton de session expiré → Nouvelle signature
  de défi, **transparente pour l'usager** → Nouveau jeton ».
- §12.4 bis : « Un jeton de session absent ou expiré reçoit 401 CREDENTIALS_INVALID : l'application
  refait alors une signature de défi (§6). »

## Ce qui le contredit

- `contracts/mobile-api.yaml`, `POST /session` : le corps `SessionRequest` **exige le PIN**
  (`required: msisdn, pin, deviceId, challenge, signature`).
- Charte qualité mobile, règle 5 (CLAUDE.md §10) : le PIN n'est **jamais conservé** par
  l'application. Un renouvellement « transparent » exigerait de le garder.
- Décision P-09 (2026-10-10) : les trois durées de session sont imposées par le serveur ; un
  renouvellement transparent les annulerait toutes.

## Correction demandée

Remplacer, dans §6 et §12.4 bis, « transparente pour l'usager » par : « l'usager **retape son PIN** ;
l'application signe un nouveau défi ; un jeton mort n'est jamais ranimé ». Décision de la
propriétaire du 2026-10-24 (point 2), appliquée par la carte B4 (`SessionTokens.touch`,
`MobileSession.open`).

## Même traitement pour ES-14b (carte B3b)

ISC-SRS-FR v1.5, ES-14b : « révoquer la clé de dispositif précédente **immédiatement après
enregistrement** d'une nouvelle clé ». Ce texte est antérieur à l'état EN ATTENTE (ADR-041) : révoquer
à l'enregistrement rouvrirait AC-02 si la confirmation n'arrivait jamais (le compte n'aurait plus
qu'une clé EN ATTENTE, et `/enrolment/start` renverrait vers l'OTP, donc vers la SIM). Décision de
la propriétaire du 2026-10-24 (point 3) : la révocation a lieu à l'**activation** de la nouvelle
clé, dans la **même transaction**. ES-20 (une seule clé ACTIVE) est respecté en permanence.
Correction demandée : « immédiatement après **activation** de la nouvelle clé, dans la même
transaction ».
