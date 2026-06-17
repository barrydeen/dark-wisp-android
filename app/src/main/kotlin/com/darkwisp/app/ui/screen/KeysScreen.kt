package com.darkwisp.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContextWrapper
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.darkwisp.app.R
import com.darkwisp.app.nostr.Nip19
import com.darkwisp.app.nostr.toHex
import com.darkwisp.app.repo.KeyRepository
import com.darkwisp.app.ui.component.QrCodeDialog

private fun android.content.Context.findFragmentActivity(): FragmentActivity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(
    keyRepository: KeyRepository,
    onBack: () -> Unit,
    avatarUrl: String? = null
) {
    val keypair = remember { keyRepository.getKeypair() }
    val npub = remember { keypair?.let { Nip19.npubEncode(it.pubkey) } }
    val pubkeyHex = remember { keypair?.pubkey?.toHex() }
    var nsec by remember { mutableStateOf<String?>(null) }
    var showNpubQr by remember { mutableStateOf(false) }
    var showNsecQr by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val revealPrivateKeyTitle = stringResource(R.string.btn_reveal_private_key)
    val revealPrivateKeyDescription = stringResource(R.string.settings_authenticate_view_key)

    // Clear nsec from memory when the composable leaves composition
    DisposableEffect(Unit) {
        onDispose { nsec = null }
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_keys)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
        ) {
            // Public key section
            Text(
                text = stringResource(R.string.settings_public_key),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = npub ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = { if (pubkeyHex != null) showNpubQr = true }) {
                        Icon(
                            Icons.Outlined.QrCode,
                            contentDescription = stringResource(R.string.cd_show_qr_code),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = {
                        npub?.let {
                            clipboardManager.setText(AnnotatedString(it))
                            Toast.makeText(context, context.getString(R.string.settings_public_key_copied), Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(R.string.cd_copy_npub),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (showNpubQr && pubkeyHex != null) {
                QrCodeDialog(
                    pubkeyHex = pubkeyHex,
                    avatarUrl = avatarUrl,
                    onDismiss = { showNpubQr = false }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Private key section
            Text(
                text = stringResource(R.string.settings_private_key),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            if (nsec != null) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = nsec ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = { showNsecQr = true }) {
                            Icon(
                                Icons.Outlined.QrCode,
                                contentDescription = stringResource(R.string.cd_show_qr_code),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = {
                            nsec?.let { key ->
                                val clip = ClipData.newPlainText("", key)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    clip.description.extras = PersistableBundle().apply {
                                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                                    }
                                }
                                val cm = context.getSystemService(ClipboardManager::class.java)
                                cm.setPrimaryClip(clip)
                                Toast.makeText(context, context.getString(R.string.settings_private_key_copied), Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.btn_copy),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                if (showNsecQr) {
                    nsec?.let { NsecQrDialog(nsec = it, avatarUrl = avatarUrl, onDismiss = { showNsecQr = false }) }
                }
            } else {
                Button(
                    onClick = {
                        val activity = context.findFragmentActivity()
                            ?: return@Button

                        val biometricManager = BiometricManager.from(context)
                        val canAuth = biometricManager.canAuthenticate(
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        )
                        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                            // No device lock set — reveal directly
                            keypair?.let { nsec = Nip19.nsecEncode(it.privkey) }
                            return@Button
                        }

                        val executor = ContextCompat.getMainExecutor(context)
                        val callback = object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                keypair?.let { nsec = Nip19.nsecEncode(it.privkey) }
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                ) {
                                    Toast.makeText(context, context.getString(R.string.settings_auth_failed, errString), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }

                        val promptInfo = BiometricPrompt.PromptInfo.Builder()
                            .setTitle(revealPrivateKeyTitle)
                            .setDescription(revealPrivateKeyDescription)
                            .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                            .build()

                        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Visibility, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_reveal_private_key))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.private_key_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun NsecQrDialog(nsec: String, avatarUrl: String? = null, onDismiss: () -> Unit) {
    val qrBitmap = remember(nsec) {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val writer = QRCodeWriter()
        val size = 512
        val matrix = writer.encode(nsec, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        for (x in 0 until matrix.width)
            for (y in 0 until matrix.height)
                bmp.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
        bmp
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_nsec_qr_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.keys_nsec_qr_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(16.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(androidx.compose.ui.graphics.Color.White)
                        .padding(8.dp)
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.keys_nsec_qr_title),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.matchParentSize()
                    )
                    if (avatarUrl != null) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(androidx.compose.ui.graphics.Color.White)
                                .padding(3.dp)
                        ) {
                            AsyncImage(
                                model = avatarUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(CircleShape)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_done)) }
        }
    )
}
