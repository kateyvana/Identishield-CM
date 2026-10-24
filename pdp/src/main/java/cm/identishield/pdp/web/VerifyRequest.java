package cm.identishield.pdp.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Corps de POST /device/verify (contracts/device.yaml, carte B4) : le défi tel que remis à
 * l'application et sa signature par la clé {@code deviceId} du compte.
 */
public record VerifyRequest(
    @NotBlank String accountId,
    @NotBlank String deviceId,
    @NotBlank String challenge,
    @NotBlank String signature) {}
