package cm.identishield.core.transfer;

import cm.identishield.core.config.RuleSet;

/**
 * Résolution d'une décision STEP_UP selon le canal (ES-06), lue dans STEP_UP_POLICY : USSD = CANCEL
 * (ADR-016 §3), application = DEFER (ADR-016-A). Le cœur ne prend en charge que ces deux
 * résolutions ; toute autre valeur est refusée, jamais devinée.
 */
final class StepUpResolver {

  // @implements ES-06

  enum Resolution {
    CANCEL,
    DEFER
  }

  private final RuleSet ruleSet;

  StepUpResolver(RuleSet ruleSet) {
    this.ruleSet = ruleSet;
  }

  /** Canal du cœur : APP ou USSD. */
  Resolution forChannel(String channel) {
    String key =
        switch (channel) {
          case "APP" -> "app";
          case "USSD" -> "ussd";
          default -> throw new IllegalArgumentException("canal inconnu : " + channel);
        };
    String value = ruleSet.stepUpPolicy(key);
    return switch (value) {
      case "CANCEL" -> Resolution.CANCEL;
      case "DEFER" -> Resolution.DEFER;
      default ->
          throw new IllegalStateException(
              "STEP_UP_POLICY." + key + " = " + value + " non pris en charge par le cœur");
    };
  }
}
