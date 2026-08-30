package cm.identishield.core.pdp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * @covers ES-11 (fail-closed au-dessus de CAP_PROB quand le PDP est indisponible).
 */
class FailClosedPolicyTest {

  private static final long CAP_PROB = 50_000;
  private final FailClosedPolicy policy = new FailClosedPolicy(CAP_PROB);

  @Test
  void refusesStrictlyAboveCapProb() {
    assertEquals(Outcome.REFUSED_FAIL_CLOSED, policy.onPdpUnavailable(CAP_PROB + 1));
  }

  @Test
  void degradedAtCapProbBoundary() {
    assertEquals(Outcome.ALLOWED_DEGRADED, policy.onPdpUnavailable(CAP_PROB));
  }

  @Test
  void degradedBelowCapProb() {
    assertEquals(Outcome.ALLOWED_DEGRADED, policy.onPdpUnavailable(CAP_PROB - 1));
  }
}
