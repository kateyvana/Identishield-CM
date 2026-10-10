package cm.identishield.pdp.web;

import cm.identishield.pdp.signature.NonceStore;
import cm.identishield.pdp.signature.NonceStore.IssuedNonce;
import java.time.format.DateTimeFormatter;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /nonce (contrats/evaluate.yaml ; ES-10) : nonce de 128 bits lié au compte, valable NONCE_TTL,
 * à usage unique. Le cœur le demande pour le compte de la session et le transmet à l'application.
 */
@RestController
public class NonceController {

  private final NonceStore nonces;

  public NonceController(NonceStore nonces) {
    this.nonces = nonces;
  }

  @GetMapping("/nonce")
  public NonceResponse issue(@RequestParam String accountId)
      throws MissingServletRequestParameterException {
    if (accountId.isBlank()) {
      throw new MissingServletRequestParameterException("accountId", "String"); // 400 du contrat
    }
    IssuedNonce n = nonces.issue(accountId);
    return new NonceResponse(
        n.value(),
        n.accountId(),
        DateTimeFormatter.ISO_INSTANT.format(n.issuedAt()),
        DateTimeFormatter.ISO_INSTANT.format(n.expiresAt()));
  }
}
