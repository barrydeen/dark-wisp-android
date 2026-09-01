package com.darkwisp.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.darkwisp.app.R
import com.darkwisp.app.nostr.NipA3
import com.darkwisp.app.ui.component.PaymentTargetGlyph
import com.darkwisp.app.ui.component.QrScanner

/**
 * NIP-A3 payment targets (kind 10133).
 *
 * Lives under Settings rather than the wallet: targets are Nostr profile
 * metadata published as a replaceable event, and publishing them never
 * requires a connected Lightning wallet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentTargetsScreen(
    targets: List<NipA3.PaymentTarget>,
    isLoading: Boolean,
    error: String?,
    isDirty: Boolean,
    onLoad: () -> Unit,
    onAdd: (type: String, authority: String) -> Boolean,
    onRemove: (NipA3.PaymentTarget) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    /** The profile's kind-0 lud16, if set. Used to warn that a Lightning payto
     *  target duplicates the zap address NIP-57 clients already use. */
    profileLightningAddress: String? = null
) {
    var typeInput by remember { mutableStateOf("") }
    var authorityInput by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    // Declared here (not beside the dropdown) because the QR handler sets it too.
    var customType by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onLoad() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payment Targets") },
                navigationIcon = {
                    IconButton(onClick = { if (scanning) scanning = false else onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        if (scanning) {
            QrScanner(
                onResult = { raw ->
                    val scan = NipA3.parseScannedUri(raw)
                    // A bare address carries no scheme, so keep whatever type is selected.
                    scan.type?.let {
                        typeInput = it
                        // An unrecognized scanned type (payto://iban/...) has no menu
                        // entry, so drop into custom mode to keep it editable.
                        customType = it !in NipA3.RECOGNIZED
                    }
                    authorityInput = scan.authority
                    scanning = false
                },
                promptText = "Scan a payment address QR code",
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                "Publish addresses for other cryptocurrencies and payment apps so people can pay you beyond Lightning zaps.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isLoading) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            if (targets.isEmpty() && !isLoading) {
                Text(
                    "No payment targets yet. Add one below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            targets.forEach { target ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PaymentTargetGlyph(
                                    type = target.type,
                                    size = 18.dp,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    NipA3.displayName(target.type),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                target.authority,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onRemove(target) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove payment target",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "Add target",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(12.dp))

            val usedTypes = targets.map { it.type }.toSet()
            val typeAlreadyUsed = typeInput.trim().lowercase() in usedTypes
            var typeMenuExpanded by remember { mutableStateOf(false) }
            val typeFocusRequester = remember { FocusRequester() }
            val profileLn = profileLightningAddress?.takeIf { it.isNotBlank() }
            val lightningSelected = typeInput.trim().lowercase() == "lightning"

            ExposedDropdownMenuBox(
                expanded = typeMenuExpanded,
                onExpandedChange = { typeMenuExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = if (customType) typeInput else NipA3.displayName(typeInput).takeIf { typeInput.isNotBlank() } ?: "",
                    onValueChange = {
                        if (customType) {
                            if (it != typeInput) authorityInput = ""
                            typeInput = it
                        }
                    },
                    readOnly = !customType,
                    label = { Text(if (customType) "Custom type" else "Network type") },
                    placeholder = { Text(if (customType) "iban" else "bitcoin") },
                    singleLine = true,
                    isError = typeAlreadyUsed,
                    supportingText = when {
                        typeAlreadyUsed -> {
                            {
                                Text("Already added \u2014 remove the existing one first to change its address.")
                            }
                        }
                        lightningSelected && profileLn != null -> {
                            {
                                Text("Your profile already advertises $profileLn for zaps \u2014 keep them identical so they can't drift apart.")
                            }
                        }
                        customType -> {
                            {
                                Text("Any lowercase type works \u2014 letters, digits and hyphens.")
                            }
                        }
                        else -> null
                    },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded)
                    },
                    modifier = Modifier
                        .menuAnchor(
                            if (customType) MenuAnchorType.PrimaryEditable
                            else MenuAnchorType.PrimaryNotEditable
                        )
                        .focusRequester(typeFocusRequester)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = typeMenuExpanded,
                    onDismissRequest = { typeMenuExpanded = false }
                ) {
                    NipA3.RECOGNIZED.keys.forEach { type ->
                        val used = type in usedTypes
                        DropdownMenuItem(
                            enabled = !used,
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PaymentTargetGlyph(
                                        type = type,
                                        size = 18.dp,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(NipA3.displayName(type))
                                    if (used) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "already added",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            onClick = {
                                // An address is type-specific, so a leftover one is
                                // wrong the moment the type changes.
                                if (typeInput != type) authorityInput = ""
                                typeInput = type
                                customType = false
                                // Seed from the profile's zap address so a Lightning
                                // target starts out matching lud16 instead of diverging.
                                if (type == "lightning" && authorityInput.isBlank() && profileLn != null) {
                                    authorityInput = profileLn
                                }
                                typeMenuExpanded = false
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Custom\u2026",
                                color = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            typeInput = ""
                            authorityInput = ""
                            customType = true
                            typeMenuExpanded = false
                            typeFocusRequester.requestFocus()
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = authorityInput,
                onValueChange = { authorityInput = it },
                label = { Text("Address") },
                placeholder = { Text("bc1q…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { scanning = true }) {
                        Icon(
                            Icons.Outlined.QrCodeScanner,
                            contentDescription = "Scan address QR code"
                        )
                    }
                }
            )

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    if (onAdd(typeInput, authorityInput)) {
                        typeInput = ""
                        authorityInput = ""
                    }
                },
                enabled = typeInput.trim().isNotEmpty() && authorityInput.trim().isNotEmpty() && !typeAlreadyUsed,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add")
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onSave,
                enabled = isDirty,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save & Publish")
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
