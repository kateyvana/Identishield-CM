package cm.identishield.pdp.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.device.DeviceEnrolment;
import cm.identishield.pdp.device.DeviceEnrolment.Refusal;
import cm.identishield.pdp.device.DeviceEnrolment.Refused;
import cm.identishield.pdp.device.DeviceEnrolment.Registered;
import cm.identishield.pdp.device.DeviceRegistry;
import cm.identishield.pdp.device.DeviceRegistry.BindingState;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Routes internes du PDP (contracts/device.yaml) : statuts et codes d'erreur du contrat. Tout échec
 * d'activation reçoit la MÊME réponse (401 RECOVERY_INVALID).
 *
 * <p>@covers ES-18
 *
 * <p>@covers ES-22
 */
class DeviceControllerTest {

  private final DeviceEnrolment enrolment = mock(DeviceEnrolment.class);
  private final DeviceRegistry registry = mock(DeviceRegistry.class);
  private final cm.identishield.pdp.signature.NonceStore nonces =
      mock(cm.identishield.pdp.signature.NonceStore.class);
  private final cm.identishield.pdp.signature.SignatureVerifier signatures =
      mock(cm.identishield.pdp.signature.SignatureVerifier.class);
  private final DeviceController controller =
      new DeviceController(enrolment, registry, nonces, signatures);

  /** Carte B4 : le défi de session est un nonce émis pour le compte de la clé ACTIVE. */
  @Test
  void nonce_isIssuedForTheAccountOfTheActiveKey_404Otherwise() {
    when(registry.findActiveById("dev-1"))
        .thenReturn(Optional.of(new DeviceRegistry.DeviceKey("dev-1", "acc-1", "cGs=")));
    when(nonces.issue("acc-1"))
        .thenReturn(
            new cm.identishield.pdp.signature.NonceStore.IssuedNonce(
                "9f86d081884c7d659a2feaa0c55ad015", "acc-1", null, null));
    ResponseEntity<Object> ok = controller.nonce("dev-1");
    assertEquals(200, ok.getStatusCode().value());
    assertEquals(new DeviceNonceResponse("9f86d081884c7d659a2feaa0c55ad015"), ok.getBody());

    when(registry.findActiveById("dev-9")).thenReturn(Optional.empty());
    ResponseEntity<Object> unknown = controller.nonce("dev-9");
    assertEquals(404, unknown.getStatusCode().value());
    assertEquals("DEVICE_UNKNOWN", ((ApiError) unknown.getBody()).code());
    org.mockito.Mockito.verify(nonces, org.mockito.Mockito.times(1)).issue("acc-1");
  }

  @Test
  void verify_returnsThePdpVerdict_andDecidesNothing() {
    for (var verdict : cm.identishield.pdp.signature.SignatureVerifier.ChallengeVerdict.values()) {
      when(signatures.verifyChallenge("acc-1", "dev-1", "nonce", "c2ln")).thenReturn(verdict);
      assertEquals(
          new VerifyResponse(verdict.name()),
          controller.verify(new VerifyRequest("acc-1", "dev-1", "nonce", "c2ln")));
    }
  }

