package cm.identishield.core.notification;

/**
 * Refus effectif d'une notification dont le contenu viole ENF-17 (anti-hameçonnage). La contrainte
 * est appliquée par le code, pas par la discipline : l'émission LÈVE cette exception plutôt que de
 * signaler. Le {@link Reason} nomme le motif exact, pour une preuve de refus lisible.
 */
public class NotificationRefusedException extends RuntimeException {

  /** Motif du refus — chacun correspond à un vecteur de hameçonnage interdit par ENF-17. */
  public enum Reason {
    WEB_LINK,
    PHONE_NUMBER,
    SECRET_REQUEST
  }

  private final transient Reason reason;

  public NotificationRefusedException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
