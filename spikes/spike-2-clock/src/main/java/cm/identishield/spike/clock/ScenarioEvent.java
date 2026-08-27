package cm.identishield.spike.clock;

import java.time.Instant;

/** One virtual-time event of the replayable scenario (spec §6, composant 4). */
public record ScenarioEvent(long seq, Instant at, Type type, long amountXaf) {

  public enum Type {
    ENROLMENT,
    IMSI_CHANGE,
    OUTFLOW,
    FREEZE_CREATED
  }

  public String line() {
    return seq + "|" + at + "|" + type + "|" + amountXaf;
  }
}
