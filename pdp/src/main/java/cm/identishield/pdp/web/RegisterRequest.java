package cm.identishield.pdp.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Corps de POST /device/register (contracts/device.yaml). {@code recoveryCode} : l'ancien code, exigé
 * au réenrôlement (compte déjà lié), absent au premier enrôlement. {@code origin} et {@code
 * installationId} : clés de comptage des échecs (ADR-030) ; l'installation peut être inconnue.
 */
public record RegisterRequest(
    @NotBlank String accountId,
    @NotBlank String publicKey,
    @NotEmpty List<@NotBlank String> attestationChain,
    @NotBlank String attestationChallenge,
    @NotBlank String challengeSignature,
    String recoveryCode,
    @NotBlank String origin,
    String installationId) {}
