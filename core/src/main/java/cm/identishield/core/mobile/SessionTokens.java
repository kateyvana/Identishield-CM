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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Session de l'application : un jeton aléatoire remis une fois, dont SEULE l'empreinte est
 * conservée ({@code core.session}). Ses trois durées sont IMPOSÉES PAR LE SERVEUR (P-09, décision
 * de la propriétaire du 2026-10-10, appliquée le 2026-10-24, carte B4), qui ne voit que des
 * requêtes :
 *
 * <ul>
 *   <li>inactivité APP_SESSION_TTL : aucune requête de l'USAGER depuis cette durée ;
 *   <li>arrière-plan APP_SESSION_BACKGROUND_TTL : aucune requête, même automatique, depuis cette
 *       durée (au premier plan, l'application interroge toutes les POLL_FOREGROUND) ;
 *   <li>durée maximale APP_SESSION_MAX_TTL depuis l'émission, inconditionnelle.
 * </ul>
 *
 * Un jeton mort n'est jamais ranimé : l'usager retape son PIN (décision du 2026-10-24, point 2).
 *
 * <p>Pourquoi SHA-256 SANS sel, et pas PBKDF2 : c'est l'ENTROPIE du secret qui décide. Le jeton
 * fait 256 bits tirés de {@link SecureRandom}, jamais tronqué : aucune recherche exhaustive ni
 * table précalculée n'a de prise, le sel et le coût par essai n'ajouteraient rien. Si le jeton
 * devient un jour dérivé ou raccourci, PBKDF2 revient.
 *
 * <p>Revue ligne à ligne requise : secret de session (tirage, empreinte), durées.
 */
// @implements ES-15
public class SessionTokens {

  /** Remis à l'application : le jeton en clair (une seule fois) et sa durée de vie en secondes. */
  public record IssuedSession(String token, long expiresInSeconds) {}

  /** Session vivante, après qu'une requête l'a touchée. */
  public record LiveSession(UUID accountId, String deviceId) {}

  static final String INSERT_SQL =
      "INSERT INTO core.session (token_hash, account_id, device_id, expires_at, created_at, "
          + "last_seen_at, last_activity_at) VALUES (?, ?, ?, ?, ?, ?, ?)";

  /**
   * Une seule instruction : la session n'est touchée QUE si elle est vivante selon les trois
   * durées, et la requête de l'usager seule avance last_activity_at. Un jeton inconnu et un jeton
   * mort reçoivent la même absence de ligne : l'appelant n'apprend pas lequel (ES-22).
   */
  static final String TOUCH_SQL =
      "UPDATE core.session SET last_seen_at = ?, "
          + "last_activity_at = CASE WHEN ? THEN ? ELSE last_activity_at END "
          + "WHERE token_hash = ? AND expires_at > ? AND last_seen_at > ? AND last_activity_at > ? "
          + "RETURNING account_id, device_id";

  /** 256 bits. */
  private static final int TOKEN_BYTES = 32;

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final Duration idle;
  private final Duration background;
  private final Duration max;
  private final SecureRandom random = new SecureRandom();

  public SessionTokens(
      JdbcTemplate jdbc, Clock clock, Duration idle, Duration background, Duration max) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.idle = idle;
    this.background = background;
    this.max = max;
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
        utc(now.plus(max)),
        utc(now),
        utc(now),
        utc(now));
    // Le contrat annonce APP_SESSION_TTL (l'inactivité) ; les deux autres durées sont du serveur.
    return new IssuedSession(token, idle.toSeconds());
  }

  /**
   * Touche la session du jeton présenté : vivante selon les trois durées → elle est prolongée et
   * renvoyée ; sinon vide, et rien n'est modifié. {@code userActivity} est faux pour une requête
   * automatique de l'application (interrogation des alertes), qui ne compte pas comme activité.
   */
  public Optional<LiveSession> touch(String token, boolean userActivity) {
    if (token == null || token.isEmpty()) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    List<LiveSession> rows =
        jdbc.query(
            TOUCH_SQL,
            (rs, i) ->
                new LiveSession(rs.getObject("account_id", UUID.class), rs.getString("device_id")),
            utc(now),
            userActivity,
            utc(now),
            sha256Hex(token),
            utc(now),
            utc(now.minus(background)),
            utc(now.minus(idle)));
    return rows.stream().findFirst();
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

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
