package cm.identishield.mobile.daily

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cm.identishield.mobile.R
import cm.identishield.mobile.data.Alert
import cm.identishield.mobile.enrolment.BackHeader
import cm.identishield.mobile.enrolment.BodyText
import cm.identishield.mobile.enrolment.ErrorText
import cm.identishield.mobile.enrolment.Hairline
import cm.identishield.mobile.enrolment.Page
import cm.identishield.mobile.enrolment.Secondary
import cm.identishield.mobile.enrolment.VerticalGap
import cm.identishield.mobile.enrolment.formatMsisdn
import cm.identishield.mobile.ui.theme.Amber
import cm.identishield.mobile.ui.theme.Danger
import cm.identishield.mobile.ui.theme.Ink
import cm.identishield.mobile.ui.theme.ProtectedGreen
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
    Page(AlertTags.LIST) {
        BackHeader(stringResource(R.string.alerts_title), onBack)
        VerticalGap(12)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (alerts.isEmpty()) BodyText(stringResource(R.string.alerts_empty))
            alerts.forEach { alert ->
                val pending = actionable(alert)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (pending) Amber.copy(alpha = 0.16f) else Color.White)
                        .border(1.dp, if (pending) Amber.copy(alpha = 0.45f) else Hairline, RoundedCornerShape(20.dp))
                        .clickable { onOpen(alert) }
                        .padding(20.dp)
                        .testTag("alert-${alert.alertId}"),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        // Le titre cède la place au badge et passe à la ligne s'il est long.
                        Text(
                            alertTitle(alert.type),
                            color = Ink,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (pending) {
                            Text(
                                "   " + stringResource(R.string.alert_to_handle),
                                color = Amber,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("to-handle-${alert.alertId}"),
                            )
                        }
                    }
                    Text(formatWhen(alert.at, zone) + " · " + alert.summary, color = Ink, fontSize = 16.sp)
                }
            }
        }
        BottomNav(NavTab.ALERTS, onHome = onHome, onSend = onSend, onHistory = onHistory, onAlerts = null)
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
    Page(AlertTags.DETAIL) {
        BackHeader(stringResource(R.string.alert_title), onBack.takeUnless { state.busy })
        VerticalGap(12)
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Amber.copy(alpha = 0.16f))
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center,
        ) { Text("📶", fontSize = 38.sp) }
        VerticalGap(16)
        Text(
            alertTitle(alert.type),
            color = Ink,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        VerticalGap(8)
        BodyText(
            formatWhen(alert.at, zone) + " · " + alert.summary,
            align = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("alert-summary"),
        )
        VerticalGap(16)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 6.dp),
        ) {
            if (msisdn != null) {
                Row(Modifier.padding(vertical = 10.dp)) {
                    Text(stringResource(R.string.alert_number), color = Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text(formatMsisdn(msisdn), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.alert_this_phone), color = Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.alert_still_bound),
                    color = ProtectedGreen,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(ProtectedGreen.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        state.problem?.let { ErrorText(problemText(it)) }
        VerticalGap(16)
        when {
            actionable(alert) -> {
                Text(
                    stringResource(R.string.alert_question),
                    color = Ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                VerticalGap(12)
                if (AlertsViewModel.ACTION_ACK in alert.actions) {
                    AnswerButton(stringResource(R.string.alert_yes), ProtectedGreen, enabled = !state.busy, tag = "answer-ack") { onAnswer(false) }
                    VerticalGap(10)
                }
                if (AlertsViewModel.ACTION_NOT_ME in alert.actions) {
                    AnswerButton(stringResource(R.string.alert_no), Danger, enabled = !state.busy, tag = "answer-not-me") { onAnswer(true) }
                }
            }
            alert.status == "ACKNOWLEDGED" ->
                BodyText(stringResource(R.string.alert_answered_ack), align = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("answered"))
            alert.status == "NOT_ME" ->
                Text(
                    stringResource(R.string.alert_answered_not_me),
                    color = Danger,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("answered"),
                )
        }
        VerticalGap(10)
        Text(
            stringResource(R.string.alert_never_code),
            color = Secondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AnswerButton(
    text: String,
    color: Color,
    enabled: Boolean,
    tag: String,
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
                .background(if (enabled) color else color.copy(alpha = 0.4f))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 16.dp)
                .testTag(tag),
    )
}
