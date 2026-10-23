package cm.identishield.pdp.device;

import cm.identishield.pdp.config.RuleSet;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Code de récupération (ADR-041 §5, ES-18) : produit par le PDP, renvoyé UNE seule fois en clair ;
 * seule une empreinte PBKDF2-HMAC-SHA256 à sel unique est conservée ({@code pdp.recovery_code}),
 * jamais le code. Aucune primitive réimplémentée : {@link SecretKeyFactory} et {@link SecureRandom}
 * du JDK.
 *
 * <p>Le nombre d'itérations est fixé par un BUDGET de latence (ADR-042), pas par la recommandation
 * pour mots de passe. Ce qui protège le code est le comptage des essais en ligne (ES-21 : délai
 * croissant sur l'origine, jamais sur le compte) et le fait qu'il ne suffit jamais seul (le flux D
 * exige aussi le PIN, puis l'attestation matérielle).
 *
 * <p>Revue ligne à ligne requise : secret de récupération (empreinte, comparaison).
 */
// @implements ES-18
@Component
@EnableConfigurationProperties(RecoveryCodeProperties.class)
public class RecoveryCodes {

  /** Alphabet sans 0/O/1/I/L (ADR-041 §5), identique à celui du faux serveur. */
  static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

  static final String INSERT_SQL =
      "INSERT INTO pdp.recovery_code (device_id, code_hash, salt, iterations, issued_at) "
          + "VALUES (?, ?, ?, ?, ?)";
  static final String FIND_SQL =
      "SELECT code_hash, salt, iterations FROM pdp.recovery_code WHERE device_id = ?";

  private record Stored(String hash, String salt, int iterations) {}

  private final JdbcTemplate jdbc;
  private final RuleSet ruleSet;
  private final RecoveryCodeProperties props;
  private final SecureRandom random = new SecureRandom();

  public RecoveryCodes(JdbcTemplate jdbc, RuleSet ruleSet, RecoveryCodeProperties props) {
    this.jdbc = jdbc;
    this.ruleSet = ruleSet;
    this.props = props;
  }

  /** Tire un code, en conserve l'empreinte pour [deviceId] et renvoie le clair, une seule fois. */
  public String issue(String deviceId, Instant at) {
    int length = (int) ruleSet.integer("CODE_LENGTH");
    StringBuilder code = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    byte[] salt = new byte[props.saltBytes()];
    random.nextBytes(salt);
    String hash = derive(code.toString(), salt, props.iterations());
    jdbc.update(
        INSERT_SQL,
        deviceId,
        hash,
        Base64.getEncoder().encodeToString(salt),
        props.iterations(),
        OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
    return code.toString();
  }

  /**
   * Vrai si [candidate] est le code de [deviceId]. Comparaison en temps constant ; un appareil
   * inconnu dérive quand même une empreinte, pour que la durée ne dise pas s'il existe (ES-22).
   */
  public boolean matches(String deviceId, String candidate) {
    List<Stored> rows =
        jdbc.query(
            FIND_SQL,
            (rs, i) ->
                new Stored(
                    rs.getString("code_hash"), rs.getString("salt"), rs.getInt("iterations")),
            deviceId);
    if (rows.isEmpty()) {
      derive(candidate, new byte[props.saltBytes()], props.iterations());
      return false;
    }
    Stored s = rows.get(0);
    String computed = derive(candidate, Base64.getDecoder().decode(s.salt()), s.iterations());
    return MessageDigest.isEqual(
        Base64.getDecoder().decode(computed), Base64.getDecoder().decode(s.hash()));
  }

  private String derive(String code, byte[] salt, int iterations) {
    PBEKeySpec spec = new PBEKeySpec(code.toCharArray(), salt, iterations, props.keyBits());
    try {
      byte[] key =
          SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
      return Base64.getEncoder().encodeToString(key);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("PBKDF2WithHmacSHA256 indisponible", e);
    } finally {
      spec.clearPassword();
    }
  }
}
