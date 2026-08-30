package cm.identishield.pdp.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Écriture du journal d'audit (ED-06, ENF-03). Le rôle applicatif isc_pdp_app ne fait qu'INSERT
 * (payload, written_at) ; le chaînage — index d'ordre DANS le verrou, prev_hash, hash — est assuré
 * par le trigger BEFORE INSERT en base (SPIKE-3 / ADR-020). L'app n'a AUCUN accès à chain_head et
 * ne peut pas forger la chaîne. L'unicité sur decisionId garantit au plus une entrée par décision.
 *
 * <p>Revue ligne à ligne requise : journal d'audit.
 */
@Component
public class AuditLog {

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public AuditLog(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** Décision déjà journalisée pour ce decisionId (idempotence, ENF-03), si elle existe. */
  public Optional<Map<String, Object>> findByDecisionId(String decisionId) {
    List<String> rows =
        jdbc.query(
            "SELECT payload::text FROM pdp.audit_entry WHERE payload ->> 'decisionId' = ?",
            (rs, i) -> rs.getString(1),
            decisionId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(parse(rows.get(0)));
  }

  /**
   * Écrit une entrée d'audit. Lève {@code DuplicateKeyException} si une entrée existe déjà pour ce
   * decisionId (rejeu concurrent) : l'appelant renvoie alors la décision gagnante. written_at vient
   * de l'horloge injectée (ED-04).
   */
  public void append(Map<String, Object> payload, Instant writtenAt) {
    jdbc.update(
        "INSERT INTO pdp.audit_entry(payload, written_at) VALUES (?::jsonb, ?)",
        write(payload),
        OffsetDateTime.ofInstant(writtenAt, ZoneOffset.UTC));
  }

  private Map<String, Object> parse(String jsonText) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = json.readValue(jsonText, Map.class);
      return map;
    } catch (Exception e) {
      throw new IllegalStateException("payload d'audit illisible", e);
    }
  }

  private String write(Map<String, Object> payload) {
    try {
      return json.writeValueAsString(payload);
    } catch (Exception e) {
      throw new IllegalStateException("payload d'audit non sérialisable", e);
    }
  }
}
