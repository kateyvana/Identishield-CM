package cm.identishield.pdp.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** Corps de POST /device/register (contracts/device.yaml). */
public record RegisterRequest(
    @NotBlank String accountId,
    @NotBlank String publicKey,
    @NotEmpty List<@NotBlank String> attestationChain,
    @NotBlank String attestationChallenge,
    @NotBlank String challengeSignature) {}
