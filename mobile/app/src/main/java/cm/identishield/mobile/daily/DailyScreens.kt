package cm.identishield.mobile.daily

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import cm.identishield.mobile.R
import cm.identishield.mobile.data.Account
import cm.identishield.mobile.data.Alert
import cm.identishield.mobile.data.DeferredOperation
import cm.identishield.mobile.data.Transaction
import cm.identishield.mobile.enrolment.AmountText
import cm.identishield.mobile.enrolment.BodyText
import cm.identishield.mobile.enrolment.Callout
import cm.identishield.mobile.enrolment.CenteredInput
import cm.identishield.mobile.enrolment.DangerButton
import cm.identishield.mobile.enrolment.Disc
import cm.identishield.mobile.enrolment.ErrorText
import cm.identishield.mobile.enrolment.HairlineDivider
import cm.identishield.mobile.enrolment.HorizontalGap
import cm.identishield.mobile.enrolment.InputBox
import cm.identishield.mobile.enrolment.Keypad
import cm.identishield.mobile.enrolment.Page
import cm.identishield.mobile.enrolment.Pill
import cm.identishield.mobile.enrolment.PinDots
import cm.identishield.mobile.enrolment.PrimaryButton
import cm.identishield.mobile.enrolment.SecondaryButton
import cm.identishield.mobile.enrolment.SectionLabel
import cm.identishield.mobile.enrolment.SurfaceCard
import cm.identishield.mobile.enrolment.TextAction
import cm.identishield.mobile.enrolment.Title
import cm.identishield.mobile.enrolment.VerticalGap
import cm.identishield.mobile.enrolment.formatMsisdn
import cm.identishield.mobile.enrolment.formatXaf
import cm.identishield.mobile.enrolment.maskMsisdn
import cm.identishield.mobile.ui.theme.Amber
import cm.identishield.mobile.ui.theme.AmberText
import cm.identishield.mobile.ui.theme.AmountType
import cm.identishield.mobile.ui.theme.Background
import cm.identishield.mobile.ui.theme.Border
import cm.identishield.mobile.ui.theme.DISABLED_ALPHA
import cm.identishield.mobile.ui.theme.Danger
import cm.identishield.mobile.ui.theme.Dimens
import cm.identishield.mobile.ui.theme.Ink
import cm.identishield.mobile.ui.theme.Navy
import cm.identishield.mobile.ui.theme.NavyTint
import cm.identishield.mobile.ui.theme.PillShape
import cm.identishield.mobile.ui.theme.ProtectedGreen
import cm.identishield.mobile.ui.theme.Spacing
import cm.identishield.mobile.ui.theme.Surface
import cm.identishield.mobile.ui.theme.TextSecondary
import cm.identishield.mobile.ui.theme.Tone
import cm.identishield.mobile.ui.useNavigationRail
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

/** Masque du solde caché (œil barré) : aucune information sur le montant. */
private const val HIDDEN_BALANCE = "••••••"

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
        VerticalGap(Spacing.L)
        Image(
            painter = painterResource(R.drawable.logo_mark),
            contentDescription = stringResource(R.string.launch_logo_description),
            modifier =
                Modifier
                    .size(Dimens.Disc)
                    .align(Alignment.CenterHorizontally),
        )
        VerticalGap(Spacing.L)
        Text(
            maskMsisdn(step.msisdn),
            style = MaterialTheme.typography.titleLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("masked-number"),
        )
        VerticalGap(Spacing.L)
        Text(
            stringResource(R.string.unlock_pin),
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalGap(Spacing.L)
        PinDots(pin.length)
        VerticalGap(Spacing.L)
        BodyText(stringResource(R.string.unlock_then), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        step.problem?.let { ErrorText(problemText(it)) }
        Spacer(Modifier.weight(1f))
        Keypad(onDigit = { pin += it }, onDelete = { pin = pin.dropLast(1) })
        VerticalGap(Spacing.M)
        PrimaryButton(stringResource(R.string.action_continue), { onUnlock(pin) }, enabled = pin.isNotEmpty())
    }
}

// ---- Navigation principale ---------------------------------------------------------------------

