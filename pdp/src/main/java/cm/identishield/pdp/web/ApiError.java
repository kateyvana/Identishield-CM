package cm.identishield.pdp.web;

/** Corps d'erreur du contrat (contrats/evaluate.yaml : {code, message}). */
public record ApiError(String code, String message) {}
