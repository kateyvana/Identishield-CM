package cm.identishield.pdp.web;

import jakarta.validation.constraints.NotBlank;

/** Corps de POST /device/activate (contracts/device.yaml) : le code complet, retapé une fois. */
public record ActivateRequest(
    @NotBlank String accountId, @NotBlank String deviceId, @NotBlank String recoveryCode) {}
