package cm.identishield.core.notification;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistance des notifications simulées (EF-09), table {@code core.notification} de T2-01. Rien
 * n'est envoyé : on écrit et on relit. L'horodatage vient de l'horloge injectée (ED-04), jamais
 * d'un DEFAULT de base.
 */
@Repository
public class NotificationRepository {

  private final JdbcTemplate jdbc;

  public NotificationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Insère une notification déjà validée et composée, et renvoie l'enregistrement stocké. */
  public Notification insert(
      String msisdn,
      NotificationChannel channel,
      String recipientRef,
      String type,
      String body,
      Instant sentAt) {
    UUID id =
        jdbc.queryForObject(
            "INSERT INTO core.notification(msisdn, recipient_ref, channel, type, body, sent_at)"
                + " VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
            UUID.class,
            msisdn,
            recipientRef,
            channel.name(),
            type,
            body,
            OffsetDateTime.ofInstant(sentAt, ZoneOffset.UTC));
    return new Notification(id, msisdn, channel, recipientRef, type, body, sentAt);
  }

  /** Notifications d'un numéro, les plus anciennes d'abord (consultation par numéro, EF-09). */
  public List<Notification> listByMsisdn(String msisdn) {
    return jdbc.query(
        "SELECT id, msisdn, recipient_ref, channel, type, body, sent_at"
            + " FROM core.notification WHERE msisdn = ? ORDER BY sent_at, id",
        (rs, i) ->
            new Notification(
                (UUID) rs.getObject("id"),
                rs.getString("msisdn"),
                NotificationChannel.valueOf(rs.getString("channel")),
                rs.getString("recipient_ref"),
                rs.getString("type"),
                rs.getString("body"),
                rs.getObject("sent_at", OffsetDateTime.class).toInstant()),
        msisdn);
  }

  /** Notifications émises les plus récentes, tous comptes (EF-37) : vue console, contenu inclus. */
  public List<Notification> listRecent(int limit) {
    return jdbc.query(
        "SELECT id, msisdn, recipient_ref, channel, type, body, sent_at"
            + " FROM core.notification ORDER BY sent_at DESC, id LIMIT ?",
        (rs, i) ->
            new Notification(
                (UUID) rs.getObject("id"),
                rs.getString("msisdn"),
                NotificationChannel.valueOf(rs.getString("channel")),
                rs.getString("recipient_ref"),
                rs.getString("type"),
                rs.getString("body"),
                rs.getObject("sent_at", OffsetDateTime.class).toInstant()),
        limit);
  }
}
