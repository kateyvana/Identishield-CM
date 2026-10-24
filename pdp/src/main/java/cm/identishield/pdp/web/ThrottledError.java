package cm.identishield.pdp.web;

/** Corps d'un 429 (contracts/device.yaml) : l'erreur du contrat, plus l'attente en secondes. */
public record ThrottledError(String code, String message, long retryAfterSeconds) {}
