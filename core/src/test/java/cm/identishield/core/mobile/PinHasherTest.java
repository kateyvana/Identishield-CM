package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.identishield.core.mobile.PinHasher.ProtectedPin;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * PIN applicatif (carte B3a, décision 2) : empreinte PBKDF2-HMAC-SHA256 à sel aléatoire, jamais le
 * PIN ; paramètres nommés de configuration ; comparaison en temps constant.
 *
 * <p>@covers ES-22
 */
class PinHasherTest {

  /** Itérations réduites pour la vitesse des tests ; la production lit sa configuration. */
  private static final PinProperties PROPS = new PinProperties(1_000, 16, 256);

  private final PinHasher hasher = new PinHasher(PROPS);

  @Test
  void protect_keepsOnlyASaltedHash_withTheConfiguredParameters() {
    ProtectedPin p = hasher.protect("4096");
    assertFalse(p.hash().contains("4096"));
    assertEquals(PROPS.saltBytes(), Base64.getDecoder().decode(p.salt()).length);
    assertEquals(PROPS.iterations(), p.iterations());
  }

  @Test
  void theRightPinMatches_aWrongOneDoesNot() {
    ProtectedPin p = hasher.protect("4096");
    assertTrue(hasher.matches("4096", p));
    assertFalse(hasher.matches("4097", p));
    assertFalse(hasher.matches("", p));
  }

  @Test
  void withoutAStoredHash_nothingMatches() {
    assertFalse(hasher.matches("4096", null));
  }

  @Test
  void thesamePin_neverGivesTheSameHash() {
    assertNotEquals(hasher.protect("4096").hash(), hasher.protect("4096").hash());
  }
}
