package app.pairline.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.pairline.android.AppGraph
import app.pairline.android.R
import app.pairline.android.telephony.Perms
import app.pairline.android.ui.PIcons
import app.pairline.android.ui.components.ListRow

/**
 * "Photos for the iPhone" (D-088, off by default): turning it on asks Android for photo access; the iPhone can then
 * browse thumbnails and fetch the photos it taps, over the Wi-Fi link only.
 */
@Composable
fun PhotosRow() {
    val context = LocalContext.current
    val photos = AppGraph.photos
    var on by remember { mutableStateOf(photos.available()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        AppGraph.prefs.photosEnabled = granted
        on = granted && photos.available()
        AppGraph.router.statusChanged()
    }
    fun set(want: Boolean) {
        if (want && !Perms.granted(context, photos.permission)) {
            ask.launch(photos.permission)
            return
        }
        AppGraph.prefs.photosEnabled = want
        on = photos.available()
        AppGraph.router.statusChanged()
    }
    ListRow(
        title = stringResource(R.string.photos_title),
        subtitle = stringResource(R.string.photos_sub),
        icon = PIcons.File,
        onClick = { set(!on) },
        trailing = { Switch(checked = on, onCheckedChange = null) },
    )
}
