package cm.identishield.pdp.rules;

import cm.identishield.pdp.config.RuleSet;

/**
 * Résolution d'un STEP_UP selon le canal (ES-06, STEP_UP_POLICY) : USSD = CANCEL (ADR-016 §3),
 * application = DEFER (ADR-016-A). Lue et validée ici ; son application est la carte A3.
 */
public record StepUpPolicy(Resolution app, Resolution ussd) {

  // @implements ES-06

  public enum Resolution {
    SIGNATURE,
    DEFER,
    CANCEL
  }

  public static StepUpPolicy from(RuleSet rs) {
    return new StepUpPolicy(
        Resolution.valueOf(rs.text("STEP_UP_POLICY.app")),
        Resolution.valueOf(rs.text("STEP_UP_POLICY.ussd")));
  }

  /** Canal du contrat /evaluate : APP ou USSD. */
  public Resolution forChannel(String channel) {
    return switch (channel) {
      case "APP" -> app;
      case "USSD" -> ussd;
      default -> throw new IllegalArgumentException("canal inconnu : " + channel);
    };
  }
}
