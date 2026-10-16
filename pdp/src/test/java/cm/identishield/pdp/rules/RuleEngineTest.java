package cm.identishield.pdp.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.config.TestRuleSet;
import cm.identishield.pdp.rules.Score.Contribution;
import cm.identishield.pdp.rules.Score.Decision;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Moteur de règles (ADR-016 §1, étapes 2 et 3) : contributions W, somme bornée, bandes. Tous les
 * poids, seuils et fenêtres sont lus dans config/ruleset.yaml ; le test n'en recopie aucun.
 *
 * <p>Exigences vérifiées :
 *
 * <p>@covers ES-05a
 *
 * <p>@covers ES-05b
 *
 * <p>@covers ES-05c
 *
 * <p>@covers ES-05d
 *
 * <p>@covers ES-05f
 */
class RuleEngineTest {

  /** 10:00 UTC = 11:00 à Douala : de jour. */
  private static final Instant T = Instant.parse("2026-10-15T10:00:00Z");

  private final RuleSet rs = TestRuleSet.load();
  private final RuleEngine engine = new RuleEngine(rs);

  private final int wNew = (int) rs.integer("W.newBeneficiary");
  private final int wNewHigh = (int) rs.integer("W.newBeneficiaryHigh");
  private final int wVel = (int) rs.integer("W.vel");
  private final int wNight = (int) rs.integer("W.night");
  private final long thrAbsolute = rs.integer("THR_HIGH.absoluteXaf");
  private final long factor = rs.integer("THR_HIGH.medianFactor");
  private final Duration medianWindow = rs.duration("THR_HIGH.medianWindow");
  private final int velCount = (int) rs.integer("VEL.count");
  private final Duration velWindow = rs.duration("VEL.window");
  private final int velNew = (int) rs.integer("VEL.newBeneficiaries");
  private final Duration velNewWindow = rs.duration("VEL.beneficiaryWindow");

  private static Operation out(String to, long xaf, Instant at) {
    return new Operation(to, xaf, true, at);
  }

  private static Operation in(String from, long xaf, Instant at) {
    return new Operation(from, xaf, false, at);
  }

  private Decision band(int score) {
    if (score >= rs.integer("SCORE.freeze")) {
      return Decision.FREEZE;
    }
    return score >= rs.integer("SCORE.stepUp") ? Decision.STEP_UP : Decision.ALLOW;
  }

  // ---- ES-05b : bénéficiaire ----

  @Test
  void knownCounterparty_inEitherDirection_contributesNothing() {
    List<Operation> h = List.of(out("ben-a", 1_000, T.minusSeconds(86_400)), in("ben-b", 500, T));
    assertEquals(new Score(0, Decision.ALLOW, List.of()), engine.score(out("ben-a", 900, T), h));
    assertEquals(new Score(0, Decision.ALLOW, List.of()), engine.score(out("ben-b", 900, T), h));
  }

  @Test
  void newBeneficiary_belowThrHigh_contributesWNew() {
    List<Operation> h = List.of(out("ben-a", 1_000, T.minusSeconds(86_400)));
    Score s = engine.score(out("ben-new", factor * 1_000 - 1, T), h);
    assertEquals(List.of(new Contribution("NEW_BENEFICIARY", wNew)), s.contributions());
    assertEquals(wNew, s.value());
    assertEquals(band(wNew), s.decision());
  }

  @Test
  void newBeneficiary_atAbsoluteThrHigh_withoutHistory_contributesWNewHigh() {
    Score s = engine.score(out("ben-new", thrAbsolute, T), List.of());
    assertEquals(List.of(new Contribution("NEW_BENEFICIARY_HIGH", wNewHigh)), s.contributions());
    assertEquals(
        List.of(new Contribution("NEW_BENEFICIARY", wNew)),
        engine.score(out("ben-new", thrAbsolute - 1, T), List.of()).contributions());
  }

  @Test
  void thrHigh_isTheMedianFactor_whenLowerThanTheAbsolute() {
    List<Operation> h =
        List.of(
            out("ben-a", 1_000, T.minusSeconds(3_600)), out("ben-a", 2_000, T.minusSeconds(7_200)));
    // Médiane paire = (1 000 + 2 000) / 2 = 1 500 ; THR_HIGH = facteur × 1 500.
    long thr = factor * 1_500;
    assertEquals(
        "NEW_BENEFICIARY_HIGH", engine.score(out("n", thr, T), h).contributions().get(0).ruleId());
    assertEquals(
        "NEW_BENEFICIARY", engine.score(out("n", thr - 1, T), h).contributions().get(0).ruleId());
  }

  @Test
  void theMedian_onlyCountsOutflowsInsideTheMedianWindow() {
    List<Operation> h =
        List.of(
            out("ben-a", 900_000, T.minus(medianWindow).minusSeconds(1)), // hors fenêtre
            in("ben-c", 900_000, T.minusSeconds(60)), // une entrée ne compte pas
            out("ben-a", 1_000, T.minus(medianWindow))); // borne incluse
    assertEquals(
        "NEW_BENEFICIARY_HIGH",
        engine.score(out("n", factor * 1_000, T), h).contributions().get(0).ruleId());
  }

  // ---- ES-05c : vélocité ----

