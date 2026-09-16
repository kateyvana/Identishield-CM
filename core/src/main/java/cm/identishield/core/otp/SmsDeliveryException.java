package cm.identishield.core.otp;

/**
 * Échec de la remise d'un SMS au réseau simulé (isc-sim) : le réseau a refusé ou est injoignable.
 */
public class SmsDeliveryException extends RuntimeException {
  public SmsDeliveryException(String message) {
    super(message);
  }
}
