package com.example.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.app.ui.BleSensorScreen
import com.example.app.ui.screens.HistoryScreen
import com.example.app.ui.screens.HomeScreen
import com.example.app.ui.screens.ProfileScreen

object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val PROFILE = "profile"
    const val SENSOR = "sensor"
}

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenSensor = { nav.navigate(Routes.SENSOR) },
                onOpenHistory = { nav.navigate(Routes.HISTORY) },
                onOpenProfile = { nav.navigate(Routes.PROFILE) },
            )
        }
        composable(Routes.SENSOR) { BleSensorScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.HISTORY) { HistoryScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.PROFILE) { ProfileScreen(onBack = { nav.popBackStack() }) }
    }
}
