package cm.identishield.pdp.web;

/** Réponse GET /nonce (contrats/evaluate.yaml, NonceResponse) ; horodatages UTC « Z » (ED-04). */
public record NonceResponse(String nonce, String accountId, String issuedAt, String expiresAt) {}
