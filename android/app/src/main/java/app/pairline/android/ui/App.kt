package app.pairline.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import app.pairline.android.AppGraph
import app.pairline.android.R
import app.pairline.android.ui.block.BlockListScreen
import app.pairline.android.ui.components.PairlineTopBar
import app.pairline.android.ui.diagnostics.DiagnosticsScreen
import app.pairline.android.ui.help.HelpScreen
import app.pairline.android.ui.home.HomeScreen
import app.pairline.android.ui.keepalive.KeepAliveScreen
import app.pairline.android.ui.pairing.PairingContent
import app.pairline.android.ui.settings.PermissionsScreen
import app.pairline.android.ui.settings.SettingsScreen
import app.pairline.android.ui.setup.SetupFlow
import app.pairline.android.ui.update.UpdateFlowDialog

/** Screens after setup. A tiny back stack is enough for one activity (D-023: no navigation library). */
sealed interface Route {
    data object Home : Route
    data object Pairing : Route
    data object Settings : Route
    data object Permissions : Route
    data object KeepAlive : Route
    data object Diagnostics : Route
    data object Help : Route
    data object BlockList : Route
}

@Composable
fun PairlineAppUi() {
    val prefsTick by AppGraph.prefs.changes.collectAsState()
    val setupDone = remember(prefsTick) { AppGraph.prefs.setupDone }
    if (!setupDone) {
        SetupFlow(onFinished = {})
    } else {
        MainNavigation()
    }
}

private val allRoutes: List<Route> = listOf(
    Route.Home, Route.Pairing, Route.Settings, Route.Permissions, Route.KeepAlive, Route.Diagnostics, Route.Help,
    Route.BlockList,
)

/** Keeps the back stack across activity recreation (e.g. a language change on Android 11–12). */
private val RouteStackSaver = listSaver<SnapshotStateList<Route>, Int>(
    save = { stack -> stack.map { allRoutes.indexOf(it) } },
    restore = { saved -> mutableStateListOf(*saved.mapNotNull { allRoutes.getOrNull(it) }.ifEmpty { listOf(Route.Home) }.toTypedArray()) },
)

@Composable
private fun MainNavigation() {
    val stack = rememberSaveable(saver = RouteStackSaver) { mutableStateListOf<Route>(Route.Home) }
    val navigate: (Route) -> Unit = { if (stack.last() != it) stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { back() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    AnimatedContent(
        targetState = stack.last() to stack.size,
        transitionSpec = {
            val forward = targetState.second >= initialState.second
            val dir = (if (forward) 1 else -1) * (if (rtl) -1 else 1)
            (slideInHorizontally { it * dir / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 4 } + fadeOut())
        },
        label = "nav",
    ) { (route, _) ->
        when (route) {
            Route.Home -> HomeScreen(navigate)
            Route.Pairing -> PairingScreen(back)
            Route.Settings -> SettingsScreen(navigate, back)
            Route.Permissions -> PermissionsScreen(back)
            Route.KeepAlive -> KeepAliveScreen(back)
            Route.Diagnostics -> DiagnosticsScreen(back)
            Route.Help -> HelpScreen(back)
            Route.BlockList -> BlockListScreen(back)
        }
    }
    UpdateFlowDialog()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingScreen(back: () -> Unit) {
    Scaffold(topBar = { PairlineTopBar(stringResource(R.string.pair_title), onBack = back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            PairingContent(onDone = back)
        }
    }
}
