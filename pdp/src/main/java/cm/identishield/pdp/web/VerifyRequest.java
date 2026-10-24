package cm.identishield.pdp.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Corps de POST /device/verify (contracts/device.yaml, cartes B4 et B6) : le défi tel que remis à
 * l'application et sa signature par la clé {@code deviceId} du compte. {@code nonce} (facultatif,
 * B6) : la signature porte alors sur {@code challenge} (chaîne canonique ADR-018 qui contient le
 * nonce) et c'est {@code nonce} qui est consommé.
 */
public record VerifyRequest(
    @NotBlank String accountId,
    @NotBlank String deviceId,
    @NotBlank String challenge,
    @NotBlank String signature,
    String nonce) {

  /** Forme B4 : le défi est lui-même le nonce. */
  public VerifyRequest(String accountId, String deviceId, String challenge, String signature) {
    this(accountId, deviceId, challenge, signature, null);
  }
}
