package cm.identishield.mobile.enrolment

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import cm.identishield.mobile.R
import cm.identishield.mobile.daily.DailyHomeScreen
import cm.identishield.mobile.data.Account
import cm.identishield.mobile.ui.theme.Amber
import cm.identishield.mobile.ui.theme.AmberText
import cm.identishield.mobile.ui.theme.AmountType
import cm.identishield.mobile.ui.theme.Border
import cm.identishield.mobile.ui.theme.Dimens
import cm.identishield.mobile.ui.theme.FieldBorder
import cm.identishield.mobile.ui.theme.Ink
import cm.identishield.mobile.ui.theme.Navy
import cm.identishield.mobile.ui.theme.NavyTint
import cm.identishield.mobile.ui.theme.Spacing
import cm.identishield.mobile.ui.theme.Surface
import cm.identishield.mobile.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/** Identifiants de test stables (MOB-NF-06) : un par écran. */
object EnrolTags {
    const val WELCOME = "E-02"
    const val NUMBER = "E-03"
    const val CHECKING = "E-04"
    const val OTP = "E-05"
    const val PIN = "E-06"
    const val SECURING = "E-07"
    const val SCREEN_LOCK = "E-07-verrou" // précondition D3
    const val RECOVERY = "E-08"
    const val CONFIRM = "E-09"
    const val HOME = "E-11"
}

/** Texte d'une erreur ; [retryAfterSeconds] (Retry-After du serveur) ne sert qu'au 503 transitoire (D-B7-1). */
@Composable
fun errorMessage(
    error: EnrolError,
    retryAfterSeconds: Long? = null,
): String =
    if (error == EnrolError.SERVICE_UNAVAILABLE && retryAfterSeconds != null) {
        stringResource(R.string.error_service_unavailable_in, retryAfterSeconds)
    } else {
        stringResource(
            when (error) {
            EnrolError.NUMBER_INVALID -> R.string.error_number_invalid
            EnrolError.CREDENTIALS_INVALID -> R.string.error_credentials_invalid
            EnrolError.OTP_INVALID -> R.string.error_otp_invalid
            EnrolError.OTP_EXPIRED -> R.string.error_otp_expired
            EnrolError.SESSION_EXPIRED -> R.string.error_session_expired
            EnrolError.UNREACHABLE -> R.string.error_unreachable
            EnrolError.RECOVERY_INVALID -> R.string.error_recovery_invalid
            EnrolError.REENROL_FROZEN -> R.string.error_reenrol_frozen
            EnrolError.DEVICE_REFUSED -> R.string.error_device_refused
            EnrolError.UNLOCK_CANCELLED -> R.string.error_unlock_cancelled
            EnrolError.CONFIRM_MISMATCH -> R.string.error_confirm_mismatch
            EnrolError.PROCESS_RESTARTED -> R.string.error_process_restarted
            EnrolError.SERVICE_UNAVAILABLE -> R.string.error_service_unavailable
            EnrolError.UNEXPECTED -> R.string.error_unexpected
            },
        )
    }

// ---- E-02 Bienvenue -----------------------------------------------------------------------------
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    Page(EnrolTags.WELCOME) {
        VerticalGap(Spacing.XL)
        Image(
            painter = painterResource(R.drawable.logo_mark),
            contentDescription = stringResource(R.string.launch_logo_description),
            modifier =
                Modifier
                    .size(Dimens.Logo)
                    .align(Alignment.CenterHorizontally),
        )
        VerticalGap(Spacing.XL)
        Title(stringResource(R.string.welcome_title))
        VerticalGap(Spacing.L)
        BodyText(stringResource(R.string.welcome_body))
        VerticalGap(Spacing.L)
        Callout(
            buildAnnotatedString {
                append(stringResource(R.string.welcome_warning_before))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.welcome_warning_key)) }
                append(stringResource(R.string.welcome_warning_after))
            },
        )
        Spacer(Modifier.weight(1f))
        VerticalGap(Spacing.L)
        PrimaryButton(stringResource(R.string.welcome_start), onStart, tag = "start")
        VerticalGap(Spacing.L)
        // Texte seul : aucun appel USSD réel n'est composé depuis le bac à sable (écart documenté).
        BodyText(
            stringResource(R.string.welcome_ussd),
            align = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("ussd"),
        )
    }
}

