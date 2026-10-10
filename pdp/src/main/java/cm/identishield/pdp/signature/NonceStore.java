package cm.identishield.pdp.signature;

import cm.identishield.pdp.config.RuleSet;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Nonces de signature (ISC-SAD NonceStore ; ES-10) : 128 bits, liés au compte, validité NONCE_TTL,
 * usage unique. L'usage unique est garanti par la base : un seul UPDATE conditionnel sur {@code
 * consumed_at IS NULL}. De deux présentations du même nonce, même concurrentes, une seule le
 * consomme ; la seconde est un rejeu.
 */
// @implements ES-10
@Component
public class NonceStore {

  /** Consommation atomique : nonce de CE compte, jamais consommé, non expiré à l'instant donné. */
  static final String CONSUME_SQL =
      "UPDATE pdp.nonce SET consumed_at = ? "
          + "WHERE value = ? AND account_id = ? AND consumed_at IS NULL AND expires_at > ?";

  private static final String INSERT_SQL =
      "INSERT INTO pdp.nonce (value, account_id, issued_at, expires_at) VALUES (?, ?, ?, ?)";
  private static final String CONSUMED_SQL =
      "SELECT count(*) FROM pdp.nonce WHERE value = ? AND account_id = ? AND consumed_at IS NOT NULL";
  private static final int NONCE_BYTES = 16; // 128 bits (ES-10, ADR-018)

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final RuleSet ruleSet;
  private final SecureRandom random = new SecureRandom();

  public NonceStore(JdbcTemplate jdbc, Clock clock, RuleSet ruleSet) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.ruleSet = ruleSet;
  }

  /** Nonce émis pour un compte. */
  public record IssuedNonce(String value, String accountId, Instant issuedAt, Instant expiresAt) {}

  public IssuedNonce issue(String accountId) {
    Instant now = clock.instant();
    Instant expires = now.plus(ruleSet.nonceTtl());
    byte[] bytes = new byte[NONCE_BYTES];
    random.nextBytes(bytes);
    String value = HexFormat.of().formatHex(bytes);
    jdbc.update(INSERT_SQL, value, accountId, utc(now), utc(expires));
    return new IssuedNonce(value, accountId, now, expires);
  }

  /**
   * Vrai si CET appel a consommé le nonce ; faux s'il est inconnu, d'un autre compte, expiré ou
   * déjà consommé.
   */
  public boolean consume(String nonce, String accountId) {
    OffsetDateTime now = utc(clock.instant());
    return jdbc.update(CONSUME_SQL, now, nonce, accountId, now) == 1;
  }

  /** Après un échec de consommation : le nonce avait-il déjà servi (rejeu) ? */
  public boolean wasConsumed(String nonce, String accountId) {
    Integer n = jdbc.queryForObject(CONSUMED_SQL, Integer.class, nonce, accountId);
    return n != null && n > 0;
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
