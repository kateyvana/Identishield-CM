package cm.identishield.core.account;

import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lecture du solde d'un compte (EF-01), sur le réseau interne isc-net. {@code GET
 * /account?msisdn=...} renvoie le solde courant — l'observation dont l'outil attaquant a besoin
 * pour constater le solde initial puis le solde vidé (l'oracle d'AC-02). Lecture seule.
 */
// @implements EF-01
@RestController
public class AccountController {

  private final AccountRepository accounts;

  public AccountController(AccountRepository accounts) {
    this.accounts = accounts;
  }

  @GetMapping("/account")
  public ResponseEntity<Map<String, Object>> balance(@RequestParam String msisdn) {
    Optional<Account> account = accounts.findByMsisdn(msisdn);
    if (account.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", "compte inconnu : " + msisdn));
    }
    Account a = account.get();
    return ResponseEntity.ok(Map.of("msisdn", a.msisdn(), "balanceXaf", a.balanceXaf()));
  }
}
