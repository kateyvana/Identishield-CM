package cm.identishield.core.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Une notification simulée persistée (EF-09). {@code recipientRef} est la cible de livraison —
 * l'IMSI courant pour un SMS, une référence d'appareil pour un push — distincte du {@code msisdn}
 * destinataire, qui sert à la consultation par numéro.
 */
public record Notification(
    UUID id,
    String msisdn,
    NotificationChannel channel,
    String recipientRef,
    String type,
    String body,
    Instant sentAt) {}
