package cm.identishield.core.account;

import java.util.UUID;

/**
 * Compte du cœur, identifié par MSISDN (plage fictive, ED-09), avec solde entier FCFA (ED-05).
 * L'état de confiance (EF-01) est au mois 4 (ISC-SKEL §3 : aucune machine à états de confiance au
 * mois 2).
 */
public record Account(UUID id, String msisdn, long balanceXaf) {}