enum class NavTab { HOME, SEND, HISTORY, ALERTS }

private class NavDestination(
    val icon: Int,
    val label: Int,
    val selected: Boolean,
    val onClick: (() -> Unit)?,
    val tag: String,
) {
    /** L'onglet courant reste actif ; un onglet sans action est INDISPONIBLE (jamais un lien mort). */
    val enabled get() = selected || onClick != null
}

/**
 * Navigation des maquettes (E-11, E-19, E-23) : barre basse en largeur compacte, rail latéral au-delà
 * ([useNavigationRail]). Cinq destinations ; Sécurité (E-25, hors périmètre) et toute destination sans
 * action sont indisponibles : opacité réduite, aucun effet au toucher, annoncées « désactivées ».
 */
@Composable
fun NavScaffold(
    selected: NavTab,
    onHome: (() -> Unit)?,
    onSend: (() -> Unit)?,
    onHistory: (() -> Unit)?,
    onAlerts: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val destinations =
        listOf(
            NavDestination(R.drawable.ic_home, R.string.home_home, selected == NavTab.HOME, onHome, "nav-home"),
            NavDestination(R.drawable.ic_send, R.string.home_send, selected == NavTab.SEND, onSend, "nav-send"),
            NavDestination(R.drawable.ic_history, R.string.home_history, selected == NavTab.HISTORY, onHistory, "nav-history"),
            NavDestination(R.drawable.ic_alerts, R.string.home_alerts, selected == NavTab.ALERTS, onAlerts, "nav-alerts"),
            NavDestination(R.drawable.ic_security, R.string.home_security, false, null, "nav-security"),
        )
    if (useNavigationRail()) {
        Row(
            Modifier
                .fillMaxSize()
                .background(Background),
        ) {
            Column(Modifier.width(IntrinsicSize.Max).background(Surface)) {
                // Sous la barre d'état : bleu nuit, comme le bandeau voisin (icônes système claires lisibles).
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars)
                        .background(Navy),
                )
                NavigationRail(
                    containerColor = Surface,
                    windowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Start),
                    modifier = Modifier.testTag("nav-rail"),
                ) {
                    destinations.forEach { d ->
                        NavigationRailItem(
                            selected = d.selected,
                            onClick = d.onClick ?: {},
                            enabled = d.enabled,
                            icon = { Icon(painterResource(d.icon), contentDescription = null) },
                            label = { Text(stringResource(d.label), style = MaterialTheme.typography.labelSmall) },
                            colors =
                                NavigationRailItemDefaults.colors(
                                    selectedIconColor = Navy,
                                    selectedTextColor = Navy,
                                    indicatorColor = NavyTint,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary,
                                ),
                            modifier = Modifier.testTag(d.tag),
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) { content() }
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .background(Background),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .consumeWindowInsets(WindowInsets.navigationBars),
            ) { content() }
            NavigationBar(containerColor = Surface, modifier = Modifier.testTag("bottom-nav")) {
                destinations.forEach { d ->
                    NavigationBarItem(
                        selected = d.selected,
                        onClick = d.onClick ?: {},
                        enabled = d.enabled,
                        icon = { Icon(painterResource(d.icon), contentDescription = null) },
                        label = { Text(stringResource(d.label), style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                        colors =
                            NavigationBarItemDefaults.colors(
                                selectedIconColor = Navy,
                                selectedTextColor = Navy,
                                indicatorColor = NavyTint,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                            ),
                        modifier = Modifier.testTag(d.tag),
                    )
                }
            }
        }
    }
}

// ---- E-11 Accueil ------------------------------------------------------------------------------

/**
 * Titulaire, solde, badge « protégé » (session ouverte, donc liaison vérifiée par le serveur),
 * envois différés annulables, dernières opérations ; bandeau et compteur des alertes à traiter (M-08).
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
    pendingAlerts: List<Alert> = emptyList(),
    onAlerts: (() -> Unit)? = null,
    onAlert: ((Alert) -> Unit)? = null,
) {
    NavScaffold(NavTab.HOME, onHome = null, onSend = onSend, onHistory = onHistory, onAlerts = onAlerts) {
        Page(DailyTags.HOME, hero = { HomeHero(account, deviceModel, onSend, onHistory, onAlerts, pendingAlerts.size) }) {
            problem?.let { ErrorText(problemText(it)) }
            pendingAlerts.firstOrNull()?.let { alert ->
                VerticalGap(Spacing.S)
                AlertBanner(alert) { onAlert?.invoke(alert) }
            }
            if (account.deferredOperations.isNotEmpty()) {
                VerticalGap(Spacing.XL)
                SectionTitle(stringResource(R.string.home_deferred))
                account.deferredOperations.forEach { op ->
                    VerticalGap(Spacing.S)
                    SurfaceCard(
                        Modifier
                            .clickable(enabled = onDeferred != null) { onDeferred?.invoke(op) }
                            .testTag("deferred-${op.transferId}"),
                    ) {
                        Text(
                            stringResource(R.string.home_deferred_item, formatXaf(op.amountXaf), formatMsisdn(op.beneficiaryMsisdn)),
                            color = Navy,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                formatWhen(op.executeAt, zone),
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            HorizontalGap(Spacing.S)
                            Pill(stringResource(R.string.action_cancel), Tone.DANGER)
                        }
                    }
                }
            }
            if (transactions.isNotEmpty()) {
                VerticalGap(Spacing.XL)
                SectionTitle(stringResource(R.string.home_recent))
                transactions.take(RECENT_OPERATIONS_SHOWN).forEach {
                    VerticalGap(Spacing.S)
                    TransactionCard(it, zone)
                }
            }
        }
    }
}

/**
 * En-tête bleu nuit pleine largeur (sous la barre d'état, bord bas arrondi) portant le nom de
 * l'application ; la carte du solde en chevauche le bas.
 */
@Composable
private fun HomeHero(
    account: Account,
    deviceModel: String,
    onSend: (() -> Unit)?,
    onHistory: (() -> Unit)?,
    onAlerts: (() -> Unit)?,
    pendingAlerts: Int,
) {
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = Spacing.XXL, bottomEnd = Spacing.XXL))
                .background(Navy)
                .statusBarsPadding()
                .padding(bottom = Dimens.HeroOverlap)
                .heightIn(min = Dimens.HeaderBar),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Surface,
            )
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = Dimens.HeaderBar)
                .widthIn(max = Dimens.ContentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = Spacing.XL),
        ) { BalanceCard(account, deviceModel, onSend, onHistory, onAlerts, pendingAlerts) }
    }
}

