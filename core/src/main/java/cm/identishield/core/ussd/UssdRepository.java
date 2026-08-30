package cm.identishield.core.ussd;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistance des sessions USSD (table core.ussd_session, rôle isc_core_app). Le payload (montant,
 * bénéficiaire accumulés) est en jsonb ; l'expiration vient de l'horloge injectée (ED-04). Les
 * sessions expirées ne sont pas rejouées.
 */
@Repository
public class UssdRepository {

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public UssdRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public UUID create(String msisdn, UssdSession.Step step, Instant expiresAt) {
    return jdbc.queryForObject(
        "INSERT INTO core.ussd_session(msisdn, step, payload, expires_at)"
            + " VALUES (?, ?, '{}'::jsonb, ?) RETURNING id",
        (rs, i) -> (UUID) rs.getObject("id"),
        msisdn,
        step.name(),
        OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
  }

  /** Charge une session NON expirée (au regard de l'instant fourni), si elle existe. */
  public Optional<UssdSession> findActive(UUID id, Instant now) {
    List<UssdSession> rows =
        jdbc.query(
            "SELECT id, msisdn, step, payload::text AS payload FROM core.ussd_session"
                + " WHERE id = ? AND expires_at > ?",
            (rs, i) -> {
              Map<String, Object> p = parse(rs.getString("payload"));
              Number amount = (Number) p.get("amountXaf");
              return new UssdSession(
                  (UUID) rs.getObject("id"),
                  rs.getString("msisdn"),
                  UssdSession.Step.valueOf(rs.getString("step")),
                  amount == null ? null : amount.longValue(),
                  (String) p.get("beneficiary"));
            },
            id,
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public void update(UssdSession session) {
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    if (session.amountXaf() != null) {
      payload.put("amountXaf", session.amountXaf());
    }
    if (session.beneficiary() != null) {
      payload.put("beneficiary", session.beneficiary());
    }
    jdbc.update(
        "UPDATE core.ussd_session SET step = ?, payload = ?::jsonb WHERE id = ?",
        session.step().name(),
        write(payload),
        session.id());
  }

  public void delete(UUID id) {
    jdbc.update("DELETE FROM core.ussd_session WHERE id = ?", id);
  }

  private Map<String, Object> parse(String jsonText) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> m = json.readValue(jsonText, Map.class);
      return m;
    } catch (Exception e) {
      throw new IllegalStateException("payload de session illisible", e);
    }
  }

  private String write(Map<String, Object> payload) {
    try {
      return json.writeValueAsString(payload);
    } catch (Exception e) {
      throw new IllegalStateException("payload de session non serialisable", e);
    }
  }
}
