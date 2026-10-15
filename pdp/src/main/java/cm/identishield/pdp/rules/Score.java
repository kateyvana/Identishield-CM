package cm.identishield.pdp.rules;

import java.util.List;

/** Résultat du moteur de règles : score borné, décision par bandes (ES-05f), contributions. */
public record Score(int value, Decision decision, List<Contribution> contributions) {

  public enum Decision {
    ALLOW,
    STEP_UP,
    FREEZE
  }

  /** Contribution d'une règle déclenchée, en points. */
  public record Contribution(String ruleId, int points) {}
}