@Composable
private fun BalanceCard(
    account: Account,
    deviceModel: String,
    onSend: (() -> Unit)?,
    onHistory: (() -> Unit)?,
    onAlerts: (() -> Unit)?,
    pendingAlerts: Int,
) {
    // Visible à l'ouverture ; le choix n'est jamais enregistré (décision B-UI, point 10).
    var hidden by rememberSaveable { mutableStateOf(false) }
    val hiddenLabel = stringResource(R.string.balance_hidden)
    SurfaceCard(Modifier.testTag("balance-card"), contentPadding = PaddingValues(top = Spacing.L)) {
        Column(Modifier.padding(horizontal = Spacing.L)) {
            Text(stringResource(R.string.home_wallet), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            VerticalGap(Spacing.XS)
            Text(account.holderName, style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.testTag("holder"))
            Text(stringResource(R.string.home_number, formatMsisdn(account.msisdn)), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            VerticalGap(Spacing.M)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                    if (hidden) {
                        AmountText(
                            HIDDEN_BALANCE,
                            AmountType.Hero,
                            Navy,
                            Modifier
                                .weight(1f, fill = false)
                                .semantics { contentDescription = hiddenLabel }
                                .testTag("balance"),
                        )
                    } else {
                        AmountText(
                            formatXaf(account.balanceXaf),
                            AmountType.Hero,
                            Navy,
                            Modifier
                                .weight(1f, fill = false)
                                .testTag("balance"),
                            fit = true,
                        )
                    }
                    HorizontalGap(Spacing.S)
                    Text(
                        stringResource(R.string.home_currency),
                        style = AmountType.Currency,
                        color = Navy,
                        modifier = Modifier.padding(bottom = Spacing.XS),
                    )
                }
                IconButton(onClick = { hidden = !hidden }, modifier = Modifier.testTag("balance-toggle")) {
                    Icon(
                        painterResource(if (hidden) R.drawable.ic_visibility else R.drawable.ic_visibility_off),
                        contentDescription = stringResource(if (hidden) R.string.balance_show else R.string.balance_hide),
                        tint = TextSecondary,
                    )
                }
            }
            Text(stringResource(R.string.home_balance), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            VerticalGap(Spacing.M)
            // Vert : état vérifié par le serveur (session ouverte par signature) — règle de la palette.
            Pill(stringResource(R.string.home_protected, deviceModel), Tone.VERIFIED, Modifier.testTag("protected"))
            VerticalGap(Spacing.L)
        }
        HairlineDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            HomeAction(R.drawable.ic_send, stringResource(R.string.home_send), 0, onSend, "tile-send")
            VerticalRule()
            HomeAction(R.drawable.ic_history, stringResource(R.string.home_history), 0, onHistory, "tile-history")
            VerticalRule()
            HomeAction(R.drawable.ic_alerts, stringResource(R.string.home_alerts), pendingAlerts, onAlerts, "tile-alerts")
        }
    }
}