// ---- E-03 Numéro --------------------------------------------------------------------------------
@Composable
fun NumberScreen(
    step: EnrolStep.Number,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    Page(EnrolTags.NUMBER, title = stringResource(R.string.number_title), onBack = onBack) {
        BodyText(stringResource(R.string.number_body))
        VerticalGap(Spacing.XL)
        InputBox(stringResource(R.string.number_label), field = focus) {
            Text(stringResource(R.string.number_prefix), style = MaterialTheme.typography.titleLarge, color = TextSecondary)
            HorizontalGap(Spacing.M)
            BasicTextField(
                value = value,
                // D8 (B1b) : la valeur reste les chiffres bruts (neuf au plus) ; l'affichage les groupe.
                onValueChange = { v -> value = v.filter { it.isDigit() }.take(EnrolmentViewModel.NATIONAL_NUMBER_LENGTH) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = Ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                visualTransformation = MsisdnTransformation,
                modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(focus)
                        .testTag("number-input"),
                decorationBox = CenteredInput,
            )
        }
        VerticalGap(Spacing.S)
        BodyText(stringResource(R.string.number_hint))
        step.error?.let { ErrorText(errorMessage(it, step.retryAfterSeconds)) }
        Spacer(Modifier.weight(1f))
        VerticalGap(Spacing.L)
        PrimaryButton(stringResource(R.string.action_continue), { onSubmit(value) }, enabled = value.isNotBlank())
    }
}

// ---- E-06 PIN -----------------------------------------------------------------------------------
@Composable
fun PinScreen(
    step: EnrolStep.Pin,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
) {
    var pin by remember(step) { mutableStateOf("") }
    Page(EnrolTags.PIN, secure = true, title = stringResource(R.string.pin_title), onBack = onBack) {
        Text(
            buildAnnotatedString {
                append(stringResource(R.string.pin_account) + " ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(formatMsisdn(step.msisdn)) }
            },
            color = Ink,
            style = MaterialTheme.typography.bodyLarge,
        )
        VerticalGap(Spacing.M)
        BodyText(stringResource(R.string.pin_body))
        VerticalGap(Spacing.XL)
        PinDots(pin.length)
        VerticalGap(Spacing.XL)
        BodyText(stringResource(R.string.pin_note), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        step.error?.let { ErrorText(errorMessage(it, step.retryAfterSeconds)) }
        Spacer(Modifier.weight(1f))
        VerticalGap(Spacing.M)
        Keypad(onDigit = { pin += it }, onDelete = { pin = pin.dropLast(1) })
        VerticalGap(Spacing.M)
        // Écart : bouton « Continuer » (la longueur du PIN n'est fixée par aucune spécification).
        PrimaryButton(stringResource(R.string.action_continue), { onSubmit(pin) }, enabled = pin.isNotEmpty())
    }
}

// ---- E-04 Vérification --------------------------------------------------------------------------
@Composable
fun CheckingScreen() {
    Page(EnrolTags.CHECKING) {
        Spacer(Modifier.weight(1f))
        Disc(R.drawable.ic_sync, Navy)
        VerticalGap(Spacing.XL)
        Title(stringResource(R.string.checking_title))
        VerticalGap(Spacing.M)
        BodyText(stringResource(R.string.checking_body), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        VerticalGap(Spacing.XL)
        // Bleu nuit : une vérification EN COURS n'est pas encore un état vérifié (le vert lui est réservé).
        LinearProgressIndicator(
            color = Navy,
            trackColor = Border,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.XXL),
        )
        Spacer(Modifier.weight(1f))
    }
}

// ---- E-05 OTP -----------------------------------------------------------------------------------
@Composable
fun OtpScreen(
    step: EnrolStep.Otp,
    resendDelaySeconds: Int,
    onSubmit: (String) -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
) {
    var otp by remember(step) { mutableStateOf("") }
    var remaining by remember(step.resendGeneration) { mutableIntStateOf(resendDelaySeconds) }
    LaunchedEffect(step.resendGeneration) {
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
        }
    }
    Page(EnrolTags.OTP, title = stringResource(R.string.otp_title), onBack = onBack) {
        BodyText(stringResource(R.string.otp_body, maskMsisdn(step.msisdn)))
        VerticalGap(Spacing.XL)
        // Écart : autant de cases que de chiffres tapés (la longueur de l'OTP est validée par le serveur).
        // Les cases partagent la largeur disponible : elles ne débordent jamais de l'écran.
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.S, Alignment.CenterHorizontally),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("otp-digits"),
        ) {
            (otp.toList() + listOf(null)).forEach { c ->
                Box(
                    Modifier
                        .weight(1f, fill = false)
                        .width(Dimens.TouchTarget)
                        .height(Dimens.Button)
                        .clip(MaterialTheme.shapes.small)
                        .background(Surface)
                        .border(Dimens.Hairline, if (c == null) Navy else FieldBorder, MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center,
                ) { Text(c?.toString() ?: "", style = MaterialTheme.typography.headlineMedium, color = Ink) }
            }
        }
        VerticalGap(Spacing.L)
        BodyText(stringResource(R.string.otp_note))
        step.error?.let { ErrorText(errorMessage(it, step.retryAfterSeconds)) }
        VerticalGap(Spacing.L)
        SecondaryButton(
            if (remaining > 0) stringResource(R.string.otp_resend_wait, remaining) else stringResource(R.string.otp_resend),
            onResend,
            enabled = remaining == 0,
            tag = "resend",
        )
        Spacer(Modifier.weight(1f))
        VerticalGap(Spacing.M)
        Keypad(onDigit = { otp += it }, onDelete = { otp = otp.dropLast(1) })
        VerticalGap(Spacing.M)
        PrimaryButton(stringResource(R.string.action_continue), { onSubmit(otp) }, enabled = otp.isNotEmpty())
    }
}

// ---- E-07 Sécurisation --------------------------------------------------------------------------
@Composable
fun SecuringScreen(
    step: EnrolStep.Securing,
    onSecure: () -> Unit,
    onRestart: () -> Unit,
    onRetryRegistration: () -> Unit = onSecure, // D-B7-1 : après un 503, rejoue l'enregistrement, même clé
) {
    // Lancement automatique à l'arrivée sur l'écran : la clé ne naît qu'ici, après l'OTP validé.
    LaunchedEffect(Unit) { if (step.error == null && step.stage == SecuringStage.CREATING_KEY) onSecure() }
    Page(EnrolTags.SECURING) {
        VerticalGap(Spacing.XL)
        // Bleu nuit : la liaison n'est pas encore vérifiée par le serveur (le vert lui est réservé).
        Disc(R.drawable.ic_security, Navy)
        VerticalGap(Spacing.XL)
        Title(stringResource(R.string.securing_title))
        VerticalGap(Spacing.M)
        BodyText(stringResource(R.string.securing_body), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        VerticalGap(Spacing.L)
        SurfaceCard(contentPadding = PaddingValues(horizontal = Spacing.L, vertical = Spacing.XS)) {
            val order = SecuringStage.entries
            listOf(
                R.string.securing_step_key to SecuringStage.CREATING_KEY,
                R.string.securing_step_unlock to SecuringStage.UNLOCKING,
                R.string.securing_step_register to SecuringStage.REGISTERING,
            ).forEachIndexed { i, (label, stage) ->
                val done = order.indexOf(step.stage) > order.indexOf(stage)
                val current = step.stage == stage
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.M)
                            .testTag("stage-${stage.name}"),
                ) {
                    Text(stringResource(label), color = Ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        if (done) "✓" else if (current) "…" else "·",
                        color = if (done) Navy else if (current) AmberText else TextSecondary,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                if (i < 2) HairlineDivider()
            }
        }
        step.error?.let { error ->
            ErrorText(
                if (error == EnrolError.UNREACHABLE) stringResource(R.string.error_connection_lost) else errorMessage(error, step.retryAfterSeconds),
            )
            VerticalGap(Spacing.L)
            val restart = error == EnrolError.DEVICE_REFUSED || error == EnrolError.SESSION_EXPIRED
            SecondaryButton(
                stringResource(if (restart) R.string.welcome_start else R.string.securing_retry),
                when {
                    restart -> onRestart
                    error == EnrolError.SERVICE_UNAVAILABLE -> onRetryRegistration // même clé, même requête
                    else -> onSecure
                },
                tag = "securing-retry",
            )
        }
    }
}

// ---- E-07, précondition : verrouillage d'écran requis (D3) -------------------------------------

/**
 * La clé exige le déverrouillage local (MOB-S-03) : sans verrouillage d'écran, la puce refuse de la
 * créer. L'application le constate AVANT d'essayer, dit pourquoi et mène aux réglages (charte 3, 4).
 */
@Composable
fun ScreenLockRequiredScreen(
    step: EnrolStep.ScreenLockRequired,
    onOpenSettings: () -> Unit,
    onConfigured: () -> Unit,
) {
    Page(EnrolTags.SCREEN_LOCK) {
        VerticalGap(Spacing.XL)
        Disc(R.drawable.ic_lock, AmberText)
        VerticalGap(Spacing.L)
        Title(stringResource(R.string.screen_lock_title))
        VerticalGap(Spacing.M)
        BodyText(stringResource(R.string.screen_lock_body))
        VerticalGap(Spacing.L)
        SurfaceCard(contentPadding = PaddingValues(Spacing.L)) {
            listOf(R.string.screen_lock_step_1, R.string.screen_lock_step_2, R.string.screen_lock_step_3).forEach {
                Text(stringResource(it), color = Ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = Spacing.S))
            }
        }
        if (step.stillMissing) ErrorText(stringResource(R.string.screen_lock_still_missing), tag = "still-missing")
        Spacer(Modifier.weight(1f))
        VerticalGap(Spacing.L)
        PrimaryButton(stringResource(R.string.screen_lock_open_settings), onOpenSettings, tag = "open-settings")
        VerticalGap(Spacing.M)
        SecondaryButton(stringResource(R.string.screen_lock_done), onConfigured, tag = "lock-configured")
    }
}

// ---- E-08 Clé de récupération -------------------------------------------------------------------
@Composable
fun RecoveryKeyScreen(
    step: EnrolStep.RecoveryKey,
    onWritten: () -> Unit,
) {
    // Texte non sélectionnable (MOB-S-06). D2 : la clé est retirée de l'arbre d'accessibilité
    // (FLAG_SECURE bloque les pixels, pas le texte) ; compromis lecteurs d'écran documenté (README).
    val hiddenFromReaders = stringResource(R.string.recovery_code_a11y)
    Page(EnrolTags.RECOVERY, secure = true, title = stringResource(R.string.recovery_title), onBack = null) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.recovery_intro_bold)) }
                append(stringResource(R.string.recovery_intro_before))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.recovery_intro_never)) }
                append(stringResource(R.string.recovery_intro_after))
            },
            color = Ink,
            style = MaterialTheme.typography.bodyLarge,
        )
        VerticalGap(Spacing.L)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(Surface)
                    .border(Dimens.Hairline, Navy, MaterialTheme.shapes.medium)
                    .padding(vertical = Spacing.L)
                    .clearAndSetSemantics {
                        testTag = "recovery-code"
                        contentDescription = hiddenFromReaders
                    },
        ) {
            groupRecoveryCode(step.code).forEach { line ->
                Text(line, style = AmountType.Code, color = Ink)
            }
        }
        VerticalGap(Spacing.L)
        Callout(stringResource(R.string.recovery_warning))
        if (step.lastDisplay) {
            VerticalGap(Spacing.M)
            Text(
                stringResource(R.string.recovery_last_display),
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("last-display"),
            )
        }
        Spacer(Modifier.weight(1f))
        VerticalGap(Spacing.L)
        PrimaryButton(stringResource(R.string.recovery_written), onWritten, tag = "written")
    }
}

