package cm.identishield.pdp.web;

/** Réponse de POST /device/verify : VALID, INVALID ou REVOKED. Le PDP énonce, le cœur arbitre. */
public record VerifyResponse(String verdict) {}
