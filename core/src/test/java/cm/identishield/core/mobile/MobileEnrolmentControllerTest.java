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
      ResponseEntity<Map<String, Object>> r = controller.start(body);
      assertEquals(400, r.getStatusCode().value(), body);
      assertEquals(Map.of("reason", "REQUEST_INVALID"), r.getBody(), body);
    }
    verifyNoInteractions(service);
  }

  @Test
  void aTransient503_carriesRetryAfter_fromTheBreakerCooldown_roundedUp() {
    when(service.start("677000200", "4096"))
        .thenReturn(new Answer(503, Map.of("reason", "SERVICE_UNAVAILABLE"), true));

    ResponseEntity<Map<String, Object>> r =
        controller.start("{\"msisdn\":\"677000200\",\"pin\":\"4096\"}");

    assertEquals(503, r.getStatusCode().value());
    assertEquals("5", r.getHeaders().getFirst("Retry-After"));
  }

  @Test
  void aTerminalRefusal_carriesNoRetryAfter() {
    when(service.start("677000200", "4096"))
        .thenReturn(new Answer(401, Map.of("reason", "CREDENTIALS_INVALID"), false));

    ResponseEntity<Map<String, Object>> r =
        controller.start("{\"msisdn\":\"677000200\",\"pin\":\"4096\"}");

    assertEquals(401, r.getStatusCode().value());
    assertNull(r.getHeaders().getFirst("Retry-After"));
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
                    + "\"attestationChain\":[],\"challengeSignature\":\"c2ln\"}")
            .getStatusCode()
            .value());
    verifyNoInteractions(service);
  }
}
