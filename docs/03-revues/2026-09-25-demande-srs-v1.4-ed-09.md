# Demande de correction de spécification — ISC-SRS v1.4, exigence ED-09

**Date :** 2026-09-25
**Décision de référence :** ADR-035 (opérateur simulé MTN Cameroun)
**Documents concernés :** `docs/02-specifications/ISC-SRS-FR_Cahier_des_charges_v1.3.docx` ; `ISC-SRS-EN_Software_Requirements_Specification_v1.2.docx` ; tout autre document de `docs/02-specifications/` qui cite la plage (les `.docx` ne sont pas analysés par les portes : relecture manuelle à prévoir)

## Pourquoi cette note

L'agent de codage ne modifie jamais un document de `docs/02-specifications/` (CLAUDE.md §2) : quand une tâche l'exige, il s'arrête et le signale. L'amendement T3-04 demande une « MAJ SRS versionnée ». Cette mise à jour est donc portée par une nouvelle version du SRS, livrée par le propriétaire ou l'encadrement, comme pour ADR-033 et le SAD §5.

## Correction demandée (versionnée, rien supprimé)

**ED-09, texte actuel (v1.3) :**
> Identifiants : UUID v4 pour décisions, gels, transactions ; MSISDN dans la plage fictive 6 99 000 000 – 6 99 009 999, à confirmer hors de tout bloc attribué par l'ART avant adoption (QO-17).

**ED-09, texte proposé (v1.4) — ancien texte barré :**
> Identifiants : UUID v4 pour décisions, gels, transactions ; MSISDN dans la plage ~~fictive 6 99 000 000 – 6 99 009 999, à confirmer hors de tout bloc attribué par l'ART avant adoption (QO-17)~~ **6 77 000 000 – 6 77 009 999 de l'opérateur simulé MTN Cameroun ; IMSI 624-01 + MSIN (15 chiffres). Aucune plage n'est réservée à la fiction dans le plan camerounais : la sûreté repose sur l'isolement du bac à sable (réseau interne sans sortie), pas sur l'inexistence des numéros (ADR-035, QO-17 fermée).**

Journal des modifications du SRS v1.4 : « ED-09 : plage MSISDN migrée vers MTN 677 (ADR-035) ; ajout de la structure IMSI ; motif : 69x est un préfixe Orange, incohérent avec l'IMSI MTN 624-01 ».

## État

En attente de la nouvelle version du SRS. Dans l'intervalle, le code, les portes et les tests appliquent déjà ADR-035.
