package io.github.serogee.hive.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import io.github.serogee.hive.data.sync.SyncStatus
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.compose.material3.MaterialTheme
import io.github.serogee.hive.ui.accounts.AccountsScreen
import io.github.serogee.hive.ui.accounts.SettingsScreen
import io.github.serogee.hive.ui.common.Crumb
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.ui.home.HomeScreen
import io.github.serogee.hive.ui.rooms.HostSetupScreen
import io.github.serogee.hive.ui.rooms.LobbyScreen
import io.github.serogee.hive.ui.rooms.QuizRoomScreen
import io.github.serogee.hive.ui.review.ReviewScreen
import io.github.serogee.hive.ui.rooms.SessionEntryScreen
import io.github.serogee.hive.ui.stacks.EditorExit
import io.github.serogee.hive.ui.stacks.ImportScreen
import io.github.serogee.hive.ui.stacks.MyStacksScreen
import io.github.serogee.hive.ui.stacks.StackDetailsScreen
import io.github.serogee.hive.ui.stacks.StackEditorScreen
import io.github.serogee.hive.ui.stacks.StackListPurpose
import io.github.serogee.hive.ui.theme.Graphite
import io.github.serogee.hive.ui.theme.Honey
import io.github.serogee.hive.ui.theme.WideHexagonShape
import io.github.serogee.hive.ui.theme.hiveColors
import io.github.serogee.hive.ui.welcome.WelcomeScreen
import io.github.serogee.hive.ui.accounts.SyncChoiceDialog
import io.github.serogee.hive.ui.common.LocalAppContainer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable

@Serializable
object WelcomeRoute

@Serializable
object HomeRoute

@Serializable
object MyStacksRoute

@Serializable
object QuizRoomRoute

@Serializable
object AccountsRoute

/** Sign-in requested by another screen. Returns there afterwards. */
@Serializable
object SignInRoute

/** Settings, from the gear button in Account. */
@Serializable
object SettingsRoute

/** [hostAddress] is the Host phone's address for a room hosted on a phone. */
@Serializable
data class SessionEntryRoute(val code: String, val hostAddress: String? = null)

@Serializable
data class HostSetupRoute(val stackId: String)

/** Lists stacks so the user can pick one to host. */
@Serializable
object HostPickerRoute

/** Lists stacks so the user can pick one to review. */
@Serializable
object ReviewPickerRoute

@Serializable
data class StackDetailsRoute(val stackId: String)

@Serializable
data class ReviewRoute(val attemptId: String)

/**
 * [hostAfter] continues to Host Setup when the import started from the host picker. [reviewAfter]
 * returns to the review picker when the import started there.
 */
@Serializable
data class ImportRoute(val hostAfter: Boolean = false, val reviewAfter: Boolean = false)

/**
 * [hostAfter] continues to Host Setup when the stack was created from the host picker. [reviewAfter]
 * returns to the review picker when the stack was created there.
 */
@Serializable
data class StackEditorRoute(
    val stackId: String,
    val hostAfter: Boolean = false,
    val reviewAfter: Boolean = false,
    /** Whether the stack was created to open the editor, so that leaving without saving removes it. */
    val isNew: Boolean = false,
)

@Serializable
data class LobbyRoute(val roomId: String)

private data class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val route: Any)

private val tabs = listOf(
    Tab("Home", Icons.Outlined.Home, Icons.Filled.Home, HomeRoute),
    Tab("Stacks", Icons.Outlined.Layers, Icons.Filled.Layers, MyStacksRoute),
    Tab("Rooms", Icons.Outlined.Groups, Icons.Filled.Groups, QuizRoomRoute),
    Tab("Account", Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle, AccountsRoute),
)

