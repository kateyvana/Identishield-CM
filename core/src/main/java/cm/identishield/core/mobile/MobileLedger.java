package cm.identishield.core.mobile;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Lectures du cœur pour les écrans du quotidien de l'application (carte B6 ; ISC-MOB-SPEC §7, flux
 * B et C) : compte, historique P2P, opérations différées, titulaire d'un bénéficiaire. Lecture
 * SEULE : toute écriture passe par {@code TransferService} et {@code TransactionRepository}. Les
 * identifiants rendus à l'application sont le {@code decision_id} de la transaction : c'est lui que
 * l'annulation signée et le journal d'audit connaissent.
 */
@Component
public class MobileLedger {

  /**
   * Le compte tel que l'accueil l'affiche ; {@code holderName} nul si aucun nom n'est enregistré.
   */
  public record AccountView(UUID id, String msisdn, String holderName, long balanceXaf) {}

  /** Une ligne d'historique, sens vu du compte de la session. */
  public record TransactionView(
      String transferId,
      String direction,
      long amountXaf,
      String counterpartyMsisdn,
      String counterpartyName,
      String status,
      Instant at) {}

  /** Une opération différée (STEP_UP résolu par DEFER), annulable jusqu'à son échéance. */
  public record DeferredView(
      String transferId, long amountXaf, String beneficiaryMsisdn, Instant executeAt) {}

  /** Lignes d'historique rendues au plus : les plus récentes d'abord (E-19, écran défilant). */
  static final int HISTORY_LIMIT = 50;

  private static final String ACCOUNT_SQL =
      "SELECT id, msisdn, holder_name, balance_xaf FROM core.account WHERE ";

  // Un seul passage : les sorties du compte (hors PENDING_DECISION, jamais montrée) et les entrées
  // P2P EXÉCUTÉES dont il est le bénéficiaire. Le bénéficiaire d'un P2P est son MSISDN
  // (TransferService) : le nom vient de core.account quand il existe dans le système.
  private static final String HISTORY_SQL =
      "SELECT COALESCE(t.decision_id::text, t.id::text) AS transfer_id,"
          + " CASE WHEN t.account_id = ? THEN 'OUT' ELSE 'IN' END AS direction,"
          + " t.amount_xaf,"
          + " CASE WHEN t.account_id = ? THEN t.beneficiary_id ELSE o.msisdn END AS counterparty,"
          + " CASE WHEN t.account_id = ? THEN b.holder_name ELSE o.holder_name END AS counterparty_name,"
          + " t.state, t.created_at"
          + " FROM core.transaction t"
          + " JOIN core.account o ON o.id = t.account_id"
          + " LEFT JOIN core.account b ON b.msisdn = t.beneficiary_id"
          + " WHERE t.type = 'P2P'"
          + " AND ((t.account_id = ? AND t.state <> 'PENDING_DECISION')"
          + "   OR (t.beneficiary_id = ? AND t.state = 'EXECUTED'))"
          + " ORDER BY t.created_at DESC, t.id DESC LIMIT "
          + HISTORY_LIMIT;

  private static final String DEFERRED_SQL =
      "SELECT decision_id::text AS transfer_id, amount_xaf, beneficiary_id, execute_at"
          + " FROM core.transaction WHERE account_id = ? AND type = 'P2P' AND state = 'DEFERRED'"
          + " ORDER BY execute_at, id";

  private static final String EVER_PAID_SQL =
      "SELECT EXISTS (SELECT 1 FROM core.transaction WHERE account_id = ? AND type = 'P2P'"
          + " AND beneficiary_id = ? AND state IN ('EXECUTED', 'DEFERRED'))";

  private final JdbcTemplate jdbc;

  public MobileLedger(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<AccountView> find(UUID accountId) {
    return jdbc.query(ACCOUNT_SQL + "id = ?", MobileLedger::account, accountId).stream()
        .findFirst();
  }

  public Optional<AccountView> findByMsisdn(String msisdn) {
    return jdbc.query(ACCOUNT_SQL + "msisdn = ?", MobileLedger::account, msisdn).stream()
        .findFirst();
  }

  /** Historique P2P du compte, les plus récentes d'abord. */
  public List<TransactionView> history(UUID accountId, String msisdn) {
    return jdbc.query(
        HISTORY_SQL,
        (rs, i) ->
            new TransactionView(
                rs.getString("transfer_id"),
                rs.getString("direction"),
                rs.getLong("amount_xaf"),
                rs.getString("counterparty"),
                rs.getString("counterparty_name"),
                rs.getString("state"),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()),
        accountId,
        accountId,
        accountId,
        accountId,
        msisdn);
  }

  /** Opérations différées du compte, par échéance croissante. */
  public List<DeferredView> deferred(UUID accountId) {
    return jdbc.query(
        DEFERRED_SQL,
        (rs, i) ->
            new DeferredView(
                rs.getString("transfer_id"),
                rs.getLong("amount_xaf"),
                rs.getString("beneficiary_id"),
                rs.getObject("execute_at", java.time.OffsetDateTime.class).toInstant()),
        accountId);
  }

  /**
   * Vrai si le compte a déjà payé ce numéro (badge « nouveau bénéficiaire », ES-05b côté écran).
   */
  public boolean everPaid(UUID accountId, String beneficiaryMsisdn) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(EVER_PAID_SQL, Boolean.class, accountId, beneficiaryMsisdn));
  }

  private static AccountView account(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
    return new AccountView(
        rs.getObject("id", UUID.class),
        rs.getString("msisdn"),
        rs.getString("holder_name"),
        rs.getLong("balance_xaf"));
  }
}
