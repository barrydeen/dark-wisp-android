package com.darkwisp.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.automirrored.outlined.Message

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.darkwisp.app.R
import com.darkwisp.app.nostr.NipA3
import com.darkwisp.app.repo.FiatPreferences
import com.darkwisp.app.repo.ZapPreferences
import com.darkwisp.app.repo.ZapPreset
import com.darkwisp.app.ui.theme.WispThemeColors
import com.darkwisp.app.ui.util.AmountFormatter
import androidx.compose.runtime.collectAsState
import kotlin.math.sin
import kotlin.random.Random

/**
 * Zap composer — iOS-faithful layout in a draggable bottom sheet.
 *
 * Layout, top to bottom:
 *   1. Toolbar           — Close (left, pill) / Presets (right, orange pill)
 *   2. Recipient row     — avatar + display name + lud16 + copy
 *                          (hidden if no `profileLookup` data for the
 *                          `recipientPubkey`)
 *   3. Hero amount       — editable BasicTextField styled as the big
 *                          orange number; doubles as the amount input,
 *                          matching iOS.
 *   4. Preset strip      — wrapping FlowRow of pills + Custom-with-plus chip
 *   5. Message field     — single-line OutlinedTextField
 *   6. Privacy dropdown  — Public / Anonymous / Private with helper text
 *   7. Instant zaps      — toggle bound to `quickZapEnabled` setting
 *   8. Zap button        — full-width orange action button. Over 1M sats
 *                          disables it; over 10K routes through a
 *                          soft-confirmation dialog.
 *
 * Wrapping `ModalBottomSheet` provides drag-handle dismiss, scrim-tap
 * dismiss, and a partial-height presentation so the sheet doesn't take
 * over the whole viewport — fixes the "impossible to dismiss" complaint
 * from the previous Dialog-based version.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ZapDialog(
    isWalletConnected: Boolean,
    onDismiss: () -> Unit,
    onZap: (amountMsats: Long, message: String, isAnonymous: Boolean, isPrivate: Boolean) -> Unit,
    onGoToWallet: () -> Unit,
    /**
     * Per-account preset store. Must be the same instance the
     * `AppSettingsRepository` registered its `onSyncedFieldChanged`
     * callback on, otherwise preset writes from the dialog land in a
     * different SharedPreferences file than NIP-78 reads from on
     * publish/restore — the symptom is presets appearing not to sync
     * between Android and iOS.
     */
    zapPrefsRepo: ZapPreferences,
    canPrivateZap: Boolean = false,
    /**
     * Lock the zap to DIP-03 private mode (private + anon toggles hidden, isPrivate held true).
     * Used when zapping a NIP-17 private reply — falling back to a public zap would attach an
     * e-tag pointing at the rumor id on public relays.
     */
    forcePrivate: Boolean = false,
    /** When opening from a quick preset (e.g. chat actions sheet), pre-select that amount in sats. */
    initialSatsHint: Int? = null,
    /** Note author / zap recipient; enables the NIP-A3 "Other ways to pay" section. */
    recipientPubkey: String? = null,
    /** False when the recipient's profile has no lightning address. */
    recipientHasLud16: Boolean = true,
    /** Loads the recipient's NIP-A3 payment targets (FeedViewModel::fetchPaymentTargets). */
    fetchPaymentTargets: (suspend (String) -> List<NipA3.PaymentTarget>)? = null
) {
    var paymentTargets by remember { mutableStateOf<List<NipA3.PaymentTarget>>(emptyList()) }
    var selectedTarget by remember { mutableStateOf<NipA3.PaymentTarget?>(null) }

    LaunchedEffect(recipientPubkey) {
        val pk = recipientPubkey ?: return@LaunchedEffect
        val fetch = fetchPaymentTargets ?: return@LaunchedEffect
        paymentTargets = fetch(pk)
    }

    selectedTarget?.let { target ->
        PaymentTargetSheet(target = target) { selectedTarget = null }
    }

    // Lightning zapping needs a connected wallet and a recipient lightning address.
    // When either is missing but the author published NIP-A3 payment targets, show
    // those instead of dead-ending.
    if (!isWalletConnected || !recipientHasLud16) {
        if (paymentTargets.isNotEmpty()) {
            PaymentTargetsOnlyDialog(
                targets = paymentTargets,
                showGoToWallet = !isWalletConnected,
                onTargetClick = { selectedTarget = it },
                onGoToWallet = onGoToWallet,
                onDismiss = onDismiss
            )
            return
        }
        if (!isWalletConnected) {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.zap_wallet_not_connected)) },
                text = { Text(stringResource(R.string.zap_connect_wallet)) },
                confirmButton = {
                    TextButton(onClick = {
                        onDismiss()
                        onGoToWallet()
                    }) {
                        Text(stringResource(R.string.btn_go_to_wallet))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
                }
            )
            return
        }
        // Wallet connected but no lud16 and no payment targets: fall through to the
        // regular dialog — the zap send surfaces the missing-lightning-address error,
        // matching pre-NIP-A3 behavior.
    }

    val context = LocalContext.current
    val fiatPrefs = remember { FiatPreferences.get(context) }
    val fiatMode by fiatPrefs.fiatMode.collectAsState()
    val fiatCurrency by fiatPrefs.currency.collectAsState()
    val interfacePrefs = remember { com.darkwisp.app.repo.InterfacePreferences(context) }
    var presets by remember { mutableStateOf(zapPrefsRepo.getPresets().sortedBy { it.amountSats }) }
    var selectedPreset by remember { mutableStateOf<ZapPreset?>(presets.firstOrNull()) }
    var isCustom by remember { mutableStateOf(false) }
    var customAmount by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var isAnonymous by remember { mutableStateOf(false) }
    var isPrivate by remember(forcePrivate) { mutableStateOf(forcePrivate) }
    var instantZapsEnabled by remember { mutableStateOf(interfacePrefs.isQuickZapEnabled()) }
    var showLargeAmountConfirm by remember { mutableStateOf(false) }
    var showEditPresetsSheet by remember { mutableStateOf(false) }
    var privacyMenuExpanded by remember { mutableStateOf(false) }
    val amountFocusRequester = remember { FocusRequester() }

    val recipientProfile = recipientPubkey?.let { profileLookup(it) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun closeSheet() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    LaunchedEffect(initialSatsHint) {
        val hint = initialSatsHint ?: return@LaunchedEffect
        val h = hint.toLong().coerceAtLeast(1L)
        val fromPrefs = ZapPreferences(context).getPresets().sortedBy { it.amountSats }
        val match = fromPrefs.find { it.amountSats == h }
        if (match != null) {
            selectedPreset = match
            isCustom = false
            message = match.message
        } else {
            isCustom = true
            customAmount = h.toString()
            message = ""
        }
    }

    val effectiveAmount = if (isCustom) {
        customAmount.toLongOrNull() ?: 0L
    } else {
        selectedPreset?.amountSats ?: 0L
    }

    val effectiveMessage = if (isCustom) message else (selectedPreset?.message ?: "")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Two-row stack: scrollable content on top, pinned Zap button
        // at the bottom. `fillMaxSize()` locks the sheet to the full
        // available height from open — without it the sheet sizes to
        // content, and the keyboard rising 450ms later forces a second
        // layout pass that visibly jumps the sheet taller. `imePadding`
        // then lifts the stack above the keyboard within the fixed
        // sheet bounds so the Zap button stays visible.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            // ── 1. Toolbar ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PillButton(text = stringResource(R.string.btn_close), onClick = { closeSheet() })
                PillButton(
                    text = "Presets",
                    onClick = { showEditPresetsSheet = true },
                    contentColor = accent,
                    borderColor = accent.copy(alpha = 0.45f)
                )

                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header with animated bolt
                    AnimatedBoltHeader()

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = stringResource(
                            if (fiatMode) R.string.zap_send_money else R.string.zap_send
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(Modifier.height(4.dp))

                    // Amount display — tap to edit directly
                    if (isCustom) {
                        BasicTextField(
                            value = customAmount,
                            onValueChange = { customAmount = it.filter { c -> c.isDigit() } },
                            textStyle = MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = LightningOrange,
                                textAlign = TextAlign.Center
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            cursorBrush = SolidColor(LightningOrange),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.Center) {
                                    if (customAmount.isEmpty()) {
                                        Text(
                                            text = "0",
                                            style = MaterialTheme.typography.displaySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = LightningOrange.copy(alpha = 0.3f)
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                        if (!fiatMode) {
                            Text(
                                text = stringResource(R.string.zap_sats),
                                style = MaterialTheme.typography.labelLarge,
                                color = LightningOrange.copy(alpha = 0.7f)
                            )
                        }
                    } else if (effectiveAmount > 0) {
                        Text(
                            text = AmountFormatter.formatShort(effectiveAmount, context),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = LightningOrange,
                            modifier = Modifier
                                .clickable {
                                    customAmount = effectiveAmount.toString()
                                    isCustom = true
                                }
                                .padding(vertical = 4.dp)
                        )
                        if (!fiatMode) {
                            Text(
                                text = stringResource(R.string.zap_sats),
                                style = MaterialTheme.typography.labelLarge,
                                color = LightningOrange.copy(alpha = 0.7f)
                            )
                        }
                    }

            // ── 3. Hero amount (editable) ───────────────────────────
            // The hero IS the input — matches iOS. Typed digits update
            // the value directly; preset taps seed it; visual
            // transformation inserts thousands separators in bitcoin
            // mode so the displayed number stays readable while the
            // underlying state stays as raw digits.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val heroStyle = TextStyle(
                    color = accent,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                // Hide the text-selection background so the seeded
                // select-all (which powers first-keystroke-replaces-seed)
                // doesn't paint an ugly box behind the hero number. iOS
                // achieves the same UX with no visible selection rect.
                val invisibleSelection = remember(accent) {
                    TextSelectionColors(
                        handleColor = accent,
                        backgroundColor = Color.Transparent
                    )
                }
                CompositionLocalProvider(LocalTextSelectionColors provides invisibleSelection) {
                    BasicTextField(
                        value = customAmountTfv,
                        onValueChange = { newTfv ->
                            val filtered = newTfv.text.filter { it.isDigit() }
                            customAmountTfv = newTfv.copy(text = filtered)
                            if (filtered.isNotEmpty()) isCustom = true
                        },
                        textStyle = heroStyle,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        cursorBrush = SolidColor(accent),
                        visualTransformation = if (fiatMode) VisualTransformation.None
                            else ThousandsSeparatorTransformation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(amountFocusRequester),
                        decorationBox = { inner ->
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (customAmountTfv.text.isEmpty()) {
                                    Text(
                                        "0",
                                        style = heroStyle.copy(color = accent.copy(alpha = 0.35f))
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }
                Text(
                    if (fiatMode) ExchangeRateRepository.currencyFor(fiatCurrency).code else "sats",
                    color = accent.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.titleSmall
                )
            }

            // ── 4. Preset strip ─────────────────────────────────────
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    val selected = !isCustom && selectedPreset?.amountSats == preset.amountSats
                    PresetPill(
                        label = AmountFormatter.formatShort(preset.amountSats, context),
                        selected = selected,
                        accent = accent,
                        onClick = {
                            selectedPreset = preset
                            isCustom = false
                            // Seed the hero with the preset's value so
                            // the big number reflects the selection.
                            seedCustomAmount(preset.amountSats.toString()) { customAmountTfv = it }
                            // Auto-fill the preset's optional default
                            // message only when the message field is
                            // currently empty (don't clobber typing).
                            if (preset.message.isNotEmpty() && message.isBlank()) {
                                message = preset.message
                            }
                        }

                    )
                }
                CustomPlusPill(
                    label = if (isCustom && effectiveAmount > 0)
                        AmountFormatter.formatShort(effectiveAmount, context)
                    else "Custom",
                    selected = isCustom,
                    accent = accent,
                    showPlus = canSavePreset,
                    onClick = {
                        isCustom = true
                        if (effectiveAmount == 0L) {
                            customAmountTfv = TextFieldValue("")
                        } else {
                            // Re-seed and select-all so first keystroke replaces.
                            seedCustomAmount(customAmount) { customAmountTfv = it }
                        }
                        runCatching { amountFocusRequester.requestFocus() }
                    },
                    onPlusClick = {
                        // Save the current custom amount as a new preset
                        if (canSavePreset) {
                            presets = zapPrefsRepo.addPreset(
                                ZapPreset(effectiveAmount, message.trim())
                            ).sortedBy { it.amountSats }
                        }
                    }
                )
            }

            // ── 5. Message ──────────────────────────────────────────
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                placeholder = { Text("Message (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // ── 6. Privacy dropdown ─────────────────────────────────
            if (!forcePrivate) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { privacyMenuExpanded = true },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.zap_quick_amounts),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row {
                            if (editMode) {
                                TextButton(onClick = { editMode = false }) {
                                    Text(stringResource(R.string.btn_done), color = LightningOrange, fontSize = 12.sp)
                                }
                            } else {
                                IconButton(
                                    onClick = { editMode = true },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.btn_edit),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Preset amount chips
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        presets.forEach { preset ->
                            ZapPresetChip(
                                preset = preset,
                                isSelected = !isCustom && selectedPreset == preset,
                                editMode = editMode,
                                onClick = {
                                    if (!editMode) {
                                        selectedPreset = preset
                                        isCustom = false
                                        message = preset.message
                                    }
                                },
                                onRemove = {
                                    presets = ZapPreferences(context).removePreset(preset)
                                    if (selectedPreset == preset) {
                                        selectedPreset = presets.firstOrNull()
                                    }
                                }
                            )
                        }

            // ── 7. Instant zaps toggle ──────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bolt),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (fiatMode) "Instant payments" else "Instant zaps",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = instantZapsEnabled,
                        onCheckedChange = {
                            instantZapsEnabled = it
                            interfacePrefs.setQuickZapEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = accent,
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surface
                        )
                    )

                    // Clear message when switching away from a preset with a saved message
                    // (message is pre-filled when selecting a preset with a message)

            // ── 8. Zap button — pinned to the bottom of the sheet ──
            // Lives outside the scrollable region above so it stays on
            // screen even when the keyboard is up. The outer Column's
            // imePadding() ensures it floats above the IME.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (overHardCap) {
                    Text(
                        "Max ${"%,d".format(ZAP_HARD_CAP_SATS)} sats per zap",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
                Button(
                    onClick = {
                        if (effectiveAmount > ZAP_SOFT_CONFIRM_SATS) {
                            showLargeAmountConfirm = true
                        } else {
                            onZap(effectiveAmount * 1000, effectiveMessage.ifEmpty { message }, isAnonymous, isPrivate)
                        }
                    } else {
                    // Anonymous toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.btn_anonymous),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = isAnonymous,
                            onCheckedChange = {
                                isAnonymous = it
                                if (it) isPrivate = false
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = LightningOrange,
                                checkedTrackColor = LightningOrange.copy(alpha = 0.5f),
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                uncheckedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                    }

                    // Private toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.btn_private),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (canPrivateZap) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            if (!canPrivateZap) {
                                Text(
                                    text = stringResource(R.string.zap_both_parties_need_dm_relays),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                        Switch(
                            checked = isPrivate,
                            onCheckedChange = {
                                isPrivate = it
                                if (it) isAnonymous = false
                            },
                            enabled = canPrivateZap,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = LightningOrange,
                                checkedTrackColor = LightningOrange.copy(alpha = 0.5f),
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                uncheckedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                    }
                    } // end !forcePrivate

                    // NIP-A3 payment targets
                    if (paymentTargets.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.zap_other_ways_to_pay),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            paymentTargets.forEach { target ->
                                PaymentTargetChip(target) { selectedTarget = target }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.btn_cancel))
                        }

                        Button(
                            onClick = {
                                onZap(effectiveAmount * 1000, effectiveMessage.ifEmpty { message }, isAnonymous, isPrivate)
                            },
                            enabled = effectiveAmount > 0,
                            modifier = Modifier.weight(2f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LightningOrange,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_bolt),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (fiatMode) {
                                    stringResource(
                                        R.string.zap_x_amount,
                                        AmountFormatter.formatShort(effectiveAmount, context)
                                    )
                                } else {
                                    stringResource(R.string.zap_x_sats, effectiveAmount)
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // 10K-sat soft-confirmation dialog. Large zaps surface a "double-check
    // before sending" prompt so a stray preset tap doesn't drain a wallet.
    if (showLargeAmountConfirm) {
        AlertDialog(
            onDismissRequest = { showLargeAmountConfirm = false },
            title = { Text("Zap %,d sats?".format(effectiveAmount)) },
            text = { Text("This is a large amount, double-check before sending.") },
            confirmButton = {
                Button(
                    onClick = {
                        showLargeAmountConfirm = false
                        onZap(effectiveAmount * 1000, effectiveMessage.ifEmpty { message }, isAnonymous, isPrivate)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) { Text("Send", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showLargeAmountConfirm = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (showEditPresetsSheet) {
        EditPresetsSheet(
            initial = presets,
            accent = accent,
            onDismiss = { showEditPresetsSheet = false },
            onSave = { newList ->
                zapPrefsRepo.setPresets(newList)
                presets = newList.sortedBy { it.amountSats }
                showEditPresetsSheet = false
            }
        )
    }
}

/**
 * Shown instead of the zap dialog when lightning zapping isn't possible
 * (no wallet or no lud16) but the author published NIP-A3 payment targets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentTargetsOnlyDialog(
    targets: List<NipA3.PaymentTarget>,
    showGoToWallet: Boolean,
    onTargetClick: (NipA3.PaymentTarget) -> Unit,
    onGoToWallet: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(28.dp),
            color = WispThemeColors.backgroundColor,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.zap_other_ways_to_pay),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(16.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    targets.forEach { target ->
                        PaymentTargetChip(target) { onTargetClick(target) }
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (showGoToWallet) {
                    TextButton(onClick = {
                        onDismiss()
                        onGoToWallet()
                    }) {
                        Text(stringResource(R.string.zap_connect_wallet_to_zap))
                    }
                }

                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        }
    }
}

@Composable
private fun PaymentTargetChip(target: NipA3.PaymentTarget, onClick: () -> Unit) {
    val label = buildString {
        NipA3.symbol(target.type)?.let { append(it).append(' ') }
        append(NipA3.displayName(target.type))
    }
    ZapChipButton(
        label = label,
        isSelected = false,
        onClick = onClick
    )
}

/**
 * Register-style sanitiser: digits only. The fiat-mode amount field is
 * interpreted as integer cents (last two digits = cents place), so
 * decimal points and other punctuation in user input are stripped — the
 * decimal in the displayed string ("$0.21") is presentation-only.
 */
private fun sanitizeFiatInput(text: String): String =
    text.filter { it.isDigit() }

// ─── Helpers ─────────────────────────────────────────────────────────────

private const val ZAP_SOFT_CONFIRM_SATS = 10_000L
private const val ZAP_HARD_CAP_SATS = 1_000_000L

/**
 * Insert thousands separators in the hero number while typing without
 * mutating the underlying raw-digit state. Maps cursor positions so a
 * tap or arrow-key lands on the digit the user expects.
 */
private val ThousandsSeparatorTransformation: VisualTransformation = VisualTransformation { text ->
    val raw = text.text
    if (raw.isEmpty()) return@VisualTransformation TransformedText(text, OffsetMapping.Identity)
    val formatted = try { "%,d".format(raw.toLong()) } catch (_: NumberFormatException) { raw }
    val mapping = object : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int {
            val clamped = offset.coerceIn(0, raw.length)
            val digitsFromRight = raw.length - clamped
            val totalCommas = (raw.length - 1) / 3
            val commasFromRight = ((digitsFromRight - 1).coerceAtLeast(0)) / 3
            val commasBefore = totalCommas - commasFromRight
            return (clamped + commasBefore).coerceIn(0, formatted.length)
        }
        override fun transformedToOriginal(offset: Int): Int {
            val clamped = offset.coerceIn(0, formatted.length)
            var rawOffset = 0
            for (i in 0 until clamped) {
                if (formatted[i] != ',') rawOffset++
            }
            return rawOffset.coerceIn(0, raw.length)
        }
    }
    TransformedText(AnnotatedString(formatted), mapping)
}

/**
 * Seed the custom-amount field with the given text AND select the
 * whole range — so the next keystroke replaces the seed entirely.
 * Lets the user open the sheet, see the configured instant-zap
 * amount, then type a new value over it without backspacing first.
 */
private fun seedCustomAmount(text: String, set: (TextFieldValue) -> Unit) {
    set(TextFieldValue(text = text, selection = TextRange(0, text.length)))
}

/**
 * Pill-shaped text button — used for the toolbar's Close + Presets
 * actions. Border-only by default, fillable via `borderColor`.
 */
@Composable
private fun AnimatedBoltHeader() {
    val infiniteTransition = rememberInfiniteTransition(label = "bolt")

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    val boltScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "boltScale"
    )

    val zapColor = WispThemeColors.zapColor

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(64.dp)
    ) {
        // Glow circle behind bolt
        Box(
            modifier = Modifier
                .size(56.dp)
                .alpha(glowAlpha)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            zapColor.copy(alpha = 0.4f),
                            zapColor.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // Bolt icon
        Icon(
            painter = painterResource(R.drawable.ic_bolt),
            contentDescription = null,
            tint = zapColor,
            modifier = Modifier
                .size(30.dp)
                .scale(boltScale)
        )
    }
}

@Composable
private fun LightningBackground(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "lightning_bg")

    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val zapColor = WispThemeColors.zapColor

    // Stable random values for bolt positions
    val boltData = remember {
        List(5) { i ->
            Triple(
                Random(i * 42).nextFloat(),       // x position (0..1)
                Random(i * 42 + 1).nextFloat(),    // y position (0..1)
                Random(i * 42 + 2).nextFloat() * 0.3f + 0.1f  // size scale
            )
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Subtle gradient overlay at the top
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    zapColor.copy(alpha = 0.03f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = h * 0.4f
            )
        )

        // Animated mini lightning bolts scattered in background
        boltData.forEach { (xFrac, yFrac, scale) ->
            val animatedAlpha = (sin((phase + xFrac) * Math.PI * 2).toFloat() * 0.5f + 0.5f) * 0.06f
            val cx = xFrac * w
            val cy = yFrac * h
            val boltSize = 20f * scale

            drawMiniBolt(
                center = Offset(cx, cy),
                size = boltSize,
                color = zapColor.copy(alpha = animatedAlpha)
            )
        }
    }
}

private fun DrawScope.drawMiniBolt(center: Offset, size: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - size)
        lineTo(center.x - size * 0.4f, center.y + size * 0.1f)
        lineTo(center.x + size * 0.1f, center.y + size * 0.1f)
        lineTo(center.x - size * 0.1f, center.y + size)
        lineTo(center.x + size * 0.4f, center.y - size * 0.1f)
        lineTo(center.x - size * 0.1f, center.y - size * 0.1f)
        close()
    }
    drawPath(path, color, style = Fill)
}

@Composable
private fun ZapPresetChip(
    preset: ZapPreset,
    isSelected: Boolean,
    editMode: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "chipScale"
    )

    Box {
        Surface(
            modifier = Modifier
                .scale(scale)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(16.dp),
            color = if (isSelected) LightningOrange else MaterialTheme.colorScheme.surfaceVariant,
            border = if (isSelected) {
                androidx.compose.foundation.BorderStroke(
                    1.5.dp,
                    Brush.linearGradient(listOf(LightningYellow, LightningOrange))
                )
            } else {
                null
            },
            shadowElevation = if (isSelected) 4.dp else 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bolt),
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = Color.White
                    )
                    Spacer(Modifier.width(3.dp))
                }
                val chipContext = LocalContext.current
                Text(
                    text = AmountFormatter.formatShort(preset.amountSats, chipContext),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (preset.message.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Outlined.Message,
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = if (isSelected) Color.White.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // Remove badge in edit mode
        if (editMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.btn_remove),
                    modifier = Modifier
                        .padding(2.dp)
                        .size(16.dp),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun ZapChipButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) LightningOrange else MaterialTheme.colorScheme.surfaceVariant,
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(
                1.5.dp,
                Brush.linearGradient(listOf(LightningYellow, LightningOrange))
            )
        } else {
            null
        }
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * iOS-equivalent "Edit Presets" sheet — full list editor reachable from
 * the composer's "Presets" pill. Mirrors the iOS layout: each row has
 * inline editable amount + message text fields, a leading minus icon to
 * remove the row, and a final "+ Add preset" row in accent color. Done
 * persists the list via `zapPrefsRepo.setPresets`, which kicks the
 * NIP-78 debounced publish so the change propagates to the user's other
 * devices.
 *
 * The Add row is disabled while a blank row already exists so the
 * caller can't pile up empty entries (matches iOS behavior).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPresetsSheet(
    initial: List<ZapPreset>,
    accent: Color,
    onDismiss: () -> Unit,
    onSave: (List<ZapPreset>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Working copy — only committed back via `onSave` when Done is
    // pressed, so dismissing via drag-down / scrim discards in-progress
    // edits (matches the iOS sheet's Cancel-on-dismiss semantics).
    var rows by remember {
        mutableStateOf(initial.map { EditableRow(it.amountSats.toString(), it.message) })
    }
    fun closeSheet(commit: Boolean) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (commit) {
                val parsed = rows.mapNotNull { r ->
                    val sats = r.amount.toLongOrNull() ?: return@mapNotNull null
                    if (sats <= 0) null else ZapPreset(sats, r.message.trim())
                }
                onSave(parsed)
            } else {
                onDismiss()
            }
        }
    }
    val hasBlankRow = rows.any { it.amount.isBlank() || (it.amount.toLongOrNull() ?: 0L) == 0L }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // fillMaxHeight expands the sheet to the full available height
        // under the drag handle; ModalBottomSheet's outer container
        // reserves the system insets, so this stops just below the
        // status bar instead of bleeding into it.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header row — "Edit Presets" centered, "Done" right-aligned.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.width(60.dp))
                Text(
                    "Edit Presets",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                PillButton(
                    text = stringResource(R.string.btn_done),
                    onClick = { closeSheet(commit = true) },
                    contentColor = accent,
                    borderColor = accent.copy(alpha = 0.45f)
                )
            }
            Spacer(Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                Column {
                    rows.forEachIndexed { idx, row ->
                        if (idx > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 14.dp)
                            )
                        }
                        // Keyed by stable row identity so swiping away one
                        // row doesn't leak its dismiss state into the next
                        // row sliding into its position.
                        val rowKey = remember { java.util.UUID.randomUUID().toString() }
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { target ->
                                if (target == SwipeToDismissBoxValue.EndToStart) {
                                    rows = rows.toMutableList().also { it.removeAt(idx) }
                                    true
                                } else false
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            enableDismissFromEndToStart = true,
                            backgroundContent = {
                                // Trailing-swipe affordance — solid iOS-red
                                // panel with a trailing delete glyph. Sized
                                // to fillMaxSize so the panel spans the full
                                // row height and reaches the trailing edge.
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFFFF3B30)),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete preset",
                                        tint = Color.White,
                                        modifier = Modifier.padding(end = 24.dp)
                                    )
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 14.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicTextField(
                                    value = row.amount,
                                    onValueChange = { newVal ->
                                        val filtered = newVal.filter { it.isDigit() }
                                        rows = rows.toMutableList().also {
                                            it[idx] = it[idx].copy(amount = filtered)
                                        }
                                    },
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 16.sp
                                    ),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    cursorBrush = SolidColor(accent),
                                    decorationBox = { inner ->
                                        Box {
                                            if (row.amount.isEmpty()) {
                                                Text(
                                                    "Sats",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        .copy(alpha = 0.7f),
                                                    fontSize = 16.sp
                                                )
                                            }
                                            inner()
                                        }
                                    },
                                    modifier = Modifier.width(80.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                BasicTextField(
                                    value = row.message,
                                    onValueChange = { newVal ->
                                        val sanitized = newVal.replace(",", "").replace(":", "")
                                        rows = rows.toMutableList().also {
                                            it[idx] = it[idx].copy(message = sanitized)
                                        }
                                    },
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 16.sp
                                    ),
                                    singleLine = true,
                                    cursorBrush = SolidColor(accent),
                                    decorationBox = { inner ->
                                        Box {
                                            if (row.message.isEmpty()) {
                                                Text(
                                                    "Message (optional)",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        .copy(alpha = 0.7f),
                                                    fontSize = 16.sp
                                                )
                                            }
                                            inner()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    if (rows.isNotEmpty()) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    }
                    // Add preset row — iOS disables it while a blank row
                    // already exists so the user finishes the current
                    // entry before adding another.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !hasBlankRow) {
                                rows = rows + EditableRow("", "")
                            }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = if (hasBlankRow) accent.copy(alpha = 0.35f) else accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Add preset",
                            color = if (hasBlankRow) accent.copy(alpha = 0.35f) else accent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Working-copy row used inside the Edit Presets sheet. */
private data class EditableRow(val amount: String, val message: String)
