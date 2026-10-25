package cm.identishield.mobile.enrolment

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import cm.identishield.mobile.R
import cm.identishield.mobile.security.SecureScreen
import cm.identishield.mobile.ui.theme.AmountType
import cm.identishield.mobile.ui.theme.Background
import cm.identishield.mobile.ui.theme.Border
import cm.identishield.mobile.ui.theme.DISC_TINT_ALPHA
import cm.identishield.mobile.ui.theme.Danger
import cm.identishield.mobile.ui.theme.Dimens
import cm.identishield.mobile.ui.theme.FieldBorder
import cm.identishield.mobile.ui.theme.Ink
import cm.identishield.mobile.ui.theme.Navy
import cm.identishield.mobile.ui.theme.PillShape
import cm.identishield.mobile.ui.theme.Spacing
import cm.identishield.mobile.ui.theme.Surface
import cm.identishield.mobile.ui.theme.TextSecondary
import cm.identishield.mobile.ui.theme.Tone

/**
 * Page standard. [title] : bandeau bleu nuit (chevron retour si [onBack]) ; [hero] : bloc pleine largeur
 * en tête de la zone qui défile (accueil). Le contenu défile quand il dépasse (paysage, police à 200 %),
 * et sa colonne est bornée à [Dimens.ContentMaxWidth] puis centrée (tablette).
 * [secure] = écran 🔒 des maquettes : capture bloquée (FLAG_SECURE, MOB-S-05) ET cadenas « capture
 * d'écran bloquée » devant le titre, toujours ensemble (décision du propriétaire du 2026-10-16 : un
 * cadenas discret plutôt que la grande pastille des maquettes).
 */
@Composable
fun Page(
    tag: String,
    modifier: Modifier = Modifier,
    secure: Boolean = false,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    hero: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (secure) SecureScreen()
    val navyTop = title != null || hero != null
    StatusBarIcons(lightIcons = navyTop)
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Background)
                .testTag(tag),
    ) {
        if (title != null) ScreenHeader(title, onBack, secure)
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding(),
        ) {
            val viewport = maxHeight
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                hero?.invoke()
                Column(
                    Modifier
                        .widthIn(max = Dimens.ContentMaxWidth)
                        .fillMaxWidth()
                        // Hauteur minimale = la fenêtre : les Spacer(weight) poussent le bouton en bas
                        // quand tout tient ; sinon la colonne s'allonge et défile.
                        .heightIn(min = if (hero == null) viewport else 0.dp)
                        .then(if (navyTop) Modifier else Modifier.statusBarsPadding())
                        .navigationBarsPadding()
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                        .padding(horizontal = Spacing.XL, vertical = Spacing.L),
                ) {
                    if (secure && title == null) CaptureBlockedBadge(Modifier.align(Alignment.CenterHorizontally), Navy)
                    content()
                }
            }
        }
    }
}

/** Icônes de la barre d'état : claires sur le bandeau bleu nuit, sombres sur le fond clair. */
@Composable
private fun StatusBarIcons(lightIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !lightIcons
    }
}

/**
 * Bandeau des écrans secondaires : passe sous la barre d'état, chevron retour, titre centré blanc ;
 * [secure] : cadenas devant le titre, comme celui d'une adresse sûre dans un navigateur (un état, pas un bouton).
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    secure: Boolean = false,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Navy)
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .heightIn(min = Dimens.HeaderBar)
                .padding(horizontal = Spacing.XS),
        contentAlignment = Alignment.Center,
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .testTag("back"),
            ) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back), tint = Surface) }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Dimens.TouchTarget + Spacing.XS, vertical = Spacing.S),
        ) {
            if (secure) {
                CaptureBlockedBadge(tint = Surface)
                HorizontalGap(Spacing.S)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Surface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

/** Cadenas des écrans 🔒 (maquettes E-06, E-08, E-09, E-14, E-15) : annoncé « capture d'écran bloquée ». */
@Composable
fun CaptureBlockedBadge(
    modifier: Modifier = Modifier,
    tint: Color,
) {
    Icon(
        painterResource(R.drawable.ic_lock),
        contentDescription = stringResource(R.string.capture_blocked),
        tint = tint,
        modifier =
            modifier
                .size(Dimens.SmallIcon)
                .testTag("capture-blocked"),
    )
}

