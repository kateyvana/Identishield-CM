package cm.identishield.pdp.web;

import cm.identishield.pdp.device.DeviceEnrolment;
import cm.identishield.pdp.device.DeviceEnrolment.Refused;
import cm.identishield.pdp.device.DeviceEnrolment.Registered;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

  public DeviceController(DeviceEnrolment enrolment) {
    this.enrolment = enrolment;
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
