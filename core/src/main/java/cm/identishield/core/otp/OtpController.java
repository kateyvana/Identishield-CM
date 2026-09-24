package cm.identishield.core.otp;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Surface HTTP de l'OTP (EF-03), sur le réseau interne isc-net. Le cœur ÉMET et CONSOMME l'OTP ; il
 * ne le LIVRE ni ne le RELIT — la livraison part vers le réseau SMS simulé ({@code isc-sim}) et
 * l'interception se lit là-bas (ADR-034). Deux points seulement :
 *
 * <ul>
 *   <li>{@code POST /otp/issue} : émet un OTP vers l'IMSI COURANT du numéro (empreinte stockée en
 *       base) et le REMET au réseau via la passerelle SMS. Renvoie l'IMSI cible, JAMAIS le code.
 *   <li>{@code POST /otp/verify} : consomme un OTP (usage unique).
 * </ul>
 *
 * <p>Il n'existe volontairement AUCUN endpoint « lire l'OTP livré » ici : ce serait une porte
 * dérobée dans le cœur de production. Cette lecture n'existe que sur isc-sim.
 *
 * <p><b>Revue ligne à ligne requise</b> : credential à usage unique (OTP).
 */
// @implements EF-03
@RestController
public class OtpController {

  private final OtpService otp;
  private final SmsGateway sms;

  public OtpController(OtpService otp, SmsGateway sms) {
    this.otp = otp;
    this.sms = sms;
  }

  @PostMapping("/otp/issue")
  public ResponseEntity<Map<String, Object>> issue(@RequestBody MsisdnRequest req) {
    if (blank(req.msisdn())) {
      return error(HttpStatus.BAD_REQUEST, "msisdn obligatoire");
    }
    OtpEmission em;
    try {
      em = otp.issue(req.msisdn());
    } catch (IllegalStateException e) {
      return error(HttpStatus.NOT_FOUND, e.getMessage());
    }
    try {
      sms.deliver(em.targetImsi(), em.code()); // remise au réseau ; le cœur ne relit jamais
    } catch (SmsDeliveryException e) {
      return error(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("msisdn", em.msisdn());
    body.put("targetImsi", em.targetImsi());
    body.put("delivered", true);
    return ResponseEntity.ok(body);
  }

  @PostMapping("/otp/verify")
  public ResponseEntity<Map<String, Object>> verify(@RequestBody VerifyRequest req) {
    if (blank(req.msisdn()) || blank(req.code())) {
      return error(HttpStatus.BAD_REQUEST, "msisdn et code obligatoires");
    }
    OtpOutcome outcome = otp.verify(req.msisdn(), req.code());
    return ResponseEntity.ok(Map.of("outcome", outcome.name()));
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("error", message));
  }

  /** Corps portant un seul numéro. */
  public record MsisdnRequest(String msisdn) {}

  /** Corps de vérification : numéro + code à consommer. */
  public record VerifyRequest(String msisdn, String code) {}
}
