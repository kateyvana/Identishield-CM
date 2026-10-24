package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Interrupteur ALLUMÉ mais configuration incomplète : le cœur ne démarre PAS, et le message nomme
 * la cause (décision de la propriétaire du 2026-10-23 : jamais de démarrage partiel).
 *
 * <p>@covers ENF-07
 */
class MobileTlsConfigTest {

  private static final int INTERNAL = 8080;

  @Test
  void aMissingCertificate_isNamedAndRefused() {
    MobileTlsProperties tls =
        new MobileTlsProperties(true, 8443, "/absent/core-mobile.crt", "/absent/core-mobile.key");
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> MobileTlsConfig.connector(tls, INTERNAL));
    assertTrue(e.getMessage().contains("certificat"), e.getMessage());
    assertTrue(e.getMessage().contains("/absent/core-mobile.crt"), e.getMessage());
  }

  @Test
  void aMobilePortEqualToTheInternalPort_orOutOfRange_isRefused() {
    for (int port : new int[] {INTERNAL, 0, 70000}) {
      MobileTlsProperties tls = new MobileTlsProperties(true, port, "c", "k");
      IllegalStateException e =
          assertThrows(IllegalStateException.class, () -> MobileTlsConfig.connector(tls, INTERNAL));
      assertTrue(e.getMessage().contains("port mobile invalide"), e.getMessage());
    }
  }
}
