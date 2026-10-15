package cm.identishield.pdp.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.config.TestRuleSet;
import cm.identishield.pdp.rules.StepUpPolicy.Resolution;
import org.junit.jupiter.api.Test;

/**
 * STEP_UP_POLICY et CAP_FAILCLOSED lus dans le jeu de règles versionné (leur application est la
 * carte A3).
 *
 * <p>@covers ES-06
 */
class StepUpPolicyTest {

  private final RuleSet rs = TestRuleSet.load();

  @Test
  void theResolution_perChannel_comesFromTheVersionedConfig() {
    StepUpPolicy p = StepUpPolicy.from(rs);
    assertEquals(Resolution.valueOf(rs.text("STEP_UP_POLICY.app")), p.forChannel("APP"));
    assertEquals(Resolution.valueOf(rs.text("STEP_UP_POLICY.ussd")), p.forChannel("USSD"));
    // ADR-016 §3 et ADR-016-A : USSD = CANCEL, application = DEFER.
    assertEquals(Resolution.CANCEL, p.forChannel("USSD"));
    assertEquals(Resolution.DEFER, p.forChannel("APP"));
  }

  @Test
  void anUnknownChannelOrResolution_isRefused() {
    StepUpPolicy p = StepUpPolicy.from(rs);
    assertThrows(IllegalArgumentException.class, () -> p.forChannel("SMS"));
    assertThrows(
        IllegalArgumentException.class,
        () -> StepUpPolicy.from(TestRuleSet.with("STEP_UP_POLICY.ussd", "QUESTION")));
  }

  @Test
  void capFailClosed_isReadFromTheVersionedConfig() {
    assertEquals(Long.parseLong(rs.text("CAP_FAILCLOSED.xaf")), rs.capFailClosedXaf());
    assertThrows(
        IllegalStateException.class,
        () -> TestRuleSet.with("CAP_FAILCLOSED.xaf", null).capFailClosedXaf());
  }
}
