package cm.identishield.core.account;

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

  private final AccountRepository accounts;
  private final Clock clock;

  public AccountBootstrap(AccountRepository accounts, Clock clock) {
    this.accounts = accounts;
    this.clock = clock;
  }

  public void ensureSeedAccounts() {
    accounts.insertIfAbsent(SENDER_MSISDN, SENDER_INITIAL_XAF, clock.instant());
    accounts.insertIfAbsent(BENEFICIARY_MSISDN, 0, clock.instant());
  }
}
