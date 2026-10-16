package cm.identishield.core.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import cm.identishield.core.time.VirtualClock;
import cm.identishield.core.transaction.PendingTransaction;
import cm.identishield.core.transaction.TransactionRepository;
import cm.identishield.core.transaction.TransactionRepository.DeferredTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

/**
 * Application des décisions par le cœur (carte A3) : un STEP_UP est résolu selon STEP_UP_POLICY
 * (USSD = CANCEL, application = DEFER, exécution à l'échéance sauf annulation, évaluée à la
 * lecture), un FREEZE bloque la transaction. STEP_UP_POLICY et DEFER sont lus dans
 * config/ruleset.yaml.
 *
 * <p>Exigences vérifiées :
 *
 * <p>@covers ES-06
 *
 * <p>@covers EF-05
 */
class StepUpApplicationTest {

  private static final String FROM = "677000001";
  private static final String BENEF = "677000002";
  private static final Instant T0 = Instant.parse("2026-10-16T10:00:00Z");

  private static RuleSet load() {
    try {
      return new Binder(
              ConfigurationPropertySources.from(
                  new YamlPropertySourceLoader()
                      .load("ruleset", new FileSystemResource("../config/ruleset.yaml"))))
          .bind("ruleset", RuleSet.class)
          .get();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private final RuleSet ruleSet = load();
  private final Duration defer = ruleSet.defer();
  private final VirtualClock clock = new VirtualClock(T0);
  private final AccountRepository accounts = Mockito.mock(AccountRepository.class);
  private final TransactionRepository transactions = Mockito.mock(TransactionRepository.class);
  private final PdpClient pdp = Mockito.mock(PdpClient.class);
  private final Account sender = new Account(UUID.randomUUID(), FROM, 1_000_000);
  private final PendingTransaction pending = new PendingTransaction(UUID.randomUUID(), "did-1");

  private TransferService service(RuleSet rs) {
    return new TransferService(
        accounts,
        transactions,
        pdp,
        rs,
        clock,
        new ObjectMapper(),
        Mockito.mock(HlrService.class),
        Mockito.mock(NotificationService.class),
        Mockito.mock(LayerMode.class));
  }

  private final TransferService service = service(ruleSet);

  @BeforeEach
  void wire() {
    when(accounts.findByMsisdn(FROM)).thenReturn(Optional.of(sender));
    when(accounts.findByMsisdn(BENEF)).thenReturn(Optional.empty());
    when(accounts.debit(any(), anyLong())).thenReturn(true);
    when(transactions.createPending(any(), anyString(), anyLong(), anyString(), anyString(), any()))
        .thenReturn(pending);
  }

  // ---- STEP_UP selon le canal ----

  @Test
  void stepUpOnUssd_isCancelled_andNeverExecuted() {
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.of("STEP_UP", "did-1"));
    TransferResult r = service.transferP2P(FROM, BENEF, 30_000, "USSD");
    assertEquals(TransferResult.State.CANCELLED, r.state());
    verify(transactions).markCancelled(pending.id());
    verify(transactions, never()).markExecuted(any());
    verify(accounts, never()).debit(any(), anyLong());
  }

  @Test
  void stepUpOnApp_isDeferredUntilNowPlusDefer_andNotExecutedYet() {
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.of("STEP_UP", "did-1"));
    TransferResult r = service.transferP2P(FROM, BENEF, 30_000, "APP");
    assertEquals(TransferResult.State.DEFERRED, r.state());
    assertEquals(T0.plus(defer).toString(), r.detail());
    verify(transactions).markDeferred(pending.id(), T0.plus(defer));
    verify(accounts, never()).debit(any(), anyLong());
  }

