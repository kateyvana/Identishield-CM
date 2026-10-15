package cm.identishield.pdp.rules;

import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.rules.Score.Contribution;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * ES-05d : une sortie dans la fenêtre NIGHT, lue en heure locale de NIGHT_ZONE, contribue W.night.
 * Jamais un veto. La fenêtre [début ; fin[ peut passer minuit.
 */
final class NightRule implements Rule {

  // @implements ES-05d

  private final int wNight;
  private final LocalTime start;
  private final LocalTime end;
  private final ZoneId zone;

  NightRule(RuleSet rs) {
    this.wNight = (int) rs.integer("W.night");
    String[] window = rs.text("NIGHT").split("-");
    this.start = LocalTime.parse(window[0]);
    this.end = LocalTime.parse(window[1]);
    this.zone = ZoneId.of(rs.text("NIGHT_ZONE"));
  }

  @Override
  public Contribution evaluate(Operation op, List<Operation> history) {
    LocalTime t = op.at().atZone(zone).toLocalTime();
    boolean night =
        start.isAfter(end)
            ? !t.isBefore(start) || t.isBefore(end)
            : !t.isBefore(start) && t.isBefore(end);
    return night ? new Contribution("NIGHT", wNight) : null;
  }
}
