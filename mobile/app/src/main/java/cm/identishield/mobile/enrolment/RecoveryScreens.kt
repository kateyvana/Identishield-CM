package cm.identishield.mobile.enrolment

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import cm.identishield.mobile.R
import cm.identishield.mobile.ui.theme.Amber
import cm.identishield.mobile.ui.theme.Danger
import cm.identishield.mobile.ui.theme.Ink
import cm.identishield.mobile.ui.theme.Navy
import cm.identishield.mobile.ui.theme.ProtectedGreen
import cm.identishield.mobile.ui.theme.Spacing
import cm.identishield.mobile.ui.theme.Tone
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
    Page(RecoveryTags.ENTRY, secure = true, title = stringResource(R.string.reenrol_title), onBack = onBack) {
        // Vert : le serveur a constaté que le compte est déjà lié à un autre téléphone.
        Callout(stringResource(R.string.recovery_banner), Tone.VERIFIED, tag = "recovery-banner")
        VerticalGap(Spacing.M)
        Text(
            buildAnnotatedString {
                append(stringResource(R.string.recovery_body_before))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.recovery_body_this)) }
                append(stringResource(R.string.recovery_body_after))
            },
            color = Ink,
            style = MaterialTheme.typography.bodyLarge,
        )
        VerticalGap(Spacing.L)
        CodeField(code, { code = it }, codeLength, "recovery-input", stringResource(R.string.recovery_label))
        VerticalGap(Spacing.S)
        BodyText(stringResource(R.string.recovery_no_sms))
        step.error?.let { ErrorText(errorMessage(it)) }
        Spacer(Modifier.weight(1f))
        VerticalGap(Spacing.L)
        PrimaryButton(stringResource(R.string.action_continue), { onSubmit(code) }, enabled = code.length == codeLength)
        TextAction(stringResource(R.string.recovery_lost), onLostKey, tag = "lost-key")
    }
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
    Page(RecoveryTags.WAIT, secure = true, title = stringResource(R.string.reenrol_title), onBack = onBack) {
        Disc("⏳", Amber)
        VerticalGap(Spacing.L)
        Title(stringResource(R.string.wait_title))
        VerticalGap(Spacing.M)
        BodyText(stringResource(R.string.wait_body), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(
            left?.let { formatWait(it) } ?: "00:00",
            color = Navy,
            style = MaterialTheme.typography.displayLarge,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("countdown"),
        )
        VerticalGap(Spacing.M)
        Callout(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.wait_not_blocked)) }
                append(stringResource(R.string.wait_not_blocked_body))
            },
            Tone.VERIFIED,
            tag = "not-blocked",
        )
        VerticalGap(Spacing.M)
        BodyText(stringResource(R.string.wait_grows), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        TextAction(stringResource(R.string.recovery_lost), onLostKey, tag = "lost-key")
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
        Disc("🛡", ProtectedGreen) // nouvelle liaison vérifiée par le serveur
        VerticalGap(Spacing.L)
        Title(stringResource(R.string.rebound_title))
        VerticalGap(Spacing.S)
        BodyText(formatMsisdn(step.msisdn), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        VerticalGap(Spacing.L)
        SurfaceCard(contentPadding = PaddingValues(horizontal = Spacing.L, vertical = Spacing.XS)) {
            StatusRow(stringResource(R.string.rebound_old), stringResource(R.string.rebound_unbound), Tone.DANGER, "old-phone")
            HairlineDivider()
            StatusRow(stringResource(R.string.rebound_this, deviceModel), stringResource(R.string.rebound_bound), Tone.VERIFIED, "this-phone")
        }
        VerticalGap(Spacing.M)
        Callout(stringResource(R.string.rebound_warning))
        VerticalGap(Spacing.L)
        PrimaryButton(stringResource(R.string.rebound_show_key), onShowKey, tag = "show-key")
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun StatusRow(
    label: String,
    status: String,
    tone: Tone,
    tag: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.M)
                .testTag(tag),
    ) {
        Text(label, color = Ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        HorizontalGap(Spacing.S)
        Pill(status, tone)
    }
}

// ---- E-26 Clé perdue ----------------------------------------------------------------------------

/** Orientation seulement : la procédure dépend de QM-05 (point ouvert). */
@Composable
fun KeyLostScreen(onBack: () -> Unit) {
    Page(RecoveryTags.LOST, title = stringResource(R.string.lost_title), onBack = onBack) {
        Disc("🗝", Navy)
        VerticalGap(Spacing.L)
        Title(stringResource(R.string.lost_heading))
        VerticalGap(Spacing.M)
        BodyText(stringResource(R.string.lost_body))
        VerticalGap(Spacing.L)
        SurfaceCard(contentPadding = PaddingValues(Spacing.L)) {
            listOf(R.string.lost_step_1, R.string.lost_step_2, R.string.lost_step_3).forEach {
                Text(stringResource(it), color = Ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = Spacing.S))
            }
        }
        VerticalGap(Spacing.M)
        Callout(stringResource(R.string.lost_pending))
        Spacer(Modifier.weight(1f))
        VerticalGap(Spacing.L)
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
        VerticalGap(Spacing.L)
        Title(stringResource(if (invalidated) R.string.invalidated_title else R.string.revoked_title))
        VerticalGap(Spacing.M)
        BodyText(
            stringResource(if (invalidated) R.string.invalidated_body else R.string.revoked_body),
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!invalidated) {
            VerticalGap(Spacing.L)
            Callout(stringResource(R.string.revoked_warning), Tone.DANGER, tag = "revoked-warning")
        }
        VerticalGap(Spacing.L)
        PrimaryButton(stringResource(R.string.revoked_secure_again), onSecureAgain, tag = "secure-again")
        VerticalGap(Spacing.S)
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
        VerticalGap(Spacing.L)
        Title(stringResource(R.string.incompatible_title))
        VerticalGap(Spacing.M)
        BodyText(stringResource(R.string.incompatible_body), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        VerticalGap(Spacing.M)
        BodyText(stringResource(R.string.incompatible_ussd), align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        VerticalGap(Spacing.L)
        SecondaryButton(stringResource(R.string.incompatible_quit), onQuit, tag = "quit")
        Spacer(Modifier.weight(1f))
    }
}
