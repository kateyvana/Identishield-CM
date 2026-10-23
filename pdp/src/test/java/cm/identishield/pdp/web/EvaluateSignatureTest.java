package cm.identishield.pdp.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * POST /evaluate avec preuve SIGNATURE, de bout en bout dans le contrôleur (HTTP simulé) : veto,
 * rejeu en 409 NONCE_CONSUMED (contrat), journal.
 *
 * <p>@covers ES-02 ; @covers ES-10
 */
class EvaluateSignatureTest {

  private static final Instant T0 = Instant.parse("2026-10-06T09:15:00Z");
  private static final String NONCE = "0123456789abcdef0123456789abcdef";
  private static final String CHAIN = "acc-000123|P2P|25000|acc-000456|" + NONCE;

  private final List<Map<String, Object>> journal = new ArrayList<>();
  private final AuditLog auditLog = mock(AuditLog.class);
  private final DeviceRegistry registry = mock(DeviceRegistry.class);
  private final NonceStore nonces = mock(NonceStore.class);
  private final KeyPair device;
  private final MockMvc http;

  EvaluateSignatureTest() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    device = g.generateKeyPair();
    String spki = Base64.getEncoder().encodeToString(device.getPublic().getEncoded());
    when(registry.findActive("acc-000123"))
        .thenReturn(Optional.of(new DeviceKey("dk-7f3a2b", "acc-000123", spki)));
    // Journal en mémoire : append() enregistre, findByDecisionId() relit (idempotence ENF-03).
    org.mockito.Mockito.doAnswer(
            inv -> {
              journal.add(inv.getArgument(0));
              return null;
            })
        .when(auditLog)
        .append(any(), any());
    when(auditLog.findByDecisionId(anyString()))
        .thenAnswer(
            inv ->
                journal.stream()
                    .filter(p -> inv.getArgument(0).equals(p.get("decisionId")))
                    .findFirst());
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

  private static String body(String decisionId, long amount, String signature) {
    return """
        {"decisionId":"%s","accountId":"acc-000123","msisdn":"677000123",
         "currentImsi":"624019900001230","channel":"APP","type":"P2P","amountXaf":%d,
         "beneficiaryId":"acc-000456","serverTimestamp":"2026-10-06T09:15:00Z",
         "deviceKeyId":"dk-7f3a2b","signature":"%s","nonce":"%s","proofType":"SIGNATURE",
         "balanceXaf":1000000}"""
        .formatted(decisionId, amount, signature, NONCE);
  }

  private static final String D1 = "5f1e7c1a-9d3b-4e6a-8c2f-0b7d9a4e6c1d";
  private static final String D2 = "6a2f8d2b-0e4c-4f7b-9d30-1c8e0b5f7d2e";

  @Test
  void validSignature_isAllowed_andJournaledOnce() throws Exception {
    when(nonces.consume(NONCE, "acc-000123")).thenReturn(true);
    http.perform(
            post("/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(D1, 25000, sign(CHAIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("ALLOW"))
        .andExpect(jsonPath("$.vetoApplied").value("NONE"));
    assertEquals(1, journal.size());
  }

  @Test
  void modifiedPayload_isRejectedByTheVeto_andJournaled() throws Exception {
    http.perform(
            post("/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(D1, 25001, sign(CHAIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("REJECTED"))
        .andExpect(jsonPath("$.vetoApplied").value("SIGNATURE_INVALID"));
    assertEquals("REJECTED", journal.get(0).get("decision"));
    verify(nonces, never()).consume(anyString(), anyString());
  }

  @Test
  void replay_theSameSignedPayload_theServerRefusesTheSecond() throws Exception {
    when(nonces.consume(NONCE, "acc-000123")).thenReturn(true, false);
    when(nonces.wasConsumed(NONCE, "acc-000123")).thenReturn(true);
    String signature = sign(CHAIN);
    http.perform(
            post("/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(D1, 25000, signature)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("ALLOW"));
    // Même charge signée, rejouée sous un nouveau decisionId.
    http.perform(
            post("/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(D2, 25000, signature)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NONCE_CONSUMED"));
    assertEquals(2, journal.size());
    assertEquals("NONCE_REUSED", journal.get(1).get("event"));
    assertEquals(1L, journal.stream().filter(p -> "ALLOW".equals(p.get("decision"))).count());
  }

  @Test
  void retryOfTheSameDecisionId_keepsItsAnswer_withoutConsumingAgain() throws Exception {
    when(nonces.consume(NONCE, "acc-000123")).thenReturn(true, false);
    when(nonces.wasConsumed(NONCE, "acc-000123")).thenReturn(true);
    String signature = sign(CHAIN);
    for (int i = 0; i < 2; i++) {
      http.perform(
              post("/evaluate")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body(D1, 25000, signature)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.decision").value("ALLOW"));
    }
    verify(nonces, times(1)).consume(NONCE, "acc-000123");
    assertEquals(1, journal.size());
  }

  @Test
  void trustedContactProof_isOutOfContract_andNothingIsJournaled() throws Exception {
    // Contact de confiance ABANDONNÉ (REGISTRE_DECISIONS P-01) : la valeur n'est plus au contrat.
    String trustedContact =
        body(D1, 25000, sign(CHAIN)).replace("\"SIGNATURE\"", "\"TRUSTED_CONTACT\"");
    http.perform(post("/evaluate").contentType(MediaType.APPLICATION_JSON).content(trustedContact))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONTRACT_VIOLATION"));
    assertEquals(0, journal.size());
    verify(nonces, never()).consume(anyString(), anyString());
  }

  @Test
  void nonce_isIssuedForTheAccount_withUtcTimestamps() throws Exception {
    NonceStore issuing = mock(NonceStore.class);
    when(issuing.issue("acc-000123"))
        .thenReturn(new NonceStore.IssuedNonce(NONCE, "acc-000123", T0, T0.plusSeconds(120)));
    MockMvc nonceHttp =
        MockMvcBuilders.standaloneSetup(new NonceController(issuing))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    nonceHttp
        .perform(get("/nonce").param("accountId", "acc-000123"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nonce").value(NONCE))
        .andExpect(jsonPath("$.issuedAt").value("2026-10-06T09:15:00Z"))
        .andExpect(jsonPath("$.expiresAt").value("2026-10-06T09:17:00Z"));
    nonceHttp
        .perform(get("/nonce").param("accountId", " "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONTRACT_VIOLATION"));
    nonceHttp.perform(get("/nonce")).andExpect(status().isBadRequest());
  }
}
