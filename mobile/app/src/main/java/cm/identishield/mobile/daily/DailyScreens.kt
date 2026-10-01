package cm.identishield.mobile.daily

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cm.identishield.mobile.R
import cm.identishield.mobile.data.Account
import cm.identishield.mobile.data.DeferredOperation
import cm.identishield.mobile.data.Transaction
import cm.identishield.mobile.enrolment.BackHeader
import cm.identishield.mobile.enrolment.BodyText
import cm.identishield.mobile.enrolment.ErrorText
import cm.identishield.mobile.enrolment.Hairline
import cm.identishield.mobile.enrolment.HorizontalGap
import cm.identishield.mobile.enrolment.Keypad
import cm.identishield.mobile.enrolment.Page
import cm.identishield.mobile.enrolment.PinDots
import cm.identishield.mobile.enrolment.PrimaryButton
import cm.identishield.mobile.enrolment.Secondary
import cm.identishield.mobile.enrolment.SecondaryButton
import cm.identishield.mobile.enrolment.VerticalGap
import cm.identishield.mobile.enrolment.WarningBox
import cm.identishield.mobile.enrolment.formatMsisdn
import cm.identishield.mobile.enrolment.formatXaf
import cm.identishield.mobile.enrolment.maskMsisdn
import cm.identishield.mobile.ui.theme.Amber
import cm.identishield.mobile.ui.theme.Danger
import cm.identishield.mobile.ui.theme.Ink
import cm.identishield.mobile.ui.theme.Navy
import cm.identishield.mobile.ui.theme.ProtectedGreen
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Identifiants de test stables (MOB-NF-06). */
object DailyTags {
    const val UNLOCK = "E-10"
    const val HOME = "E-11"
    const val BENEFICIARY = "E-12"
    const val AMOUNT = "E-13"
    const val REVIEW = "E-14"
    const val AUTHORIZE = "E-15"
    const val SENT = "E-16"
    const val DEFERRED = "E-17"
    const val STOPPED = "E-18"
    const val HISTORY = "E-19"
}

/** Lignes « Dernières opérations » de l'accueil (maquette E-11). */
const val RECENT_OPERATIONS_SHOWN = 2

/** Bénéficiaires « récents » affichés en E-12 (maquette E-12). */
const val RECENT_BENEFICIARIES_SHOWN = 2

/** Garde technique de saisie : au-delà, le montant déborderait un entier long ; le serveur fait foi. */
const val MAX_AMOUNT_DIGITS = 12

private val FRENCH = Locale.FRENCH

/** « 2026-10-01T16:42:00Z » → « 1 oct. à 17:42 » (heure du téléphone) ; texte brut si illisible. */
fun formatWhen(
    iso: String,
    zone: ZoneId = ZoneId.systemDefault(),
): String = formatInstant(iso, zone, "d MMM 'à' HH:mm")

/** Date d'une ligne d'historique : « 25 sept. 14:07 ». */
fun formatHistoryDate(
    iso: String,
    zone: ZoneId = ZoneId.systemDefault(),
): String = formatInstant(iso, zone, "d MMM HH:mm")

private fun formatInstant(
    iso: String,
    zone: ZoneId,
    pattern: String,
): String =
    runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern(pattern, FRENCH)) }
        .getOrDefault(iso)

@Composable
private fun duration(seconds: Long): String =
    if (seconds < 60) {
        stringResource(R.string.duration_seconds, seconds.toInt())
    } else {
        stringResource(R.string.duration_minutes, ((seconds + 59) / 60).toInt())
    }

