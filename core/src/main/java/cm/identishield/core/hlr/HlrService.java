package cm.identishield.core.hlr;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HLR simulé (EF-02, EF-08, EI-02). Réattribution de SIM : écrit une ligne d'historique complète et
 * bascule l'IMSI actif de façon atomique, puis publie un événement « changement d'IMSI » interne,
 * consommable par le PDP (le PDP ne l'exploite qu'au mois 4).
 *
 * <p><b>Deux invariants de conception :</b>
 *
 * <ul>
 *   <li>Le <b>motif</b> déclaré par l'agent est PERSISTÉ (traçabilité) mais N'EST PAS transmis au
 *       PDP (ES-16b) : il vit dans l'historique et dans l'événement interne, jamais dans une charge
 *       utile /evaluate. Un agent complice saisit le motif qu'il veut ; il ne doit rien décider.
 *   <li>L'<b>horodatage</b> vient de l'horloge injectée (ED-04, OB-1), jamais d'un DEFAULT de base
 *       : un {@code now()} en base contournerait silencieusement l'horloge et rendrait CAR_IMSI
 *       intestable.
 * </ul>
 */
// @implements EF-02
// @implements EF-08
@Service
public class HlrService {

  private static final Logger log = LoggerFactory.getLogger(HlrService.class);

  private final HlrRepository hlr;
  private final java.time.Clock clock;
  private final ApplicationEventPublisher events;

  public HlrService(HlrRepository hlr, java.time.Clock clock, ApplicationEventPublisher events) {
    this.hlr = hlr;
    this.clock = clock;
    this.events = events;
  }

  /** Fixe l'IMSI initial d'un numéro s'il n'en a pas (amorçage). */
  public void ensureSubscriber(String msisdn, String imsi) {
    hlr.ensureSubscriber(msisdn, imsi, clock.instant());
  }

  /** IMSI actif du numéro (source de currentImsi pour /evaluate ; jamais le motif). */
  public Optional<String> currentImsi(String msisdn) {
    return hlr.currentImsi(msisdn);
  }

  /** Horodatage du dernier changement d'IMSI (EF-08). */
  public Optional<Instant> lastChangeAt(String msisdn) {
    return hlr.lastChangeAt(msisdn);
  }

  /** Historique complet des réattributions du numéro. */
  public List<HlrReassignment> history(String msisdn) {
    return hlr.history(msisdn);
  }

  /**
   * Réattribue la SIM du numéro à {@code newImsi}, sur ordre de {@code agentId} avec le {@code
   * reason} déclaré. Écrit l'historique et bascule l'IMSI actif atomiquement, puis publie
   * l'événement. Renvoie l'événement émis.
   */
  @Transactional
  public ImsiChangeEvent reassign(String msisdn, String newImsi, String agentId, String reason) {
    Instant now = clock.instant();
    String oldImsi = hlr.currentImsi(msisdn).orElse(null);
    hlr.appendReassignment(msisdn, oldImsi, newImsi, agentId, reason, now);
    hlr.setCurrentImsi(msisdn, newImsi, now);
    ImsiChangeEvent event = new ImsiChangeEvent(msisdn, oldImsi, newImsi, now, agentId, reason);
    events.publishEvent(event);
    // Trace interne : l'événement porte le motif (traçabilité) ; cette trace ne franchit pas la
    // frontière vers le PDP.
    log.info(
        "HLR reassignment: msisdn={} old={} new={} at={} agent={}",
        msisdn,
        oldImsi,
        newImsi,
        now,
        agentId);
    return event;
  }
}