// ---- E-09 Confirmation --------------------------------------------------------------------------

/** MOB-S-06 : ni copier ni coller sur la saisie de la clé (barre d'outils de texte neutralisée). */
internal object NoTextToolbar : TextToolbar {
    override val status = TextToolbarStatus.Hidden

    override fun hide() = Unit

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit
}

/**
 * Décision du propriétaire du 2026-10-10 (méthode « carte bancaire ») : rappel du code émis, masqué
 * sauf ses derniers caractères ([EnrolmentViewModel.CODE_VISIBLE_TAIL]), puis ressaisie COMPLÈTE dans
 * un champ mot de passe. L'écran ne reçoit que la fin du code : le reste ne quitte pas le ViewModel.
 * Le rappel est retiré de l'arbre d'accessibilité (D2) ; seule sa fin, déjà visible, est annoncée.
 */
@Composable
fun ConfirmKeyScreen(
    step: EnrolStep.Confirm,
    codeLength: Int,
    onConfirm: (String) -> Unit,
) {
    var typed by remember(step.codeTail) { mutableStateOf("") }
    val reminderSpoken = stringResource(R.string.confirm_reminder_a11y, step.codeTail.toList().joinToString(" "))
    Page(EnrolTags.CONFIRM, secure = true, title = stringResource(R.string.confirm_title), onBack = null) {
        BodyText(stringResource(R.string.confirm_body))
        VerticalGap(Spacing.L)
        SectionLabel(stringResource(R.string.confirm_reminder_label))
        VerticalGap(Spacing.S)
        Text(
            codeReminder(codeLength, step.codeTail),
            style = AmountType.Code,
            color = Ink,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(NavyTint)
                    .padding(vertical = Spacing.M)
                    .clearAndSetSemantics {
                        testTag = "code-reminder"
                        contentDescription = reminderSpoken
                    },
        )
        VerticalGap(Spacing.L)
        CodeField(typed, { typed = it }, codeLength, "confirm-input", stringResource(R.string.confirm_input_label, codeLength))
        VerticalGap(Spacing.S)
        BodyText(stringResource(R.string.confirm_masked_hint))
        BodyText(stringResource(R.string.confirm_note))
        step.error?.let { ErrorText(errorMessage(it, step.retryAfterSeconds)) }
        Spacer(Modifier.weight(1f))
        VerticalGap(Spacing.L)
        PrimaryButton(
            stringResource(R.string.confirm_action),
            { onConfirm(typed) },
            enabled = typed.length == codeLength,
            tag = "confirm",
        )
    }
}

// ---- E-11 Accueil -------------------------------------------------------------------------------
@Composable
fun HomeScreen(
    account: Account,
    deviceModel: String,
) = DailyHomeScreen(account, deviceModel) // E-11 vit dans daily/ depuis M-07 (actions, opérations, navigation)
