package cm.identishield.pdp.web;

/** Réponse 200 de POST /device/activate : la clé est ACTIVE. */
public record ActivateResponse(String deviceId) {}
