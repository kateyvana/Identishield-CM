package cm.identishield.core.mobile;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Alertes de sécurité du compte ({@code core.alert}, migration 006 ; flux F, ISC-MOB-SPEC §9).
 * Écrites par le cœur quand l'événement se produit, lues par le téléphone lié à travers sa session.
 * Une alerte à réponse ({@code actionable}) naît OPEN et ne se ferme qu'une fois, par une réponse
 * signée ; une alerte informative naît INFO et n'attend rien (EF-47). Le texte ne contient jamais
 * de lien, de code ni de demande de saisie (ES-28) : il est fixé par {@link SecurityAlerts}.
 */
// @implements ES-28
@Repository
public class Alerts {

  public static final String OPEN = "OPEN";
  public static final String INFO = "INFO";
  public static final String ACKNOWLEDGED = "ACKNOWLEDGED";
  public static final String NOT_ME = "NOT_ME";

  /** Une alerte telle que l'écran E-23/E-24 la montre. */
  public record AlertView(
      UUID id, String type, String summary, boolean actionable, String status, Instant at) {}

  /** Alertes rendues au plus, les plus récentes d'abord. */
  static final int LIST_LIMIT = 50;

  private static final String COLUMNS = "id, type, summary, actionable, status, created_at";

  private final JdbcTemplate jdbc;

  public Alerts(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Lève une alerte : OPEN si une réponse est attendue, INFO sinon. */
  public UUID raise(UUID accountId, String type, String summary, boolean actionable, Instant now) {
    return jdbc.queryForObject(
        "INSERT INTO core.alert (account_id, type, summary, actionable, status, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
        UUID.class,
        accountId,
        type,
        summary,
        actionable,
        actionable ? OPEN : INFO,
        utc(now));
  }

  public List<AlertView> list(UUID accountId) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM core.alert WHERE account_id = ?"
            + " ORDER BY created_at DESC, id DESC LIMIT "
            + LIST_LIMIT,
        Alerts::view,
        accountId);
  }

  /** L'alerte si elle appartient au compte et attend encore une réponse. */
  public Optional<AlertView> findOpen(UUID accountId, UUID alertId) {
    return jdbc
        .query(
            "SELECT "
                + COLUMNS
                + " FROM core.alert WHERE id = ? AND account_id = ?"
                + " AND status = 'OPEN'",
            Alerts::view,
            alertId,
            accountId)
        .stream()
        .findFirst();
  }

  /**
   * Ferme une alerte OPEN par {@code status} (ACKNOWLEDGED ou NOT_ME) ; vrai si cet appel l'a fait.
   */
  public boolean answer(UUID alertId, String status, Instant now) {
    return jdbc.update(
            "UPDATE core.alert SET status = ?, answered_at = ? WHERE id = ? AND status = 'OPEN'",
            status,
            utc(now),
            alertId)
        == 1;
  }

  private static AlertView view(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
    return new AlertView(
        rs.getObject("id", UUID.class),
        rs.getString("type"),
        rs.getString("summary"),
        rs.getBoolean("actionable"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime utc(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
