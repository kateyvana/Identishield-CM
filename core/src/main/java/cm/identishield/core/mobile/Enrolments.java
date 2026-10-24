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
 * Enrôlements en cours ({@code core.enrolment}) : OTP → BIND → CONFIRM → DONE. Chaque transition
 * est un UPDATE conditionnel à l'étape attendue : un état ne progresse jamais deux fois, et la
 * consommation (DONE) n'est obtenue que par UN appelant. Les horodatages viennent de l'horloge
 * injectée (ED-04).
 *
 * <p>Revue ligne à ligne requise : machine à états de l'enrôlement.
 */
// @implements ES-25
@Repository
public class Enrolments {

  /** Un enrôlement, avec le numéro du compte (pour l'OTP, émis par numéro). */
  public record Enrolment(
      UUID id,
      UUID accountId,
      String msisdn,
      String stage,
      int otpFailures,
      String attestationChallenge,
      String deviceId,
      Instant expiresAt) {}

  private static final String INSERT_SQL =
      "INSERT INTO core.enrolment (account_id, stage, expires_at, created_at) "
          + "VALUES (?, ?, ?, ?) RETURNING id";
  private static final String FIND_SQL =
      "SELECT e.id, e.account_id, a.msisdn, e.stage, e.otp_failures, e.attestation_challenge,"
          + " e.device_id, e.expires_at FROM core.enrolment e"
          + " JOIN core.account a ON a.id = e.account_id WHERE e.id = ?";

  private final JdbcTemplate jdbc;

  public Enrolments(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Premier enrôlement (flux A) : l'OTP est la première étape. */
  public UUID open(UUID accountId, Instant now, Instant expiresAt) {
    return open(accountId, "OTP", now, expiresAt);
  }

  /**
   * Réenrôlement (flux D, carte B3b) : le compte est déjà lié, aucun OTP (EF-46) ; la première
   * étape est le code de récupération, vérifié par le PDP avec l'attestation à l'étape BIND.
   */
  public UUID openRecovery(UUID accountId, Instant now, Instant expiresAt) {
    return open(accountId, "RECOVERY", now, expiresAt);
  }

  private UUID open(UUID accountId, String stage, Instant now, Instant expiresAt) {
    return jdbc.queryForObject(
        INSERT_SQL, UUID.class, accountId, stage, utc(expiresAt), utc(now));
  }

  public Optional<Enrolment> find(UUID id) {
    List<Enrolment> rows =
        jdbc.query(
            FIND_SQL,
            (rs, i) ->
                new Enrolment(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("account_id"),
                    rs.getString("msisdn"),
                    rs.getString("stage"),
                    rs.getInt("otp_failures"),
                    rs.getString("attestation_challenge"),
                    rs.getString("device_id"),
                    rs.getObject("expires_at", OffsetDateTime.class).toInstant()),
            id);
    return rows.stream().findFirst();
  }

  public void otpFailed(UUID id) {
    jdbc.update(
        "UPDATE core.enrolment SET otp_failures = otp_failures + 1 WHERE id = ? AND stage = 'OTP'",
        id);
  }

  /**
   * OTP validé : le défi d'attestation est émis (ES-25). Vrai si CET appel a fait la transition.
   */
  public boolean toBind(UUID id, String attestationChallenge) {
    return toBind(id, attestationChallenge, "OTP");
  }

  /** Depuis {@code fromStage} (OTP au premier enrôlement, RECOVERY au réenrôlement). */
  public boolean toBind(UUID id, String attestationChallenge, String fromStage) {
    return jdbc.update(
            "UPDATE core.enrolment SET stage = 'BIND', attestation_challenge = ?"
                + " WHERE id = ? AND stage = ?",
            attestationChallenge,
            id,
            fromStage)
        == 1;
  }

  /** Clé EN ATTENTE enregistrée au PDP : seul son identifiant est gardé, jamais le code. */
  public boolean toConfirm(UUID id, String deviceId) {
    return jdbc.update(
            "UPDATE core.enrolment SET stage = 'CONFIRM', device_id = ? WHERE id = ? AND stage = ?",
            deviceId,
            id,
            "BIND")
        == 1;
  }

  /**
   * CONSOMME l'enrôlement depuis [fromStage] : toute reprise recevra 410. Vrai pour UN seul
   * appelant.
   */
  public boolean consume(UUID id, String fromStage) {
    return jdbc.update(
            "UPDATE core.enrolment SET stage = 'DONE' WHERE id = ? AND stage = ?", id, fromStage)
        == 1;
  }

  private static OffsetDateTime utc(Instant at) {
    return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
  }
}