  /**
   * Carte B6 : avec `nonce` nommé, c'est la forme à cinq champs du vérificateur qui est appelée.
   */
  @Test
  void verify_withExplicitNonce_passesTheChainAndTheNonceApart() {
    String chain = "acc-1|CANCEL|tr-1|9f86d081884c7d659a2feaa0c55ad015";
    when(signatures.verifyChallenge(
            "acc-1", "dev-1", chain, "c2ln", "9f86d081884c7d659a2feaa0c55ad015"))
        .thenReturn(cm.identishield.pdp.signature.SignatureVerifier.ChallengeVerdict.VALID);
    assertEquals(
        new VerifyResponse("VALID"),
        controller.verify(
            new VerifyRequest(
                "acc-1", "dev-1", chain, "c2ln", "9f86d081884c7d659a2feaa0c55ad015")));
    org.mockito.Mockito.verify(signatures, org.mockito.Mockito.never())
        .verifyChallenge(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
  }

  private final RegisterRequest register =
      new RegisterRequest(
          "acc-1", "cGs=", List.of("Y2VydA=="), "ZGVmaQ==", "c2ln", null, "100.64.0.9", null);

  private void willRefuse(Refusal reason) {
    when(enrolment.register(
            eq("acc-1"),
            eq("cGs="),
            eq(List.of("Y2VydA==")),
            eq("ZGVmaQ=="),
            eq("c2ln"),
            isNull(),
            any()))
        .thenReturn(new Refused(reason));
  }

  @Test
  void registered_is200_withDeviceIdAndCodeOnce() {
    when(enrolment.register(
            eq("acc-1"),
            eq("cGs="),
            eq(List.of("Y2VydA==")),
            eq("ZGVmaQ=="),
            eq("c2ln"),
            isNull(),
            any()))
        .thenReturn(new Registered("dev-1", "K7MQ3X"));
    ResponseEntity<Object> r = controller.register(register);
    assertEquals(200, r.getStatusCode().value());
    assertEquals(new RegisterResponse("dev-1", "K7MQ3X"), r.getBody());
  }

  @Test
  void refusals_followTheContract() {
    willRefuse(Refusal.RECOVERY_INVALID);
    ResponseEntity<Object> invalid = controller.register(register);
    assertEquals(401, invalid.getStatusCode().value());
    assertEquals("RECOVERY_INVALID", ((ApiError) invalid.getBody()).code());
    when(enrolment.register(
            eq("acc-1"),
            eq("cGs="),
            eq(List.of("Y2VydA==")),
            eq("ZGVmaQ=="),
            eq("c2ln"),
            isNull(),
            any()))
        .thenReturn(new Refused(Refusal.THROTTLED, 37));
    ResponseEntity<Object> throttled = controller.register(register);
    assertEquals(429, throttled.getStatusCode().value());
    assertEquals("37", throttled.getHeaders().getFirst("Retry-After"));
    assertEquals(
        new ThrottledError("THROTTLED", "too many failed attempts", 37), throttled.getBody());

    willRefuse(Refusal.ATTESTATION_REJECTED);
    ResponseEntity<Object> rejected = controller.register(register);
    assertEquals(422, rejected.getStatusCode().value());
    assertEquals("ATTESTATION_REJECTED", ((ApiError) rejected.getBody()).code());

    willRefuse(Refusal.DEVICE_UNSUPPORTED);
    ResponseEntity<Object> unsupported = controller.register(register);
    assertEquals(422, unsupported.getStatusCode().value());
    assertEquals("DEVICE_UNSUPPORTED", ((ApiError) unsupported.getBody()).code());
  }

  @Test
  void state_statesTheFact_noneOrPendingOrActive_andDecidesNothing() {
    when(registry.bindingState("acc-1")).thenReturn(BindingState.ACTIVE);
    when(registry.bindingState("acc-2")).thenReturn(BindingState.PENDING);
    when(registry.bindingState("acc-3")).thenReturn(BindingState.NONE);
    assertEquals(new DeviceStateResponse("ACTIVE"), controller.state("acc-1").getBody());
    assertEquals(new DeviceStateResponse("PENDING"), controller.state("acc-2").getBody());
    assertEquals(new DeviceStateResponse("NONE"), controller.state("acc-3").getBody());
    assertEquals(400, controller.state(" ").getStatusCode().value());
  }

  @Test
  void activation_200_orTheSingleFailure401() {
    when(enrolment.activate("acc-1", "dev-1", "K7MQ3X")).thenReturn(true);
    ResponseEntity<Object> ok =
        controller.activate(new ActivateRequest("acc-1", "dev-1", "K7MQ3X"));
    assertEquals(200, ok.getStatusCode().value());
    assertEquals(new ActivateResponse("dev-1"), ok.getBody());

    ResponseEntity<Object> ko =
        controller.activate(new ActivateRequest("acc-1", "dev-1", "WRONG1"));
    assertEquals(401, ko.getStatusCode().value());
    assertEquals("RECOVERY_INVALID", ((ApiError) ko.getBody()).code());
  }
}
