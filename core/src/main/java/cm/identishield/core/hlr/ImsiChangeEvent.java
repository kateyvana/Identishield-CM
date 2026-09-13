package cm.identishield.core.hlr;

import java.time.Instant;

/**
 * Événement interne « changement d'IMSI » (EF-08, EI-02), publié à chaque réattribution et
 * consommable par le PDP. Au mois 2 le PDP ne l'exploite pas encore (l'exploitation ES-01b est au
 * mois 4) ; l'émission est en place et testable.
 *
 * <p>Il porte l'ancien et le nouvel IMSI et l'horodatage du changement (équivalent CAMARA Sim
 * Swap). Le motif (reason) y figure pour la traçabilité interne uniquement — il ne franchit pas la
 * frontière vers le PDP (ES-16b).
 */
// @implements EF-08
// @implements EI-02
public record ImsiChangeEvent(
    String msisdn,
    String oldImsi,
    String newImsi,
    Instant changedAt,
    String agentId,
    String reason) {}