/** Texte d'un problème ; [onAuthorize] : sur E-15, un refus d'identifiants est un « PIN incorrect ». */
@Composable
fun problemText(
    problem: Problem,
    onAuthorize: Boolean = false,
): String =
    when (problem.error) {
        DailyError.CREDENTIALS_INVALID ->
            stringResource(if (onAuthorize) R.string.error_pin_incorrect else R.string.error_credentials_invalid)
        DailyError.THROTTLED ->
            problem.retryAfterSeconds?.let { stringResource(R.string.error_throttled, duration(it)) }
                ?: stringResource(R.string.error_throttled_later)
        DailyError.KEY_REVOKED -> stringResource(R.string.error_key_revoked)
        DailyError.KEY_INVALIDATED -> stringResource(R.string.error_key_invalidated)
        DailyError.ACCOUNT_FROZEN -> stringResource(R.string.error_account_frozen)
        DailyError.UNLOCK_CANCELLED -> stringResource(R.string.error_unlock_cancelled)
        DailyError.UNREACHABLE -> stringResource(R.string.error_unreachable)
        DailyError.SESSION_EXPIRED -> stringResource(R.string.error_session_ended)
        DailyError.INSUFFICIENT_FUNDS -> stringResource(R.string.error_insufficient_funds)
        DailyError.NUMBER_INVALID -> stringResource(R.string.error_number_invalid)
        DailyError.BENEFICIARY_UNKNOWN -> stringResource(R.string.error_beneficiary_unknown)
        DailyError.CANCEL_UNAVAILABLE -> stringResource(R.string.error_cancel_unavailable)
        DailyError.UNEXPECTED -> stringResource(R.string.error_unexpected)
    }

// ---- E-10 Déverrouillage -----------------------------------------------------------------------

/** Numéro mémorisé MASQUÉ ; ni nom ni badge « protégé » avant que le serveur ait vérifié (écarts). */
@Composable
fun UnlockScreen(
    step: DailyStep.Unlock,
    onUnlock: (String) -> Unit,
) {
    var pin by remember(step) { mutableStateOf("") }
    Page(DailyTags.UNLOCK) {
        VerticalGap(16)
        Image(
            painter = painterResource(R.drawable.logo_mark),
            contentDescription = stringResource(R.string.launch_logo_description),
            modifier =
                Modifier
                    .size(96.dp)
                    .align(Alignment.CenterHorizontally),
        )
        VerticalGap(16)
        Text(
            maskMsisdn(step.msisdn),
            fontSize = 20.sp,
            color = Secondary,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("masked-number"),
        )
        VerticalGap(16)
        Text(stringResource(R.string.unlock_pin), fontSize = 20.sp, color = Secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        VerticalGap(16)
        PinDots(pin.length)
        VerticalGap(16)
        BodyText(stringResource(R.string.unlock_then), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        step.problem?.let { ErrorText(problemText(it)) }
        Spacer(Modifier.weight(1f))
        Keypad(onDigit = { pin += it }, onDelete = { pin = pin.dropLast(1) })
        VerticalGap(12)
        PrimaryButton(stringResource(R.string.action_continue), { onUnlock(pin) }, enabled = pin.isNotEmpty())
    }
}

// ---- E-11 Accueil ------------------------------------------------------------------------------

/**
 * Titulaire, solde, badge « protégé » (session ouverte, donc liaison vérifiée par le serveur),
 * envois différés annulables, dernières opérations. Pas de bandeau d'alerte (M-08).
 */
@Composable
fun DailyHomeScreen(
    account: Account,
    deviceModel: String,
    transactions: List<Transaction> = emptyList(),
    problem: Problem? = null,
    onSend: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
    onDeferred: ((DeferredOperation) -> Unit)? = null,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Page(DailyTags.HOME) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            VerticalGap(16)
            BalanceCard(account, deviceModel)
            VerticalGap(16)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Tile(R.string.home_send, "↗", onSend, "tile-send", Modifier.weight(1f))
                Tile(R.string.home_history, "≡", onHistory, "tile-history", Modifier.weight(1f))
                Tile(R.string.home_alerts, "🔔", null, "tile-alerts", Modifier.weight(1f))
                Tile(R.string.home_security, "🛡", null, "tile-security", Modifier.weight(1f))
            }
            problem?.let { ErrorText(problemText(it)) }
            if (account.deferredOperations.isNotEmpty()) {
                VerticalGap(16)
                Card(R.string.home_deferred, "deferred") {
                    account.deferredOperations.forEach { op ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = onDeferred != null) { onDeferred?.invoke(op) }
                                    .padding(vertical = 10.dp)
                                    .testTag("deferred-${op.transferId}"),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.home_deferred_item, formatXaf(op.amountXaf), formatMsisdn(op.beneficiaryMsisdn)),
                                    color = Ink,
                                    fontSize = 16.sp,
                                )
                                Text(formatWhen(op.executeAt, zone), color = Secondary, fontSize = 14.sp)
                            }
                            Pill(stringResource(R.string.action_cancel), Danger)
                        }
                    }
                }
            }
            if (transactions.isNotEmpty()) {
                VerticalGap(16)
                Card(R.string.home_recent, "recent") {
                    transactions.take(RECENT_OPERATIONS_SHOWN).forEach { TransactionRow(it, zone) }
                }
            }
        }
        BottomNav(NavTab.HOME, onHome = null, onSend = onSend, onHistory = onHistory)
    }
}

