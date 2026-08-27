package cm.identishield.spike.clock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Properties;

/** ES-15: named parameters loaded from configuration; none of their values appears in code. */
public record Parameters(
    Duration carImsi,
    LocalTime nightStart,
    LocalTime nightEnd,
    int vel,
    Duration velWindow,
    Duration ttlFreeze) {

  public static Parameters load(Path file) {
    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      props.load(in);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read parameters file " + file, e);
    }
    String[] night = required(props, "NIGHT").split("-");
    return new Parameters(
        Duration.parse(required(props, "CAR_IMSI")),
        LocalTime.parse(night[0]),
        LocalTime.parse(night[1]),
        Integer.parseInt(required(props, "VEL")),
        Duration.parse(required(props, "VEL_WINDOW")),
        Duration.parse(required(props, "TTL_FREEZE")));
  }

  private static String required(Properties props, String key) {
    String value = props.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("missing named parameter " + key);
    }
    return value.trim();
  }
}
