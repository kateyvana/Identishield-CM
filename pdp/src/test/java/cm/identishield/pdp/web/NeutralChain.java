package cm.identishield.pdp.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.audit.AuthorizedHistory;
import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.rules.RuleEngine;
import cm.identishield.pdp.rules.Score;
import cm.identishield.pdp.signature.SignatureVerifier;
import cm.identishield.pdp.trust.DecisionEnforcer;
import cm.identishield.pdp.trust.ProbationVeto;
import cm.identishield.pdp.trust.TrustStateMachine;
import cm.identishield.pdp.trust.TrustStateMachine.State;
import cm.identishield.pdp.trust.TrustStateMachine.Trust;
import java.time.Clock;
import java.util.List;

/**
 * Chaîne de décision neutre pour les tests du contrôleur antérieurs à la carte A4 (idempotence,
 * signature) : compte TRUSTED, historique vide, score 0 ALLOW. Ces tests vérifient ce qui précède
 * l'état du compte et les règles ; la chaîne complète est vérifiée par EvaluateChainTest.
 */
final class NeutralChain {

  private NeutralChain() {}

  static EvaluateController controller(
      AuditLog auditLog, Clock clock, RuleSet ruleSet, SignatureVerifier verifier) {
    TrustStateMachine trust = mock(TrustStateMachine.class);
    when(trust.observed(anyString(), anyString(), any(), anyLong()))
        .thenAnswer(
            inv ->
                new Trust(
                    inv.getArgument(0),
                    State.TRUSTED,
                    null,
                    inv.getArgument(1),
                    inv.getArgument(1),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
    AuthorizedHistory history = mock(AuthorizedHistory.class);
    when(history.of(anyString())).thenReturn(List.of());
    RuleEngine engine = mock(RuleEngine.class);
    when(engine.score(any(), any())).thenReturn(new Score(0, Score.Decision.ALLOW, List.of()));
    return new EvaluateController(
        auditLog,
        clock,
        ruleSet,
        verifier,
        trust,
        history,
        engine,
        mock(ProbationVeto.class),
        mock(DecisionEnforcer.class));
  }
}
