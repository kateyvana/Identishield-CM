package cm.identishield.pdp.web;

/** Rejeu : la preuve présentée porte un nonce déjà consommé (ES-10 ; contrat : 409). */
public class NonceReplayedException extends RuntimeException {
  public NonceReplayedException() {
    super("nonce already consumed");
  }
}