  @Test
  void velocity_moreThanCountOutflowsInTheWindow_contributesWVel() {
    List<Operation> h = new ArrayList<>();
    for (int i = 1; i <= velCount; i++) {
      h.add(out("ben-a", 100, T.minus(velWindow).plusSeconds(i)));
    }
    Score s = engine.score(out("ben-a", 100, T), h);
    assertEquals(List.of(new Contribution("VELOCITY", wVel)), s.contributions());
    assertEquals(band(wVel), s.decision());
    // L'opération située exactement à T - fenêtre en sort (fenêtre ouverte à gauche).
    h.set(0, out("ben-a", 100, T.minus(velWindow)));
    assertEquals(List.of(), engine.score(out("ben-a", 100, T), h).contributions());
  }

  @Test
  void velocity_moreThanNewBeneficiariesInTheWindow_contributesWVel() {
    List<Operation> h = new ArrayList<>();
    for (int i = 1; i <= velNew; i++) {
      h.add(out("ben-new-" + i, 100, T.minus(velNewWindow).plusSeconds(60L * i)));
    }
    List<String> ids =
        engine.score(out("ben-new-x", 100, T), h).contributions().stream()
            .map(Contribution::ruleId)
            .toList();
    assertEquals(List.of("NEW_BENEFICIARY", "VELOCITY"), ids);
    // Vers un bénéficiaire déjà vu, la clause « nouveaux bénéficiaires » ne joue pas.
    assertEquals(List.of(), engine.score(out("ben-new-1", 100, T), h).contributions());
  }

  // ---- ES-05d : nuit, en heure locale de NIGHT_ZONE ----

  @Test
  void night_isReadInTheLocalTimeOfNightZone() {
    List<Operation> h = List.of(out("ben-a", 100, T.minusSeconds(86_400)));
    Instant d = Instant.parse("2026-10-15T00:00:00Z");
    // Douala = UTC+1 : 22:30Z = 23:30 locale (nuit) ; 21:59Z = 22:59 ; 03:59Z = 04:59 ; 04:00Z =
    // 05:00.
    assertEquals(
        List.of(new Contribution("NIGHT", wNight)),
        engine.score(out("ben-a", 100, d.plusSeconds(22 * 3600 + 1800)), h).contributions());
    assertEquals(
        List.of(),
        engine.score(out("ben-a", 100, d.plusSeconds(21 * 3600 + 3540)), h).contributions());
    assertEquals(
        List.of(new Contribution("NIGHT", wNight)),
        engine.score(out("ben-a", 100, d.plusSeconds(3 * 3600 + 3540)), h).contributions());
    assertEquals(
        List.of(), engine.score(out("ben-a", 100, d.plusSeconds(4 * 3600)), h).contributions());
  }

  // ---- ES-05a : somme bornée ; ES-05f : bandes ----

  @Test
  void theScore_isTheSumOfContributions_cappedAtScoreMax() {
    List<Operation> h = new ArrayList<>();
    Instant night = Instant.parse("2026-10-15T01:00:00Z"); // 02:00 locale
    for (int i = 1; i <= velCount; i++) {
      h.add(out("ben-a", 100, night.minusSeconds(i)));
    }
    Score s = engine.score(out("ben-new", thrAbsolute, night), h);
    assertEquals(3, s.contributions().size());
    assertEquals((int) Math.min(rs.integer("SCORE.max"), wNewHigh + wVel + wNight), s.value());
    assertEquals(band(s.value()), s.decision());
  }

  @Test
  void bands_newBeneficiaryHighAtNight_isTheExampleOfAdr016() {
    // ADR-016 : « 45 points pour un bénéficiaire nouveau avec un montant élevé, plus 25 parce qu'il
    // est 2 h du matin, ce qui fait 70 et donc un FREEZE ».
    Score s =
        engine.score(out("ben-new", thrAbsolute, Instant.parse("2026-10-15T01:00:00Z")), List.of());
    assertEquals(wNewHigh + wNight, s.value());
    assertEquals(band(wNewHigh + wNight), s.decision());
    Score smallAtNight =
        engine.score(out("ben-new", 100, Instant.parse("2026-10-15T01:00:00Z")), List.of());
    assertEquals(band(wNew + wNight), smallAtNight.decision());
  }

  @Test
  void anIncomingOperation_isNeverScored() {
    Score s =
        engine.score(in("ben-new", thrAbsolute, Instant.parse("2026-10-15T01:00:00Z")), List.of());
    assertEquals(new Score(0, Decision.ALLOW, List.of()), s);
  }

  // ---- paramètres : lus, jamais devinés ----

  @Test
  void anAggregationOtherThanBoundedSum_isRefused() {
    assertThrows(
        IllegalStateException.class,
        () -> new RuleEngine(TestRuleSet.with("SCORE.aggregation", "max")));
  }

  @Test
  void aMissingParameter_isRefused_neverDefaulted() {
    assertThrows(
        IllegalStateException.class,
        () -> new RuleEngine(TestRuleSet.with("THR_HIGH.medianWindow", null)));
    assertThrows(
        IllegalStateException.class, () -> new RuleEngine(TestRuleSet.with("W.night", null)));
  }
}
