package com.darkwisp.app.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.darkwisp.app.R
import com.darkwisp.app.nostr.Nip19
import com.darkwisp.app.nostr.hexToByteArray
import com.darkwisp.app.toRoute
import com.darkwisp.app.ui.theme.WispThemeColors
import kotlinx.coroutines.launch

/**
 * Consolidated QR sheet showing Nostr identity, Lightning address, and a scan
 * tab in a tabbed bottom sheet with swipeable pages.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ProfileQrSheet(
    pubkeyHex: String,
    avatarUrl: String? = null,
    lud16: String? = null,
    onNavigate: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val useZapBolt = com.darkwisp.app.ui.util.useBoltIcon()

    val npub = remember(pubkeyHex) { Nip19.npubEncode(pubkeyHex.hexToByteArray()) }
    val npubQr = remember(npub) { generateQrBitmap(npub) }
    val lightningQr = remember(lud16) { lud16?.let { generateQrBitmap(it) } }

    val hasLightning = lud16 != null
    // Page indices: 0 = Nostr, 1 = Lightning (if present), last = Scan.
    val lightningPage = if (hasLightning) 1 else -1
    val scanPage = if (hasLightning) 2 else 1
    val pageCount = if (hasLightning) 3 else 2
    val pagerState = rememberPagerState(pageCount = { pageCount })
    var scanError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Full-height sheet matching NofferPaySheet, inset below the status
        // bar so the drag handle clears the camera cutout.
        modifier = Modifier
            .fillMaxHeight()
            .statusBarsPadding()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 32.dp)
        ) {
            // iOS-style segmented control: rounded container with a pill
            // highlight on the selected segment.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(4.dp)
            ) {
                SegmentedTab(
                    label = "Nostr",
                    selected = pagerState.currentPage == 0,
                    modifier = Modifier.weight(1f),
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } }
                )
                if (hasPayment) {
                    SegmentedTab(
                        label = "Lightning",
                        selected = pagerState.currentPage == lightningPage,
                        modifier = Modifier.weight(1f),
                        onClick = { scope.launch { pagerState.animateScrollToPage(lightningPage) } }
                    )
                }
                SegmentedTab(
                    label = "Scan",
                    icon = { Icon(Icons.Outlined.QrCodeScanner, null, modifier = Modifier.size(16.dp)) },
                    selected = pagerState.currentPage == scanPage,
                    modifier = Modifier.weight(1f),
                    onClick = { scope.launch { pagerState.animateScrollToPage(scanPage) } }
                )
            }

            Spacer(Modifier.height(16.dp))

            // Fill the remaining sheet height and top-align every page so the
            // pager doesn't resize/re-center when pages of different heights
            // compose in — that's what made the content shift on tab change.
            HorizontalPager(
                state = pagerState,
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                ) {
                    when (page) {
                        0 -> {
                            // Nostr QR
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(240.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(androidx.compose.ui.graphics.Color.White)
                                    .padding(8.dp)
                            ) {
                                Image(
                                    bitmap = npubQr.asImageBitmap(),
                                    contentDescription = stringResource(R.string.cd_qr_code),
                                    modifier = Modifier.matchParentSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(androidx.compose.ui.graphics.Color.White)
                                        .padding(3.dp)
                                ) {
                                    if (avatarUrl != null) {
                                        AsyncImage(
                                            model = avatarUrl,
                                            contentDescription = "Avatar",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.matchParentSize().clip(CircleShape)
                                        )
                                    } else {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_wisp_logo),
                                            contentDescription = "Wisp",
                                            tint = androidx.compose.ui.graphics.Color.Unspecified,
                                            modifier = Modifier.matchParentSize()
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            Text(
                                "npub",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            CopyableRow(text = npub, onCopy = {
                                clipboardManager.setText(AnnotatedString(npub))
                            })
                        }
                        lightningPage -> {
                            if (lightningQr != null && lud16 != null) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(240.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(androidx.compose.ui.graphics.Color.White)
                                        .padding(8.dp)
                                ) {
                                    Image(
                                        bitmap = lightningQr.asImageBitmap(),
                                        contentDescription = "Lightning QR Code",
                                        modifier = Modifier.matchParentSize()
                                    )
                                    // Avatar in the QR center, matching iOS's
                                    // qrWithCenterAvatar and the Nostr pane.
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(androidx.compose.ui.graphics.Color.White)
                                            .padding(3.dp)
                                    ) {
                                        if (avatarUrl != null) {
                                            AsyncImage(
                                                model = avatarUrl,
                                                contentDescription = "Avatar",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.matchParentSize().clip(CircleShape)
                                            )
                                        } else if (useZapBolt) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_bolt),
                                                contentDescription = "Lightning",
                                                tint = WispThemeColors.zapColor,
                                                modifier = Modifier.matchParentSize().padding(4.dp)
                                            )
                                        } else {
                                            Icon(
                                                Icons.Outlined.CurrencyBitcoin,
                                                contentDescription = "Bitcoin",
                                                tint = WispThemeColors.zapColor,
                                                modifier = Modifier.matchParentSize().padding(4.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(16.dp))

                                Text(
                                    "Lightning Address",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                CopyableRow(text = lud16, onCopy = {
                                    clipboardManager.setText(AnnotatedString(lud16))
                                })
                            }
                        }
                        scanPage -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(360.dp)
                            ) {
                                QrScanner(
                                    onResult = { raw ->
                                        val route = Nip19.decodeNostrQr(raw)?.toRoute()
                                        if (route != null) {
                                            scanError = null
                                            onNavigate(route)
                                            onDismiss()
                                        } else {
                                            scanError = "Not a Nostr QR code"
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    promptText = scanError
                                        ?: "Point at an npub, note, profile, event, or address QR"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentedTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CopyableRow(text: String, onCopy: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onCopy) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "Copy",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
