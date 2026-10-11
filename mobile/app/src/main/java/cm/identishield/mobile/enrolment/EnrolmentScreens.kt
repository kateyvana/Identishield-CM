package cm.identishield.mobile.enrolment

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cm.identishield.mobile.R
import cm.identishield.mobile.daily.DailyHomeScreen
import cm.identishield.mobile.data.Account
import cm.identishield.mobile.ui.theme.Amber
import cm.identishield.mobile.ui.theme.Ink
import cm.identishield.mobile.ui.theme.Navy
import cm.identishield.mobile.ui.theme.ProtectedGreen
import kotlinx.coroutines.delay

/** Identifiants de test stables (MOB-NF-06) : un par écran. */
object EnrolTags {
    const val WELCOME = "E-02"
    const val NUMBER = "E-03"
    const val CHECKING = "E-04"
    const val OTP = "E-05"
    const val PIN = "E-06"
    const val SECURING = "E-07"
    const val RECOVERY = "E-08"
    const val CONFIRM = "E-09"
    const val HOME = "E-11"
}

@Composable
fun errorMessage(error: EnrolError): String =
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
            EnrolError.UNEXPECTED -> R.string.error_unexpected
        },
    )

// ---- E-02 Bienvenue -----------------------------------------------------------------------------
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    Page(EnrolTags.WELCOME) {
        VerticalGap(24)
        Image(
            painter = painterResource(R.drawable.logo_mark),
            contentDescription = stringResource(R.string.launch_logo_description),
            modifier =
                Modifier
                    .size(120.dp)
                    .align(Alignment.CenterHorizontally),
        )
        VerticalGap(24)
        Text(
            stringResource(R.string.welcome_title),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalGap(16)
        BodyText(stringResource(R.string.welcome_body))
        VerticalGap(16)
        WarningBox(content = {
            Text(
                buildAnnotatedString {
                    append(stringResource(R.string.welcome_warning_before))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.welcome_warning_key)) }
                    append(stringResource(R.string.welcome_warning_after))
                },
                color = Ink,
                fontSize = 16.sp,
            )
        })
        Spacer(Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.welcome_start), onStart, tag = "start")
        VerticalGap(16)
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
    Page(EnrolTags.NUMBER) {
        BackHeader(stringResource(R.string.number_title), onBack)
        VerticalGap(16)
        BodyText(stringResource(R.string.number_body))
        VerticalGap(20)
        Text(stringResource(R.string.number_label), color = Secondary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
            Text(stringResource(R.string.number_prefix), fontSize = 20.sp, color = Ink)
            HorizontalGap(12)
            BasicTextField(
                value = value,
                onValueChange = { v -> value = v.filter { it.isDigit() || it == ' ' } },
                singleLine = true,
                textStyle = TextStyle(fontSize = 22.sp, color = Ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier =
                    Modifier
                        .weight(1f)
                        .testTag("number-input"),
            )
        }
        VerticalGap(8)
        BodyText(stringResource(R.string.number_hint))
        step.error?.let { ErrorText(errorMessage(it)) }
        Spacer(Modifier.weight(1f))
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
    Page(EnrolTags.PIN, secure = true) {
        BackHeader(stringResource(R.string.pin_title), onBack)
        VerticalGap(16)
        Text(
            buildAnnotatedString {
                append(stringResource(R.string.pin_account) + " ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(formatMsisdn(step.msisdn)) }
            },
            color = Ink,
            fontSize = 19.sp,
        )
        VerticalGap(16)
        BodyText(stringResource(R.string.pin_body))
        VerticalGap(24)
        PinDots(pin.length)
        VerticalGap(24)
        BodyText(stringResource(R.string.pin_note), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        step.error?.let { ErrorText(errorMessage(it)) }
        Spacer(Modifier.weight(1f))
        Keypad(onDigit = { pin += it }, onDelete = { pin = pin.dropLast(1) })
        VerticalGap(12)
        // Écart : bouton « Continuer » (la longueur du PIN n'est fixée par aucune spécification).
        PrimaryButton(stringResource(R.string.action_continue), { onSubmit(pin) }, enabled = pin.isNotEmpty())
    }
}

// ---- E-04 Vérification --------------------------------------------------------------------------
@Composable
fun CheckingScreen() {
    Page(EnrolTags.CHECKING) {
        VerticalGap(24)
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Navy.copy(alpha = 0.08f))
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center,
        ) { Text("⟳", fontSize = 40.sp, color = Navy) }
        VerticalGap(24)
        Text(
            stringResource(R.string.checking_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalGap(12)
        BodyText(stringResource(R.string.checking_body), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        VerticalGap(28)
        LinearProgressIndicator(
            color = ProtectedGreen,
            trackColor = Hairline,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp),
        )
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
    Page(EnrolTags.OTP) {
        BackHeader(stringResource(R.string.otp_title), onBack)
        VerticalGap(16)
        BodyText(stringResource(R.string.otp_body, maskMsisdn(step.msisdn)))
        VerticalGap(20)
        // Écart : autant de cases que de chiffres tapés (la longueur de l'OTP est validée par le serveur).
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.testTag("otp-digits"),
        ) {
            (otp.toList() + listOf(null)).forEach { c ->
                Box(
                    Modifier
                        .size(width = 48.dp, height = 60.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, if (c == null) Navy else Hairline, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(c?.toString() ?: "", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink) }
            }
        }
        VerticalGap(16)
        BodyText(stringResource(R.string.otp_note))
        step.error?.let { ErrorText(errorMessage(it)) }
        VerticalGap(16)
        SecondaryButton(
            if (remaining > 0) stringResource(R.string.otp_resend_wait, remaining) else stringResource(R.string.otp_resend),
            onResend,
            enabled = remaining == 0,
            tag = "resend",
        )
        Spacer(Modifier.weight(1f))
        Keypad(onDigit = { otp += it }, onDelete = { otp = otp.dropLast(1) })
        VerticalGap(12)
        PrimaryButton(stringResource(R.string.action_continue), { onSubmit(otp) }, enabled = otp.isNotEmpty())
    }
}

// ---- E-07 Sécurisation --------------------------------------------------------------------------
@Composable
fun SecuringScreen(
    step: EnrolStep.Securing,
    onSecure: () -> Unit,
    onRestart: () -> Unit,
) {
    // Lancement automatique à l'arrivée sur l'écran : la clé ne naît qu'ici, après l'OTP validé.
    LaunchedEffect(Unit) { if (step.error == null && step.stage == SecuringStage.CREATING_KEY) onSecure() }
    Page(EnrolTags.SECURING) {
        VerticalGap(24)
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(ProtectedGreen.copy(alpha = 0.12f))
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center,
        ) { Text("🛡", fontSize = 40.sp, color = ProtectedGreen) }
        VerticalGap(24)
        Text(
            stringResource(R.string.securing_title),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalGap(12)
        BodyText(stringResource(R.string.securing_body), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        VerticalGap(20)
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                    .padding(20.dp),
        ) {
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
                            .padding(vertical = 12.dp)
                            .testTag("stage-${stage.name}"),
                ) {
                    Text(stringResource(label), color = Ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Text(
                        if (done) "✓" else if (current) "…" else "·",
                        color = if (done) ProtectedGreen else if (current) Amber else Secondary,
                        fontSize = 20.sp,
                    )
                }
                if (i < 2) Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
            }
        }
        step.error?.let { error ->
            ErrorText(if (error == EnrolError.UNREACHABLE) stringResource(R.string.error_connection_lost) else errorMessage(error))
            VerticalGap(16)
            val restart = error == EnrolError.DEVICE_REFUSED || error == EnrolError.SESSION_EXPIRED
            SecondaryButton(
                stringResource(if (restart) R.string.welcome_start else R.string.securing_retry),
                if (restart) onRestart else onSecure,
                tag = "securing-retry",
            )
        }
    }
}

// ---- E-08 Clé de récupération -------------------------------------------------------------------
@Composable
fun RecoveryKeyScreen(
    step: EnrolStep.RecoveryKey,
    onWritten: () -> Unit,
) {
    // Texte non sélectionnable (MOB-S-06).
    Page(EnrolTags.RECOVERY, secure = true) {
        BackHeader(stringResource(R.string.recovery_title), onBack = null)
        VerticalGap(16)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.recovery_intro_bold)) }
                append(stringResource(R.string.recovery_intro_before))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.recovery_intro_never)) }
                append(stringResource(R.string.recovery_intro_after))
            },
            color = Ink,
            fontSize = 17.sp,
            lineHeight = 25.sp,
        )
        VerticalGap(20)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.dp, Navy, RoundedCornerShape(16.dp))
                    .padding(vertical = 20.dp)
                    .testTag("recovery-code"),
        ) {
            groupRecoveryCode(step.code).forEach { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 26.sp, color = Ink, letterSpacing = 2.sp)
            }
        }
        VerticalGap(20)
        WarningBox(content = { Text(stringResource(R.string.recovery_warning), color = Ink, fontSize = 16.sp) })
        if (step.lastDisplay) {
            VerticalGap(12)
            Text(
                stringResource(R.string.recovery_last_display),
                color = Ink,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("last-display"),
            )
        }
        Spacer(Modifier.weight(1f))
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
    Page(EnrolTags.CONFIRM, secure = true) {
        BackHeader(stringResource(R.string.confirm_title), onBack = null)
        VerticalGap(16)
        BodyText(stringResource(R.string.confirm_body))
        VerticalGap(20)
        Text(stringResource(R.string.confirm_reminder_label), color = Secondary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        VerticalGap(8)
        Text(
            codeReminder(codeLength, step.codeTail),
            fontFamily = FontFamily.Monospace,
            fontSize = 26.sp,
            letterSpacing = 2.sp,
            color = Ink,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Navy.copy(alpha = 0.06f))
                    .padding(vertical = 14.dp)
                    .clearAndSetSemantics {
                        testTag = "code-reminder"
                        contentDescription = reminderSpoken
                    },
        )
        VerticalGap(20)
        Text(stringResource(R.string.confirm_input_label, codeLength), color = Secondary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        VerticalGap(8)
        CodeField(typed, { typed = it }, codeLength, "confirm-input")
        VerticalGap(8)
        BodyText(stringResource(R.string.confirm_masked_hint))
        BodyText(stringResource(R.string.confirm_note))
        step.error?.let { ErrorText(errorMessage(it)) }
        Spacer(Modifier.weight(1f))
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
