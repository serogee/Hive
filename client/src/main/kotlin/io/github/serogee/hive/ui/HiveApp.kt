package io.github.serogee.hive.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.padding
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
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
import kotlinx.serialization.Serializable

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

@Serializable
data class SessionEntryRoute(val code: String)

@Serializable
data class HostSetupRoute(val stackId: String)

/** Lists stacks so the user can pick one to host. */
@Serializable
object HostPickerRoute

@Serializable
data class StackDetailsRoute(val stackId: String)

@Serializable
data class ReviewRoute(val attemptId: String)

/** [hostAfter] continues to Host Setup when the import started from the host picker. */
@Serializable
data class ImportRoute(val hostAfter: Boolean = false)

/** [hostAfter] continues to Host Setup when the stack was created from the host picker. */
@Serializable
data class StackEditorRoute(val stackId: String, val hostAfter: Boolean = false)

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
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val onTab = tabs.any { destination?.hasRoute(it.route::class) == true }

    Scaffold(
        bottomBar = {
            if (onTab) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = destination?.hasRoute(tab.route::class) == true,
                            onClick = { navController.openTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier.padding(padding),
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    onJoinCode = { navController.navigate(SessionEntryRoute(it)) },
                    onOpenStacks = { navController.openTab(MyStacksRoute) },
                    onOpenStack = { navController.navigate(StackDetailsRoute(it.value)) },
                    onContinueReview = { navController.navigate(ReviewRoute(it)) },
                    onReturnToRoom = { navController.navigate(LobbyRoute(it.roomId.value)) },
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
                    if (route.hostAfter) {
                        navController.navigate(HostSetupRoute(route.stackId)) { popUpTo<StackEditorRoute> { inclusive = true } }
                    } else {
                        navController.navigate(StackDetailsRoute(route.stackId)) { popUpTo<StackEditorRoute> { inclusive = true } }
                    }
                })
            }
            composable<QuizRoomRoute> {
                QuizRoomScreen(
                    onJoinCode = { navController.navigate(SessionEntryRoute(it)) },
                    onOpenRoom = { navController.navigate(LobbyRoute(it)) },
                    onCreateRoom = { navController.navigate(HostPickerRoute) },
                    onSignIn = { navController.navigate(SignInRoute) },
                )
            }
            composable<AccountsRoute> { AccountsScreen() }
            composable<SignInRoute> { AccountsScreen(onSignedIn = { navController.popBackStack() }) }
            composable<SessionEntryRoute> { entry ->
                SessionEntryScreen(
                    code = entry.toRoute<SessionEntryRoute>().code,
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

private fun NavHostController.openTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
