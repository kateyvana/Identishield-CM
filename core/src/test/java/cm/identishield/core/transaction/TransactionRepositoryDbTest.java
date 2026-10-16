package cm.identishield.core.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.identishield.core.transaction.TransactionRepository.DeferredTransaction;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Opérations différées (carte A3) sur une VRAIE base PostgreSQL construite par infra/init/init.sql,
 * sous le rôle applicatif isc_core_app : l'échéance, la prise unique à l'échéance (jamais deux
 * exécutions), l'annulation seulement avant l'échéance et seulement par le compte.
 *
 * <p>Sans base (ISC_TEST_PG_URL absente, ex. make check sur le poste), la classe est ignorée ;
 * tools/verify_trust_state.sh la lance contre une base jetable dans la VM.
 *
 * <p>@covers ES-06
 */
@EnabledIfEnvironmentVariable(named = "ISC_TEST_PG_URL", matches = ".+")
class TransactionRepositoryDbTest {

  private static final Instant T0 = Instant.parse("2026-10-16T10:00:00Z");
  private static final Duration DELAY = Duration.ofMinutes(90);
  private static final AtomicInteger NEXT = new AtomicInteger();

  private static JdbcTemplate jdbc;
  private static TransactionRepository repo;

  private UUID account;
  private PendingTransaction tx;

  @BeforeAll
  static void connect() {
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                System.getenv("ISC_TEST_PG_URL"),
                System.getenv("ISC_TEST_PG_CORE_USER"),
                System.getenv("ISC_TEST_PG_CORE_PASSWORD")));
    repo = new TransactionRepository(jdbc);
  }

  private static UUID newAccount() {
    String msisdn = String.format("6770080%02d", NEXT.incrementAndGet());
    return jdbc.queryForObject(
        "INSERT INTO core.account(msisdn, balance_xaf, created_at) VALUES (?, 100000, ?)"
            + " RETURNING id",
        UUID.class,
        msisdn,
        OffsetDateTime.ofInstant(T0, ZoneOffset.UTC));
  }

  private String state(UUID id) {
    return jdbc.queryForObject("SELECT state FROM core.transaction WHERE id = ?", String.class, id);
  }

  @BeforeEach
  void deferredTransaction() {
    account = newAccount();
    tx = repo.createPending(account, "P2P", 30_000, "677008999", "APP", T0);
    repo.markDeferred(tx.id(), T0.plus(DELAY));
  }

  @Test
  void aDeferredOperation_isDueOnlyAtItsDeadline() {
    assertEquals("DEFERRED", state(tx.id()));
    assertEquals(List.of(), repo.findDueDeferred(account, T0.plus(DELAY).minusSeconds(1)));
    assertEquals(
        List.of(new DeferredTransaction(tx.id(), tx.decisionId(), "P2P", 30_000, "677008999")),
        repo.findDueDeferred(account, T0.plus(DELAY)));
  }

  @Test
  void aDueOperation_isClaimedOnce_neverTwice() {
    assertFalse(repo.claimDueDeferred(tx.id(), T0.plus(DELAY).minusSeconds(1)), "pas avant");
    assertTrue(repo.claimDueDeferred(tx.id(), T0.plus(DELAY)));
    assertFalse(repo.claimDueDeferred(tx.id(), T0.plus(DELAY)), "seconde prise refusée");
    assertEquals("PENDING_DECISION", state(tx.id()));
    assertEquals(List.of(), repo.findDueDeferred(account, T0.plus(DELAY)));
  }

  @Test
  void anOperation_isCancellableOnlyBeforeItsDeadline_andOnlyOnce() {
    assertFalse(repo.cancelDeferred(account, tx.decisionId(), T0.plus(DELAY)), "échue : non");
    assertTrue(repo.cancelDeferred(account, tx.decisionId(), T0.plus(DELAY).minusSeconds(1)));
    assertFalse(repo.cancelDeferred(account, tx.decisionId(), T0), "déjà annulée");
    assertEquals("CANCELLED", state(tx.id()));
    assertEquals(List.of(), repo.findDueDeferred(account, T0.plus(DELAY)));
    assertFalse(repo.claimDueDeferred(tx.id(), T0.plus(DELAY)), "une annulée n'est jamais prise");
  }

  @Test
  void anotherAccount_cannotCancelIt() {
    assertFalse(repo.cancelDeferred(newAccount(), tx.decisionId(), T0));
    assertEquals("DEFERRED", state(tx.id()));
  }

  @Test
  void aStepUpOnUssd_isCancelled() {
    PendingTransaction ussd = repo.createPending(account, "P2P", 30_000, "677008999", "USSD", T0);
    repo.markCancelled(ussd.id());
    assertEquals("CANCELLED", state(ussd.id()));
  }

  @Test
  void aDeferredStateWithoutDeadline_isRefusedByTheDatabase() {
    PendingTransaction other = repo.createPending(account, "P2P", 10, "677008999", "APP", T0);
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update("UPDATE core.transaction SET state = 'DEFERRED' WHERE id = ?", other.id()));
  }
}
