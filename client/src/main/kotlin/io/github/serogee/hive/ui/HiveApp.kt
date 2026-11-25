package io.github.serogee.hive.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.graphicsLayer
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
import io.github.serogee.hive.core.model.StackId
import io.github.serogee.hive.ui.home.HomeScreen
import io.github.serogee.hive.ui.rooms.HostSetupScreen
import io.github.serogee.hive.ui.rooms.LobbyScreen
import io.github.serogee.hive.ui.rooms.QuizRoomScreen
import io.github.serogee.hive.ui.review.ReviewScreen
import io.github.serogee.hive.ui.rooms.SessionEntryScreen
import io.github.serogee.hive.ui.stacks.ImportScreen
import io.github.serogee.hive.ui.stacks.MyStacksScreen
import io.github.serogee.hive.ui.stacks.StackDetailsScreen
import io.github.serogee.hive.ui.stacks.StackEditorScreen
import io.github.serogee.hive.ui.stacks.StackListPurpose
import io.github.serogee.hive.ui.theme.Graphite
import io.github.serogee.hive.ui.theme.HEXAGON_RATIO
import io.github.serogee.hive.ui.theme.HexagonShape
import io.github.serogee.hive.ui.theme.Honey
import io.github.serogee.hive.ui.theme.hiveColors
import io.github.serogee.hive.ui.welcome.WelcomeScreen
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
data class StackEditorRoute(val stackId: String, val hostAfter: Boolean = false, val reviewAfter: Boolean = false)

@Serializable
data class LobbyRoute(val roomId: String)

private data class Tab(val label: String, val icon: ImageVector, val route: Any)

private val tabs = listOf(
    Tab("Home", Icons.Outlined.Home, HomeRoute),
    Tab("My Stacks", Icons.Outlined.Layers, MyStacksRoute),
    Tab("Quiz Room", Icons.Outlined.Groups, QuizRoomRoute),
    Tab("Accounts", Icons.Outlined.AccountCircle, AccountsRoute),
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
                    onSelect = { navController.openTab(it.route) },
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
                    onStackCreated = { navController.navigate(StackEditorRoute(it.value)) },
                )
            }
            composable<MyStacksRoute> {
                MyStacksScreen(
                    purpose = StackListPurpose.BROWSE,
                    onOpen = { navController.navigate(StackDetailsRoute(it.value)) },
                    onCreated = { navController.navigate(StackEditorRoute(it.value)) },
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
                        navController.navigate(StackEditorRoute(it.value, hostAfter = true)) {
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
                    onCreated = { navController.navigate(StackEditorRoute(it.value, reviewAfter = true)) },
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
                ImportScreen(onSaved = { stackId ->
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
                StackDetailsScreen(
                    stackId = stackId,
                    onHost = { navController.navigate(HostSetupRoute(stackId.value)) },
                    onReview = { navController.navigate(ReviewRoute(it)) },
                    onEdit = { navController.navigate(StackEditorRoute(stackId.value)) },
                    onDeleted = { navController.popBackStack() },
                )
            }
            composable<StackEditorRoute> { entry ->
                val route = entry.toRoute<StackEditorRoute>()
                StackEditorScreen(StackId(route.stackId), onDone = {
                    if (route.reviewAfter) {
                        navController.popBackStack()
                    } else if (route.hostAfter) {
                        navController.navigate(HostSetupRoute(route.stackId)) { popUpTo<StackEditorRoute> { inclusive = true } }
                    } else {
                        navController.navigate(StackDetailsRoute(route.stackId)) { popUpTo<StackEditorRoute> { inclusive = true } }
                    }
                })
            }
            composable<QuizRoomRoute> {
                QuizRoomScreen(
                    onJoinCode = { code, address -> navController.navigate(SessionEntryRoute(code, address)) },
                    onOpenRoom = { navController.navigate(LobbyRoute(it)) },
                    onCreateRoom = { navController.navigate(HostPickerRoute) },
                    onStackCreated = { navController.navigate(StackEditorRoute(it.value)) },
                    onSignIn = { navController.navigate(SignInRoute) },
                )
            }
            composable<AccountsRoute> {
                AccountsScreen(
                    onOpenReview = { navController.navigate(ReviewRoute(it)) },
                )
            }
            composable<SignInRoute> {
                AccountsScreen(
                    signInRequested = true,
                    onSignedIn = { navController.popBackStack() },
                    onOpenReview = { navController.navigate(ReviewRoute(it)) },
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
    }
}

/** A honey hexagon rises behind the icon of the selected tab. */
@Composable
private fun HiveTabBar(current: Tab?, onSelect: (Tab) -> Unit) {
    var tapped by remember { mutableStateOf<Tab?>(null) }
    LaunchedEffect(tapped) {
        val tab = tapped ?: return@LaunchedEffect
        // Draw the new selection first, because the next screen can take a moment to compose.
        withFrameNanos { }
        onSelect(tab)
    }
    LaunchedEffect(current) { if (tapped == current) tapped = null }
    val selectedTab = tapped ?: current

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.hiveColors.border)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEach { tab ->
                val selected = tab == selectedTab
                val rise by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
                    label = "tab",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .selectable(selected = selected, role = Role.Tab, onClick = { if (!selected) tapped = tab })
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(width = 48.dp, height = 40.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .graphicsLayer {
                                    translationY = (1f - rise) * size.height
                                    alpha = rise.coerceIn(0f, 1f)
                                }
                                .size(width = 40.dp * HEXAGON_RATIO, height = 40.dp)
                                .background(Honey, HexagonShape),
                        )
                        Icon(
                            tab.icon,
                            contentDescription = null,
                            tint = if (selected) Graphite else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        tab.label.uppercase(),
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private const val FADE_MILLIS = 120

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
