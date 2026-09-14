package cm.identishield.core.otp;

/**
 * Émission d'un OTP (EF-03) : le code à USAGE UNIQUE et l'IMSI COURANT vers lequel il part. Dans le
 * monde réel il partirait par SMS vers cet IMSI ; un SIM swap le détourne — c'est la faiblesse
 * réelle que le projet reproduit. Le code n'est présent qu'à l'émission ; la base n'en conserve
 * qu'une empreinte, jamais le clair.
 */
public record OtpEmission(String msisdn, String targetImsi, String code) {}