@Composable
private fun BalanceCard(
    account: Account,
    deviceModel: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Navy)
                .padding(24.dp),
    ) {
        Text(stringResource(R.string.home_hello), color = Color.White.copy(alpha = 0.8f), fontSize = 17.sp)
        Text(account.holderName, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("holder"))
        Text(stringResource(R.string.home_number, formatMsisdn(account.msisdn)), color = Color.White.copy(alpha = 0.8f), fontSize = 17.sp)
        VerticalGap(16)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatXaf(account.balanceXaf), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("balance"))
            HorizontalGap(8)
            Text(stringResource(R.string.home_currency), color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(stringResource(R.string.home_balance), color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
        VerticalGap(16)
        // Vert : état vérifié par le serveur (session ouverte par signature) — règle de la palette.
        Text(
            stringResource(R.string.home_protected, deviceModel),
            color = ProtectedGreen,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(ProtectedGreen.copy(alpha = 0.15f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("protected"),
        )
    }
}

/** Tuile d'action ; sans action (null) elle est grisée : Alertes et Sécurité arrivent en M-08. */
@Composable
private fun Tile(
    label: Int,
    glyph: String,
    onClick: (() -> Unit)?,
    tag: String,
    modifier: Modifier,
) {
    val color = if (onClick != null) Navy else Secondary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .border(1.dp, Hairline, RoundedCornerShape(16.dp))
                .clickable(enabled = onClick != null) { onClick?.invoke() }
                .padding(vertical = 14.dp)
                .testTag(tag),
    ) {
        Text(glyph, fontSize = 26.sp, color = color)
        Text(stringResource(label), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

enum class NavTab { HOME, SEND, HISTORY }

/** Barre de navigation des maquettes (E-11, E-19). Alertes et Sécurité : inactives jusqu'à M-08. */
@Composable
fun BottomNav(
    selected: NavTab,
    onHome: (() -> Unit)?,
    onSend: (() -> Unit)?,
    onHistory: (() -> Unit)?,
) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Hairline),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("bottom-nav"),
        ) {
            NavItem("⌂", R.string.home_home, selected == NavTab.HOME, onHome, "nav-home", Modifier.weight(1f))
            NavItem("↗", R.string.home_send, selected == NavTab.SEND, onSend, "nav-send", Modifier.weight(1f))
            NavItem("≡", R.string.home_history, selected == NavTab.HISTORY, onHistory, "nav-history", Modifier.weight(1f))
            NavItem("🔔", R.string.home_alerts, false, null, "nav-alerts", Modifier.weight(1f))
            NavItem("🛡", R.string.home_security, false, null, "nav-security", Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavItem(
    glyph: String,
    label: Int,
    selected: Boolean,
    onClick: (() -> Unit)?,
    tag: String,
    modifier: Modifier,
) {
    val color = if (selected) Navy else Secondary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .clickable(enabled = onClick != null) { onClick?.invoke() }
                .testTag(tag),
    ) {
        Text(glyph, fontSize = 20.sp, color = color)
        Text(stringResource(label), fontSize = 12.sp, color = color, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun Card(
    title: Int?,
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .testTag(tag),
    ) {
        title?.let { Text(stringResource(it), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink) }
        content()
    }
}

@Composable
private fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier =
            modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Une opération : seulement les champs du contrat (nom ou numéro, date, sens, montant, statut). */
@Composable
private fun TransactionRow(
    t: Transaction,
    zone: ZoneId,
) {
    val outgoing = t.direction == DailyViewModel.DIRECTION_OUT
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .testTag("tx-${t.transferId}"),
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.counterpartyName ?: formatMsisdn(t.counterpartyMsisdn), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                formatHistoryDate(t.at, zone) + " · " + stringResource(if (outgoing) R.string.history_out else R.string.history_in),
                color = Secondary,
                fontSize = 14.sp,
            )
        }
        val badge =
            when (t.status) {
                "DEFERRED" -> R.string.status_deferred to Amber
                "CANCELLED" -> R.string.status_cancelled to Secondary
                "FROZEN" -> R.string.status_frozen to Danger
                "REJECTED" -> R.string.status_rejected to Danger
                else -> null
            }
        if (badge != null) {
            Pill(stringResource(badge.first), badge.second, Modifier.testTag("status-${t.transferId}"))
        } else {
            Text(
                (if (outgoing) "- " else "+ ") + formatXaf(t.amountXaf),
                color = if (outgoing) Ink else ProtectedGreen,
                fontSize = 18.sp,
            )
        }
    }
}

// ---- E-19 Historique ---------------------------------------------------------------------------

@Composable
fun HistoryScreen(
    step: DailyStep.History,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onSend: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Page(DailyTags.HISTORY) {
        BackHeader(stringResource(R.string.history_title), onBack)
        VerticalGap(12)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (step.items.isEmpty()) {
                BodyText(stringResource(R.string.history_empty))
            } else {
                Card(null, "history-list") { step.items.forEach { TransactionRow(it, zone) } }
            }
        }
        BottomNav(NavTab.HISTORY, onHome = onHome, onSend = onSend, onHistory = null)
    }
}

// ---- E-12 Bénéficiaire -------------------------------------------------------------------------

@Composable
fun BeneficiaryScreen(
    step: DailyStep.Beneficiary,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
) {
    var number by remember(step.recents) { mutableStateOf("") }
    Page(DailyTags.BENEFICIARY) {
        BackHeader(stringResource(R.string.send_title), onBack)
        VerticalGap(16)
        Text(stringResource(R.string.beneficiary_label), color = Secondary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        VerticalGap(8)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.dp, Hairline, RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(stringResource(R.string.beneficiary_prefix), fontSize = 20.sp, color = Ink)
            HorizontalGap(12)
            BasicTextField(
                value = number,
                onValueChange = { v -> number = v.filter { it.isDigit() }.take(DailyViewModel.NATIONAL_NUMBER_LENGTH) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 20.sp, color = Ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier =
                    Modifier
                        .weight(1f)
                        .testTag("beneficiary-input"),
            )
        }
        step.problem?.let { ErrorText(problemText(it)) }
        val recents = step.recents.take(RECENT_BENEFICIARIES_SHOWN)
        if (recents.isNotEmpty()) {
            VerticalGap(20)
            Text(stringResource(R.string.recents_label), color = Secondary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            VerticalGap(8)
            Card(null, "recents") {
                recents.forEach { t ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSubmit(t.counterpartyMsisdn) }
                                .padding(vertical = 10.dp)
                                .testTag("recent-${t.counterpartyMsisdn}"),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.counterpartyName ?: formatMsisdn(t.counterpartyMsisdn), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(formatMsisdn(t.counterpartyMsisdn), color = Secondary, fontSize = 15.sp)
                        }
                        Pill(stringResource(R.string.recent_known), Navy)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.action_continue), { onSubmit(number) }, enabled = number.isNotEmpty())
    }
}

// ---- E-13 Montant ------------------------------------------------------------------------------

@Composable
fun AmountScreen(
    step: DailyStep.Amount,
    quickAmounts: List<Long>,
    onSubmit: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var digits by remember(step) { mutableStateOf("") }
    val amount = digits.toLongOrNull() ?: 0
    Page(DailyTags.AMOUNT) {
        BackHeader(stringResource(R.string.amount_title), onBack)
        VerticalGap(12)
        Text(
            buildAnnotatedString {
                append(stringResource(R.string.amount_to))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(step.holder.holderName) }
                append(" · " + formatMsisdn(step.holder.msisdn))
            },
            color = Ink,
            fontSize = 17.sp,
        )
        VerticalGap(16)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            Text(formatXaf(amount), fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.testTag("amount"))
            HorizontalGap(8)
            Text(stringResource(R.string.home_currency), fontSize = 24.sp, color = Ink, modifier = Modifier.padding(bottom = 8.dp))
        }
        // Indicatif seulement : le serveur refuse un solde insuffisant (SPEC §7.4).
        if (amount in 1..step.balanceXaf) {
            Text(
                stringResource(R.string.amount_after, formatXaf(step.balanceXaf - amount)),
                color = Secondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("balance-after"),
            )
        }
        VerticalGap(12)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            quickAmounts.forEach { q ->
                Text(
                    formatXaf(q),
                    color = Ink,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White)
                            .border(1.dp, Hairline, RoundedCornerShape(50))
                            .clickable { digits = q.toString() }
                            .padding(vertical = 8.dp)
                            .testTag("quick-$q"),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Keypad(
            onDigit = { d -> if (digits.length < MAX_AMOUNT_DIGITS && !(digits.isEmpty() && d == '0')) digits += d },
            onDelete = { digits = digits.dropLast(1) },
        )
        VerticalGap(12)
        PrimaryButton(stringResource(R.string.action_continue), { onSubmit(amount) }, enabled = amount >= 1)
    }
}

// ---- E-14 Confirmation 🔒 ----------------------------------------------------------------------

/** Nom du titulaire (ED-11) et badge « nouveau bénéficiaire » ; aucune ligne de frais (écart). */
// @implements ED-11
@Composable
fun ReviewScreen(
    step: DailyStep.Confirm,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    Page(DailyTags.REVIEW, secure = true) {
        BackHeader(stringResource(R.string.review_title), onBack)
        VerticalGap(16)
        Card(null, "review-beneficiary") {
            Text(stringResource(R.string.review_beneficiary), color = Secondary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(step.holder.holderName, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("holder-name"))
            Text(stringResource(R.string.home_number, formatMsisdn(step.holder.msisdn)), color = Secondary, fontSize = 16.sp)
            if (step.holder.newBeneficiary) {
                VerticalGap(10)
                Pill(stringResource(R.string.review_new), Danger, Modifier.testTag("new-beneficiary"))
            }
        }
        VerticalGap(12)
        Card(null, "review-amount") {
            Text(stringResource(R.string.review_amount), color = Secondary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.review_amount_value, formatXaf(step.amountXaf)), color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        if (step.holder.newBeneficiary) {
            VerticalGap(12)
            WarningBox(content = {
                Text(
                    buildAnnotatedString {
                        append(stringResource(R.string.review_new_warning_before))
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.review_new_warning_key)) }
                        append(stringResource(R.string.review_new_warning_after))
                    },
                    color = Ink,
                    fontSize = 16.sp,
                )
            }, tag = "new-warning")
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.action_confirm), onConfirm, tag = "confirm")
        VerticalGap(8)
        Text(
            stringResource(R.string.action_cancel),
            color = Secondary,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCancel)
                    .padding(vertical = 12.dp)
                    .testTag("cancel"),
        )
    }
}

