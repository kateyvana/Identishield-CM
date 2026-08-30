package cm.identishield.core.transaction;

import java.util.UUID;

/** Transaction créée à PENDING_DECISION, avec son decisionId (généré en base). */
public record PendingTransaction(UUID id, String decisionId) {}
