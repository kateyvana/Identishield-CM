package cm.identishield.pdp.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.config.RuleSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * @covers EF-11 ; @covers ENF-03 (decision squelette + idempotence, sans base).
 */
class EvaluateControllerTest {

  private static final String DID = "5f1e7c1a-9d3b-4e6a-8c2f-0b7d9a4e6c1d";
  private static final Instant T0 = Instant.parse("2026-08-26T09:15:00Z");

  private final AuditLog auditLog = mock(AuditLog.class);
  private final RuleSet ruleSet = new RuleSet("rs-0.1.0", Map.of());
  private final EvaluateController controller =
      new EvaluateController(auditLog, Clock.fixed(T0, ZoneOffset.UTC), ruleSet);

  private EvaluateRequest request() {
    return new EvaluateRequest(
        DID,
        "acc-1",
        "699000123",
        "624019900001230",
        "APP",
        "CASH_OUT",
        25000,
        "ben-1",
        "2026-08-26T09:15:00Z",
        null,
        null,
        null,
        null,
        "NONE");
  }

  @Test
  void freshDecisionReturnsAllowAndWritesOneAuditEntry() {
    when(auditLog.findByDecisionId(DID)).thenReturn(Optional.empty());

    EvaluateResponse r = controller.evaluate(request());

    assertEquals("ALLOW", r.decision());
    assertEquals(0, r.score());
    assertEquals("NONE", r.requiredAction());
    assertEquals("NONE", r.vetoApplied());
    assertEquals(DID, r.decisionId());
    assertEquals("rs-0.1.0", r.ruleSetVersion());
    assertEquals("2026-08-26T09:15:00Z", r.evaluatedAt()); // horloge injectee (UTC Z)
    verify(auditLog).append(any(), any()); // exactement une ecriture
  }

  @Test
  void replayReturnsStoredDecisionWithoutWritingAgain() {
    when(auditLog.findByDecisionId(DID))
        .thenReturn(
            Optional.of(
                Map.of(
                    "decision", "STEP_UP",
                    "score", 42,
                    "requiredAction", "DEFER",
                    "vetoApplied", "NONE",
                    "decisionId", DID,
                    "ruleSetVersion", "rs-0.1.0",
                    "evaluatedAt", "2026-08-26T09:15:00Z")));

    EvaluateResponse r = controller.evaluate(request());

    assertEquals("STEP_UP", r.decision()); // la decision STOCKEE, pas recalculee
    assertEquals(42, r.score());
    verify(auditLog, never()).append(any(), any()); // aucune seconde ecriture (ENF-03)
  }
}