// ---- E-15 Autorisation 🔒 ----------------------------------------------------------------------

@Composable
fun AuthorizeScreen(
    step: DailyStep.Authorize,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
) {
    var pin by remember(step.problem, step.holder, step.amountXaf) { mutableStateOf("") }
    Page(DailyTags.AUTHORIZE, secure = true) {
        BackHeader(stringResource(R.string.authorize_title), onBack.takeUnless { step.busy })
        VerticalGap(8)
        Text(
            stringResource(R.string.authorize_summary, formatXaf(step.amountXaf), step.holder.holderName),
            color = Ink,
            fontSize = 18.sp,
            modifier = Modifier.testTag("summary"),
        )
        VerticalGap(12)
        Text(stringResource(R.string.authorize_step_pin), color = Secondary, fontSize = 18.sp)
        VerticalGap(12)
        PinDots(pin.length)
        VerticalGap(12)
        Card(null, "unlock-card") {
            Text(
                "🔐 " + stringResource(R.string.authorize_step_unlock),
                color = Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.authorize_step_unlock_body),
                color = Secondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        step.problem?.let { ErrorText(problemText(it, onAuthorize = true)) }
        Spacer(Modifier.weight(1f))
        Keypad(
            onDigit = { if (!step.busy) pin += it },
            onDelete = { if (!step.busy) pin = pin.dropLast(1) },
        )
        VerticalGap(12)
        PrimaryButton(stringResource(R.string.action_continue), { onSubmit(pin) }, enabled = pin.isNotEmpty() && !step.busy)
    }
}

// ---- E-16 Succès -------------------------------------------------------------------------------

/** Reçu : référence = transferId du serveur. Aucune date fabriquée par le téléphone (écart). */
@Composable
fun SentScreen(
    step: DailyStep.Sent,
    deviceModel: String,
    onHome: () -> Unit,
) {
    Page(DailyTags.SENT) {
        Spacer(Modifier.weight(1f))
        ResultIcon("✓", ProtectedGreen)
        VerticalGap(20)
        CenteredTitle(stringResource(R.string.sent_title), 24)
        Text(
            stringResource(R.string.review_amount_value, formatXaf(step.amountXaf)),
            color = Ink,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        BodyText(
            stringResource(R.string.sent_to, step.holder.holderName, formatMsisdn(step.holder.msisdn)),
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalGap(20)
        Card(null, "receipt") {
            ReceiptLine(stringResource(R.string.receipt_reference), step.transferId, "reference")
            ReceiptLine(stringResource(R.string.receipt_signed_by), stringResource(R.string.receipt_this_phone, deviceModel), "signed-by")
        }
        VerticalGap(20)
        PrimaryButton(stringResource(R.string.action_home), onHome, tag = "home")
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ReceiptLine(
    label: String,
    value: String,
    tag: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
    ) {
        Text(label, color = Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(value, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun ColumnScope.ResultIcon(
    glyph: String,
    color: Color,
) {
    Box(
        Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .align(Alignment.CenterHorizontally),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = 44.sp, color = color) }
}

@Composable
private fun CenteredTitle(
    text: String,
    size: Int,
) = Text(
    text,
    color = Ink,
    fontSize = size.sp,
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center,
    modifier =
        Modifier
            .fillMaxWidth()
            .testTag("title"),
)

// ---- E-17 Exécution différée -------------------------------------------------------------------

/** L'heure d'échéance donnée par le serveur ; jamais de décompte calculé sur l'horloge (écart). */
@Composable
fun DeferredScreen(
    step: DailyStep.Deferred,
    onCancel: () -> Unit,
    onHome: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Page(DailyTags.DEFERRED) {
        Spacer(Modifier.weight(1f))
        ResultIcon("⏱", Amber)
        VerticalGap(20)
        CenteredTitle(stringResource(R.string.deferred_title), 26)
        VerticalGap(12)
        Text(
            step.executeAt?.let { stringResource(R.string.deferred_body, formatWhen(it, zone)) }
                ?: stringResource(R.string.deferred_body_unknown_time),
            color = Ink,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("deferred-body"),
        )
        VerticalGap(8)
        BodyText(stringResource(R.string.deferred_can_cancel), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        VerticalGap(16)
        Card(null, "deferred-card") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_deferred_item, formatXaf(step.amountXaf), step.holderName ?: formatMsisdn(step.msisdn)),
                    color = Ink,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f),
                )
                if (step.cancelled) {
                    Pill(stringResource(R.string.status_cancelled), Secondary, Modifier.testTag("cancelled"))
                } else {
                    Pill(stringResource(R.string.deferred_pending), Amber)
                }
            }
        }
        step.problem?.let {
            ErrorText(if (it.error == DailyError.UNREACHABLE) stringResource(R.string.error_cancel_unconfirmed) else problemText(it))
        }
        VerticalGap(16)
        if (step.cancelled) {
            BodyText(stringResource(R.string.deferred_cancelled), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            DangerButton(stringResource(R.string.deferred_cancel), onCancel)
        }
        VerticalGap(8)
        Text(
            stringResource(R.string.action_home),
            color = Secondary,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onHome)
                    .padding(vertical = 12.dp)
                    .testTag("home"),
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun DangerButton(
    text: String,
    onClick: () -> Unit,
) {
    Text(
        text,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Danger)
                .clickable(onClick = onClick)
                .padding(vertical = 16.dp)
                .testTag("cancel-transfer"),
    )
}

// ---- E-18 Bloquée / Rejetée --------------------------------------------------------------------

/** Message par code de raison ; « depuis votre téléphone lié » réservé au cas de la clé révoquée. */
@Composable
fun StoppedScreen(
    step: DailyStep.Stopped,
    onHome: () -> Unit,
    onHistory: () -> Unit,
) {
    Page(DailyTags.STOPPED) {
        Spacer(Modifier.weight(1f))
        ResultIcon(if (step.outcome == Outcome.UNKNOWN) "?" else "✕", if (step.outcome == Outcome.UNKNOWN) Amber else Danger)
        VerticalGap(20)
        when (step.outcome) {
            Outcome.FROZEN -> {
                CenteredTitle(stringResource(R.string.frozen_title), 26)
                VerticalGap(12)
                BodyText(stringResource(R.string.frozen_body), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                VerticalGap(16)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.frozen_what)) }
                        append(stringResource(R.string.frozen_what_body))
                    },
                    color = Danger,
                    fontSize = 16.sp,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Danger.copy(alpha = 0.08f))
                            .border(1.dp, Danger.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                            .testTag("what-to-do"),
                )
            }
            Outcome.REJECTED, Outcome.REJECTED_KEY -> {
                CenteredTitle(stringResource(R.string.rejected_title), 26)
                VerticalGap(12)
                BodyText(
                    stringResource(if (step.outcome == Outcome.REJECTED_KEY) R.string.rejected_key_body else R.string.rejected_body),
                    align = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("stopped-body"),
                )
            }
            Outcome.UNKNOWN -> {
                CenteredTitle(stringResource(R.string.unknown_title), 26)
                VerticalGap(12)
                BodyText(stringResource(R.string.unknown_body), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        VerticalGap(20)
        if (step.outcome == Outcome.UNKNOWN) {
            PrimaryButton(stringResource(R.string.action_history), onHistory, tag = "history")
            VerticalGap(8)
            SecondaryButton(stringResource(R.string.action_home), onHome, tag = "home")
        } else {
            PrimaryButton(stringResource(R.string.action_home), onHome, tag = "home")
        }
        Spacer(Modifier.weight(1f))
    }
}
