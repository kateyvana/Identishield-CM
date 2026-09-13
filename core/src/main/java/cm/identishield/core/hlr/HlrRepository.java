package cm.identishield.core.hlr;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * HLR simulé (EF-02) : association MSISDN → IMSI courant (table {@code core.hlr_subscriber}) et
 * historique des réattributions (table {@code core.hlr_reassignment}, créées par T2-01). Un seul
 * IMSI actif par numéro. L'historique est en écriture seule au niveau applicatif : ce repository
 * n'expose qu'INSERT et SELECT dessus, jamais d'UPDATE ni de DELETE. Les horodatages viennent de
 * l'horloge injectée (fournis par l'appelant), jamais d'un DEFAULT de base (ED-04, OB-1).
 */
// @implements EF-02
// @implements ED-02
@Repository
public class HlrRepository {

  private final JdbcTemplate jdbc;

  public HlrRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** IMSI initial d'un numéro s'il n'en a pas encore (amorçage idempotent). */
  public void ensureSubscriber(String msisdn, String imsi, Instant at) {
    jdbc.update(
        "INSERT INTO core.hlr_subscriber(msisdn, current_imsi, updated_at) VALUES (?, ?, ?)"
            + " ON CONFLICT (msisdn) DO NOTHING",
        msisdn,
        imsi,
        OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
  }

  /** IMSI actif du numéro, s'il existe. */
  public Optional<String> currentImsi(String msisdn) {
    List<String> rows =
        jdbc.query(
            "SELECT current_imsi FROM core.hlr_subscriber WHERE msisdn = ?",
            (rs, i) -> rs.getString(1),
            msisdn);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** Horodatage du dernier changement d'IMSI du numéro (EF-08), s'il existe. */
  public Optional<Instant> lastChangeAt(String msisdn) {
    List<Instant> rows =
        jdbc.query(
            "SELECT updated_at FROM core.hlr_subscriber WHERE msisdn = ?",
            (rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant(),
            msisdn);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** Ajoute (INSERT seul) une ligne d'historique de réattribution. On n'y revient jamais. */
  public void appendReassignment(
      String msisdn, String oldImsi, String newImsi, String agentId, String reason, Instant at) {
    jdbc.update(
        "INSERT INTO core.hlr_reassignment"
            + "(msisdn, old_imsi, new_imsi, agent_id, reason, occurred_at)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        msisdn,
        oldImsi,
        newImsi,
        agentId,
        reason,
        OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
  }

  /** Bascule l'IMSI actif du numéro (upsert). L'horodatage est celui du changement. */
  public void setCurrentImsi(String msisdn, String newImsi, Instant at) {
    jdbc.update(
        "INSERT INTO core.hlr_subscriber(msisdn, current_imsi, updated_at) VALUES (?, ?, ?)"
            + " ON CONFLICT (msisdn) DO UPDATE SET current_imsi = EXCLUDED.current_imsi,"
            + " updated_at = EXCLUDED.updated_at",
        msisdn,
        newImsi,
        OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
  }

  /** Historique complet du numéro, dans l'ordre chronologique d'insertion. */
  public List<HlrReassignment> history(String msisdn) {
    return jdbc.query(
        "SELECT id, msisdn, old_imsi, new_imsi, agent_id, reason, occurred_at"
            + " FROM core.hlr_reassignment WHERE msisdn = ? ORDER BY occurred_at, id",
        (rs, i) ->
            new HlrReassignment(
                (UUID) rs.getObject("id"),
                rs.getString("msisdn"),
                rs.getString("old_imsi"),
                rs.getString("new_imsi"),
                rs.getString("agent_id"),
                rs.getString("reason"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant()),
        msisdn);
  }
}
