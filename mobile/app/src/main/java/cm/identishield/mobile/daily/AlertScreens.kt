package cm.identishield.mobile.daily

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import cm.identishield.mobile.R
import cm.identishield.mobile.data.Alert
import cm.identishield.mobile.enrolment.BodyText
import cm.identishield.mobile.enrolment.DangerButton
import cm.identishield.mobile.enrolment.Disc
import cm.identishield.mobile.enrolment.ErrorText
import cm.identishield.mobile.enrolment.HairlineDivider
import cm.identishield.mobile.enrolment.HorizontalGap
import cm.identishield.mobile.enrolment.Page
import cm.identishield.mobile.enrolment.Pill
import cm.identishield.mobile.enrolment.PrimaryButton
import cm.identishield.mobile.enrolment.SurfaceCard
import cm.identishield.mobile.enrolment.Title
import cm.identishield.mobile.enrolment.VerticalGap
import cm.identishield.mobile.enrolment.formatMsisdn
import cm.identishield.mobile.ui.theme.Amber
import cm.identishield.mobile.ui.theme.AmberText
import cm.identishield.mobile.ui.theme.Border
import cm.identishield.mobile.ui.theme.Danger
import cm.identishield.mobile.ui.theme.Dimens
import cm.identishield.mobile.ui.theme.Ink
import cm.identishield.mobile.ui.theme.Spacing
import cm.identishield.mobile.ui.theme.Surface
import cm.identishield.mobile.ui.theme.TextSecondary
import cm.identishield.mobile.ui.theme.Tone
import java.time.ZoneId

/** Identifiants de test stables du flux F (MOB-NF-06). */
object AlertTags {
    const val LIST = "E-23"
    const val DETAIL = "E-24"
}

/** Titre d'une alerte selon son type (liste fermée du contrat). */
@Composable
fun alertTitle(type: String): String =
    stringResource(
        when (type) {
            "SIM_CHANGED" -> R.string.alert_type_sim_changed
            "REENROL_ATTEMPT" -> R.string.alert_type_reenrol_attempt
            "DEVICE_REBOUND" -> R.string.alert_type_device_rebound
            "TRANSFER_DEFERRED" -> R.string.alert_type_transfer_deferred
            "TRANSFER_FROZEN" -> R.string.alert_type_transfer_frozen
            else -> R.string.alert_title
        },
    )

private fun actionable(alert: Alert) = alert.status == AlertsViewModel.STATUS_OPEN && alert.actions.isNotEmpty()

// ---- E-23 Alertes -------------------------------------------------------------------------------

@Composable
fun AlertsListScreen(
    alerts: List<Alert>,
    onOpen: (Alert) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onSend: () -> Unit,
    onHistory: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    NavScaffold(NavTab.ALERTS, onHome = onHome, onSend = onSend, onHistory = onHistory, onAlerts = null) {
        Page(AlertTags.LIST, title = stringResource(R.string.alerts_title), onBack = onBack) {
            if (alerts.isEmpty()) BodyText(stringResource(R.string.alerts_empty))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                alerts.forEach { alert -> AlertCard(alert, zone) { onOpen(alert) } }
            }
        }
    }
}

/** Une alerte, une carte ; celle qui attend une réponse est sur fond ambre, avec « À traiter ». */
@Composable
private fun AlertCard(
    alert: Alert,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    val pending = actionable(alert)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(if (pending) Tone.WARNING.fill else Surface)
            .border(Dimens.Hairline, if (pending) Tone.WARNING.stroke else Border, MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(Spacing.L)
            .testTag("alert-${alert.alertId}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // Le titre cède la place au badge et passe à la ligne s'il est long.
            Text(
                alertTitle(alert.type),
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (pending) {
                HorizontalGap(Spacing.M)
                Text(
                    stringResource(R.string.alert_to_handle),
                    color = AmberText,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("to-handle-${alert.alertId}"),
                )
            }
        }
        VerticalGap(Spacing.XS)
        Text(formatWhen(alert.at, zone) + " · " + alert.summary, color = Ink, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---- E-24 Détail d'alerte -----------------------------------------------------------------------

/**
 * Quoi, quand ; « C'est moi » / « Ce n'est pas moi » signés, sur le téléphone lié seulement et tant
 * que l'alerte est ouverte. Le résumé vient du serveur et reste un texte : jamais de lien ni de code.
 */
@Composable
fun AlertDetailScreen(
    state: AlertsScreen.Detail,
    msisdn: String?,
    onAnswer: (notMe: Boolean) -> Unit,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val alert = state.alert
    Page(AlertTags.DETAIL, title = stringResource(R.string.alert_title), onBack = onBack.takeUnless { state.busy }) {
        Disc(R.drawable.ic_alerts, AmberText)
        VerticalGap(Spacing.L)
        Title(alertTitle(alert.type))
        VerticalGap(Spacing.S)
        BodyText(
            formatWhen(alert.at, zone) + " · " + alert.summary,
            align = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("alert-summary"),
        )
        VerticalGap(Spacing.L)
        SurfaceCard(contentPadding = PaddingValues(horizontal = Spacing.L, vertical = Spacing.XS)) {
            if (msisdn != null) {
                Row(Modifier.padding(vertical = Spacing.M), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.alert_number),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatMsisdn(msisdn), color = Ink, style = MaterialTheme.typography.titleMedium)
                }
                HairlineDivider()
            }
            Row(Modifier.padding(vertical = Spacing.M), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.alert_this_phone),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                // Vert : liaison toujours vérifiée par le serveur.
                Pill(stringResource(R.string.alert_still_bound), Tone.VERIFIED)
            }
        }
        state.problem?.let { ErrorText(problemText(it)) }
        VerticalGap(Spacing.XL)
        when {
            actionable(alert) -> {
                Text(
                    stringResource(R.string.alert_question),
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                VerticalGap(Spacing.M)
                // Bleu nuit, pas vert : le vert est réservé à un état vérifié par le serveur.
                if (AlertsViewModel.ACTION_ACK in alert.actions) {
                    PrimaryButton(stringResource(R.string.alert_yes), { onAnswer(false) }, enabled = !state.busy, tag = "answer-ack")
                    VerticalGap(Spacing.M)
                }
                if (AlertsViewModel.ACTION_NOT_ME in alert.actions) {
                    DangerButton(stringResource(R.string.alert_no), { onAnswer(true) }, enabled = !state.busy, tag = "answer-not-me")
                }
            }
            alert.status == "ACKNOWLEDGED" ->
                BodyText(stringResource(R.string.alert_answered_ack), align = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("answered"))
            alert.status == "NOT_ME" ->
                Text(
                    stringResource(R.string.alert_answered_not_me),
                    color = Danger,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("answered"),
                )
        }
        VerticalGap(Spacing.M)
        Text(
            stringResource(R.string.alert_never_code),
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
