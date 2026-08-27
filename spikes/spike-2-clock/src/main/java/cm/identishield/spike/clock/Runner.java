package cm.identishield.spike.clock;

import cm.identishield.spike.clock.time.VirtualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays the scenario and records one decision line per event. Decisions are always taken at the
 * event instants; {@code step} only controls how virtual time travels between two events: {@code
 * null} jumps straight to the next event (batch style), a duration advances tick by tick and lands
 * exactly on the event instant (unit style). Speed invariance (spec §4 étape 3) means the recorded
 * lines are identical whatever the traversal.
 */
public final class Runner {

  private Runner() {}

  public static List<String> run(long seed, Instant start, Parameters params, Duration step) {
    VirtualClock clock = new VirtualClock(start);
    Evaluator evaluator = new Evaluator(clock, params);
    List<ScenarioEvent> events = Scenario.generate(seed, start);
    RuleContext ctx = new RuleContext(events);
    List<String> lines = new ArrayList<>(events.size());
    for (ScenarioEvent event : events) {
      if (step != null) {
        while (clock.instant().plus(step).isBefore(event.at())) {
          clock.advance(step);
        }
      }
      clock.setInstant(event.at());
      lines.add(event.line() + "|" + evaluator.evaluate(ctx));
    }
    return lines;
  }
}
