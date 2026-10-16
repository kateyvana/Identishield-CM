package cm.identishield.core.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.core.account.Account;
import cm.identishield.core.account.AccountRepository;
import cm.identishield.core.config.RuleSet;
import cm.identishield.core.hlr.HlrService;
import cm.identishield.core.mode.LayerMode;
import cm.identishield.core.notification.NotificationService;
import cm.identishield.core.pdp.EvaluateOutcome;
import cm.identishield.core.pdp.PdpClient;
import cm.identishield.core.transaction.PendingTransaction;
import cm.identishield.core.transaction.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * ES-11 ratifié (propriétaire, 2026-10-15) : quand le PDP est indisponible, le cœur refuse toute
 * sortie au-dessus de CAP_FAILCLOSED, et non plus de CAP_PROB. Les deux seuils sont injectés avec
 * des valeurs DIFFÉRENTES : seul le bon seuil fait passer ces tests.
 *
 * <p>@covers ES-11
 */
class FailClosedThresholdTest {

  private static final String FROM = "677000001";
  private static final String BENEF = "677000002";
  private static final long CAP_PROB = 25_000;
  private static final long CAP_FAILCLOSED = 50_000;

  private final AccountRepository accounts = Mockito.mock(AccountRepository.class);
  private final TransactionRepository transactions = Mockito.mock(TransactionRepository.class);
  private final PdpClient pdp = Mockito.mock(PdpClient.class);
  private final RuleSet ruleSet =
      new RuleSet(
          "rs-test",
          Map.of(
              "CAP_PROB", Map.of("dailyXaf", 25000),
              "CAP_FAILCLOSED", Map.of("xaf", 50000)));

  private final TransferService service =
      new TransferService(
          accounts,
          transactions,
          pdp,
          ruleSet,
          Clock.fixed(Instant.parse("2026-10-16T09:15:00Z"), ZoneOffset.UTC),
          new ObjectMapper(),
          Mockito.mock(HlrService.class),
          Mockito.mock(NotificationService.class),
          Mockito.mock(LayerMode.class));

  private final Account sender = new Account(UUID.randomUUID(), FROM, 1_000_000);
  private final PendingTransaction pending = new PendingTransaction(UUID.randomUUID(), "did-1");

  @BeforeEach
  void wire() {
    when(accounts.findByMsisdn(FROM)).thenReturn(Optional.of(sender));
    when(accounts.findByMsisdn(BENEF)).thenReturn(Optional.empty());
    when(accounts.debit(any(), anyLong())).thenReturn(true);
    when(transactions.createPending(any(), anyString(), anyLong(), anyString(), anyString(), any()))
        .thenReturn(pending);
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.unavailable());
  }

  @Test
  void aboveCapProbButNotAboveCapFailClosed_runsDegraded() {
    TransferResult r = service.transferP2P(FROM, BENEF, CAP_PROB + 1, "USSD");
    assertEquals(TransferResult.State.EXECUTED_DEGRADED, r.state());
    verify(accounts).debit(eq(sender.id()), eq(CAP_PROB + 1));
  }

  @Test
  void atCapFailClosed_runsDegraded() {
    TransferResult r = service.transferP2P(FROM, BENEF, CAP_FAILCLOSED, "USSD");
    assertEquals(TransferResult.State.EXECUTED_DEGRADED, r.state());
  }

  @Test
  void aboveCapFailClosed_isRefused_andNothingIsDebited() {
    TransferResult r = service.transferP2P(FROM, BENEF, CAP_FAILCLOSED + 1, "USSD");
    assertEquals(TransferResult.State.REJECTED, r.state());
    verify(transactions).markRejected(pending.id());
    verify(accounts, never()).debit(any(), anyLong());
  }
}
