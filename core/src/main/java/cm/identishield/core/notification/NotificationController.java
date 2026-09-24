package cm.identishield.core.notification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consultation des notifications d'un numéro (EF-09), sur le réseau interne isc-net. {@code GET
 * /notifications?msisdn=...} renvoie les notifications persistées, avec leur {@code recipientRef}
 * (la cible de livraison = IMSI courant au moment de l'émission). L'outil attaquant s'en sert pour
 * constater qu'aucune notification n'est parvenue à l'ANCIEN IMSI de la victime (l'oracle d'AC-02).
 * Lecture seule.
 */
// @implements EF-09
@RestController
public class NotificationController {

  private final NotificationRepository notifications;

  public NotificationController(NotificationRepository notifications) {
    this.notifications = notifications;
  }

  @GetMapping("/notifications")
  public List<Map<String, Object>> byMsisdn(@RequestParam String msisdn) {
    return notifications.listByMsisdn(msisdn).stream()
        .map(
            n ->
                Map.<String, Object>of(
                    "recipientRef", n.recipientRef(),
                    "channel", n.channel().name(),
                    "type", n.type(),
                    "sentAt", n.sentAt().toString()))
        .toList();
  }

  /**
   * Notifications ÉMISES les plus récentes, tous comptes (EF-37) : vue console. Inclut le {@code
   * body} — le CONTENU tel qu'il a été émis, déjà contraint par le garde-fou de T2-14 (ENF-17 : ni
   * lien, ni numéro de rappel, ni code). Lecture seule.
   */
  // @implements EF-37
  @GetMapping("/notifications/recent")
  public List<Map<String, Object>> recent(@RequestParam(defaultValue = "50") int limit) {
    return notifications.listRecent(Math.min(Math.max(limit, 1), 200)).stream()
        .map(
            n -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("msisdn", n.msisdn());
              m.put("recipientRef", n.recipientRef());
              m.put("channel", n.channel().name());
              m.put("type", n.type());
              m.put("body", n.body());
              m.put("sentAt", n.sentAt().toString());
              return m;
            })
        .toList();
  }
}
