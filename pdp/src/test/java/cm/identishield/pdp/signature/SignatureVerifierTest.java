package cm.identishield.pdp.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.device.DeviceRegistry;
import cm.identishield.pdp.device.DeviceRegistry.DeviceKey;
import cm.identishield.pdp.signature.SignatureVerifier.ChallengeVerdict;
import cm.identishield.pdp.signature.SignatureVerifier.SignedTransaction;
import cm.identishield.pdp.signature.SignatureVerifier.Verdict;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Veto de signature : vraie paire EC P-256, canonical recalculé par le serveur, nonce à usage
 * unique.
 *
 * <p>@covers ES-02 ; @covers ES-10 ; @covers ES-20
 */
class SignatureVerifierTest {

  private static final String ACCOUNT = "acc-000123";
  private static final String KEY_ID = "dk-7f3a2b";
  private static final String NONCE = "0123456789abcdef0123456789abcdef";

  private final KeyPair device = keyPair("secp256r1");
  private final DeviceRegistry registry = mock(DeviceRegistry.class);
  private final NonceStore nonces = mock(NonceStore.class);
  private final SignatureVerifier verifier = new SignatureVerifier(registry, nonces);

  SignatureVerifierTest() {
    register(device);
    when(nonces.consume(anyString(), anyString())).thenReturn(true);
  }

  private void register(KeyPair pair) {
    String spki = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    when(registry.findActive(ACCOUNT))
        .thenReturn(Optional.of(new DeviceKey(KEY_ID, ACCOUNT, spki)));
  }

