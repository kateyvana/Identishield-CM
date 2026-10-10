package cm.identishield.pdp.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * Canonical ADR-018 recalculé par le serveur : octet pour octet celui de l'application.
 *
 * <p>@covers ES-02
 */
class CanonicalPayloadTest {

  private static final String NONCE = "0123456789abcdef0123456789abcdef";

  /**
   * Même vecteur et même empreinte dans SignedPayloadTest (application) : les deux côtés produisent
   * les mêmes octets, accents compris (méthode de SPIKE-1).
   */
  static final String SHARED_VECTOR_SHA256 =
      "a24d5dd54b7423e70e23105c15a2bed33b07d640c37951f867a19457f5f2aafd";

  @Test
  void fixedOrderChain_noTimestamp_utf8() throws Exception {
    byte[] bytes = CanonicalPayload.transfer("acc-000123", "P2P", 25000, "acc-000456", NONCE);
    assertEquals(
        "acc-000123|P2P|25000|acc-000456|" + NONCE, new String(bytes, StandardCharsets.UTF_8));
    byte[] shared = CanonicalPayload.transfer("acc-000123", "P2P", 25000, "bén-0045é", NONCE);
    assertEquals(
        SHARED_VECTOR_SHA256,
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(shared)));
  }

  @Test
  void ambiguousFields_areRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalPayload.transfer("acc|1", "P2P", 1, "b", NONCE));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalPayload.transfer("a", "P2P", 1, "b|c", NONCE));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalPayload.transfer(" a", "P2P", 1, "b", NONCE));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalPayload.transfer("a", "P2P", 1, "b\n", NONCE));
    assertThrows(
        IllegalArgumentException.class, () -> CanonicalPayload.transfer("", "P2P", 1, "b", NONCE));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalPayload.transfer(null, "P2P", 1, "b", NONCE));
  }

  @Test
  void amountAndNonce_mustHaveTheAdr018Form() {
    assertThrows(
        IllegalArgumentException.class, () -> CanonicalPayload.transfer("a", "P2P", 0, "b", NONCE));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalPayload.transfer("a", "P2P", 1, "b", NONCE.toUpperCase()));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalPayload.transfer("a", "P2P", 1, "b", NONCE.substring(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalPayload.transfer("a", "P2P", 1, "b", NONCE + "\n"));
  }
}
