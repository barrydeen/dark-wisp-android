package com.darkwisp.app.ui.component

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.darkwisp.app.R
import com.darkwisp.app.relay.TorManager

/**
 * Onion toggle for the pre-login screens (Splash/Auth), where no drawer exists.
 * Self-contained: observes [TorManager.state] and handles the notification
 * permission prompt; the caller only routes the enable/disable intent.
 */
@Composable
fun TorCornerButton(
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val torState by TorManager.state.collectAsState()
    val context = LocalContext.current
    // The Tor foreground service runs without this permission; requesting it
    // just makes the status notification visible on API 33+.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (torState is TorManager.State.Starting || torState is TorManager.State.Stopping) {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                strokeWidth = 2.dp,
                color = Color(0xFFFFB74D)
            )
        }
        IconButton(onClick = {
            val enable = torState is TorManager.State.Off || torState is TorManager.State.Error
            if (enable && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            onToggle(enable)
        }) {
            Icon(
                painter = painterResource(R.drawable.ic_onion),
                contentDescription = stringResource(R.string.cd_tor_toggle),
                modifier = Modifier.size(24.dp),
                tint = when (torState) {
                    is TorManager.State.On -> Color(0xFF9C59D1)
                    is TorManager.State.Starting, TorManager.State.Stopping -> Color(0xFFFFB74D)
                    is TorManager.State.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
