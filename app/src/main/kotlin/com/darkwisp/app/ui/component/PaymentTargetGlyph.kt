package com.darkwisp.app.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkwisp.app.R
import com.darkwisp.app.nostr.NipA3

/**
 * Monochrome marks for NIP-A3 payment target types.
 *
 * Crypto marks come from spothq/cryptocurrency-icons (CC0-1.0), vendored as
 * tintable single-color vectors. Payment apps (PayPal, Venmo, Revolut, Cash App)
 * are deliberately absent: their logos are trademarks, not open assets, so those
 * types fall back to a plain lettermark instead.
 */
@DrawableRes
fun paymentTargetIcon(type: String): Int? = when (type) {
    "bitcoin" -> R.drawable.ic_pt_bitcoin
    "bitcoincash" -> R.drawable.ic_pt_bitcoincash
    "dash" -> R.drawable.ic_pt_dash
    "ethereum" -> R.drawable.ic_pt_ethereum
    "litecoin" -> R.drawable.ic_pt_litecoin
    "monero" -> R.drawable.ic_pt_monero
    "nano" -> R.drawable.ic_pt_nano
    "solana" -> R.drawable.ic_pt_solana
    "zcash" -> R.drawable.ic_pt_zcash
    // Lightning reuses the in-repo bolt so it matches zap affordances elsewhere.
    "lightning" -> R.drawable.ic_bolt
    // BIP-352 (silent payments) and BIP-353 (DNS payment instructions) are Bitcoin
    // address formats, so they carry the Bitcoin mark; the label disambiguates them.
    "bip352", "bip353" -> R.drawable.ic_pt_bitcoin
    else -> null
}

/** Short lettermark for types with no redistributable icon. */
fun paymentTargetLetters(type: String): String {
    val name = NipA3.displayName(type)
    val words = name.split(' ', '-').filter { it.isNotBlank() }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
        name.length >= 2 -> name.take(2).replaceFirstChar { it.uppercase() }
        else -> name.take(1).uppercase()
    }
}

/**
 * Renders a payment target's mark: the vendored vector when one exists,
 * otherwise a plain lettermark. No disc or background — the mark stands alone.
 */
@Composable
fun PaymentTargetGlyph(
    type: String,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val icon = paymentTargetIcon(type)
    if (icon != null) {
        Icon(
            painter = painterResource(icon),
            contentDescription = NipA3.displayName(type),
            tint = tint,
            modifier = modifier.size(size)
        )
    } else {
        Box(
            modifier = modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = paymentTargetLetters(type),
                color = tint,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = (size.value * 0.55f).sp
                )
            )
        }
    }
}
