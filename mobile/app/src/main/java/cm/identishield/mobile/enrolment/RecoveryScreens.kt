package cm.identishield.mobile.enrolment

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cm.identishield.mobile.R
import cm.identishield.mobile.ui.theme.Amber
import cm.identishield.mobile.ui.theme.Danger
import cm.identishield.mobile.ui.theme.Ink
import cm.identishield.mobile.ui.theme.Navy
import cm.identishield.mobile.ui.theme.ProtectedGreen
import kotlinx.coroutines.delay
import java.time.Duration

/** Identifiants de test stables des écrans du flux D et d'E-27 (MOB-NF-06). */
object RecoveryTags {
    const val ENTRY = "E-20"
    const val WAIT = "E-21"
    const val REBOUND = "E-22"
    const val LOST = "E-26"
    const val REVOKED = "E-27a"
    const val INCOMPATIBLE = "E-27b"
}

/** « 04:00 », ou « 1:04:00 » au-delà d'une heure. */
fun formatWait(remaining: Duration): String {
    val total = remaining.seconds + if (remaining.nano > 0) 1 else 0
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

// ---- E-20 Clé de récupération 🔒 -----------------------------------------------------------------

/**
 * La clé est saisie dans le champ commun [CodeField] (capture bloquée, MOB-S-05 ; sans copier-coller,
 * MOB-S-06 ; clavier « mot de passe » ; masquée sauf la fin ; champ mot de passe pour l'accessibilité,
 * D2) ; elle part au serveur et n'est gardée nulle part. Le message d'échec est le même pour tout le
 * monde (ES-22).
 */
@Composable
fun RecoveryScreen(
    step: EnrolStep.Recovery,
    codeLength: Int,
    onSubmit: (String) -> Unit,
    onLostKey: () -> Unit,
    onBack: () -> Unit,
) {
    var code by remember(step) { mutableStateOf("") }
    Page(RecoveryTags.ENTRY, secure = true) {
        BackHeader(stringResource(R.string.reenrol_title), onBack)
        VerticalGap(12)
        Text(
            stringResource(R.string.recovery_banner),
            color = ProtectedGreen,
            fontSize = 17.sp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ProtectedGreen.copy(alpha = 0.10f))
                    .border(1.dp, ProtectedGreen.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
        )
        VerticalGap(12)
        Text(
            buildAnnotatedString {
                append(stringResource(R.string.recovery_body_before))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.recovery_body_this)) }
                append(stringResource(R.string.recovery_body_after))
            },
            color = Ink,
            fontSize = 17.sp,
        )
        VerticalGap(20)
        Text(stringResource(R.string.recovery_label), color = Secondary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        VerticalGap(8)
        CodeField(code, { code = it }, codeLength, "recovery-input")
        VerticalGap(8)
        BodyText(stringResource(R.string.recovery_no_sms))
        step.error?.let { ErrorText(errorMessage(it)) }
        Spacer(Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.action_continue), { onSubmit(code) }, enabled = code.length == codeLength)
        LostKeyLink(onLostKey)
    }
}

@Composable
private fun LostKeyLink(onLostKey: () -> Unit) {
    Text(
        stringResource(R.string.recovery_lost),
        color = Secondary,
        fontSize = 17.sp,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onLostKey)
                .padding(vertical = 14.dp)
                .testTag("lost-key"),
    )
}

// ---- E-21 Attente imposée 🔒 ----------------------------------------------------------------------

/**
 * Compte à rebours lu sur l'horloge injectée ([remaining]) ; « Votre compte n'est pas bloqué » :
 * seul CET appareil attend (ES-21). À zéro, retour à E-20.
 */
@Composable
fun RecoveryWaitScreen(
    step: EnrolStep.RecoveryWait,
    remaining: () -> Duration?,
    onWaitOver: () -> Unit,
    onLostKey: () -> Unit,
    onBack: () -> Unit,
) {
    var left by remember(step) { mutableStateOf(remaining()) }
    LaunchedEffect(step) {
        while (true) {
            left = remaining()
            if (left == null) {
                onWaitOver()
                break
            }
            delay(TICK_MILLIS)
        }
    }
    Page(RecoveryTags.WAIT, secure = true) {
        BackHeader(stringResource(R.string.reenrol_title), onBack)
        VerticalGap(16)
        Disc("⏳", Amber)
        VerticalGap(20)
        Title(stringResource(R.string.wait_title))
        VerticalGap(12)
        BodyText(stringResource(R.string.wait_body), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(
            left?.let { formatWait(it) } ?: "00:00",
            color = Navy,
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("countdown"),
        )
        VerticalGap(12)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.wait_not_blocked)) }
                append(stringResource(R.string.wait_not_blocked_body))
            },
            color = ProtectedGreen,
            fontSize = 16.sp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ProtectedGreen.copy(alpha = 0.10f))
                    .border(1.dp, ProtectedGreen.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
                    .testTag("not-blocked"),
        )
        VerticalGap(12)
        BodyText(stringResource(R.string.wait_grows), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        LostKeyLink(onLostKey)
    }
}

