package cm.identishield.pdp.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.device.DeviceRegistry;
import cm.identishield.pdp.device.DeviceRegistry.DeviceKey;
import cm.identishield.pdp.signature.NonceStore;
import cm.identishield.pdp.signature.SignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * F-B7-2 (constat de la passe B7 du 2026-10-25) : chaque entrée d'audit d'une décision /evaluate
 * porte {@code proofType} (SIGNATURE ou NONE), et jamais la signature ni le nonce en clair.
 *
 * <p>@covers EF-11 ; @covers ES-09
 */
class EvaluateProofTypeAuditTest {

  private static final Instant T0 = Instant.parse("2026-10-25T14:00:00Z");
  private static final String NONCE = "0123456789abcdef0123456789abcdef";
  private static final String CHAIN = "acc-000123|P2P|25000|acc-000456|" + NONCE;
  private static final String D1 = "5f1e7c1a-9d3b-4e6a-8c2f-0b7d9a4e6c1d";

  private final List<Map<String, Object>> journal = new ArrayList<>();
  private final AuditLog auditLog = mock(AuditLog.class);
  private final DeviceRegistry registry = mock(DeviceRegistry.class);
  private final NonceStore nonces = mock(NonceStore.class);
  private final KeyPair device;
  private final MockMvc http;

  EvaluateProofTypeAuditTest() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    device = g.generateKeyPair();
    String spki = Base64.getEncoder().encodeToString(device.getPublic().getEncoded());
    when(registry.findActive("acc-000123"))
        .thenReturn(Optional.of(new DeviceKey("dk-7f3a2b", "acc-000123", spki)));
    org.mockito.Mockito.doAnswer(
            inv -> {
              journal.add(inv.getArgument(0));
              return null;
            })
        .when(auditLog)
        .append(any(), any());
    when(auditLog.findByDecisionId(anyString())).thenReturn(Optional.empty());
    EvaluateController controller =
        NeutralChain.controller(
            auditLog,
            Clock.fixed(T0, ZoneOffset.UTC),
            new RuleSet("rs-test", Map.of()),
            new SignatureVerifier(registry, nonces));
    http =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private String sign(String chain) throws Exception {
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(device.getPrivate());
    s.update(chain.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(s.sign());
  }

  private static String signedBody(long amount, String signature) {
    return """
        {"decisionId":"%s","accountId":"acc-000123","msisdn":"677000123",
         "currentImsi":"624019900001230","channel":"APP","type":"P2P","amountXaf":%d,
         "beneficiaryId":"acc-000456","serverTimestamp":"2026-10-25T14:00:00Z",
         "deviceKeyId":"dk-7f3a2b","signature":"%s","nonce":"%s","proofType":"SIGNATURE",
         "balanceXaf":1000000}"""
        .formatted(D1, amount, signature, NONCE);
  }

  private static String unsignedBody() {
    return """
        {"decisionId":"%s","accountId":"acc-000123","msisdn":"677000123",
         "currentImsi":"624019900001230","channel":"USSD","type":"P2P","amountXaf":25000,
         "beneficiaryId":"acc-000456","serverTimestamp":"2026-10-25T14:00:00Z",
         "proofType":"NONE","balanceXaf":1000000}"""
        .formatted(D1);
  }

  private Map<String, Object> theOnlyEntry() {
    assertEquals(1, journal.size(), "une seule entrée d'audit par décision");
    return journal.get(0);
  }

  @Test
  void aSignedDecision_isAuditedWithProofTypeSignature_withoutTheSignatureNorTheNonce()
      throws Exception {
    when(nonces.consume(NONCE, "acc-000123")).thenReturn(true);
    String signature = sign(CHAIN);
    http.perform(
            post("/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signedBody(25000, signature)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("ALLOW"));
    Map<String, Object> p = theOnlyEntry();
    assertEquals("SIGNATURE", p.get("proofType"));
    assertFalse(p.toString().contains(signature), "jamais la signature en clair");
    assertFalse(p.toString().contains(NONCE), "jamais le nonce en clair");
  }

  @Test
  void anInvalidSignature_isAuditedWithProofTypeSignature_withoutTheSignatureNorTheNonce()
      throws Exception {
    String signature = sign(CHAIN);
    http.perform(
            post("/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signedBody(25001, signature)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vetoApplied").value("SIGNATURE_INVALID"));
    Map<String, Object> p = theOnlyEntry();
    assertEquals("SIGNATURE", p.get("proofType"));
    assertFalse(p.toString().contains(signature));
    assertFalse(p.toString().contains(NONCE));
  }

  @Test
  void anUnsignedDecision_isAuditedWithProofTypeNone() throws Exception {
    http.perform(post("/evaluate").contentType(MediaType.APPLICATION_JSON).content(unsignedBody()))
        .andExpect(status().isOk());
    assertEquals("NONE", theOnlyEntry().get("proofType"));
  }
}
