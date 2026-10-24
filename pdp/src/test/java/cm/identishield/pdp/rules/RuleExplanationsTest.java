package cm.identishield.pdp.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Carte A5 (#39) : les trois surfaces de {@link RuleExplanations} couvrent bien les clés qu'elles
 * doivent couvrir, et refusent toute clé inconnue.
 *
 * @covers EF-11
 */
class RuleExplanationsTest {

  /**
   * Les quatre règles du RuleEngine (ScoreRule) sont les clés attendues par le contrat. Les ajouter
   * ici liste tout ce qui doit avoir une explication. Un ajout de règle non reflété ici tombe au
   * runtime (RuleExplanations.of jette) ; l'un ou l'autre, jamais silencieux.
   */
  private static final List<String> RULES =
      List.of("NEW_BENEFICIARY", "NEW_BENEFICIARY_HIGH", "VELOCITY", "NIGHT");

  /**
   * Vetos émis par EvaluateController (ES-11b, ES-04, et le refus de solde) et la pseudo-clé pour
   * l'ALLOW sans règle. IMSI_CHANGE n'est PAS émis au mois 4 (contrat evaluate.yaml, vetoApplied) :
   * il ne figure pas ici.
   */
  private static final List<String> VETOS =
      List.of(
          "SIGNATURE_INVALID",
          "PROBATION_VIOLATION",
          "ACCOUNT_FROZEN",
          "INSUFFICIENT_FUNDS",
          RuleExplanations.NONE_ALLOW);

  /** Les quatre événements émis par TrustStateMachine (A1, machine à états). */
  private static final List<String> TRUST_EVENTS =
      List.of(
          "TRUST_PROBATION_ENTERED", "TRUST_PROBATION_EXPIRED", "TRUST_FROZEN", "TRUST_UNFROZEN");

  /** Les six causes acceptées par le CHECK sur pdp.trust_transition.cause (infra/init/init.sql). */
  private static final List<String> CAUSES =
      List.of(
          "IMSI_CHANGE",
          "CAR_IMSI_EXPIRED",
          "FREEZE_DECISION",
          "NOT_ME",
          "TTL_FREEZE_EXPIRED",
          "RELEASE");

  @Test
  void every_rule_has_a_non_empty_explanation() {
    for (String rule : RULES) {
      String text = RuleExplanations.of(rule);
      assertFalse(text.isBlank(), "règle sans explication : " + rule);
    }
  }

  @Test
  void every_veto_has_a_non_empty_reason() {
    for (String veto : VETOS) {
      String text = RuleExplanations.ofVeto(veto);
      assertFalse(text.isBlank(), "veto sans raison : " + veto);
    }
  }

  @Test
  void every_trust_event_has_a_non_empty_label_with_its_cause() {
    for (String event : TRUST_EVENTS) {
      for (String cause : CAUSES) {
        String text = RuleExplanations.ofTrustEvent(event, cause);
        assertFalse(text.isBlank(), event + " / " + cause + " : libellé vide");
        assertTrue(text.contains(cause), event + " / " + cause + " : la cause doit être écrite");
      }
    }
  }

  @Test
  void an_unknown_rule_is_a_programming_error() {
    assertThrows(IllegalStateException.class, () -> RuleExplanations.of("INEXISTANT"));
  }

  @Test
  void an_unknown_veto_is_a_programming_error() {
    // NONE n'a pas de raison : si une décision est portée par aucun veto et porte une règle, c'est
    // l'explication de la règle qui parle, et ofVeto ne doit pas être appelée. Idem IMSI_CHANGE.
    assertThrows(IllegalStateException.class, () -> RuleExplanations.ofVeto("NONE"));
    assertThrows(IllegalStateException.class, () -> RuleExplanations.ofVeto("IMSI_CHANGE"));
  }

  @Test
  void an_unknown_trust_event_is_a_programming_error() {
    assertThrows(
        IllegalStateException.class,
        () -> RuleExplanations.ofTrustEvent("TRUST_INCONNU", "RELEASE"));
  }
}
