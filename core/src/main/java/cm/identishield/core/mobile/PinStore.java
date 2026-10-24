package cm.identishield.core.mobile;

import cm.identishield.core.mobile.PinHasher.ProtectedPin;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Empreinte du PIN applicatif sur {@code core.account} (carte B3a, décision 2). Seule l'empreinte,
 * le sel et le nombre d'itérations sont lus ou écrits ; le PIN n'existe jamais en base.
 */
@Repository
public class PinStore {

  /** Compte trouvé par son numéro ; [pin] est nul si aucun PIN n'est posé. */
  public record AccountPin(UUID accountId, ProtectedPin pin) {}

  private final JdbcTemplate jdbc;

  public PinStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<AccountPin> find(String msisdn) {
    List<AccountPin> rows =
        jdbc.query(
            "SELECT id, pin_hash, pin_salt, pin_iterations FROM core.account WHERE msisdn = ?",
            (rs, i) -> {
              String hash = rs.getString("pin_hash");
              ProtectedPin pin =
                  hash == null
                      ? null
                      : new ProtectedPin(
                          hash, rs.getString("pin_salt"), rs.getInt("pin_iterations"));
              return new AccountPin((UUID) rs.getObject("id"), pin);
            },
            msisdn);
    return rows.stream().findFirst();
  }

  /** Pose le PIN d'un compte qui n'en a pas (amorçage idempotent) ; vrai s'il a été posé. */
  public boolean setIfAbsent(String msisdn, ProtectedPin pin) {
    return jdbc.update(
            "UPDATE core.account SET pin_hash = ?, pin_salt = ?, pin_iterations = ?"
                + " WHERE msisdn = ? AND pin_hash IS NULL",
            pin.hash(),
            pin.salt(),
            pin.iterations(),
            msisdn)
        == 1;
  }
}
