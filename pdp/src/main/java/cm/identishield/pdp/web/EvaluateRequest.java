package cm.identishield.pdp.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Requête POST /evaluate (EF-10, contrats/evaluate.yaml). Les contraintes rendent une requête hors
 * contrat en 400 (CONTRACT_VIOLATION). MSISDN dans la plage fictive (ED-09), montant entier ≥ 1
 * (ED-05), horodatage serveur UTC avec Z (ED-04), decisionId = UUID v4 clé d'idempotence (ENF-03).
 */
public record EvaluateRequest(
    @NotBlank
        @Pattern(
            regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            message = "decisionId must be a v4 UUID")
        String decisionId,
    @NotBlank String accountId,
    @NotBlank @Pattern(regexp = "^67700[0-9]{4}$", message = "msisdn out of fictitious range")
        String msisdn,
    @NotBlank String currentImsi,
    @NotBlank @Pattern(regexp = "APP|USSD") String channel,
    @NotBlank @Pattern(regexp = "P2P|CASH_OUT|MERCHANT|BILL|AIRTIME|CASH_IN") String type,
    @Min(value = 1, message = "amountXaf must be a positive integer") long amountXaf,
    @NotBlank String beneficiaryId,
    @NotBlank @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$")
        String serverTimestamp,
    String localityCellId,
    String deviceKeyId,
    String signature,
    @Pattern(regexp = "^[0-9a-f]{32}$", message = "nonce must be 128-bit hex") String nonce,
    @NotBlank @Pattern(regexp = "SIGNATURE|NONE") String proofType,
    // A4 (V4) : solde au moment de la demande ; fige le solde d'entrée en PROBATION, refuse une
    // sortie qui le dépasse ; n'entre dans aucune règle de score.
    @NotNull @Min(value = 0, message = "balanceXaf must be a non-negative integer") Long balanceXaf,
    // A4 (Q4) : date du dernier changement d'IMSI (historique HLR, EF-08) ; absent = aucun connu.
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$")
        String imsiChangedAt) {}
