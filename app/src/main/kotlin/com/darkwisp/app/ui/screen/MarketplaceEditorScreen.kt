package com.darkwisp.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.darkwisp.app.R
import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.Nip99
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.nostr.NostrSigner
import com.darkwisp.app.relay.RelayPool
import com.darkwisp.app.repo.BlossomRepository
import com.darkwisp.app.repo.EventRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceEditorScreen(
    existingEvent: NostrEvent?,
    userPubkey: String?,
    signer: NostrSigner?,
    relayPool: RelayPool,
    eventRepository: EventRepository,
    onBack: () -> Unit,
    onPublished: (NostrEvent) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val blossomRepository = remember(userPubkey) { BlossomRepository(context, userPubkey) }
    val isEditing = existingEvent != null

    var title by remember(existingEvent?.id) { mutableStateOf(existingEvent?.let { Nip99.title(it) } ?: "") }
    var summary by remember(existingEvent?.id) { mutableStateOf(existingEvent?.let { Nip99.summary(it) } ?: "") }
    var description by remember(existingEvent?.id) { mutableStateOf(existingEvent?.content ?: "") }
    var amount by remember(existingEvent?.id) { mutableStateOf(existingEvent?.let { Nip99.price(it)?.amount } ?: "") }
    var currency by remember(existingEvent?.id) { mutableStateOf(existingEvent?.let { Nip99.price(it)?.currency } ?: "BTC") }
    var location by remember(existingEvent?.id) { mutableStateOf(existingEvent?.let { Nip99.location(it) } ?: "") }
    var category by remember(existingEvent?.id) { mutableStateOf(existingEvent?.let { Nip99.categories(it).joinToString(", ") } ?: "") }
    var images by remember(existingEvent?.id) { mutableStateOf(existingEvent?.let { Nip99.images(it) } ?: emptyList()) }
    var status by remember(existingEvent?.id) { mutableStateOf(existingEvent?.let { Nip99.status(it) } ?: "active") }
    val dTag = remember(existingEvent?.id) {
        existingEvent?.let { Nip99.dTag(it) }?.takeIf { it.isNotBlank() }
            ?: ("listing-" + UUID.randomUUID().toString())
    }
    val publishedAt = remember(existingEvent?.id) {
        existingEvent?.let { Nip99.publishedAt(it) } ?: (System.currentTimeMillis() / 1000).toString()
    }

    var uploading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                uploading = true
                error = null
                try {
                    for (uri in uris.take(8 - images.size)) {
                        val result = withContext(Dispatchers.IO) {
                            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: throw IllegalStateException("Could not read selected image")
                            val ext = android.webkit.MimeTypeMap.getSingleton()
                                .getExtensionFromMimeType(mime) ?: "jpg"
                            blossomRepository.uploadMedia(bytes, mime, ext, signer)
                        }
                        images = (images + result).distinct()
                    }
                } catch (e: Exception) {
                    error = context.getString(R.string.marketplace_upload_failed, e.message ?: "")
                } finally {
                    uploading = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isEditing) R.string.marketplace_edit else R.string.marketplace_new
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.marketplace_title_field)) },
                            singleLine = true
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = summary,
                            onValueChange = { summary = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.marketplace_summary_field)) },
                            singleLine = true
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.marketplace_description_field)) },
                            minLines = 5
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = amount,
                                onValueChange = { amount = it },
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.marketplace_price_field)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = currency,
                                onValueChange = { currency = it.uppercase().take(8) },
                                modifier = Modifier.width(112.dp),
                                label = { Text(stringResource(R.string.marketplace_currency_field)) },
                                singleLine = true
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = location,
                            onValueChange = { location = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.marketplace_location_field)) },
                            singleLine = true
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = category,
                            onValueChange = { category = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.marketplace_category_field)) },
                            singleLine = true,
                            supportingText = { Text(stringResource(R.string.marketplace_category_hint)) }
                        )
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.marketplace_status_label), style = MaterialTheme.typography.titleSmall)
                            FilterChip(
                                selected = status == "active",
                                onClick = { status = "active" },
                                label = { Text(stringResource(R.string.marketplace_active)) }
                            )
                            FilterChip(
                                selected = status == "sold",
                                onClick = { status = "sold" },
                                label = { Text(stringResource(R.string.marketplace_sold)) }
                            )
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.marketplace_photos), style = MaterialTheme.typography.titleSmall)
                            Button(
                                onClick = { imagePicker.launch("image/*") },
                                enabled = !uploading && images.size < 8
                            ) {
                                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.marketplace_add_photos))
                            }
                        }
                    }
                    if (images.isNotEmpty()) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(images) { url ->
                                    Box(
                                        modifier = Modifier.size(112.dp).clip(RoundedCornerShape(12.dp))
                                    ) {
                                        AsyncImage(
                                            model = url,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                        IconButton(
                                            onClick = { images = images - url },
                                            modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(50))
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_remove_image))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (uploading) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.marketplace_uploading), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (error != null) {
                        item {
                            Text(error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (signer == null) {
                        item {
                            Text(stringResource(R.string.marketplace_login_required), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Button(
                    onClick = {
                        if (title.isBlank() || description.isBlank()) {
                            error = context.getString(R.string.marketplace_required_fields)
                            return@Button
                        }
                        if (signer == null) {
                            error = context.getString(R.string.marketplace_login_required)
                            return@Button
                        }
                        scope.launch {
                            saving = true
                            error = null
                            try {
                                val tags = Nip99.buildTags(
                                    dTag = dTag,
                                    title = title,
                                    summary = summary,
                                    publishedAt = publishedAt,
                                    amount = amount,
                                    currency = currency,
                                    location = location,
                                    category = category,
                                    images = images,
                                    status = status
                                )
                                val event = signer.signEvent(
                                    kind = Nip99.KIND_LISTING,
                                    content = description.trim(),
                                    tags = tags
                                )
                                val message = ClientMessage.event(event)
                                var outcome = relayPool.publishWithConfirmation(
                                    event.id,
                                    send = { relayPool.sendToWriteRelays(message) }
                                )
                                if (outcome.sentCount == 0) {
                                    if (relayPool.ensureWriteRelaysConnected() > 0) {
                                        outcome = relayPool.publishWithConfirmation(
                                            event.id,
                                            send = { relayPool.sendToWriteRelays(message) }
                                        )
                                    }
                                }
                                if (outcome.sentCount == 0 || !outcome.confirmed) {
                                    throw IllegalStateException(context.getString(R.string.marketplace_publish_unconfirmed))
                                }
                                eventRepository.addEvent(event)
                                onPublished(event)
                            } catch (e: Exception) {
                                error = context.getString(R.string.marketplace_publish_failed, e.message ?: "")
                            } finally {
                                saving = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving && !uploading && signer != null
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (isEditing) R.string.marketplace_update else R.string.marketplace_publish))
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
