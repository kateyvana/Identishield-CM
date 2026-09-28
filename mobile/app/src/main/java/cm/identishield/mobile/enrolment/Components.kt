package cm.identishield.mobile.enrolment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cm.identishield.mobile.R
import cm.identishield.mobile.ui.theme.Amber
import cm.identishield.mobile.ui.theme.Background
import cm.identishield.mobile.ui.theme.Danger
import cm.identishield.mobile.ui.theme.Ink
import cm.identishield.mobile.ui.theme.Navy

/** Gris des textes secondaires : le texte à 65 % (la palette n'a pas de gris ; écart documenté). */
val Secondary = Ink.copy(alpha = 0.65f)
val Hairline = Ink.copy(alpha = 0.12f)

/** Page standard : fond, marges, barre système respectée. */
@Composable
fun Page(
    tag: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Background)
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .testTag(tag),
        content = content,
    )
}

@Composable
fun BackHeader(
    title: String,
    onBack: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        if (onBack != null) {
            val label = stringResource(R.string.back)
            Text(
                text = "‹",
                fontSize = 32.sp,
                color = Navy,
                modifier =
                    Modifier
                        .clickable(onClick = onBack)
                        .semantics { contentDescription = label }
                        .testTag("back")
                        .padding(end = 16.dp),
            )
        }
        Text(text = title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tag: String = "primary",
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Navy, contentColor = Color.White),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag(tag),
    ) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tag: String = "secondary",
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (enabled) Navy else Hairline),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag(tag),
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (enabled) Navy else Secondary) }
}

/** Encadré ambre (avertissement) : fond ambre dilué, texte de la palette. */
@Composable
fun WarningBox(
    content: @Composable () -> Unit,
    tag: String = "warning",
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Amber.copy(alpha = 0.16f))
                .border(1.dp, Amber.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                .padding(16.dp)
                .testTag(tag),
    ) { content() }
}

@Composable
fun ErrorText(
    text: String,
    tag: String = "error",
) {
    Text(
        text = text,
        color = Danger,
        fontSize = 15.sp,
        modifier =
            Modifier
                .padding(top = 8.dp)
                .testTag(tag),
    )
}

/** Pavé numérique des maquettes (E-05, E-06) : aucun champ texte, donc aucun presse-papiers (MOB-S-06). */
@Composable
fun Keypad(
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
) {
    val deleteLabel = stringResource(R.string.keypad_delete)
    val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf(null, '0', '⌫'))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    val base =
                        Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clip(RoundedCornerShape(14.dp))
                    when (key) {
                        null -> Spacer(base)
                        '⌫' ->
                            KeyCell(
                                "⌫",
                                base
                                    .clickable(onClick = onDelete)
                                    .semantics { contentDescription = deleteLabel }
                                    .testTag("key-delete"),
                            )
                        else -> KeyCell(key.toString(), base.clickable { onDigit(key) }.testTag("key-$key"))
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCell(
    label: String,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .background(Color.White)
                .border(1.dp, Hairline, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 28.sp, color = Ink) }
}

/** Points de saisie du PIN : autant de points que de chiffres tapés (longueur non fixée). */
@Composable
fun PinDots(
    count: Int,
    tag: String = "pin-dots",
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        repeat(maxOf(count, 1)) { i ->
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (i < count) Navy else Hairline),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
) {
    Text(text = text, color = Secondary, fontSize = 17.sp, lineHeight = 25.sp, textAlign = align, modifier = modifier)
}

@Composable
fun VerticalGap(height: Int) = Spacer(Modifier.height(height.dp))

@Composable
fun HorizontalGap(width: Int) = Spacer(Modifier.width(width.dp))

/** 677000123 → « 677 000 123 ». */
fun formatMsisdn(msisdn: String): String = msisdn.chunked(3).joinToString(" ")

/** 677000123 → « 677 ••• 123 » (numéro masqué, MOB-S-11). */
fun maskMsisdn(msisdn: String): String =
    if (msisdn.length == 9) "${msisdn.take(3)} ••• ${msisdn.takeLast(3)}" else "•••"

/** 152300 → « 152 300 ». */
fun formatXaf(amount: Long): String =
    amount
        .toString()
        .reversed()
        .chunked(3)
        .joinToString(" ")
        .reversed()

/** Clé de récupération groupée par 4 pour la recopie sur papier (maquette E-08). */
fun groupRecoveryCode(code: String): List<String> = code.chunked(4).chunked(2).map { it.joinToString("-") }
