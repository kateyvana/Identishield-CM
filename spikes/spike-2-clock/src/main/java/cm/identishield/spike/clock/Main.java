package cm.identishield.spike.clock;

import cm.identishield.spike.clock.time.Stopwatch;
import cm.identishield.spike.clock.time.VirtualClock;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Entry point. One sub-command per spike step; each prints raw output only. */
public final class Main {

  static final long SEED = 42L;
  static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
  static final Path PARAMETERS = Path.of("config", "parameters.properties");

  public static void main(String[] args) {
    String step = args.length > 0 ? args[0] : "";
    switch (step) {
      case "step1" -> step1();
      case "step2" -> step2();
      case "step3" -> step3();
      default -> System.out.println("usage: step1 | step2 | step3");
    }
  }

  /**
   * Step 1 (spec §4): determinism of the clock + scenario mechanism alone, before any business
   * rule. Two runs, same seed, same virtual start instant; the trace couples each event with the
   * instant read back from the virtual clock; fingerprints must be identical bit for bit.
   */
  private static void step1() {
    String first = traceFingerprint();
    String second = traceFingerprint();
    System.out.println("run1.sha256=" + first);
    System.out.println("run2.sha256=" + second);
    System.out.println("identical=" + first.equals(second));
  }

  private static String traceFingerprint() {
    VirtualClock clock = new VirtualClock(START);
    List<ScenarioEvent> events = Scenario.generate(SEED, START);
    List<String> trace = new ArrayList<>(events.size());
    for (ScenarioEvent event : events) {
      clock.setInstant(event.at());
      trace.add(event.line() + "|clock=" + clock.instant());
    }
    System.out.println("events=" + events.size());
    return Fingerprint.sha256(trace);
  }

  /**
   * Step 2 (spec §7): arbitrary-instant evaluation. The scenario's IMSI change, then the four
   * probe instants t+23h / t+47h / t+71h / t+73h with CAR_IMSI read from configuration (72 h in
   * the committed file): EN_CARENCE for the first three, not for the fourth. No real wait.
   */
  private static void step2() {
    Parameters params = Parameters.load(PARAMETERS);
    List<ScenarioEvent> events = Scenario.generate(SEED, START);
    Instant change =
        events.stream()
            .filter(e -> e.type() == ScenarioEvent.Type.IMSI_CHANGE)
            .findFirst()
            .orElseThrow()
            .at();
    System.out.println("CAR_IMSI(config)=" + params.carImsi());
    System.out.println("imsi_change=" + change);
    VirtualClock clock = new VirtualClock(START);
    Evaluator evaluator = new Evaluator(clock, params);
    RuleContext ctx = new RuleContext(events);
    for (int hours : new int[] {23, 47, 71, 73}) {
      clock.setInstant(change.plus(Duration.ofHours(hours)));
      System.out.println("t+" + hours + "h  " + evaluator.evaluate(ctx));
    }
  }

  /**
   * Step 3 (spec §4): speed invariance. Same scenario, three traversals - straight event jumps,
   * one-second ticks, one-hour ticks - fingerprinted and timed. Also the no-real-wait proof: six
   * virtual months, real duration in milliseconds.
   */
  private static void step3() {
    Parameters params = Parameters.load(PARAMETERS);
    String jump = timedRun("event-jump", null, params);
    String bySecond = timedRun("step-PT1S", Duration.ofSeconds(1), params);
    String byHour = timedRun("step-PT1H", Duration.ofHours(1), params);
    System.out.println("identical(PT1S,PT1H)=" + bySecond.equals(byHour));
    System.out.println("identical(all)=" + (jump.equals(bySecond) && bySecond.equals(byHour)));
    // Proof that every rule family actually fired during the scenario.
    List<String> lines = Runner.run(SEED, START, params, null);
    for (String verdict : new String[] {"EN_CARENCE", "NUIT", "DEPASSEMENT", "EXPIRE"}) {
      long count = lines.stream().filter(l -> l.contains("=" + verdict)).count();
      System.out.println("verdict." + verdict + "=" + count);
    }
  }

  private static String timedRun(String label, Duration step, Parameters params) {
    Stopwatch.Timed<List<String>> timed =
        Stopwatch.time(() -> Runner.run(SEED, START, params, step));
    String hash = Fingerprint.sha256(timed.value());
    System.out.println(
        label
            + "  events="
            + timed.value().size()
            + "  duration_ms="
            + timed.millis()
            + "  sha256="
            + hash);
    return hash;
  }
}
