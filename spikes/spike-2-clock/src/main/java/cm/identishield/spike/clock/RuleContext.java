package cm.identishield.spike.clock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read-only view of the event history for the rules. Every query is bounded by the evaluation
 * instant: a rule can never observe an event later than the {@code now} it received, which keeps
 * rules pure functions of (history, now).
 */
public record RuleContext(List<ScenarioEvent> history) {

  public Optional<Instant> lastImsiChangeAtOrBefore(Instant now) {
    return lastOfType(ScenarioEvent.Type.IMSI_CHANGE, now);
  }

  public Optional<Instant> freezeCreatedAtOrBefore(Instant now) {
    return lastOfType(ScenarioEvent.Type.FREEZE_CREATED, now);
  }

  /** Outflows with instant in (from, to] — the current outflow, if any, counts. */
  public long outflowCount(Instant from, Instant to) {
    return history.stream()
        .filter(e -> e.type() == ScenarioEvent.Type.OUTFLOW)
        .filter(e -> e.at().isAfter(from) && !e.at().isAfter(to))
        .count();
  }

  private Optional<Instant> lastOfType(ScenarioEvent.Type type, Instant now) {
    Instant last = null;
    for (ScenarioEvent e : history) {
      if (e.at().isAfter(now)) {
        break;
      }
      if (e.type() == type) {
        last = e.at();
      }
    }
    return Optional.ofNullable(last);
  }
}
