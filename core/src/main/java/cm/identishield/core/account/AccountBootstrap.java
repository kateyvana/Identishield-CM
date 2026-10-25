package cm.identishield.core.account;

import cm.identishield.core.hlr.HlrService;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Amorce deux comptes fictifs (ED-09) pour le fil de démonstration : un émetteur approvisionné et
 * un bénéficiaire. Idempotent (ne recrée pas s'ils existent). La date de création vient de
 * l'horloge injectée (ED-04), jamais de la base.
 */
@Component
public class AccountBootstrap {

  static final String SENDER_MSISDN = "677000001";
  static final String BENEFICIARY_MSISDN = "677000002";
  private static final long SENDER_INITIAL_XAF = 1_000_000;

  /**
   * SIM de l'émetteur technique : l'IMSI que le PDP a DÉJÀ épinglé comme référence pour ce compte
   * (constaté le 2026-10-25 dans la VM, {@code pdp.trust_state.reference_imsi}), c'est-à-dire
   * l'ancien IMSI de remplacement du cœur d'avant A5-00. Un autre IMSI ferait voir au PDP un faux
   * échange de SIM et placerait le compte en PROBATION 72 h.
   */
  static final String SENDER_IMSI = "624010000000000";

  /** SIM du bénéficiaire technique : aucune référence côté PDP, dérivation 624-01 + MSISDN. */
  static final String BENEFICIARY_IMSI = "624010" + BENEFICIARY_MSISDN;

  private final AccountRepository accounts;
  private final HlrService hlr;
  private final Clock clock;

  public AccountBootstrap(AccountRepository accounts, HlrService hlr, Clock clock) {
    this.accounts = accounts;
    this.hlr = hlr;
    this.clock = clock;
  }

  public void ensureSeedAccounts() {
    accounts.insertIfAbsent(SENDER_MSISDN, SENDER_INITIAL_XAF, clock.instant());
    accounts.insertIfAbsent(BENEFICIARY_MSISDN, 0, clock.instant());
  }

  /**
   * Donne leur SIM au HLR aux deux comptes techniques, si le HLR ne leur en connaît pas : sans
   * elle, A5-00 (#57) refuse toute opération de l'émetteur avant {@code /evaluate}. Idempotent,
   * sans réattribution ni événement de changement d'IMSI : une SIM déjà connue n'est jamais
   * remplacée.
   *
   * <p>Appelé par les modes qui transigent comme ces comptes (service, transfer, ussd), PAS par les
   * modes de vérification hlr, otp et notification, qui posent eux-mêmes l'IMSI initial de leur
   * scénario.
   */
  // @implements ED-09
  public void ensureSeedSubscribers() {
    hlr.ensureSubscriber(SENDER_MSISDN, SENDER_IMSI);
    hlr.ensureSubscriber(BENEFICIARY_MSISDN, BENEFICIARY_IMSI);
  }
}
