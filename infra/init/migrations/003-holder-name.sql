-- 003 — Couloir A : nom lisible du titulaire sur core.account (ED-11). Decision de la proprietaire
-- du 2026-10-23 (onglet SMS de la console + document qui enregistre T2-17 remplacee).
-- Une seule transaction : une erreur annule TOUT. Instructions rejouables (README, regle 2).
BEGIN;

-- holder_name : texte libre, NULL = aucun nom enregistre. Aucune contrainte sur le contenu : le
-- cas vide ou l'homonymie entre comptes de demonstration est accepte, et la propriete « plage de
-- MSISDN inchangee » (ADR-035) reste la seule contrainte sur les comptes.
ALTER TABLE core.account ADD COLUMN IF NOT EXISTS holder_name text;

COMMIT;