private const val TICK_MILLIS = 1_000L

// ---- E-22 Réenrôlement réussi -------------------------------------------------------------------

/** Ni nom ni modèle de l'ancien téléphone : l'application ne les connaît pas (écart documenté). */
@Composable
fun ReboundScreen(
    step: EnrolStep.Rebound,
    deviceModel: String,
    onShowKey: () -> Unit,
) {
    Page(RecoveryTags.REBOUND) {
        Spacer(Modifier.weight(1f))
        Disc("🛡", ProtectedGreen)
        VerticalGap(20)
        Title(stringResource(R.string.rebound_title))
        VerticalGap(8)
        BodyText(formatMsisdn(step.msisdn), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        VerticalGap(16)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            StatusRow(stringResource(R.string.rebound_old), stringResource(R.string.rebound_unbound), Danger, "old-phone")
            StatusRow(stringResource(R.string.rebound_this, deviceModel), stringResource(R.string.rebound_bound), ProtectedGreen, "this-phone")
        }
        VerticalGap(12)
        WarningBox(content = { Text(stringResource(R.string.rebound_warning), color = Ink, fontSize = 16.sp) })
        VerticalGap(16)
        PrimaryButton(stringResource(R.string.rebound_show_key), onShowKey, tag = "show-key")
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun StatusRow(
    label: String,
    status: String,
    color: Color,
    tag: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .testTag(tag),
    ) {
        Text(label, color = Ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(
            status,
            color = color,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(color.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

// ---- E-26 Clé perdue ----------------------------------------------------------------------------

/** Orientation seulement : la procédure dépend de QM-05 (point ouvert). */
@Composable
fun KeyLostScreen(onBack: () -> Unit) {
    Page(RecoveryTags.LOST) {
        BackHeader(stringResource(R.string.lost_title), onBack)
        VerticalGap(16)
        Disc("🗝", Navy)
        VerticalGap(16)
        Title(stringResource(R.string.lost_heading))
        VerticalGap(12)
        BodyText(stringResource(R.string.lost_body))
        VerticalGap(16)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                .padding(20.dp),
        ) {
            listOf(R.string.lost_step_1, R.string.lost_step_2, R.string.lost_step_3).forEach {
                Text(stringResource(it), color = Ink, fontSize = 17.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        VerticalGap(12)
        WarningBox(content = { Text(stringResource(R.string.lost_pending), color = Ink, fontSize = 16.sp) })
        Spacer(Modifier.weight(1f))
        SecondaryButton(stringResource(R.string.back), onBack, tag = "lost-back")
    }
}

// ---- E-27a Clé révoquée ou invalidée ------------------------------------------------------------

/**
 * Remplace le message simple de M-07 : un parcours qui mène au flux D. [invalidated] : la clé a été
 * invalidée par la puce (nouvelle biométrie) plutôt que révoquée par le serveur.
 */
@Composable
fun KeyRevokedScreen(
    invalidated: Boolean,
    onSecureAgain: () -> Unit,
) {
    Page(RecoveryTags.REVOKED) {
        Spacer(Modifier.weight(1f))
        Disc("🔗", Danger)
        VerticalGap(20)
        Title(stringResource(if (invalidated) R.string.invalidated_title else R.string.revoked_title))
        VerticalGap(12)
        BodyText(
            stringResource(if (invalidated) R.string.invalidated_body else R.string.revoked_body),
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!invalidated) {
            VerticalGap(16)
            Text(
                stringResource(R.string.revoked_warning),
                color = Danger,
                fontSize = 16.sp,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Danger.copy(alpha = 0.08f))
                        .border(1.dp, Danger.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                        .testTag("revoked-warning"),
            )
        }
        VerticalGap(20)
        PrimaryButton(stringResource(R.string.revoked_secure_again), onSecureAgain, tag = "secure-again")
        VerticalGap(8)
        // Texte seul : aucun code USSD réel n'est composé depuis le bac à sable (comme E-02).
        BodyText(stringResource(R.string.revoked_ussd), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
    }
}

// ---- E-27b Appareil incompatible ----------------------------------------------------------------

@Composable
fun DeviceIncompatibleScreen(onQuit: () -> Unit) {
    Page(RecoveryTags.INCOMPATIBLE) {
        Spacer(Modifier.weight(1f))
        Disc("📵", Navy)
        VerticalGap(20)
        Title(stringResource(R.string.incompatible_title))
        VerticalGap(12)
        BodyText(stringResource(R.string.incompatible_body), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        VerticalGap(12)
        BodyText(stringResource(R.string.incompatible_ussd), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        VerticalGap(20)
        SecondaryButton(stringResource(R.string.incompatible_quit), onQuit, tag = "quit")
        Spacer(Modifier.weight(1f))
    }
}

// ---- communs ------------------------------------------------------------------------------------

@Composable
internal fun ColumnScope.Disc(
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
    ) { Text(glyph, fontSize = 40.sp, color = color) }
}

@Composable
internal fun Title(text: String) =
    Text(
        text,
        color = Ink,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("title"),
    )
