package cm.identishield.pdp.audit;

import cm.identishield.pdp.rules.Operation;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Historique d'un compte servant aux règles (carte A4), relu dans le journal d'audit DU PDP (V2 :
 * aucune table du cœur, ED-01). Lecture seule : seul {@link AuditLog} écrit le journal.
 *
 * <p>RÈGLE DE SÉCURITÉ (V3) : seules les décisions ALLOW en font partie. Une tentative refusée,
 * gelée ou mise en STEP_UP n'y entre jamais : sinon un attaquant « blanchirait » un bénéficiaire
 * (il deviendrait connu) par une première tentative refusée, et fausserait la vélocité et la
 * médiane de THR_HIGH. Les entrées sans décision (NONCE_REUSED, TRUST_*, REENROL_FROZEN) sont
 * écartées par le même filtre.
 *
 * <p>HYPOTHÈSE explicite : le sens de l'opération se déduit de son type — CASH_IN est une entrée,
 * tous les autres types (P2P, CASH_OUT, MERCHANT, BILL, AIRTIME) sont des sorties, comme dans le
 * cœur ({@code OperationType.isOutflow}). L'instant est celui de l'évaluation par le PDP.
 *
 * <p>Limites déclarées (acceptées) : une opération STEP_UP différée puis exécutée à l'échéance, et
 * une opération exécutée en mode dégradé (PDP indisponible), n'y figurent pas ; le bénéficiaire
 * reste alors « nouveau » (direction prudente).
 */
@Component
public class AuthorizedHistory {

  static final String CASH_IN = "CASH_IN";

  static final String SQL =
      "SELECT payload ->> 'beneficiaryId', (payload ->> 'amountXaf')::bigint, payload ->> 'type',"
          + " payload ->> 'evaluatedAt' FROM pdp.audit_entry"
          + " WHERE payload ->> 'accountId' = ? AND payload ->> 'decision' = 'ALLOW'"
          + " ORDER BY chain_index";

  private final JdbcTemplate jdbc;

  public AuthorizedHistory(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Opérations autorisées du compte, dans l'ordre de la chaîne d'audit. */
  public List<Operation> of(String accountId) {
    return jdbc.query(
        SQL,
        (rs, i) ->
            new Operation(
                rs.getString(1),
                rs.getLong(2),
                !CASH_IN.equals(rs.getString(3)),
                Instant.parse(rs.getString(4))),
        accountId);
  }
}
