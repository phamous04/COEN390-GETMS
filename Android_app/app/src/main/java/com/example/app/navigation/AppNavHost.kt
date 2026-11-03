package com.example.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.app.ui.components.ReBandIcons
import com.example.app.ui.screens.AuthState
import com.example.app.ui.screens.AuthViewModel
import com.example.app.ui.screens.ExerciseSessionScreen
import com.example.app.ui.screens.ExercisesScreen
import com.example.app.ui.screens.HistoryScreen
import com.example.app.ui.screens.HomeScreen
import com.example.app.ui.screens.LoadingScreen
import com.example.app.ui.screens.LoginScreen
import com.example.app.ui.screens.SettingsScreen
import com.example.app.ui.screens.SignUpScreen
import com.example.app.ui.theme.Teal
import com.example.app.ui.theme.TealLight

object Routes {
    const val LOGIN = "login"
    const val SIGNUP = "signup"
    const val HOME = "home"
    const val EXERCISES = "exercises"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    // Replace this destination with the team's activity screen once it is merged.
    const val EXERCISE_SESSION = "exercise_session"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", ReBandIcons.Home),
    Tab(Routes.EXERCISES, "Exercises", ReBandIcons.Exercises),
    Tab(Routes.HISTORY, "History", ReBandIcons.History),
    Tab(Routes.SETTINGS, "Settings", ReBandIcons.Settings),
)

/** Shows the login flow or the main app depending on the Supabase session. */
@Composable
fun AppNavHost(authViewModel: AuthViewModel = viewModel()) {
    val state by authViewModel.state.collectAsState()
    when (val current = state) {
        AuthState.Loading -> LoadingScreen()
        AuthState.SignedOut -> AuthNavHost(authViewModel)
        is AuthState.SignedIn -> MainNavHost(current.email, authViewModel)
    }
}

@Composable
private fun AuthNavHost(authViewModel: AuthViewModel) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(authViewModel, onCreateAccount = { nav.navigate(Routes.SIGNUP) })
        }
        composable(Routes.SIGNUP) {
            SignUpScreen(authViewModel, onBackToLogin = { nav.popBackStack() })
        }
    }
}

@Composable
private fun MainNavHost(email: String?, authViewModel: AuthViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val busy by authViewModel.busy.collectAsState()
    val error by authViewModel.error.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (currentRoute != Routes.EXERCISE_SESSION) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Teal,
                                selectedTextColor = Teal,
                                indicatorColor = TealLight,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController = nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) {
                HomeScreen(email, onStartExercise = { nav.navigate(Routes.EXERCISE_SESSION) })
            }
            composable(Routes.EXERCISES) { ExercisesScreen() }
            composable(Routes.HISTORY) { HistoryScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(email, busy = busy, error = error, onLogout = authViewModel::signOut)
            }
            composable(Routes.EXERCISE_SESSION) { ExerciseSessionScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
