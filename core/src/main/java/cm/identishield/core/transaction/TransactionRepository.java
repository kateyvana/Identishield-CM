package cm.identishield.core.transaction;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Accès aux transactions (rôle isc_core_app). Le decisionId (UUID v4, clé d'idempotence du
 * /evaluate, ENF-03) est généré par la base ({@code gen_random_uuid()}), comme les identifiants du
 * schéma (ED-09) — le code ne lit pas d'entropie (TA-CLOCK-01 interdit {@code UUID.randomUUID()}).
 * Machine à états (ISC-SAD §7.4, carte A3) : PENDING_DECISION -> EXECUTED | REJECTED | CANCELLED
 * (STEP_UP sur USSD) | DEFERRED (STEP_UP sur l'application) ; DEFERRED -> CANCELLED (annulation
 * avant l'échéance) | PENDING_DECISION (prise à l'échéance), puis EXECUTED | REJECTED.
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

  /** STEP_UP résolu par CANCEL (ES-06) : la transaction n'est jamais exécutée. */
  public void markCancelled(UUID id) {
    jdbc.update("UPDATE core.transaction SET state = 'CANCELLED' WHERE id = ?", id);
  }

  /** STEP_UP résolu par DEFER (ADR-016-A) : exécution prévue à {@code executeAt}, annulable. */
  public void markDeferred(UUID id, Instant executeAt) {
    jdbc.update(
        "UPDATE core.transaction SET state = 'DEFERRED', execute_at = ? WHERE id = ?",
        utc(executeAt),
        id);
  }

  /** Une opération différée du compte, telle qu'il faut l'exécuter à son échéance. */
  public record DeferredTransaction(
      UUID id, String decisionId, String type, long amountXaf, String beneficiaryId) {}

  /** Opérations différées du compte dont l'échéance est atteinte à {@code now}. */
  public List<DeferredTransaction> findDueDeferred(UUID accountId, Instant now) {
    return jdbc.query(
        "SELECT id, decision_id::text AS decision_id, type, amount_xaf, beneficiary_id"
            + " FROM core.transaction WHERE account_id = ? AND state = 'DEFERRED'"
            + " AND execute_at <= ? ORDER BY execute_at, id",
        (rs, i) ->
            new DeferredTransaction(
                (UUID) rs.getObject("id"),
                rs.getString("decision_id"),
                rs.getString("type"),
                rs.getLong("amount_xaf"),
                rs.getString("beneficiary_id")),
        accountId,
        utc(now));
  }

  /**
   * Prend une opération différée échue pour l'exécuter : un seul appelant réussit (UPDATE
   * conditionnel atomique), deux lectures simultanées n'exécutent donc jamais deux fois.
   */
  public boolean claimDueDeferred(UUID id, Instant now) {
    return jdbc.update(
            "UPDATE core.transaction SET state = 'PENDING_DECISION'"
                + " WHERE id = ? AND state = 'DEFERRED' AND execute_at <= ?",
            id,
            utc(now))
        == 1;
  }

  /**
   * Annule une opération différée du compte, seulement avant son échéance (après, elle est due et
   * sera exécutée). Vrai si cet appel l'a annulée.
   */
  public boolean cancelDeferred(UUID accountId, String decisionId, Instant now) {
    return jdbc.update(
            "UPDATE core.transaction SET state = 'CANCELLED'"
                + " WHERE account_id = ? AND decision_id = ?::uuid AND state = 'DEFERRED'"
                + " AND execute_at > ?",
            accountId,
            decisionId,
            utc(now))
        == 1;
  }

  private static OffsetDateTime utc(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
