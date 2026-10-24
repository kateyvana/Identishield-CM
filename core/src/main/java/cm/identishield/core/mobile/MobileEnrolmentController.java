package cm.identishield.core.mobile;

import cm.identishield.core.mobile.MobileEnrolment.Answer;
import cm.identishield.core.pdp.PdpClientProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
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

  /** Défi de session tel que remis (nonce hexadécimal du PDP) ; borné, jamais interprété. */
  private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");

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

  /**
   * L'origine réseau (P-11) est l'adresse distante vue par le cœur ; sur la surface mobile (L7),
   * c'est l'adresse Tailscale du téléphone, à VÉRIFIER au montage de L7-b (limite déclarée B4).
   */
  @PostMapping("/enrolment/start")
  public ResponseEntity<Map<String, Object>> start(
      @RequestBody String body, HttpServletRequest http) {
    StartRequest r = read(body, StartRequest.class);
    if (r == null
        || !isMsisdn(r.msisdn())
        || !isPin(r.pin())
        || (r.deviceId() != null && !isDeviceId(r.deviceId()))
        || (r.challenge() != null && !isChallenge(r.challenge()))
        || (r.signature() != null && !isBase64(r.signature()))) {
      return invalid();
    }
    return answer(
        enrolment.start(
            r.msisdn(), r.pin(), http.getRemoteAddr(), r.deviceId(), r.challenge(), r.signature()));
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

  ResponseEntity<Map<String, Object>> answer(Answer a) {
    ResponseEntity.BodyBuilder b = ResponseEntity.status(a.status());
    if (a.retryAfter()) {
      b.header("Retry-After", retryAfterSeconds);
    } else if (a.throttledSeconds() > 0) {
      b.header("Retry-After", Long.toString(a.throttledSeconds()));
    }
    return b.body(a.body());
  }

  static ResponseEntity<Map<String, Object>> invalid() {
    return ResponseEntity.badRequest().body(Map.of("reason", "REQUEST_INVALID"));
  }

  <T> T read(String body, Class<T> type) {
    try {
      return strict.readValue(body, type);
    } catch (Exception e) {
      return null;
    }
  }

  // Contrôles de forme partagés avec les routes de session (MobileSessionController).
  static boolean isMsisdn(String v) {
    return matches(MSISDN, v);
  }

  static boolean isPin(String v) {
    return matches(DIGITS, v);
  }

  static boolean isDeviceId(String v) {
    return matches(DEVICE_ID, v);
  }

  static boolean isChallenge(String v) {
    return matches(CHALLENGE, v);
  }

  static boolean isBase64(String v) {
    return matches(BASE64, v);
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
