package cm.identishield.core.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * @covers EF-05 ; @covers ES-11 (appel /evaluate avant commit + fail-closed).
 */
class TransferServiceTest {

  private static final String FROM = "677000001";
  private static final String BENEF = "677000002";
  private static final long CAP_FAILCLOSED = 25_000;

  private final AccountRepository accounts = Mockito.mock(AccountRepository.class);
  private final TransactionRepository transactions = Mockito.mock(TransactionRepository.class);
  private final PdpClient pdp = Mockito.mock(PdpClient.class);
  private final RuleSet ruleSet =
      new RuleSet("rs-0.1.0", Map.of("CAP_FAILCLOSED", Map.of("xaf", 25000)));
  private final HlrService hlr = Mockito.mock(HlrService.class);
  private final NotificationService notifications = Mockito.mock(NotificationService.class);
  private final LayerMode layerMode = Mockito.mock(LayerMode.class);

  private final TransferService service =
      new TransferService(
          accounts,
          transactions,
          pdp,
          ruleSet,
          Clock.fixed(Instant.parse("2026-08-26T09:15:00Z"), ZoneOffset.UTC),
          new ObjectMapper(),
          hlr,
          notifications,
          layerMode);

  private final Account sender = new Account(UUID.randomUUID(), FROM, 1_000_000);
  private final PendingTransaction pending = new PendingTransaction(UUID.randomUUID(), "did-1");

  @BeforeEach
  void wire() {
    when(accounts.findByMsisdn(FROM)).thenReturn(Optional.of(sender));
    when(accounts.findByMsisdn(BENEF)).thenReturn(Optional.empty());
    when(accounts.debit(any(), anyLong())).thenReturn(true);
    when(transactions.createPending(any(), anyString(), anyLong(), anyString(), anyString(), any()))
        .thenReturn(pending);
  }

  @Test
  void allowExecutesTheTransfer() {
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.of("ALLOW", "did-1"));

    TransferResult r = service.transferP2P(FROM, BENEF, 25_000, "USSD");

    assertEquals(TransferResult.State.EXECUTED, r.state());
    verify(accounts).debit(eq(sender.id()), eq(25_000L));
    verify(transactions).markExecuted(pending.id());
  }

  @Test
  void pdpUnavailableAboveCapFailClosedIsRefusedFailClosed() {
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.unavailable());

    TransferResult r = service.transferP2P(FROM, BENEF, CAP_FAILCLOSED + 1, "USSD");

    assertEquals(TransferResult.State.REJECTED, r.state());
    verify(transactions).markRejected(pending.id());
    verify(accounts, never()).debit(any(), anyLong()); // aucun debit : fail-closed
  }

  @Test
  void pdpUnavailableAtOrBelowCapFailClosedRunsDegraded() {
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.unavailable());

    TransferResult r = service.transferP2P(FROM, BENEF, CAP_FAILCLOSED, "USSD");

    assertEquals(TransferResult.State.EXECUTED_DEGRADED, r.state());
    verify(accounts).debit(eq(sender.id()), eq(CAP_FAILCLOSED));
  }

  /** La requête /evaluate porte l'IMSI courant du HLR et JAMAIS le motif d'une réattribution. */
  // @covers ES-16b
  @Test
  void evaluateRequestUsesHlrImsiAndNeverCarriesMotif() {
    when(hlr.currentImsi(FROM)).thenReturn(Optional.of("624019999999999"));

    String req = service.evaluateRequestPreview(FROM, BENEF, 15_000, "USSD");

    assertTrue(req.contains("624019999999999")); // IMSI courant résolu depuis le HLR (EF-02)
    assertFalse(req.toLowerCase(java.util.Locale.ROOT).contains("motif"));
    assertFalse(req.contains("reason"));
    assertFalse(req.contains("agent"));
  }
}
