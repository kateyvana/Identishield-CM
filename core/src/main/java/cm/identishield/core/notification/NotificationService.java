package cm.identishield.core.notification;

import cm.identishield.core.hlr.HlrService;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Sous-système de notification simulé (EF-09). Rien ne sort du sandbox : chaque notification est
 * seulement persistée, puis consultable par numéro.
 *
 * <p><b>Contenu (ENF-17)</b> — appliqué par le code, pas par la discipline :
 *
 * <ul>
 *   <li>Volet POSITIF garanti par construction : le corps est composé de {@code [type] horodatage —
 *       action}, donc il contient toujours le type d'événement, l'horodatage serveur (horloge
 *       injectée, ED-04) et l'action possible.
 *   <li>Volet NÉGATIF appliqué par {@link NotificationContentPolicy} sur les champs libres (type,
 *       action) AVANT toute persistance : lien cliquable, numéro de rappel ou demande de secret =>
 *       émission REFUSÉE ({@link NotificationRefusedException}).
 * </ul>
 *
 * <p><b>Cible</b> — un SMS part vers l'IMSI COURANT du numéro (résolu au HLR) : même faiblesse que
 * l'OTP (T2-12), un SIM swap détourne le message. Un push viserait le dispositif enrôlé ; aucun au
 * mois 2, il est seulement stocké.
 *
 * <p><b>Revue ligne à ligne requise</b> : le contenu d'une notification est un vecteur de
 * hameçonnage (ENF-08c).
 */
// @implements EF-09
// @implements ENF-17
@Service
public class NotificationService {

  // Références de livraison de repli quand la cible réelle n'existe pas encore.
  private static final String NO_CURRENT_IMSI = "(IMSI inconnu)";
  private static final String NO_DEVICE = "(aucun dispositif enrôlé)";

  private final NotificationRepository repo;
  private final HlrService hlr;
  private final Clock clock;

  public NotificationService(NotificationRepository repo, HlrService hlr, Clock clock) {
    this.repo = repo;
    this.hlr = hlr;
    this.clock = clock;
  }

  /**
   * Émet une notification de sécurité vers {@code msisdn}. Refuse (lève) si le contenu viole
   * ENF-17. Sinon résout la cible de livraison, compose le corps contraint et persiste. Renvoie
   * l'enregistrement stocké.
   */
  public Notification emit(
      String msisdn, NotificationChannel channel, String eventType, String action) {
    NotificationContentPolicy.enforce(eventType, action); // ENF-17 : refus effectif avant tout
    Instant now = clock.instant();
    String recipientRef =
        switch (channel) {
          case SMS -> hlr.currentImsi(msisdn).orElse(NO_CURRENT_IMSI);
          case PUSH -> NO_DEVICE;
        };
    String body = compose(eventType, now, action);
    return repo.insert(msisdn, channel, recipientRef, eventType, body, now);
  }

  /** Notifications d'un numéro (consultation par numéro, EF-09). */
  public List<Notification> listByMsisdn(String msisdn) {
    return repo.listByMsisdn(msisdn);
  }

  /** Corps contraint : type d'événement + horodatage serveur + action (volet positif d'ENF-17). */
  private static String compose(String eventType, Instant at, String action) {
    return "[" + eventType + "] " + DateTimeFormatter.ISO_INSTANT.format(at) + " — " + action;
  }
}
