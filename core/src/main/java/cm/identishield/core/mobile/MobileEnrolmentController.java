package cm.identishield.core.mobile;

import cm.identishield.core.mobile.MobileEnrolment.Answer;
import cm.identishield.core.pdp.PdpClientProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes /mobile/v1 du premier enrôlement (contracts/mobile-api.yaml, carte B3a). Les corps sont
 * lus STRICTEMENT (additionalProperties: false) : champ inconnu, manquant ou hors forme → 400
 * REQUEST_INVALID. Une réponse transitoire (503 SERVICE_UNAVAILABLE) porte Retry-After : le délai
 * de refroidissement du disjoncteur PDP, pour que les applications ne réessaient pas toutes à leur
 * rythme pendant une panne.
 */
@RestController
@RequestMapping("/mobile/v1")
public class MobileEnrolmentController {

  private static final Pattern MSISDN = Pattern.compile("^67700[0-9]{4}$");
  private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");
  private static final Pattern BASE64 = Pattern.compile("^[A-Za-z0-9+/]+={0,2}$");
  private static final Pattern DEVICE_ID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  record StartRequest(
      String msisdn, String pin, String deviceId, String challenge, String signature) {}

  record OtpRequest(String enrolmentId, String otp) {}

  record CompleteRequest(
      String enrolmentId,
      String publicKey,
      List<String> attestationChain,
      String challengeSignature) {}

  record ConfirmRequest(String enrolmentId, String recoveryCode) {}

  private final MobileEnrolment enrolment;
  private final ObjectMapper strict;
  private final String retryAfterSeconds;

  public MobileEnrolmentController(
      MobileEnrolment enrolment, ObjectMapper json, PdpClientProperties pdp) {
    this.enrolment = enrolment;
    this.strict = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    long cooldownMs = pdp.breaker().cooldownMs();
    this.retryAfterSeconds = Long.toString(Math.max(1, (cooldownMs + 999) / 1000));
  }

  @PostMapping("/enrolment/start")
  public ResponseEntity<Map<String, Object>> start(@RequestBody String body) {
    StartRequest r = read(body, StartRequest.class);
    if (r == null
        || !matches(MSISDN, r.msisdn())
        || !matches(DIGITS, r.pin())
        || (r.deviceId() != null && !matches(DEVICE_ID, r.deviceId()))
        || (r.signature() != null && !matches(BASE64, r.signature()))) {
      return invalid();
    }
    return answer(enrolment.start(r.msisdn(), r.pin()));
  }

  @PostMapping("/enrolment/otp")
  public ResponseEntity<Map<String, Object>> otp(@RequestBody String body) {
    OtpRequest r = read(body, OtpRequest.class);
    UUID id = r == null ? null : uuid(r.enrolmentId());
    if (id == null || !matches(DIGITS, r.otp())) {
      return invalid();
    }
    return answer(enrolment.otp(id, r.otp()));
  }

  @PostMapping("/enrolment/complete")
  public ResponseEntity<Map<String, Object>> complete(@RequestBody String body) {
    CompleteRequest r = read(body, CompleteRequest.class);
    UUID id = r == null ? null : uuid(r.enrolmentId());
    if (id == null
        || !matches(BASE64, r.publicKey())
        || !matches(BASE64, r.challengeSignature())
        || r.attestationChain() == null
        || r.attestationChain().isEmpty()
        || !r.attestationChain().stream().allMatch(c -> matches(BASE64, c))) {
      return invalid();
    }
    return answer(
        enrolment.complete(id, r.publicKey(), r.attestationChain(), r.challengeSignature()));
  }

  @PostMapping("/recovery-code/confirm")
  public ResponseEntity<Map<String, Object>> confirm(@RequestBody String body) {
    ConfirmRequest r = read(body, ConfirmRequest.class);
    UUID id = r == null ? null : uuid(r.enrolmentId());
    if (id == null || r.recoveryCode() == null || r.recoveryCode().isBlank()) {
      return invalid();
    }
    return answer(enrolment.confirm(id, r.recoveryCode()));
  }

  private ResponseEntity<Map<String, Object>> answer(Answer a) {
    ResponseEntity.BodyBuilder b = ResponseEntity.status(a.status());
    if (a.retryAfter()) {
      b.header("Retry-After", retryAfterSeconds);
    }
    return b.body(a.body());
  }

  private static ResponseEntity<Map<String, Object>> invalid() {
    return ResponseEntity.badRequest().body(Map.of("reason", "REQUEST_INVALID"));
  }

  private <T> T read(String body, Class<T> type) {
    try {
      return strict.readValue(body, type);
    } catch (Exception e) {
      return null;
    }
  }

  private static boolean matches(Pattern p, String value) {
    return value != null && p.matcher(value).matches();
  }

  private static UUID uuid(String value) {
    try {
      return value == null ? null : UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
