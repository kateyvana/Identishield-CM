package cm.identishield.pdp.web;

/** Réponse de GET /device/{deviceId}/nonce : le nonce qui servira de défi de session. */
public record DeviceNonceResponse(String nonce) {}
