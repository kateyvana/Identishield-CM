package cm.identishield.core.notification;

/**
 * Canaux de notification simulés (EF-09). Rien ne sort du sandbox : chaque notification est
 * seulement persistée.
 *
 * <ul>
 *   <li>{@code SMS} : livré vers l'IMSI COURANT du numéro (résolu au HLR, T2-10) — délibérément la
 *       même faiblesse que l'OTP (T2-12) : un SIM swap détourne le SMS vers l'IMSI de l'attaquant.
 *   <li>{@code PUSH} : livré vers le dispositif enrôlé. Aucun dispositif au mois 2 : la
 *       notification est seulement stockée.
 * </ul>
 */
public enum NotificationChannel {
  SMS,
  PUSH
}
