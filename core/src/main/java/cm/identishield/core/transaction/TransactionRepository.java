package cm.identishield.core.transaction;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Accès aux transactions (rôle isc_core_app). Le decisionId (UUID v4, clé d'idempotence du
 * /evaluate, ENF-03) est généré par la base ({@code gen_random_uuid()}), comme les identifiants du
 * schéma (ED-09) — le code ne lit pas d'entropie (TA-CLOCK-01 interdit {@code UUID.randomUUID()}).
 * Machine à états minimale au mois 2 : PENDING_DECISION -> EXECUTED | REJECTED.
 */
@Repository
public class TransactionRepository {

  private final JdbcTemplate jdbc;

  public TransactionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Crée la transaction à PENDING_DECISION et renvoie son id + son decisionId (généré en base). */
  public PendingTransaction createPending(
      UUID accountId,
      String type,
      long amountXaf,
      String beneficiaryId,
      String channel,
      Instant createdAt) {
    return jdbc.queryForObject(
        "INSERT INTO core.transaction"
            + "(account_id, type, amount_xaf, beneficiary_id, channel, state, decision_id, created_at)"
            + " VALUES (?, ?, ?, ?, ?, 'PENDING_DECISION', gen_random_uuid(), ?)"
            + " RETURNING id, decision_id::text AS decision_id",
        (rs, i) -> new PendingTransaction((UUID) rs.getObject("id"), rs.getString("decision_id")),
        accountId,
        type,
        amountXaf,
        beneficiaryId,
        channel,
        OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
  }

  public void markExecuted(UUID id) {
    jdbc.update("UPDATE core.transaction SET state = 'EXECUTED' WHERE id = ?", id);
  }

  public void markRejected(UUID id) {
    jdbc.update("UPDATE core.transaction SET state = 'REJECTED' WHERE id = ?", id);
  }
}
