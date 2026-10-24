package cm.identishield.pdp.web;

/** Réponse de GET /device/state : le FAIT de liaison, NONE | PENDING | ACTIVE. */
public record DeviceStateResponse(String state) {}
