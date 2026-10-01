package com.darkwisp.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.statusBarsPadding
import com.darkwisp.app.R
import com.darkwisp.app.ui.component.TorCornerButton
import com.darkwisp.app.viewmodel.LiveMetrics
import com.darkwisp.app.viewmodel.SplashViewModel
import androidx.activity.ComponentActivity
import com.darkwisp.app.nostr.toHex
import com.darkwisp.app.nostr.Nip19
import com.darkwisp.app.nostr.RemoteSignerBridge
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import com.darkwisp.app.ui.component.QrScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.darkwisp.app.auth.NostrCredentialSaver
import com.darkwisp.app.viewmodel.AuthViewModel
import kotlinx.coroutines.launch

private val AVATAR_SIZE = 44.dp
private val AVATAR_GAP = 4.dp

@Composable
fun SplashScreen(
    viewModel: SplashViewModel,
    authViewModel: AuthViewModel,
    onSignUp: () -> Unit,
    onAccountCreated: () -> Unit,
    onLoggedIn: () -> Unit,
    onToggleTor: (Boolean) -> Unit = {},
    onCancel: (() -> Unit)? = null,
    // Adding an account opens sign-in straight away rather than making the
    // user tap through the intro. Dismissing the sheet falls back to this
    // screen, where the Cancel pill lives.
    startOnSignIn: Boolean = false
) {
    var showNostrSheet by remember { mutableStateOf(startOnSignIn) }
    var showQrScanner by remember { mutableStateOf(false) }
    val profilePictures by viewModel.profilePictures.collectAsState()
    val liveMetrics by viewModel.liveMetrics.collectAsState()
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        val cols = ((maxWidth + AVATAR_GAP) / (AVATAR_SIZE + AVATAR_GAP)).toInt().coerceAtLeast(1)
        val screenHeightPx = constraints.maxHeight.toFloat()

        // Use real pictures, or placeholder circles while loading
        val pics = profilePictures.ifEmpty {
            val placeholderRows = ((maxHeight + AVATAR_GAP) / (AVATAR_SIZE + AVATAR_GAP)).toInt() + 1
            List(placeholderRows * cols) { "" }
        }

        val rows = (pics.size + cols - 1) / cols

        // Background collage — each picture shown at most once, no cycling
        Column(modifier = Modifier.align(Alignment.TopCenter)) {
            for (row in 0 until rows) {
                Row {
                    for (col in 0 until cols) {
                        val idx = row * cols + col
                        if (idx >= pics.size) break
                        val url = pics[idx]
                        // Background circle always visible; image loads on top.
                        // Slow or failed loads show the filled circle instead of a gap.
                        Box(
                            modifier = Modifier
                                .size(AVATAR_SIZE)
                                .clip(CircleShape)
                                .background(surfaceVariant)
                        ) {
                            if (url.isNotEmpty()) {
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.matchParentSize()
                                )
                            }
                        }
                        if (col < cols - 1) Spacer(Modifier.width(AVATAR_GAP))
                    }
                }
                if (row < rows - 1) Spacer(Modifier.height(AVATAR_GAP))
            }
        }

        // Gradient fades the collage into the background toward the bottom
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, backgroundColor),
                        startY = 0.25f * screenHeightPx,
                        endY = 0.72f * screenHeightPx
                    )
                )
        )

        if (onCancel != null) {
            // Pinned to the top-start corner (mirroring TorCornerButton at
            // top-end) so it's always reachable. Previously this sat at
            // BottomCenter with bottom padding, where the logo/wordmark/action
            // buttons column painted over it.
            Text(
                text = stringResource(R.string.btn_cancel),
                style = MaterialTheme.typography.labelLarge,
                color = Color.Black,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 16.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.92f))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        // Logo, tagline, and action buttons pinned to bottom
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp, start = 32.dp, end = 32.dp)
        ) {
            val wispTransition = rememberInfiniteTransition(label = "wisp")
            val bob by wispTransition.animateFloat(
                initialValue = 0f,
                targetValue = -8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bob"
            )
            val sway by wispTransition.animateFloat(
                initialValue = -3f,
                targetValue = 3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2400, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "sway"
            )
            Icon(
                painter = painterResource(R.drawable.ic_wisp_logo),
                contentDescription = stringResource(R.string.cd_wisp_logo),
                tint = Color.Unspecified,
                modifier = Modifier
                    .size(96.dp)
                    .graphicsLayer {
                        translationY = bob * density
                        rotationZ = sway
                    }
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.Black,
                                    Color.Black.copy(alpha = 0.6f),
                                    Color.Transparent
                                ),
                                radius = size.minDimension * 0.65f
                            ),
                            radius = size.minDimension * 0.65f
                        )
                    }
            )
            Text(
                text = "dark wisp",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.W500
                ),
                color = Color.White
            )
            liveMetrics?.let { OnlineCard(it) }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = onSignUp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.splash_create_account))
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { showNostrSheet = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.splash_log_in))
            }
        }

        TorCornerButton(
            onToggle = onToggleTor,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp)
        )
    }

    if (showNostrSheet) {
        NostrLoginSheet(
            authViewModel = authViewModel,
            onDismiss = { showNostrSheet = false },
            onAccountCreated = {
                showNostrSheet = false
                onAccountCreated()
            },
            onLoggedIn = {
                showNostrSheet = false
                onLoggedIn()
            },
            onScanQr = {
                showNostrSheet = false
                showQrScanner = true
            }
        )
    }

    if (showQrScanner) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showQrScanner = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                QrScanner(
                    onResult = { raw ->
                        showQrScanner = false
                        authViewModel.updateNsecInput(raw.trim())
                        if (authViewModel.logIn()) onLoggedIn()
                    },
                    modifier = Modifier.fillMaxSize(),
                    promptText = "Scan nsec, npub, or nprofile QR"
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NostrLoginSheet(
    authViewModel: AuthViewModel,
    onDismiss: () -> Unit,
    onAccountCreated: () -> Unit,
    onLoggedIn: () -> Unit,
    onScanQr: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nsecInput by authViewModel.nsecInput.collectAsState()
    val error by authViewModel.error.collectAsState()
    var nsecVisible by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var autofillRequested by remember { mutableStateOf(false) }
    val signerAvailable = remember { RemoteSignerBridge.isSignerAvailable(context) }

    // Track when signer login completes so we navigate after the composable is
    // back to RESUMED (activity result callbacks fire during STARTED, which
    // causes navigateSafe() to silently drop the navigation call).
    var signerLoginComplete by remember { mutableStateOf(false) }
    if (signerLoginComplete) {
        LaunchedEffect(Unit) {
            signerLoginComplete = false
            onLoggedIn()
        }
    }

    val signerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        val data = activityResult.data ?: return@rememberLauncherForActivityResult
        val result = data.getStringExtra("result") ?: return@rememberLauncherForActivityResult
        val pkg = data.getStringExtra("package")
        // Amber returns npub bech32 — decode to hex
        val pubkeyHex = if (result.startsWith("npub1")) {
            try { Nip19.npubDecode(result).toHex() } catch (_: Exception) { return@rememberLauncherForActivityResult }
        } else {
            result
        }
        authViewModel.loginWithSigner(pubkeyHex, pkg)
        signerLoginComplete = true
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isCreating) onDismiss() },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_nostr_ostrich),
                contentDescription = stringResource(R.string.cd_nostr_logo),
                tint = Color.Unspecified,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.nostr_sheet_title),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.W600
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.nostr_sheet_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = nsecInput,
                onValueChange = { authViewModel.updateNsecInput(it) },
                label = { Text(stringResource(R.string.auth_nsec_or_npub)) },
                singleLine = true,
                visualTransformation = if (nsecVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    Row {
                        IconButton(onClick = { nsecVisible = !nsecVisible }) {
                            Icon(
                                imageVector = if (nsecVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (nsecVisible) stringResource(R.string.auth_hide_key) else stringResource(R.string.auth_show_key)
                            )
                        }
                        IconButton(onClick = onScanQr) {
                            Icon(
                                imageVector = Icons.Outlined.QrCodeScanner,
                                contentDescription = "Scan QR code"
                            )
                        }
                    }
                },
                enabled = !isCreating,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused && !autofillRequested && nsecInput.isBlank()) {
                            autofillRequested = true
                            val activity = context as? ComponentActivity
                                ?: return@onFocusChanged
                            scope.launch {
                                val saved = NostrCredentialSaver.loadSavedNsec(activity)
                                if (!saved.isNullOrBlank() && authViewModel.nsecInput.value.isBlank()) {
                                    authViewModel.updateNsecInput(saved)
                                }
                            }
                        }
                    }
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    if (authViewModel.logIn()) onLoggedIn()
                },
                enabled = nsecInput.isNotBlank() && !isCreating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(stringResource(R.string.auth_log_in))
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(Modifier.height(20.dp))

            OutlinedButton(
                onClick = {
                    if (isCreating) return@OutlinedButton
                    scope.launch {
                        isCreating = true
                        try {
                            if (authViewModel.signUp()) {
                                val nsec = authViewModel.getCurrentNsec()
                                val npub = authViewModel.npub.value
                                val activity = context as? ComponentActivity
                                if (activity != null && nsec != null && npub != null) {
                                    NostrCredentialSaver.saveNsec(activity, npub, nsec)
                                }
                                onAccountCreated()
                            }
                        } finally {
                            isCreating = false
                        }
                    }
                },
                enabled = !isCreating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(
                    if (isCreating) stringResource(R.string.nostr_sheet_creating)
                    else stringResource(R.string.nostr_sheet_create)
                )
            }

            if (signerAvailable) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val permissions = """[{"type":"sign_event","kind":0},{"type":"sign_event","kind":1},{"type":"sign_event","kind":3},{"type":"sign_event","kind":5},{"type":"sign_event","kind":6},{"type":"sign_event","kind":7},{"type":"sign_event","kind":13},{"type":"sign_event","kind":9734},{"type":"sign_event","kind":10000},{"type":"sign_event","kind":10001},{"type":"sign_event","kind":10002},{"type":"sign_event","kind":10030},{"type":"sign_event","kind":10063},{"type":"sign_event","kind":22242},{"type":"sign_event","kind":24242},{"type":"sign_event","kind":30000},{"type":"sign_event","kind":30003},{"type":"sign_event","kind":30030},{"type":"nip44_encrypt"},{"type":"nip44_decrypt"}]"""
                        signerLauncher.launch(RemoteSignerBridge.buildGetPublicKeyIntent(permissions))
                    },
                    enabled = !isCreating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(stringResource(R.string.auth_login_with_signer))
                }
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}



@Composable
private fun OnlineCard(metrics: LiveMetrics) {
    Spacer(Modifier.height(16.dp))
    Card(shape = RoundedCornerShape(24.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.splash_people_online, metrics.online),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun formatCount(n: Int): String = when {
    n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000f)}M"
    n >= 1_000 -> "${"%.1f".format(n / 1_000f)}k"
    else -> n.toString()
}