/** Action de la carte du solde : icône au-dessus, libellé dessous ; sans action, indisponible. */
@Composable
private fun RowScope.HomeAction(
    icon: Int,
    label: String,
    count: Int,
    onClick: (() -> Unit)?,
    tag: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .weight(1f)
                .clickable(enabled = onClick != null) { onClick?.invoke() }
                .alpha(if (onClick != null) 1f else DISABLED_ALPHA)
                .heightIn(min = Dimens.TouchTarget)
                .padding(vertical = Spacing.M)
                .testTag(tag),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Navy)
        VerticalGap(Spacing.XS)
        Text(
            buildAnnotatedString {
                append(label)
                if (count > 0) withStyle(SpanStyle(color = Danger)) { append(" $count") }
            },
            style = MaterialTheme.typography.labelMedium,
            color = Navy,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VerticalRule() =
    Box(
        Modifier
            .padding(vertical = Spacing.M)
            .width(Dimens.Hairline)
            .fillMaxHeight()
            .background(Border),
    )

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, color = Ink)

/** Bandeau d'alerte de la maquette E-11 : résumé du serveur, jamais de lien ni de code (MOB-S-15). */
@Composable
private fun AlertBanner(
    alert: Alert,
    onClick: () -> Unit,
) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.alert_banner_title)) }
            append(" · " + alert.summary + " ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.alert_banner_see)) }
        },
        color = Ink,
        style = MaterialTheme.typography.bodyMedium,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(Tone.WARNING.fill)
                .border(Dimens.Hairline, Tone.WARNING.stroke, MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .padding(Spacing.L)
                .testTag("alert-banner"),
    )
}

/**
 * Une opération, une carte : seulement les champs du contrat (nom ou numéro, date, sens, montant,
 * statut). Montant signé en encre ; refusé (bloqué, rejeté) en rouge ; jamais en vert.
 */
@Composable
private fun TransactionCard(
    t: Transaction,
    zone: ZoneId,
) {
    val outgoing = t.direction == DailyViewModel.DIRECTION_OUT
    val badge =
        when (t.status) {
            "DEFERRED" -> R.string.status_deferred to Tone.WARNING
            "CANCELLED" -> R.string.status_cancelled to Tone.MUTED
            "FROZEN" -> R.string.status_frozen to Tone.DANGER
            "REJECTED" -> R.string.status_rejected to Tone.DANGER
            else -> null
        }
    SurfaceCard(Modifier.testTag("tx-${t.transferId}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    t.counterpartyName ?: formatMsisdn(t.counterpartyMsisdn),
                    color = Navy,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    formatHistoryDate(t.at, zone) + " · " + stringResource(if (outgoing) R.string.history_out else R.string.history_in),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalGap(Spacing.S)
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    val refused = badge?.second == Tone.DANGER
                    AmountText((if (outgoing) "- " else "+ ") + formatXaf(t.amountXaf), AmountType.Row, if (refused) Danger else Ink)
                    HorizontalGap(Spacing.XS)
                    Text(stringResource(R.string.home_currency), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                if (badge != null) {
                    VerticalGap(Spacing.XS)
                    Pill(stringResource(badge.first), badge.second, Modifier.testTag("status-${t.transferId}"))
                }
            }
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
    NavScaffold(NavTab.HISTORY, onHome = onHome, onSend = onSend, onHistory = null) {
        Page(DailyTags.HISTORY, title = stringResource(R.string.history_title), onBack = onBack) {
            if (step.items.isEmpty()) {
                BodyText(stringResource(R.string.history_empty))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.S), modifier = Modifier.testTag("history-list")) {
                    step.items.forEach { TransactionCard(it, zone) }
                }
            }
        }
    }
}

