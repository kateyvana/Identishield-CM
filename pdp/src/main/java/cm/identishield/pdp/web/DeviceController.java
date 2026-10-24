package cm.identishield.pdp.web;

import cm.identishield.pdp.device.DeviceEnrolment;
import cm.identishield.pdp.device.DeviceEnrolment.Refused;
import cm.identishield.pdp.device.DeviceEnrolment.Registered;
import cm.identishield.pdp.device.DeviceRegistry;
import cm.identishield.pdp.signature.NonceStore;
import cm.identishield.pdp.signature.SignatureVerifier;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes internes du cycle de vie de la clé d'appareil (contracts/device.yaml, ADR-041), en mTLS
 * comme /evaluate : le cœur est l'unique client. Enregistrement EN ATTENTE avec émission unique du
 * code de récupération ; activation après confirmation. Tout échec d'activation reçoit la même
 * réponse : l'appelant n'apprend jamais lequel a échoué.
 */
@RestController
public class DeviceController {

  private final DeviceEnrolment enrolment;
  private final DeviceRegistry registry;
  private final NonceStore nonces;
  private final SignatureVerifier signatures;

  public DeviceController(
      DeviceEnrolment enrolment,
      DeviceRegistry registry,
      NonceStore nonces,
      SignatureVerifier signatures) {
    this.enrolment = enrolment;
    this.registry = registry;
    this.nonces = nonces;
    this.signatures = signatures;
  }

  /**
   * Défi de session (carte B4 ; décision de la propriétaire du 2026-10-24, point 4) : le défi EST
   * un nonce du PDP, émis pour le compte de la clé ACTIVE qui porte cet identifiant, à usage
   * unique, valable NONCE_TTL. Aucune clé ACTIVE de cet identifiant : 404, et c'est le cœur qui
   * rend la réponse uniforme à l'application (ES-22). Route interne, jamais atteignable depuis
   * l'application.
   */
  // @implements ES-10
  @GetMapping("/device/{deviceId}/nonce")
  public ResponseEntity<Object> nonce(@PathVariable String deviceId) {
    return registry
        .findActiveById(deviceId)
        .<ResponseEntity<Object>>map(
            key ->
                ResponseEntity.ok(new DeviceNonceResponse(nonces.issue(key.accountId()).value())))
        .orElseGet(
            () ->
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiError("DEVICE_UNKNOWN", "no active device key with this id")));
  }

  /**
   * Signature du défi de session par la clé ACTIVE {@code deviceId} du compte (ISC-MOB-SPEC §6 : «
   * le PDP reconnaît la clé »). Verdict VALID (défi consommé), INVALID ou REVOKED ; le PDP énonce,
   * le cœur arbitre. Le cœur ne l'appelle qu'APRÈS avoir vérifié le PIN.
   */
  // @implements ES-20
  @PostMapping("/device/verify")
  public VerifyResponse verify(@Valid @RequestBody VerifyRequest r) {
    // Deux formes du contrat : B4, le défi EST le nonce ; B6, `nonce` nommé à part et le défi est
    // la chaîne canonique signée (annulation d'un virement différé, réponses aux alertes).
    SignatureVerifier.ChallengeVerdict verdict =
        r.nonce() == null
            ? signatures.verifyChallenge(r.accountId(), r.deviceId(), r.challenge(), r.signature())
            : signatures.verifyChallenge(
                r.accountId(), r.deviceId(), r.challenge(), r.signature(), r.nonce());
    return new VerifyResponse(verdict.name());
  }

  /**
   * FAIT de liaison du compte (ES-26) : NONE, PENDING ou ACTIVE. Le PDP énonce, le cœur arbitre :
   * c'est le cœur qui en déduit l'étape suivante, et seulement APRÈS avoir vérifié le PIN (la
   * réponse uniforme d'ES-22 tombe avant). Route interne, jamais atteignable depuis l'application.
   */
  // @implements ES-26
  @GetMapping("/device/state")
  public ResponseEntity<Object> state(@RequestParam String accountId) {
    if (accountId.isBlank()) {
      return ResponseEntity.badRequest()
          .body(new ApiError("CONTRACT_VIOLATION", "accountId must not be blank"));
    }
    return ResponseEntity.ok(new DeviceStateResponse(registry.bindingState(accountId).name()));
  }

  @PostMapping("/device/register")
  public ResponseEntity<Object> register(@Valid @RequestBody RegisterRequest r) {
    DeviceEnrolment.Registration outcome =
        enrolment.register(
            r.accountId(),
            r.publicKey(),
            r.attestationChain(),
            r.attestationChallenge(),
            r.challengeSignature());
    if (outcome instanceof Registered ok) {
      return ResponseEntity.ok(new RegisterResponse(ok.deviceId(), ok.recoveryCode()));
    }
    return switch (((Refused) outcome).reason()) {
      case ALREADY_BOUND ->
          ResponseEntity.status(HttpStatus.CONFLICT)
              .body(
                  new ApiError("DEVICE_ALREADY_BOUND", "account already has an active device key"));
      case DEVICE_UNSUPPORTED ->
          ResponseEntity.unprocessableEntity()
              .body(new ApiError("DEVICE_UNSUPPORTED", "hardware-backed key required"));
      case ATTESTATION_REJECTED ->
          ResponseEntity.unprocessableEntity()
              .body(
                  new ApiError(
                      "ATTESTATION_REJECTED", "attestation or challenge signature rejected"));
    };
  }

  @PostMapping("/device/activate")
  public ResponseEntity<Object> activate(@Valid @RequestBody ActivateRequest r) {
    if (enrolment.activate(r.accountId(), r.deviceId(), r.recoveryCode())) {
      return ResponseEntity.ok(new ActivateResponse(r.deviceId()));
    }
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(new ApiError("RECOVERY_INVALID", "recovery code rejected"));
  }
}
