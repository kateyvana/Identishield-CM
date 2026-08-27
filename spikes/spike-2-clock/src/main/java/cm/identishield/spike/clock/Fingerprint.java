package cm.identishield.spike.clock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** SHA-256 over the full ordered output sequence, one line per entry, LF separated (spec §4). */
public final class Fingerprint {

  private Fingerprint() {}

  public static String sha256(List<String> lines) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String line : lines) {
        digest.update(line.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