/** Bouton principal : pleine largeur, ≥ 56 dp, bleu nuit actif, gris désactivé. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tag: String = "primary",
) = FilledButton(text, onClick, enabled, tag, Navy)

/** Action destructrice (annuler un envoi, « Ce n'est pas moi ») : blanc sur rouge, 4,96:1. */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tag: String,
) = FilledButton(text, onClick, enabled, tag, Danger)

@Composable
private fun FilledButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    tag: String,
    color: Color,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = color,
                contentColor = Surface,
                disabledContainerColor = Border,
                disabledContentColor = TextSecondary,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.Button)
                .testTag(tag),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
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
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(Dimens.Hairline, if (enabled) Navy else Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Navy, disabledContentColor = TextSecondary),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.Button)
                .testTag(tag),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

/** Action textuelle (« Annuler », « Accueil », « J'ai perdu ma clé ») : pleine largeur, cible ≥ 48 dp. */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    tag: String,
) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.TouchTarget)
                .testTag(tag),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

/** Encadré : avertissement (texte encre sur ambre dilué), danger (rouge sur blanc), vérifié (vert assombri). */
@Composable
fun Callout(
    text: AnnotatedString,
    tone: Tone = Tone.WARNING,
    tag: String = "warning",
) {
    Text(
        text = text,
        color = if (tone == Tone.WARNING) Ink else tone.text,
        style = MaterialTheme.typography.bodyMedium,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(tone.fill)
                .border(Dimens.Hairline, tone.stroke, MaterialTheme.shapes.medium)
                .padding(Spacing.L)
                .testTag(tag),
    )
}

@Composable
fun Callout(
    text: String,
    tone: Tone = Tone.WARNING,
    tag: String = "warning",
) = Callout(AnnotatedString(text), tone, tag)

/** Pastille de statut ou d'action. */
@Composable
fun Pill(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        color = tone.text,
        style = MaterialTheme.typography.labelMedium,
        modifier =
            modifier
                .clip(PillShape)
                .background(tone.fill)
                .border(Dimens.Hairline, tone.stroke, PillShape)
                .padding(horizontal = Spacing.M, vertical = Spacing.XS),
    )
}

/** Carte blanche arrondie à filet fin. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.L, vertical = Spacing.M),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(Surface)
                .border(Dimens.Hairline, Border, MaterialTheme.shapes.large)
                .padding(contentPadding),
        content = content,
    )
}

/** Filet de séparation horizontal. */
@Composable
fun HairlineDivider() =
    Box(
        Modifier
            .fillMaxWidth()
            .height(Dimens.Hairline)
            .background(Border),
    )

/**
 * Champ de formulaire : grand rectangle arrondi, libellé À L'INTÉRIEUR, astérisque si requis.
 * [field] : le champ de saisie du cadre ; un appui n'importe où dans le cadre lui donne le focus et
 * ouvre le clavier (charte point 2 : tout le champ est touchable). Geste seul, sans sémantique ajoutée :
 * les lecteurs d'écran atteignent le champ lui-même.
 */