// ---- E-12 Bénéficiaire -------------------------------------------------------------------------

@Composable
fun BeneficiaryScreen(
    step: DailyStep.Beneficiary,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
    onDraft: (String) -> Unit = {},
) {
    var number by remember(step.recents) { mutableStateOf(step.draft) } // D4 : restitué après reconnexion
    val focus = remember { FocusRequester() }
    Page(DailyTags.BENEFICIARY, title = stringResource(R.string.send_title), onBack = onBack) {
        InputBox(stringResource(R.string.beneficiary_label), field = focus) {
            Text(stringResource(R.string.beneficiary_prefix), style = MaterialTheme.typography.titleLarge, color = TextSecondary)
            HorizontalGap(Spacing.M)
            BasicTextField(
                value = number,
                onValueChange = { v ->
                    number = v.filter { it.isDigit() }.take(DailyViewModel.NATIONAL_NUMBER_LENGTH)
                    onDraft(number)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = Ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(focus)
                        .testTag("beneficiary-input"),
                decorationBox = CenteredInput,
            )
        }
        step.problem?.let { ErrorText(problemText(it)) }
        val recents = step.recents.take(RECENT_BENEFICIARIES_SHOWN)
        if (recents.isNotEmpty()) {
            VerticalGap(Spacing.XL)
            SectionLabel(stringResource(R.string.recents_label))
            recents.forEach { t ->
                VerticalGap(Spacing.S)
                SurfaceCard(
                    Modifier
                        .clickable { onSubmit(t.counterpartyMsisdn) }
                        .testTag("recent-${t.counterpartyMsisdn}"),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.counterpartyName ?: formatMsisdn(t.counterpartyMsisdn), color = Navy, style = MaterialTheme.typography.titleMedium)
                            Text(formatMsisdn(t.counterpartyMsisdn), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Pill(stringResource(R.string.recent_known), Tone.NEUTRAL)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        VerticalGap(Spacing.L)
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
    onDraft: (String) -> Unit = {},
) {
    var digits by remember(step.holder, step.balanceXaf) { mutableStateOf(step.draft) } // D4
    val amount = digits.toLongOrNull() ?: 0
    Page(DailyTags.AMOUNT, title = stringResource(R.string.amount_title), onBack = onBack) {
        Text(
            buildAnnotatedString {
                append(stringResource(R.string.amount_to))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(step.holder.holderName) }
                append(" · " + formatMsisdn(step.holder.msisdn))
            },
            color = Ink,
            style = MaterialTheme.typography.bodyLarge,
        )
        VerticalGap(Spacing.L)
        InputBox(stringResource(R.string.amount_label)) {
            AmountText(
                formatXaf(amount),
                AmountType.Hero,
                Ink,
                Modifier
                    .weight(1f, fill = false)
                    .testTag("amount"),
                fit = true,
            )
            HorizontalGap(Spacing.S)
            Text(stringResource(R.string.home_currency), style = AmountType.Currency, color = Ink)
        }
        VerticalGap(Spacing.XS)
        Text(
            stringResource(R.string.amount_available, formatXaf(step.balanceXaf)),
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("balance-available"),
        )
        // Indicatif seulement : le serveur refuse un solde insuffisant (SPEC §7.4).
        if (amount in 1..step.balanceXaf) {
            Text(
                stringResource(R.string.amount_after, formatXaf(step.balanceXaf - amount)),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("balance-after"),
            )
        }
        VerticalGap(Spacing.M)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S), modifier = Modifier.fillMaxWidth()) {
            quickAmounts.forEach { q ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = Dimens.TouchTarget)
                            .clip(PillShape)
                            .background(Surface)
                            .border(Dimens.Hairline, Border, PillShape)
                            .clickable {
                                digits = q.toString()
                                onDraft(digits)
                            }.testTag("quick-$q"),
                ) { AmountText(formatXaf(q), MaterialTheme.typography.labelMedium, Ink, fit = true) }
            }
        }
        Spacer(Modifier.weight(1f))
        VerticalGap(Spacing.M)
        Keypad(
            onDigit = { d ->
                if (digits.length < MAX_AMOUNT_DIGITS && !(digits.isEmpty() && d == '0')) {
                    digits += d
                    onDraft(digits)
                }
            },
            onDelete = {
                digits = digits.dropLast(1)
                onDraft(digits)
            },
        )
        VerticalGap(Spacing.M)
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
    Page(DailyTags.REVIEW, secure = true, title = stringResource(R.string.review_title), onBack = onBack) {
        SurfaceCard(Modifier.testTag("review-beneficiary"), contentPadding = PaddingValues(Spacing.L)) {
            SectionLabel(stringResource(R.string.review_beneficiary))
            VerticalGap(Spacing.XS)
            Text(step.holder.holderName, color = Navy, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("holder-name"))
            Text(stringResource(R.string.home_number, formatMsisdn(step.holder.msisdn)), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            if (step.holder.newBeneficiary) {
                VerticalGap(Spacing.M)
                Pill(stringResource(R.string.review_new), Tone.DANGER, Modifier.testTag("new-beneficiary"))
            }
        }
        VerticalGap(Spacing.M)
        SurfaceCard(Modifier.testTag("review-amount"), contentPadding = PaddingValues(Spacing.L)) {
            SectionLabel(stringResource(R.string.review_amount))
            VerticalGap(Spacing.XS)
            AmountText(stringResource(R.string.review_amount_value, formatXaf(step.amountXaf)), AmountType.Large, Ink, fit = true)
        }
        if (step.holder.newBeneficiary) {
            VerticalGap(Spacing.M)
            Callout(
                buildAnnotatedString {
                    append(stringResource(R.string.review_new_warning_before))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.review_new_warning_key)) }
                    append(stringResource(R.string.review_new_warning_after))
                },
                tag = "new-warning",
            )
        }
        Spacer(Modifier.weight(1f))
        VerticalGap(Spacing.L)
        PrimaryButton(stringResource(R.string.action_confirm), onConfirm, tag = "confirm")
        VerticalGap(Spacing.S)
        TextAction(stringResource(R.string.action_cancel), onCancel, tag = "cancel")
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
    Page(DailyTags.AUTHORIZE, secure = true, title = stringResource(R.string.authorize_title), onBack = onBack.takeUnless { step.busy }) {
        Text(
            stringResource(R.string.authorize_summary, formatXaf(step.amountXaf), step.holder.holderName),
            color = Ink,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("summary"),
        )
        VerticalGap(Spacing.M)
        Text(
            stringResource(R.string.authorize_step_pin),
            color = TextSecondary,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalGap(Spacing.M)
        PinDots(pin.length)
        VerticalGap(Spacing.M)
        SurfaceCard(Modifier.testTag("unlock-card")) {
            Text(
                "🔐 " + stringResource(R.string.authorize_step_unlock),
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.authorize_step_unlock_body),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        step.problem?.let { ErrorText(problemText(it, onAuthorize = true)) }
        Spacer(Modifier.weight(1f))
        VerticalGap(Spacing.M)
        Keypad(
            onDigit = { if (!step.busy) pin += it },
            onDelete = { if (!step.busy) pin = pin.dropLast(1) },
        )
        VerticalGap(Spacing.M)
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
        Disc(R.drawable.ic_check, ProtectedGreen) // exécution confirmée par le serveur
        VerticalGap(Spacing.L)
        Title(stringResource(R.string.sent_title))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AmountText(stringResource(R.string.review_amount_value, formatXaf(step.amountXaf)), AmountType.Hero, Ink, fit = true)
        }
        BodyText(
            stringResource(R.string.sent_to, step.holder.holderName, formatMsisdn(step.holder.msisdn)),
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalGap(Spacing.L)
        SurfaceCard(Modifier.testTag("receipt")) {
            ReceiptLine(stringResource(R.string.receipt_reference), step.transferId, "reference")
            HairlineDivider()
            ReceiptLine(stringResource(R.string.receipt_signed_by), stringResource(R.string.receipt_this_phone, deviceModel), "signed-by")
        }
        VerticalGap(Spacing.XL)
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
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.M),
    ) {
        Text(label, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        HorizontalGap(Spacing.M)
        Text(
            value,
            color = Ink,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.End,
            modifier =
                Modifier
                    .weight(1f)
                    .testTag(tag),
        )
    }
}

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
        Disc(R.drawable.ic_schedule, AmberText)
        VerticalGap(Spacing.L)
        Title(stringResource(R.string.deferred_title))
        VerticalGap(Spacing.M)
        Text(
            step.executeAt?.let { stringResource(R.string.deferred_body, formatWhen(it, zone)) }
                ?: stringResource(R.string.deferred_body_unknown_time),
            color = Ink,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("deferred-body"),
        )
        VerticalGap(Spacing.S)
        BodyText(stringResource(R.string.deferred_can_cancel), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        VerticalGap(Spacing.L)
        SurfaceCard(Modifier.testTag("deferred-card")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_deferred_item, formatXaf(step.amountXaf), step.holderName ?: formatMsisdn(step.msisdn)),
                    color = Navy,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                HorizontalGap(Spacing.S)
                if (step.cancelled) {
                    Pill(stringResource(R.string.status_cancelled), Tone.MUTED, Modifier.testTag("cancelled"))
                } else {
                    Pill(stringResource(R.string.deferred_pending), Tone.WARNING)
                }
            }
        }
        step.problem?.let {
            ErrorText(if (it.error == DailyError.UNREACHABLE) stringResource(R.string.error_cancel_unconfirmed) else problemText(it))
        }
        VerticalGap(Spacing.L)
        if (step.cancelled) {
            BodyText(stringResource(R.string.deferred_cancelled), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            DangerButton(stringResource(R.string.deferred_cancel), onCancel, tag = "cancel-transfer")
        }
        VerticalGap(Spacing.S)
        TextAction(stringResource(R.string.action_home), onHome, tag = "home")
        Spacer(Modifier.weight(1f))
    }
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
        Disc(if (step.outcome == Outcome.UNKNOWN) R.drawable.ic_help else R.drawable.ic_close, if (step.outcome == Outcome.UNKNOWN) AmberText else Danger)
        VerticalGap(Spacing.L)
        when (step.outcome) {
            Outcome.FROZEN -> {
                Title(stringResource(R.string.frozen_title))
                VerticalGap(Spacing.M)
                BodyText(stringResource(R.string.frozen_body), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                VerticalGap(Spacing.L)
                Callout(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.frozen_what)) }
                        append(stringResource(R.string.frozen_what_body))
                    },
                    Tone.DANGER,
                    tag = "what-to-do",
                )
            }
            Outcome.REJECTED, Outcome.REJECTED_KEY -> {
                Title(stringResource(R.string.rejected_title))
                VerticalGap(Spacing.M)
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
                Title(stringResource(R.string.unknown_title))
                VerticalGap(Spacing.M)
                BodyText(stringResource(R.string.unknown_body), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        VerticalGap(Spacing.L)
        if (step.outcome == Outcome.UNKNOWN) {
            PrimaryButton(stringResource(R.string.action_history), onHistory, tag = "history")
            VerticalGap(Spacing.S)
            SecondaryButton(stringResource(R.string.action_home), onHome, tag = "home")
        } else {
            PrimaryButton(stringResource(R.string.action_home), onHome, tag = "home")
        }
        Spacer(Modifier.weight(1f))
    }
}
