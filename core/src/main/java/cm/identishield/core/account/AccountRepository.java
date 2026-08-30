package cm.identishield.core.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Accès aux comptes (rôle isc_core_app). Montants entiers FCFA (ED-05). */
@Repository
public class AccountRepository {

  private final JdbcTemplate jdbc;

  public AccountRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Account> findByMsisdn(String msisdn) {
    List<Account> rows =
        jdbc.query(
            "SELECT id, msisdn, balance_xaf FROM core.account WHERE msisdn = ?",
            (rs, i) ->
                new Account(
                    (UUID) rs.getObject("id"), rs.getString("msisdn"), rs.getLong("balance_xaf")),
            msisdn);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** Débit atomique : n'aboutit que si le solde reste positif. Vrai si débité. */
  public boolean debit(UUID accountId, long amountXaf) {
    return jdbc.update(
            "UPDATE core.account SET balance_xaf = balance_xaf - ? WHERE id = ? AND balance_xaf >= ?",
            amountXaf,
            accountId,
            amountXaf)
        == 1;
  }

  public void credit(UUID accountId, long amountXaf) {
    jdbc.update(
        "UPDATE core.account SET balance_xaf = balance_xaf + ? WHERE id = ?", amountXaf, accountId);
  }

  /** Insère un compte si le MSISDN n'existe pas encore (amorçage idempotent). */
  public void insertIfAbsent(String msisdn, long balanceXaf, java.time.Instant createdAt) {
    jdbc.update(
        "INSERT INTO core.account(msisdn, balance_xaf, created_at) VALUES (?, ?, ?)"
            + " ON CONFLICT (msisdn) DO NOTHING",
        msisdn,
        balanceXaf,
        java.time.OffsetDateTime.ofInstant(createdAt, java.time.ZoneOffset.UTC));
  }
}
