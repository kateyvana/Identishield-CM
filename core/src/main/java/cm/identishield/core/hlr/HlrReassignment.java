package cm.identishield.core.hlr;

import java.time.Instant;
import java.util.UUID;

/**
 * Une ligne d'historique de réattribution de SIM (EF-02). Immuable : l'historique est en écriture
 * seule au niveau applicatif — le service n'expose aucune modification, on n'y revient pas. Le
 * motif (reason) est conservé pour la traçabilité ; il n'est JAMAIS transmis au PDP (ES-16b).
 */
public record HlrReassignment(
    UUID id,
    String msisdn,
    String oldImsi,
    String newImsi,
    String agentId,
    String reason,
    Instant occurredAt) {}
