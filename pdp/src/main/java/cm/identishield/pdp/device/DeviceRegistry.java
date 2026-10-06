package cm.identishield.pdp.device;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Registre des clés d'appareil (ISC-SAD DeviceRegistry). Au plus une clé ACTIVE par compte,
 * garantie par l'index unique partiel de la base (ES-20). M-09 n'en lit que la clé active :
 * l'enregistrement (avec vérification d'attestation, puis révocation de la précédente, ES-14b)
 * vient avec la route d'enrôlement.
 */
@Component
public class DeviceRegistry {

  /** Clé ACTIVE d'un compte : identifiant et clé publique SubjectPublicKeyInfo en base64. */
  public record DeviceKey(String id, String accountId, String publicKey) {}

  private static final String ACTIVE_SQL =
      "SELECT id, account_id, public_key FROM pdp.device WHERE account_id = ? AND state = 'ACTIVE'";

  private final JdbcTemplate jdbc;

  public DeviceRegistry(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<DeviceKey> findActive(String accountId) {
    List<DeviceKey> rows =
        jdbc.query(
            ACTIVE_SQL,
            (rs, i) ->
                new DeviceKey(
                    rs.getString("id"), rs.getString("account_id"), rs.getString("public_key")),
            accountId);
    return rows.stream().findFirst();
  }
}
