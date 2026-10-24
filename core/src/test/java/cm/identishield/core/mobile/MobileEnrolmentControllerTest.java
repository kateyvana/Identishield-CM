package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cm.identishield.core.mobile.MobileEnrolment.Answer;
import cm.identishield.core.pdp.PdpClientProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Surface /mobile/v1 du premier enrôlement : lecture STRICTE des corps (additionalProperties:
 * false), et Retry-After sur toute réponse transitoire 503 (contracts/mobile-api.yaml).
 *
 * <p>@covers ES-26
 */
class MobileEnrolmentControllerTest {

  private final MobileEnrolment service = mock(MobileEnrolment.class);
  private final PdpClientProperties pdp =
      new PdpClientProperties(
          "https://pdp:8443",
          "/health",
          800,
          "c",
          "k",
          "a",
          new PdpClientProperties.Breaker(3, 4_500));
  private final MobileEnrolmentController controller =
      new MobileEnrolmentController(service, new ObjectMapper(), pdp);
  private final jakarta.servlet.http.HttpServletRequest http =
      mock(jakarta.servlet.http.HttpServletRequest.class);

  MobileEnrolmentControllerTest() {
    when(http.getRemoteAddr()).thenReturn("10.0.0.7");
  }

  @Test
  void aLockedOrigin_gets429_withTheRemainingSecondsAsRetryAfter_p11() {
    when(service.start("677000200", "4096", "10.0.0.7", null, null, null))
        .thenReturn(Answer.throttled(42));
    ResponseEntity<Map<String, Object>> r =
        controller.start("{\"msisdn\":\"677000200\",\"pin\":\"4096\"}", http);
    assertEquals(429, r.getStatusCode().value());
    assertEquals("42", r.getHeaders().getFirst("Retry-After"));
  }

  @Test
  void aSignedChallenge_isPassedThrough_andItsShapeIsChecked() {
    String device = "6f0a2c1e-0000-4000-8000-000000000001";
    String nonce = "9f86d081884c7d659a2feaa0c55ad015";
    when(service.start("677000200", "4096", "10.0.0.7", device, nonce, "c2ln"))
        .thenReturn(Answer.ok(Map.of("next", "SESSION")));
    String body =
        "{\"msisdn\":\"677000200\",\"pin\":\"4096\",\"deviceId\":\""
            + device
            + "\",\"challenge\":\""
            + nonce
            + "\",\"signature\":\"c2ln\"}";
    assertEquals(200, controller.start(body, http).getStatusCode().value());
    assertEquals(
        400,
        controller.start(body.replace(nonce, "court"), http).getStatusCode().value(),
        "défi hors forme");
  }

  @Test
  void anUnknownField_orABadForm_isRequestInvalid_beforeAnyWork() {
    for (String body :
        new String[] {
          "{\"msisdn\":\"677000200\",\"pin\":\"4096\",\"extra\":1}",
          "{\"msisdn\":\"67700020\",\"pin\":\"4096\"}",
          "{\"msisdn\":\"677000200\",\"pin\":\"40a6\"}",
          "{\"msisdn\":\"677000200\"}",
          "null",
          "pas du json"
        }) {
      ResponseEntity<Map<String, Object>> r = controller.start(body, http);
      assertEquals(400, r.getStatusCode().value(), body);
      assertEquals(Map.of("reason", "REQUEST_INVALID"), r.getBody(), body);
    }
    verifyNoInteractions(service);
  }

  @Test
  void aTransient503_carriesRetryAfter_fromTheBreakerCooldown_roundedUp() {
    when(service.start("677000200", "4096", "10.0.0.7", null, null, null))
        .thenReturn(new Answer(503, Map.of("reason", "SERVICE_UNAVAILABLE"), true, 0));

    ResponseEntity<Map<String, Object>> r =
        controller.start("{\"msisdn\":\"677000200\",\"pin\":\"4096\"}", http);

    assertEquals(503, r.getStatusCode().value());
    assertEquals("5", r.getHeaders().getFirst("Retry-After"));
  }

  @Test
  void aTerminalRefusal_carriesNoRetryAfter() {
    when(service.start("677000200", "4096", "10.0.0.7", null, null, null))
        .thenReturn(new Answer(401, Map.of("reason", "CREDENTIALS_INVALID"), false, 0));

    ResponseEntity<Map<String, Object>> r =
        controller.start("{\"msisdn\":\"677000200\",\"pin\":\"4096\"}", http);

    assertEquals(401, r.getStatusCode().value());
    assertNull(r.getHeaders().getFirst("Retry-After"));
  }

  @Test
  void recover_checksOnlyTheShape_andPassesTheEnrolmentThrough_neverTheCode() {
    String id = "00000000-0000-4000-8000-0000000000e1";
    for (String body :
        new String[] {
          "{\"enrolmentId\":\"abc\",\"recoveryCode\":\"K7MQ3X\"}",
          "{\"enrolmentId\":\"" + id + "\",\"recoveryCode\":\"k7\"}",
          "{\"enrolmentId\":\"" + id + "\",\"recoveryCode\":\"K7MQ3X\",\"extra\":1}",
          "{\"enrolmentId\":\"" + id + "\"}"
        }) {
      assertEquals(400, controller.recover(body).getStatusCode().value(), body);
    }
    verifyNoInteractions(service);
    when(service.recover(java.util.UUID.fromString(id)))
        .thenReturn(Answer.ok(Map.of("next", "BIND", "attestationChallenge", "ZGVmaQ==")));
    ResponseEntity<Map<String, Object>> r =
        controller.recover("{\"enrolmentId\":\"" + id + "\",\"recoveryCode\":\"K7MQ3X\"}");
    assertEquals(200, r.getStatusCode().value());
    assertEquals("BIND", r.getBody().get("next"));
  }

  @Test
  void complete_acceptsAnOptionalRecoveryCode_ofTheRightShape_andPassesTheOrigin() {
    String id = "00000000-0000-4000-8000-0000000000e1";
    String base =
        "{\"enrolmentId\":\"" + id + "\",\"publicKey\":\"cGs=\",\"attestationChain\":[\"Y2VydA==\"],"
            + "\"challengeSignature\":\"c2ln\"";
    when(service.complete(
            java.util.UUID.fromString(id), "cGs=", java.util.List.of("Y2VydA=="), "c2ln", "K7MQ3X", "10.0.0.7", null))
        .thenReturn(Answer.ok(Map.of("enrolmentId", id)));
    assertEquals(
        200, controller.complete(base + ",\"recoveryCode\":\"K7MQ3X\"}", http).getStatusCode().value());
    assertEquals(
        400,
        controller.complete(base + ",\"recoveryCode\":\"k7\"}", http).getStatusCode().value(),
        "code hors forme");
  }

  @Test
  void enrolmentIds_mustBeUuids_andTheChainMustNotBeEmpty() {
    assertEquals(
        400,
        controller.otp("{\"enrolmentId\":\"abc\",\"otp\":\"123456\"}").getStatusCode().value());
    assertEquals(
        400,
        controller
            .complete(
                "{\"enrolmentId\":\"00000000-0000-4000-8000-0000000000e1\",\"publicKey\":\"cGs=\","
                    + "\"attestationChain\":[],\"challengeSignature\":\"c2ln\"}",
                http)
            .getStatusCode()
            .value());
    verifyNoInteractions(service);
  }
}
