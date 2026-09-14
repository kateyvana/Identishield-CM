package cm.identishield.core.otp;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistance des OTP (EF-03), table {@code core.otp} de T2-01. La base ne conserve qu'une
 * EMPREINTE du code ({@code code_hash}), jamais le clair. Les horodatages viennent de l'horloge
 * injectée (ED-04), jamais d'un DEFAULT de base.
 *
 * <p>Revue ligne à ligne requise : credential à usage unique.
 */
// @implements EF-03
@Repository
public class OtpRepository {

  private final JdbcTemplate jdbc;

  public OtpRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Émet un OTP : empreinte, IMSI cible, expiration. consumed_at reste NULL (non consommé). */
  public void issue(String msisdn, String targetImsi, String codeHash, Instant expiresAt) {
    jdbc.update(
        "INSERT INTO core.otp(msisdn, target_imsi, code_hash, expires_at) VALUES (?, ?, ?, ?)",
        msisdn,
        targetImsi,
        codeHash,
        OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
  }

  /**
   * Consomme ATOMIQUEMENT l'OTP s'il est valide : non consommé ET non expiré, pour ce numéro et
   * cette empreinte. Renvoie le nombre de lignes marquées consommées (1 = accepté ; 0 = rien de
   * valide). L'usage unique est garanti par cet UPDATE conditionnel — deux appels concurrents du
   * même code : un seul obtient la ligne.
   */
  public int consumeIfValid(String msisdn, String codeHash, Instant now) {
    OffsetDateTime ts = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
    return jdbc.update(
        "UPDATE core.otp SET consumed_at = ? WHERE msisdn = ? AND code_hash = ?"
            + " AND consumed_at IS NULL AND expires_at > ?",
        ts,
        msisdn,
        codeHash,
        ts);
  }

  /**
   * Motif du refus quand {@link #consumeIfValid} n'a rien consommé : consommé (rejeu) prime sur
   * expiré ; sinon inconnu (aucune empreinte correspondante). L'entrée la plus récente fait foi.
   */
  public OtpOutcome reasonFor(String msisdn, String codeHash, Instant now) {
    List<OtpOutcome> rows =
        jdbc.query(
            "SELECT consumed_at IS NOT NULL AS consumed, expires_at <= ? AS expired"
                + " FROM core.otp WHERE msisdn = ? AND code_hash = ?"
                + " ORDER BY expires_at DESC LIMIT 1",
            (rs, i) -> {
              if (rs.getBoolean("consumed")) {
                return OtpOutcome.REFUSED_CONSUMED;
              }
              return rs.getBoolean("expired")
                  ? OtpOutcome.REFUSED_EXPIRED
                  : OtpOutcome.REFUSED_UNKNOWN;
            },
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
            msisdn,
            codeHash);
    return rows.isEmpty() ? OtpOutcome.REFUSED_UNKNOWN : rows.get(0);
  }
}
