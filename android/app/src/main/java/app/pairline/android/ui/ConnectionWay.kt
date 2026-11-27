package app.pairline.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pairline.android.R
import app.pairline.android.link.LinkSnapshot
import app.pairline.android.link.TransportKind

/**
 * How the phones reach each other right now, in the words both apps use (D-099): **Nearby** (Bluetooth, Wi-Fi, or
 * both; the phones are near each other) or **Cloud mode** (this phone stays home: straight through the home router,
 * or through the user's own iCloud).
 */
enum class ConnectionWay(@StringRes val label: Int, val icon: ImageVector, val cloud: Boolean) {
    NEARBY_BLUETOOTH(R.string.way_nearby_bt, PIcons.Bluetooth, false),
    NEARBY_WIFI(R.string.way_nearby_wifi, PIcons.Wifi, false),
    NEARBY_BOTH(R.string.way_nearby_both, PIcons.Bluetooth, false),
    CLOUD_DIRECT(R.string.way_cloud_direct, PIcons.Cloud, true),
    CLOUD_ICLOUD(R.string.way_cloud_icloud, PIcons.Cloud, true),
    ;

    companion object {
        /**
         * [viaIcloud]: no session, but Cloud mode reaches the iPhone through its iCloud. Null when the phones aren't
         * connected at all, and for the debug builds' test link (D-018), which isn't a way users have, unless the store
         * screenshots stand it in for Bluetooth.
         */
        fun of(link: LinkSnapshot, viaIcloud: Boolean): ConnectionWay? {
            val t = link.transports
            return when {
                link.connected && link.transport == TransportKind.DIRECT -> CLOUD_DIRECT
                link.connected && TransportKind.WIFI in t && TransportKind.BLUETOOTH in t -> NEARBY_BOTH
                link.connected && TransportKind.BLUETOOTH in t -> NEARBY_BLUETOOTH
                link.connected && TransportKind.WIFI in t -> NEARBY_WIFI
                link.connected && ScreenshotMode.enabled -> NEARBY_BLUETOOTH
                link.connected && (link.transport == TransportKind.DEBUG_TCP || TransportKind.DEBUG_TCP in t) -> null
                link.connected -> NEARBY_BLUETOOTH
                viaIcloud -> CLOUD_ICLOUD
                else -> null
            }
        }
    }
}

/** One line with the way's icon and name ("Nearby · Bluetooth"). */
@Composable
fun ConnectionWayLine(way: ConnectionWay, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(way.icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(way.label), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}
