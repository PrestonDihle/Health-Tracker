package com.prestondihle.healthtracker.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.prestondihle.healthtracker.di.AppContainer
import com.prestondihle.healthtracker.ui.dashboard.DashboardScreen
import com.prestondihle.healthtracker.ui.dashboard.DashboardViewModel
import com.prestondihle.healthtracker.ui.fasting.FastingPlanScreen
import com.prestondihle.healthtracker.ui.fasting.FastingPlanViewModel
import com.prestondihle.healthtracker.ui.history.HistoryScreen
import com.prestondihle.healthtracker.ui.history.HistoryViewModel
import com.prestondihle.healthtracker.ui.master.MasterGraphScreen
import com.prestondihle.healthtracker.ui.master.MasterGraphViewModel
import com.prestondihle.healthtracker.ui.settings.SettingsScreen
import com.prestondihle.healthtracker.ui.settings.SettingsViewModel
import com.prestondihle.healthtracker.ui.trends.TrendsScreen
import com.prestondihle.healthtracker.ui.trends.TrendsViewModel

/**
 * Labels are kept to one short word because there are six of them: Material's
 * navigation bar divides the width evenly and truncates whatever does not fit,
 * so "Master Graph" would render as "Master G...".
 */
enum class Screen(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "Today", Icons.Filled.Dashboard),
    Fasting("fasting", "Fasting", Icons.Filled.CalendarMonth),
    Master("master", "Master", Icons.Filled.QueryStats),
    Trends("trends", "Trends", Icons.Filled.Timeline),
    History("history", "History", Icons.Filled.HistoryToggleOff),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

@Composable
fun TrackerNavHost(appContainer: AppContainer) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected =
                            currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Dashboard.route) {
                val vm: DashboardViewModel =
                    viewModel(
                        factory = DashboardViewModel.provideFactory(appContainer.trackerRepository)
                    )
                DashboardScreen(vm, snackbarHostState)
            }
            composable(Screen.Fasting.route) {
                val vm: FastingPlanViewModel =
                    viewModel(
                        factory = FastingPlanViewModel.provideFactory(appContainer.trackerRepository)
                    )
                FastingPlanScreen(vm)
            }
            composable(Screen.Master.route) {
                val vm: MasterGraphViewModel =
                    viewModel(
                        factory = MasterGraphViewModel.provideFactory(appContainer.trackerRepository)
                    )
                MasterGraphScreen(vm)
            }
            composable(Screen.Trends.route) {
                val vm: TrendsViewModel =
                    viewModel(
                        factory = TrendsViewModel.provideFactory(appContainer.trackerRepository)
                    )
                TrendsScreen(vm)
            }
            composable(Screen.History.route) {
                val vm: HistoryViewModel =
                    viewModel(
                        factory = HistoryViewModel.provideFactory(appContainer.trackerRepository)
                    )
                HistoryScreen(vm)
            }
            composable(Screen.Settings.route) {
                val vm: SettingsViewModel =
                    viewModel(
                        factory = SettingsViewModel.provideFactory(appContainer.trackerRepository)
                    )
                SettingsScreen(vm)
            }
        }
    }
}