  @Test
  void anUnsupportedStepUpResolution_isRefused_neverGuessed() {
    Map<String, Object> params = new HashMap<>(ruleSet.parameters());
    params.put("STEP_UP_POLICY", Map.of("app", "SIGNATURE", "ussd", "CANCEL"));
    TransferService withSignature = service(new RuleSet("rs-test", params));
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.of("STEP_UP", "did-1"));
    assertThrows(
        IllegalStateException.class, () -> withSignature.transferP2P(FROM, BENEF, 30_000, "APP"));
    verify(accounts, never()).debit(any(), anyLong());
  }

  // ---- exécution différée, évaluée à la lecture ----

  private final DeferredTransaction due =
      new DeferredTransaction(UUID.randomUUID(), "did-0", "P2P", 30_000, BENEF);

  @Test
  void aDueDeferredOperation_isExecuted_whenTheAccountIsReadAgain() {
    clock.setInstant(T0.plus(defer));
    when(transactions.findDueDeferred(sender.id(), T0.plus(defer))).thenReturn(List.of(due));
    when(transactions.claimDueDeferred(due.id(), T0.plus(defer))).thenReturn(true);
    List<TransferResult> done = service.settleDeferred(FROM);
    assertEquals(List.of(new TransferResult(TransferResult.State.EXECUTED, "did-0", null)), done);
    verify(accounts).debit(sender.id(), 30_000);
    verify(transactions).markExecuted(due.id());
  }

  @Test
  void aNewOperation_firstSettlesTheDueDeferredOperationsOfTheAccount() {
    when(transactions.findDueDeferred(sender.id(), T0)).thenReturn(List.of(due));
    when(transactions.claimDueDeferred(due.id(), T0)).thenReturn(true);
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.of("ALLOW", "did-1"));
    service.transferP2P(FROM, BENEF, 1_000, "APP");
    verify(transactions).markExecuted(due.id());
    verify(accounts).debit(sender.id(), 30_000);
    verify(accounts).debit(sender.id(), 1_000);
  }

  @Test
  void aDeferredOperationClaimedElsewhere_isNeverExecutedTwice() {
    when(transactions.findDueDeferred(sender.id(), T0)).thenReturn(List.of(due));
    when(transactions.claimDueDeferred(due.id(), T0)).thenReturn(false);
    assertEquals(List.of(), service.settleDeferred(FROM));
    verify(accounts, never()).debit(any(), anyLong());
  }

  @Test
  void aDueDeferredOperation_withoutFunds_isRejected() {
    when(transactions.findDueDeferred(sender.id(), T0)).thenReturn(List.of(due));
    when(transactions.claimDueDeferred(due.id(), T0)).thenReturn(true);
    when(accounts.debit(sender.id(), 30_000)).thenReturn(false);
    List<TransferResult> done = service.settleDeferred(FROM);
    assertEquals(TransferResult.State.REJECTED, done.get(0).state());
    verify(transactions).markRejected(due.id());
    verify(transactions, never()).markExecuted(due.id());
  }

  @Test
  void cancellingADeferredOperation_delegatesToTheConditionalCancel_atTheReadInstant() {
    when(transactions.cancelDeferred(sender.id(), "did-0", T0)).thenReturn(true);
    assertTrue(service.cancelDeferred(FROM, "did-0"));
    when(transactions.cancelDeferred(sender.id(), "did-9", T0)).thenReturn(false);
    assertFalse(service.cancelDeferred(FROM, "did-9"));
  }

  // ---- FREEZE ----

  @Test
  void aFreezeDecision_blocksTheTransaction_andDebitsNothing() {
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.of("FREEZE", "did-1"));
    TransferResult r = service.transferP2P(FROM, BENEF, 30_000, "USSD");
    assertEquals(TransferResult.State.REJECTED, r.state());
    verify(transactions).markRejected(pending.id());
    verify(accounts, never()).debit(any(), anyLong());
    verify(transactions, never()).markDeferred(any(), any());
    verify(transactions, never()).markCancelled(any());
  }

  @Test
  void anUnknownChannel_isRefused() {
    assertThrows(
        IllegalArgumentException.class, () -> new StepUpResolver(ruleSet).forChannel("SMS"));
    assertEquals(
        StepUpResolver.Resolution.valueOf(ruleSet.stepUpPolicy("ussd")),
        new StepUpResolver(ruleSet).forChannel("USSD"));
    assertEquals(
        StepUpResolver.Resolution.valueOf(ruleSet.stepUpPolicy("app")),
        new StepUpResolver(ruleSet).forChannel("APP"));
    verify(accounts, never()).debit(eq(sender.id()), anyLong());
  }
}
