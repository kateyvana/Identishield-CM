package cm.identishield.core.flux;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Lecture du flux des transactions (rôle isc_core_app) pour la console (EF-30). */
@Repository
public class FluxRepository {

  private final JdbcTemplate jdbc;

  public FluxRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<FluxEntry> recent(int limit) {
    return jdbc.query(
        "SELECT t.decision_id::text AS did, a.msisdn, t.type, t.amount_xaf,"
            + " t.beneficiary_id, t.state, t.created_at"
            + " FROM core.transaction t JOIN core.account a ON a.id = t.account_id"
            + " ORDER BY t.created_at DESC LIMIT ?",
        (rs, i) ->
            new FluxEntry(
                rs.getString("did"),
                rs.getString("msisdn"),
                rs.getString("type"),
                rs.getLong("amount_xaf"),
                rs.getString("beneficiary_id"),
                decisionFor(rs.getString("state")),
                0,
                rs.getString("state"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant().toString()),
        limit);
  }

  /** Au mois 2, la décision se lit dans l'état : EXECUTED = ALLOW appliqué (score 0). */
  private static String decisionFor(String state) {
    return state.startsWith("EXECUTED") ? "ALLOW" : "REFUSE";
  }
}
