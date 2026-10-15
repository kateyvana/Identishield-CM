package cm.identishield.pdp.trust;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.config.TestRuleSet;
import cm.identishield.pdp.rules.Operation;
import cm.identishield.pdp.trust.ProbationVeto.Violation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Veto de PROBATION (ES-04, ADR-016 §1 étape 1) : bénéficiaire nouveau, ou cumul des sorties sur
 * CAP_PROB.window + montant au-delà de min(CAP_PROB.dailyXaf ; CAP_PROB.balancePct % du solde à
 * l'entrée en PROBATION). Composant distinct du moteur de score (CLAUDE.md §3).
 *
 * <p>@covers ES-04
 */
class ProbationVetoTest {

  private static final Instant ENTRY = Instant.parse("2026-10-15T08:00:00Z");
  private static final Instant T = ENTRY.plusSeconds(3_600);

  private final RuleSet rs = TestRuleSet.load();
  private final ProbationVeto veto = new ProbationVeto(rs);
  private final long daily = rs.integer("CAP_PROB.dailyXaf");
  private final long pct = rs.integer("CAP_PROB.balancePct");
  private final Duration window = rs.duration("CAP_PROB.window");

  private static Operation out(String to, long xaf, Instant at) {
    return new Operation(to, xaf, true, at);
  }

  /** Solde d'entrée pour lequel le plafond relatif vaut exactement {@code limit}. */
  private long entryFor(long limit) {
    return limit * 100 / pct;
  }

  private final List<Operation> known = List.of(out("ben-a", 100, ENTRY.minusSeconds(86_400)));

  @Test
  void aNewBeneficiary_isVetoed() {
    assertEquals(
        Optional.of(Violation.NEW_BENEFICIARY),
        veto.check(out("ben-new", 1, T), known, ENTRY, entryFor(daily)));
  }

  @Test
  void aKnownBeneficiary_underTheCap_passes() {
    assertEquals(Optional.empty(), veto.check(out("ben-a", 1, T), known, ENTRY, entryFor(daily)));
  }

  @Test
  void theCap_isThePercentageOfTheEntryBalance_whenLower() {
    long limit = daily / 2; // plafond relatif plus bas que le plafond journalier
    long entry = entryFor(limit);
    assertEquals(Optional.empty(), veto.check(out("ben-a", limit, T), known, ENTRY, entry));
    assertEquals(
        Optional.of(Violation.CAP_PROB),
        veto.check(out("ben-a", limit + 1, T), known, ENTRY, entry));
  }

  @Test
  void theCap_isTheDailyAmount_whenLower() {
    long entry = entryFor(daily) * 10;
    assertEquals(Optional.empty(), veto.check(out("ben-a", daily, T), known, ENTRY, entry));
    assertEquals(
        Optional.of(Violation.CAP_PROB),
        veto.check(out("ben-a", daily + 1, T), known, ENTRY, entry));
  }

  @Test
  void theCap_countsTheOutflowsSinceEntry_inTheSlidingWindow() {
    long entry = entryFor(daily);
    List<Operation> h =
        List.of(
            out("ben-a", 100, ENTRY.minusSeconds(86_400)),
            out("ben-a", daily, ENTRY.minusSeconds(1)), // avant l'entrée : ne compte pas
            new Operation("ben-a", daily, false, ENTRY.plusSeconds(10)), // entrée : ne compte pas
            out("ben-a", daily - 10, ENTRY.plusSeconds(60))); // dans la fenêtre
    assertEquals(Optional.empty(), veto.check(out("ben-a", 10, T), h, ENTRY, entry));
    assertEquals(Optional.of(Violation.CAP_PROB), veto.check(out("ben-a", 11, T), h, ENTRY, entry));
    // Une fois la sortie sortie de la fenêtre glissante, le plafond est de nouveau disponible.
    Instant later = ENTRY.plusSeconds(60).plus(window);
    assertEquals(Optional.empty(), veto.check(out("ben-a", daily, later), h, ENTRY, entry));
  }

  @Test
  void theRelativeCap_usesIntegerFcfa() {
    // 10 % de 99 = 9 (entier, ED-05), pas 9,9.
    long entry = 99;
    long limit = entry * pct / 100;
    assertEquals(Optional.empty(), veto.check(out("ben-a", limit, T), known, ENTRY, entry));
    assertEquals(
        Optional.of(Violation.CAP_PROB),
        veto.check(out("ben-a", limit + 1, T), known, ENTRY, entry));
  }

  @Test
  void anIncomingOperation_isNeverVetoed() {
    assertEquals(
        Optional.empty(),
        veto.check(new Operation("ben-new", daily * 10, false, T), known, ENTRY, entryFor(daily)));
  }
}
