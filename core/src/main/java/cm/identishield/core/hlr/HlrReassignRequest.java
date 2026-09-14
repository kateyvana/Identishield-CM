package cm.identishield.core.hlr;

/**
 * Requête de réattribution de SIM émise par l'outil agent opérateur (EF-42, UC-11). Les quatre
 * champs sont obligatoires : numéro visé, nouvel IMSI, identifiant de l'agent, motif déclaré. Le
 * motif est journalisé (traçabilité) ; il n'est jamais transmis au PDP (ES-16b). Aucun champ d'«
 * intention » : le système ne distingue pas un agent honnête d'un agent complice (ADR-011).
 */
public record HlrReassignRequest(String msisdn, String newImsi, String agentId, String reason) {}