@Composable
fun HiveApp() {
    val container = LocalAppContainer.current

    // Read onboarding preference; null means not yet loaded from DataStore.
    val onboardingCompleted by container.preferences.onboardingCompleted
        .collectAsStateWithLifecycle(initialValue = null)

    // Wait until DataStore has emitted the real value before rendering navigation.
    val completed = onboardingCompleted ?: return

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()

    // Dots on the tabs: a room is live, or sync needs the user.
    val joinedRoom by container.joinedRoom.collectAsStateWithLifecycle()
    val hosting by container.rooms.localHostRunning.collectAsStateWithLifecycle()
    val syncChoice by container.sync.choice.collectAsStateWithLifecycle()
    val syncStatus by container.sync.status.collectAsStateWithLifecycle()
    val attention = MaterialTheme.colorScheme.error
    val dots = buildMap {
        if (joinedRoom != null || hosting) put(tabs[2], Honey)
        if (syncChoice != null || syncStatus is SyncStatus.Waiting) put(tabs[3], attention)
    }
    val destination = backStackEntry?.destination
    val onTab = tabs.any { destination?.hasRoute(it.route::class) == true }
    val onWelcome = destination?.hasRoute(WelcomeRoute::class) == true

    // Lock the start destination to the value from the first emission.
    val startDestination: Any = remember { if (completed) HomeRoute else WelcomeRoute }

    Scaffold(
        bottomBar = {
            if (onTab && !onWelcome) {
                HiveTabBar(
                    current = tabs.firstOrNull { destination?.hasRoute(it.route::class) == true },
                    dots = dots,
                    onSelect = { navController.openTab(it.route) },
                    onReselect = { navController.popBackStack(it.route, inclusive = false) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
            // Tabs switch at once. Other screens fade quickly, because the default fade takes 700 ms.
            enterTransition = { if (betweenTabs()) EnterTransition.None else fadeIn(tween(FADE_MILLIS)) },
            exitTransition = { if (betweenTabs()) ExitTransition.None else fadeOut(tween(FADE_MILLIS)) },
        ) {
            composable<WelcomeRoute> {
                WelcomeScreen(
                    onContinue = {
                        navController.navigate(HomeRoute) {
                            popUpTo<WelcomeRoute> { inclusive = true }
                        }
                    },
                )
            }
            composable<HomeRoute> {
                HomeScreen(
                    onJoinCode = { code, address -> navController.navigate(SessionEntryRoute(code, address)) },
                    onOpenStacks = { navController.openTab(MyStacksRoute) },
                    onOpenStack = { navController.navigate(StackDetailsRoute(it.value)) },
                    onContinueReview = { navController.navigate(ReviewRoute(it)) },
                    onReturnToRoom = { navController.navigate(LobbyRoute(it.roomId.value)) },
                    onHostQuiz = { navController.navigate(HostPickerRoute) },
                    onReview = { navController.navigate(ReviewPickerRoute) },
                    onStackCreated = { navController.navigate(StackEditorRoute(it.value, isNew = true)) },
                )
            }
            composable<MyStacksRoute> {
                MyStacksScreen(
                    purpose = StackListPurpose.BROWSE,
                    onOpen = { navController.navigate(StackDetailsRoute(it.value)) },
                    onCreated = { navController.navigate(StackEditorRoute(it.value, isNew = true)) },
                    onImport = { navController.navigate(ImportRoute()) },
                    onHost = { navController.navigate(HostSetupRoute(it.value)) },
                    onReview = { navController.navigate(ReviewRoute(it)) },
                )
            }
            composable<HostPickerRoute> {
                MyStacksScreen(
                    purpose = StackListPurpose.HOST,
                    onOpen = { navController.navigate(HostSetupRoute(it.value)) { popUpTo<HostPickerRoute> { inclusive = true } } },
                    onCreated = {
                        navController.navigate(StackEditorRoute(it.value, hostAfter = true, isNew = true)) {
                            popUpTo<HostPickerRoute> { inclusive = true }
                        }
                    },
                    onImport = {
                        navController.navigate(ImportRoute(hostAfter = true)) { popUpTo<HostPickerRoute> { inclusive = true } }
                    },
                )
            }
            composable<ReviewPickerRoute> {
                MyStacksScreen(
                    purpose = StackListPurpose.REVIEW,
                    onOpen = {},
                    onCreated = { navController.navigate(StackEditorRoute(it.value, reviewAfter = true, isNew = true)) },
                    onImport = { navController.navigate(ImportRoute(reviewAfter = true)) },
                    onReview = { navController.navigate(ReviewRoute(it)) { popUpTo<ReviewPickerRoute> { inclusive = true } } },
                )
            }
            composable<ReviewRoute> { entry ->
                ReviewScreen(
                    attemptId = entry.toRoute<ReviewRoute>().attemptId,
                    onOpenAttempt = { navController.navigate(ReviewRoute(it)) { popUpTo<ReviewRoute> { inclusive = true } } },
                    onExit = { navController.popBackStack() },
                )
            }
            composable<ImportRoute> { entry ->
                val route = entry.toRoute<ImportRoute>()
                val origin = remember(entry) { navController.originTab() }
                ImportScreen(root = Crumb(origin.label) { navController.backTo(origin) }, onSaved = { stackId ->
                    if (route.reviewAfter) {
                        navController.popBackStack()
                        return@ImportScreen
                    }
                    val next: Any = if (route.hostAfter) HostSetupRoute(stackId.value) else StackDetailsRoute(stackId.value)
                    navController.navigate(next) { popUpTo<ImportRoute> { inclusive = true } }
                })
            }
            composable<StackDetailsRoute> { entry ->
                val stackId = StackId(entry.toRoute<StackDetailsRoute>().stackId)
                val origin = remember(entry) { navController.originTab() }
                StackDetailsScreen(
                    stackId = stackId,
                    onHost = { navController.navigate(HostSetupRoute(stackId.value)) },
                    onReview = { navController.navigate(ReviewRoute(it)) },
                    onEdit = { navController.navigate(StackEditorRoute(stackId.value)) },
                    onDeleted = { navController.popBackStack() },
                    root = Crumb(origin.label) { navController.backTo(origin) },
                )
            }
            composable<StackEditorRoute> { entry ->
                val route = entry.toRoute<StackEditorRoute>()
                val origin = remember(entry) { navController.originTab() }
                val openDetails = {
                    if (navController.previousBackStackEntry?.destination?.hasRoute(StackDetailsRoute::class) == true) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(StackDetailsRoute(route.stackId)) { popUpTo<StackEditorRoute> { inclusive = true } }
                    }
                    Unit
                }
                StackEditorScreen(
                    stackId = StackId(route.stackId),
                    isNew = route.isNew,
                    rootLabel = origin.label,
                    onSaved = {
                        if (route.reviewAfter) {
                            navController.popBackStack()
                        } else if (route.hostAfter) {
                            navController.navigate(HostSetupRoute(route.stackId)) { popUpTo<StackEditorRoute> { inclusive = true } }
                        } else {
                            openDetails()
                        }
                    },
                    onLeave = { exit ->
                        when (exit) {
                            EditorExit.BACK -> navController.popBackStack()
                            EditorExit.ROOT -> navController.backTo(origin)
                            EditorExit.DETAILS -> openDetails()
                        }
                    },
                )
            }
            composable<QuizRoomRoute> {
                QuizRoomScreen(
                    onJoinCode = { code, address -> navController.navigate(SessionEntryRoute(code, address)) },
                    onOpenRoom = { navController.navigate(LobbyRoute(it)) },
                    onCreateRoom = { navController.navigate(HostPickerRoute) },
                    onStackCreated = { navController.navigate(StackEditorRoute(it.value, isNew = true)) },
                    onSignIn = { navController.navigate(SignInRoute) },
                )
            }
            composable<AccountsRoute> {
                AccountsScreen(
                    onOpenReview = { navController.navigate(ReviewRoute(it)) },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable<SignInRoute> {
                AccountsScreen(
                    signInRequested = true,
                    onSignedIn = { navController.popBackStack() },
                    onOpenReview = { navController.navigate(ReviewRoute(it)) },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                )
            }
            composable<SessionEntryRoute> { entry ->
                val route = entry.toRoute<SessionEntryRoute>()
                SessionEntryScreen(
                    code = route.code,
                    hostAddress = route.hostAddress,
                    onJoined = { membership ->
                        navController.navigate(LobbyRoute(membership.room.id.value)) {
                            popUpTo<SessionEntryRoute> { inclusive = true }
                        }
                    },
                    onSignIn = { navController.navigate(SignInRoute) },
                )
            }
            composable<HostSetupRoute> { entry ->
                val stackId = entry.toRoute<HostSetupRoute>().stackId
                HostSetupScreen(
                    stackId = StackId(stackId),
                    onEdit = { navController.navigate(StackEditorRoute(stackId, hostAfter = true)) },
                    onSignIn = { navController.navigate(SignInRoute) },
                    onCreated = { membership ->
                        navController.navigate(LobbyRoute(membership.room.id.value)) {
                            popUpTo<HostSetupRoute> { inclusive = true }
                        }
                    },
                )
            }
            composable<LobbyRoute> { entry ->
                LobbyScreen(
                    roomId = entry.toRoute<LobbyRoute>().roomId,
                    onLeft = { navController.popBackStack() },
                )
            }
        }

        // After NavHost, so that it comes before NavHost's own back handling, which would go to Home.
        val activity = LocalActivity.current
        BackHandler(enabled = onTab) { activity?.finish() }
    }

    SyncChoiceDialog()
}

/** The selected tab's icon sits in a honey hexagon, which slides to the tab that the user taps. */
@Composable
private fun HiveTabBar(current: Tab?, dots: Map<Tab, Color>, onSelect: (Tab) -> Unit, onReselect: (Tab) -> Unit) {
    var tapped by remember { mutableStateOf<Tab?>(null) }
    LaunchedEffect(tapped) {
        val tab = tapped ?: return@LaunchedEffect
        // Draw the new selection first, because the next screen can take a moment to compose.
        withFrameNanos { }
        onSelect(tab)
    }
    LaunchedEffect(current) { if (tapped == current) tapped = null }
    val selectedTab = tapped ?: current
    val dark = MaterialTheme.hiveColors.isDark
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (dark) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
    ) {
        if (!dark) HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            val tabWidth = maxWidth / tabs.size
            val index = tabs.indexOf(selectedTab)
            val x by animateDpAsState(
                targetValue = tabWidth * index.coerceAtLeast(0) + (tabWidth - TabHexWidth) / 2,
                animationSpec = tween(TAB_SLIDE_MILLIS),
                label = "tab",
            )
            if (index >= 0) {
                Box(
                    Modifier
                        .padding(top = TabTopPadding)
                        .offset(x = x)
                        .size(width = TabHexWidth, height = TabHexHeight)
                        .background(Honey, TabHexagon),
                )
            }
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                tabs.forEach { tab ->
                    val selected = tab == selectedTab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { if (selected) onReselect(tab) else tapped = tab },
                            )
                            .padding(top = TabTopPadding, bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(width = TabHexWidth, height = TabHexHeight), contentAlignment = Alignment.Center) {
                            Icon(
                                if (selected) tab.selectedIcon else tab.icon,
                                contentDescription = null,
                                tint = if (selected) Graphite else muted,
                                modifier = Modifier.size(20.dp),
                            )
                            dots[tab]?.let { color ->
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 2.dp, end = 14.dp)
                                        .size(8.dp)
                                        .background(color, CircleShape),
                                )
                            }
                        }
                        Text(
                            tab.label,
                            modifier = Modifier.padding(top = 4.dp),
                            color = if (selected) MaterialTheme.colorScheme.onSurface else muted,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private val TabHexWidth = 64.dp
private val TabHexHeight = 32.dp
private val TabTopPadding = 8.dp
private const val TAB_SLIDE_MILLIS = 150

/** The honey hexagon under the selected tab, with slightly rounded corners like every hexagon in the app. */
private val TabHexagon = WideHexagonShape(endDepth = 12.dp, corner = 3.5.dp)

/** Short enough that a screen change feels instant. */
private const val FADE_MILLIS = 60

private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs(): Boolean {
    fun NavBackStackEntry.isTab() = tabs.any { destination.hasRoute(it.route::class) }
    return initialState.isTab() && targetState.isTab()
}

private fun NavHostController.openTab(route: Any) {
    navigate(route) {
        // Home is the root. Welcome can be the start destination, but it has left the back stack by now.
        popUpTo<HomeRoute> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** The tab below the current screen, which its first breadcrumb names. */
private fun NavHostController.originTab(): Tab = originTab(tabs) { tab -> runCatching { getBackStackEntry(tab.route) }.isSuccess }

/**
 * Goes back down to [tab]'s own screen. It never opens a tab on top of another one, which would leave the
 * other tab showing this tab's screen.
 */
private fun NavHostController.backTo(tab: Tab) {
    if (!popBackStack(tab.route, inclusive = false)) popBackStack(HomeRoute, inclusive = false)
}
