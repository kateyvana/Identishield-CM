package cm.identishield.pdp.device;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Registre des clés d'appareil (ISC-SAD DeviceRegistry). Au plus une clé ACTIVE par compte,
 * garantie par l'index unique partiel de la base (ES-20). Cycle de vie (ADR-041 §1) : une clé est
 * enregistrée EN ATTENTE, puis ACTIVÉE après confirmation du code de récupération (invariant D4).
 * Une clé EN ATTENTE n'est jamais renvoyée par {@link #findActive} : elle ne signe rien. La
 * révocation de la précédente (ES-14b) vient avec le réenrôlement (carte B3b).
 *
 * <p>Revue ligne à ligne requise : machine à états de la clé d'appareil.
 */
// @implements ES-20
@Component
public class DeviceRegistry {

  /** Clé ACTIVE d'un compte : identifiant et clé publique SubjectPublicKeyInfo en base64. */
  public record DeviceKey(String id, String accountId, String publicKey) {}

  private static final String ACTIVE_SQL =
      "SELECT id, account_id, public_key FROM pdp.device WHERE account_id = ? AND state = 'ACTIVE'";
  private static final String STATES_SQL =
      "SELECT state FROM pdp.device WHERE account_id = ? AND state IN ('ACTIVE', 'PENDING')";
  private static final String HAS_ACTIVE_SQL =
      "SELECT count(*) FROM pdp.device WHERE account_id = ? AND state = 'ACTIVE'";

  /**
   * Sérialise les enregistrements d'un même compte jusqu'à la fin de la transaction : sans ce
   * verrou, deux enregistrements concurrents révoqueraient puis inséreraient chacun, et le second
   * heurterait l'index device_one_pending_per_account.
   */
  private static final String LOCK_ACCOUNT_SQL = "SELECT pg_advisory_xact_lock(hashtext(?))";

  /** Une seule clé EN ATTENTE par compte : la précédente est révoquée avant la nouvelle. */
  private static final String REVOKE_PENDING_SQL =
      "UPDATE pdp.device SET state = 'REVOKED' WHERE account_id = ? AND state = 'PENDING'";

  /** Identifiant produit par la base (gen_random_uuid) : aucun UUID.randomUUID dans le code. */
  private static final String INSERT_PENDING_SQL =
      "INSERT INTO pdp.device (id, account_id, public_key, state, enrolled_at) "
          + "VALUES (gen_random_uuid()::text, ?, ?, 'PENDING', ?) RETURNING id";

  /** EN ATTENTE → ACTIVE, pour CE compte seulement ; aucune autre transition ici. */
  private static final String ACTIVATE_SQL =
      "UPDATE pdp.device SET state = 'ACTIVE' WHERE id = ? AND account_id = ? AND state = 'PENDING'";

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

  /**
   * État de liaison d'un compte, tel qu'il est en base : aucune clé, clé EN ATTENTE, clé ACTIVE.
   */
  public enum BindingState {
    NONE,
    PENDING,
    ACTIVE
  }

  /**
   * FAIT de liaison du compte (ES-26) : ACTIVE si une clé ACTIVE existe, sinon PENDING si une clé
   * EN ATTENTE existe, sinon NONE. Le PDP énonce, le cœur arbitre : la correspondance avec l'étape
   * suivante (OTP ou RECOVERY) n'est pas faite ici.
   */
  // @implements ES-26
  public BindingState bindingState(String accountId) {
    List<String> states = jdbc.queryForList(STATES_SQL, String.class, accountId);
    if (states.contains("ACTIVE")) {
      return BindingState.ACTIVE;
    }
    return states.contains("PENDING") ? BindingState.PENDING : BindingState.NONE;
  }

  /** Vrai si le compte possède déjà une clé ACTIVE (invariant R1 de l'amendement d'ADR-041). */
  public boolean hasActive(String accountId) {
    Integer n = jdbc.queryForObject(HAS_ACTIVE_SQL, Integer.class, accountId);
    return n != null && n > 0;
  }

  /**
   * Enregistre la clé EN ATTENTE ; renvoie son identifiant. [at] vient de l'horloge injectée. Toute
   * clé EN ATTENTE précédente du compte est d'abord RÉVOQUÉE : au plus une par compte, garanti par
   * l'index device_one_pending_per_account. À appeler dans une transaction (le verrou de compte
   * dure jusqu'à sa fin).
   */
  public String registerPending(String accountId, String publicKey, Instant at) {
    jdbc.queryForList(LOCK_ACCOUNT_SQL, accountId);
    jdbc.update(REVOKE_PENDING_SQL, accountId);
    return jdbc.queryForObject(
        INSERT_PENDING_SQL,
        String.class,
        accountId,
        publicKey,
        OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
  }

  /**
   * Vrai si CET appel a fait passer la clé EN ATTENTE de ce compte à ACTIVE. Si une autre clé est
   * déjà ACTIVE (course entre deux activations), l'index unique refuse : c'est un échec ordinaire,
   * jamais deux clés actives (ES-20).
   */
  public boolean activate(String accountId, String deviceId) {
    try {
      return jdbc.update(ACTIVATE_SQL, deviceId, accountId) == 1;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }
}
