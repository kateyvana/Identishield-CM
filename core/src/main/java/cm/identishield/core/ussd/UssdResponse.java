package cm.identishield.core.ussd;

/** Réponse d'une étape USSD : le message à afficher et si la session reste ouverte. */
public record UssdResponse(String message, boolean sessionOpen) {}