@Composable
fun InputBox(
    label: String,
    required: Boolean = true,
    field: FocusRequester? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Surface)
            .border(Dimens.Hairline, FieldBorder, MaterialTheme.shapes.medium)
            .then(
                if (field == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(field) {
                        detectTapGestures {
                            field.requestFocus()
                            keyboard?.show()
                        }
                    }
                },
            ).padding(horizontal = Spacing.L, vertical = Spacing.M),
    ) {
        Text(if (required) "$label *" else label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        VerticalGap(Spacing.XS)
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** Zone de saisie d'un champ : centrée verticalement dans une cible tactile d'au moins 48 dp. */
val CenteredInput: @Composable (@Composable () -> Unit) -> Unit = { inner ->
    Box(Modifier.heightIn(min = Dimens.TouchTarget), contentAlignment = Alignment.CenterStart) { inner() }
}

/** Libellé de section (au-dessus d'une liste ou d'un rappel). */
@Composable
fun SectionLabel(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, color = TextSecondary)

@Composable
fun ErrorText(
    text: String,
    tag: String = "error",
) {
    Text(
        text = text,
        color = Danger,
        style = MaterialTheme.typography.bodyMedium,
        modifier =
            Modifier
                .padding(top = Spacing.S)
                .testTag(tag),
    )
}

/** Titre centré d'un écran sans bandeau. */
@Composable
fun Title(text: String) =
    Text(
        text,
        color = Ink,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { heading() }
                .testTag("title"),
    )

/**
 * Disque coloré portant une icône (écrans de résultat et d'attente). Icône vectorielle teintée dans la
 * palette, jamais un émoji : chaque fabricant dessine les émojis à sa façon (bouclier rouge chez Samsung,
 * constaté sur la tablette le 2026-10-17). Décoratif : le titre de l'écran porte le sens.
 */
@Composable
fun ColumnScope.Disc(
    icon: Int,
    color: Color,
) {
    Box(
        Modifier
            .size(Dimens.Disc)
            .clip(CircleShape)
            .background(color.copy(alpha = DISC_TINT_ALPHA))
            .align(Alignment.CenterHorizontally),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(Dimens.DiscIcon)) }
}

/** Montant : chiffres à chasse fixe, espaces de groupe affinées ; [fit] le réduit pour tenir sur une ligne. */
@Composable
fun AmountText(
    value: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    fit: Boolean = false,
) {
    Text(
        text = thinGroupSpaces(value),
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        autoSize = if (fit) TextAutoSize.StepBased(minFontSize = AmountType.Row.fontSize, maxFontSize = style.fontSize) else null,
        modifier = modifier,
    )
}

/** Le texte reste « 152 300 » (lecteurs d'écran, tests) ; seule l'espace est dessinée plus étroite. */
private fun thinGroupSpaces(value: String) =
    buildAnnotatedString {
        value.forEach { c -> if (c == ' ') withStyle(SpanStyle(letterSpacing = AmountType.GroupSpace)) { append(c) } else append(c) }
    }

/** Pavé numérique des maquettes (E-05, E-06) : aucun champ texte, donc aucun presse-papiers (MOB-S-06). */
@Composable
fun Keypad(
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
) {
    val deleteLabel = stringResource(R.string.keypad_delete)
    val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf(null, '0', '⌫'))
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                row.forEach { key ->
                    val base =
                        Modifier
                            .weight(1f)
                            .heightIn(min = Dimens.Key)
                            .clip(MaterialTheme.shapes.small)
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
                .background(Surface)
                .border(Dimens.Hairline, Border, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.headlineSmall, color = Ink) }
}

/** Points de saisie du PIN : autant de points que de chiffres tapés (longueur non fixée). */
@Composable
fun PinDots(
    count: Int,
    tag: String = "pin-dots",
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.M, Alignment.CenterHorizontally),
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (count == 0) {
            Box(
                Modifier
                    .size(Dimens.PinDot)
                    .border(Dimens.Hairline * 2, FieldBorder, CircleShape),
            )
        }
        repeat(count) {
            Box(
                Modifier
                    .size(Dimens.PinDot)
                    .clip(CircleShape)
                    .background(Navy),
            )
        }
    }
}

@Composable
fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
) {
    Text(text = text, color = TextSecondary, style = MaterialTheme.typography.bodyLarge, textAlign = align, modifier = modifier)
}

@Composable
fun VerticalGap(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HorizontalGap(width: Dp) = Spacer(Modifier.width(width))

/** 677000123 → « 677 000 123 ». */
fun formatMsisdn(msisdn: String): String = msisdn.chunked(3).joinToString(" ")

/**
 * Saisie d'un numéro groupée à l'affichage (« 677 000 200 », D8 / B1b) : la valeur reste les neuf
 * chiffres bruts, seul l'affichage insère une espace tous les trois chiffres. Le curseur suit.
 */
object MsisdnTransformation : androidx.compose.ui.text.input.VisualTransformation {
    private const val GROUP = 3

    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val raw = text.text
        return androidx.compose.ui.text.input.TransformedText(
            androidx.compose.ui.text.AnnotatedString(formatMsisdn(raw)),
            object : androidx.compose.ui.text.input.OffsetMapping {
                override fun originalToTransformed(offset: Int) = offset + if (offset == 0) 0 else (offset - 1) / GROUP

                override fun transformedToOriginal(offset: Int) = (offset - offset / (GROUP + 1)).coerceAtMost(raw.length)
            },
        )
    }
}

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

/** Clé de récupération groupée par [CODE_GROUP] pour la recopie sur papier (maquette E-08) : « K7M-2QX ». */
fun groupRecoveryCode(code: String): List<String> = code.chunked(CODE_GROUP).chunked(2).map { it.joinToString("-") }
