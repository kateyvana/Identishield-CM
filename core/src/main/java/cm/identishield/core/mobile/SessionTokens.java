package cm.identishield.core.mobile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Session minimale de l'application (carte B3a, décision 4) : un jeton aléatoire remis une fois,
 * dont SEULE l'empreinte est conservée ({@code core.session}), avec une échéance fixe
 * APP_SESSION_TTL et aucun autre minuteur (inactivité, arrière-plan : carte B4).
 *
 * <p>Pourquoi SHA-256 SANS sel, et pas PBKDF2 : c'est l'ENTROPIE du secret qui décide. Le jeton
 * fait 256 bits tirés de {@link SecureRandom}, jamais tronqué : aucune recherche exhaustive ni
 * table précalculée n'a de prise, le sel et le coût par essai n'ajouteraient rien. Si le jeton
 * devient un jour dérivé ou raccourci, PBKDF2 revient.
 *
 * <p>Revue ligne à ligne requise : secret de session (tirage, empreinte).
 */
// @implements ES-15
public class SessionTokens {

  /** Remis à l'application : le jeton en clair (une seule fois) et sa durée de vie en secondes. */
  public record IssuedSession(String token, long expiresInSeconds) {}

  static final String INSERT_SQL =
      "INSERT INTO core.session (token_hash, account_id, device_id, expires_at, created_at) "
          + "VALUES (?, ?, ?, ?, ?)";

  /** 256 bits. */
  private static final int TOKEN_BYTES = 32;

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final Duration ttl;
  private final SecureRandom random = new SecureRandom();

  public SessionTokens(JdbcTemplate jdbc, Clock clock, Duration ttl) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.ttl = ttl;
  }

  public IssuedSession issue(UUID accountId, String deviceId) {
    byte[] raw = new byte[TOKEN_BYTES];
    random.nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    Instant now = clock.instant();
    jdbc.update(
        INSERT_SQL,
        sha256Hex(token),
        accountId,
        deviceId,
        OffsetDateTime.ofInstant(now.plus(ttl), ZoneOffset.UTC),
        OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    return new IssuedSession(token, ttl.toSeconds());
  }

  static String sha256Hex(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(token.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponible", e);
    }
  }
}
