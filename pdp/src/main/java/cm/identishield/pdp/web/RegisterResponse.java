package cm.identishield.pdp.web;

/** Réponse 200 de POST /device/register : clé EN ATTENTE, code de récupération renvoyé une fois. */
public record RegisterResponse(String deviceId, String recoveryCode) {}