  private static KeyPair keyPair(String curve) {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec(curve));
      return g.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sign(PrivateKey key, String chain) throws Exception {
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(key);
    s.update(chain.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(s.sign());
  }

  private static SignedTransaction tx(long amount, String beneficiary, String signature) {
    return new SignedTransaction(ACCOUNT, "P2P", amount, beneficiary, KEY_ID, signature, NONCE);
  }

  private static final String CHAIN = ACCOUNT + "|P2P|25000|acc-000456|" + NONCE;

  // ---- défi de session (carte B4) ------------------------------------------------------------

  @Test
  void sessionChallenge_signedByTheActiveKey_isValid_andConsumesTheNonce() throws Exception {
    assertEquals(
        ChallengeVerdict.VALID,
        verifier.verifyChallenge(ACCOUNT, KEY_ID, NONCE, sign(device.getPrivate(), NONCE)));
    verify(nonces).consume(NONCE, ACCOUNT);
  }

  @Test
  void sessionChallenge_badSignatureOrOtherKey_isInvalid_andTheNonceIsNotConsumed()
      throws Exception {
    String other = sign(keyPair("secp256r1").getPrivate(), NONCE);
    assertEquals(ChallengeVerdict.INVALID, verifier.verifyChallenge(ACCOUNT, KEY_ID, NONCE, other));
    String ofAnotherChallenge = sign(device.getPrivate(), "f" + NONCE.substring(1));
    assertEquals(
        ChallengeVerdict.INVALID,
        verifier.verifyChallenge(ACCOUNT, KEY_ID, NONCE, ofAnotherChallenge));
    // Clé ACTIVE du compte, mais pas celle que l'appelant nomme : INVALID (inconnue, pas révoquée).
    when(registry.stateOf(ACCOUNT, "dk-other")).thenReturn(Optional.empty());
    assertEquals(
        ChallengeVerdict.INVALID,
        verifier.verifyChallenge(ACCOUNT, "dk-other", NONCE, sign(device.getPrivate(), NONCE)));
    verify(nonces, never()).consume(anyString(), anyString());
  }

  @Test
  void sessionChallenge_aRevokedKeyIsNamed_andAConsumedNonceIsWorthNothing() throws Exception {
    when(registry.stateOf(ACCOUNT, "dk-old")).thenReturn(Optional.of("REVOKED"));
    assertEquals(
        ChallengeVerdict.REVOKED,
        verifier.verifyChallenge(ACCOUNT, "dk-old", NONCE, sign(device.getPrivate(), NONCE)));
    when(nonces.consume(NONCE, ACCOUNT)).thenReturn(false);
    assertEquals(
        ChallengeVerdict.INVALID,
        verifier.verifyChallenge(ACCOUNT, KEY_ID, NONCE, sign(device.getPrivate(), NONCE)));
    assertEquals(ChallengeVerdict.INVALID, verifier.verifyChallenge(ACCOUNT, KEY_ID, "", "c2ln"));
  }

  @Test
  void validSignatureOfTheExactChain_byTheActiveKey_consumesTheNonce() throws Exception {
    assertEquals(
        Verdict.VALID, verifier.verify(tx(25000, "acc-000456", sign(device.getPrivate(), CHAIN))));
    verify(nonces).consume(NONCE, ACCOUNT);
  }

  @Test
  void oneModifiedByte_isRejected_andTheNonceIsNotConsumed() throws Exception {
    String signature = sign(device.getPrivate(), CHAIN);
    // Montant modifié d'une unité : le serveur recalcule une autre chaîne.
    assertEquals(Verdict.INVALID, verifier.verify(tx(25001, "acc-000456", signature)));
    // Un caractère du bénéficiaire modifié.
    assertEquals(Verdict.INVALID, verifier.verify(tx(25000, "acc-000457", signature)));
    // Un octet de la signature modifié.
    byte[] raw = Base64.getDecoder().decode(signature);
    raw[raw.length - 1] ^= 0x01;
    assertEquals(
        Verdict.INVALID,
        verifier.verify(tx(25000, "acc-000456", Base64.getEncoder().encodeToString(raw))));
    verify(nonces, never()).consume(anyString(), anyString());
  }

  @Test
  void theChainHasNoTimestamp_aSignatureCoveringOtherBytesIsRejected() throws Exception {
    String withTimestamp = sign(device.getPrivate(), CHAIN + "|2026-10-06T09:15:00Z");
    assertEquals(Verdict.INVALID, verifier.verify(tx(25000, "acc-000456", withTimestamp)));
    String jsonOrder = sign(device.getPrivate(), "P2P|" + ACCOUNT + "|25000|acc-000456|" + NONCE);
    assertEquals(Verdict.INVALID, verifier.verify(tx(25000, "acc-000456", jsonOrder)));
  }

  @Test
  void replay_theSameSignedPayloadTwice_theSecondIsRefused() throws Exception {
    when(nonces.consume(NONCE, ACCOUNT)).thenReturn(true, false);
    when(nonces.wasConsumed(NONCE, ACCOUNT)).thenReturn(true);
    SignedTransaction once = tx(25000, "acc-000456", sign(device.getPrivate(), CHAIN));
    assertEquals(Verdict.VALID, verifier.verify(once));
    assertEquals(Verdict.REPLAYED, verifier.verify(once));
    verify(nonces, times(2)).consume(NONCE, ACCOUNT);
  }

  @Test
  void unknownExpiredOrForeignNonce_isRefused_notAReplay() throws Exception {
    when(nonces.consume(NONCE, ACCOUNT)).thenReturn(false);
    when(nonces.wasConsumed(NONCE, ACCOUNT)).thenReturn(false);
    assertEquals(
        Verdict.INVALID,
        verifier.verify(tx(25000, "acc-000456", sign(device.getPrivate(), CHAIN))));
  }

  @Test
  void anotherKey_revokedKeyId_orNoActiveKey_isRefused_es20() throws Exception {
    KeyPair attacker = keyPair("secp256r1");
    assertEquals(
        Verdict.INVALID,
        verifier.verify(tx(25000, "acc-000456", sign(attacker.getPrivate(), CHAIN))));
    SignedTransaction oldKey =
        new SignedTransaction(
            ACCOUNT,
            "P2P",
            25000,
            "acc-000456",
            "dk-revoked",
            sign(device.getPrivate(), CHAIN),
            NONCE);
    assertEquals(Verdict.INVALID, verifier.verify(oldKey));
    when(registry.findActive(ACCOUNT)).thenReturn(Optional.empty());
    assertEquals(
        Verdict.INVALID,
        verifier.verify(tx(25000, "acc-000456", sign(device.getPrivate(), CHAIN))));
    verify(nonces, never()).consume(anyString(), anyString());
  }

  @Test
  void missingProof_orAmbiguousField_isRefused() throws Exception {
    assertEquals(Verdict.INVALID, verifier.verify(tx(25000, "acc-000456", null)));
    assertEquals(Verdict.INVALID, verifier.verify(tx(25000, "acc-000456", "pas du base64 !")));
    String piped = sign(device.getPrivate(), ACCOUNT + "|P2P|25000|acc|456|" + NONCE);
    assertEquals(Verdict.INVALID, verifier.verify(tx(25000, "acc|456", piped)));
    verify(nonces, never()).consume(anyString(), anyString());
  }

  @Test
  void aRegisteredKeyOnAnotherCurve_isNeverAccepted() throws Exception {
    KeyPair p384 = keyPair("secp384r1");
    register(p384);
    assertEquals(
        Verdict.INVALID, verifier.verify(tx(25000, "acc-000456", sign(p384.getPrivate(), CHAIN))));
  }
}
