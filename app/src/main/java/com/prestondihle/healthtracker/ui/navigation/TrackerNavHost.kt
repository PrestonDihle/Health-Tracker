package com.prestondihle.healthtracker.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
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
import com.prestondihle.healthtracker.ui.master.MasterGraphScreen
import com.prestondihle.healthtracker.ui.master.MasterGraphViewModel
import com.prestondihle.healthtracker.ui.placeholder.PlaceholderScreen
import com.prestondihle.healthtracker.ui.settings.SettingsScreen
import com.prestondihle.healthtracker.ui.settings.SettingsViewModel
import com.prestondihle.healthtracker.ui.trends.TrendsScreen
import com.prestondihle.healthtracker.ui.trends.TrendsViewModel

/**
 * The six tabs, in bar order.
 *
 * Labels are kept to one short word because there are six of them: Material's
 * navigation bar divides the width evenly and truncates whatever does not fit,
 * so "Master Graph" rendered as "Master G...". Nothing here exceeds the eight
 * characters of "Settings", which has always fitted -- which is why Nutrition
 * became Fuel and Wellbeing became Wellness rather than being left to truncate.
 *
 * Three tabs are wired to a screen that is not their eventual one. The cards are
 * moved a tab at a time, and unhooking a screen before its replacement exists
 * would leave the reader unable to log anything it carried in the meantime --
 * on the phone holding the only copy of this data. Each temporary route below
 * says what will replace it.
 */
enum class Screen(val route: String, val label: String, val icon: ImageVector) {
    Today("today", "Today", Icons.Filled.QueryStats),
    Log("log", "Log", Icons.Filled.EditNote),
    Fuel("fuel", "Fuel", Icons.Filled.Restaurant),
    Activity("activity", "Activity", Icons.Filled.DirectionsRun),
    Wellness("wellness", "Wellness", Icons.Filled.MonitorHeart),
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
            startDestination = Screen.Today.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            // Today is the master graph. The screen is renamed rather than
            // rebuilt, so this points at it under its old name until that lands.
            composable(Screen.Today.route) {
                val vm: MasterGraphViewModel =
                    viewModel(
                        factory = MasterGraphViewModel.provideFactory(appContainer.trackerRepository)
                    )
                MasterGraphScreen(vm)
            }
            composable(Screen.Log.route) {
                PlaceholderScreen(
                    name = "Log",
                    comingFrom =
                        "Every logging control in one place. Until it is built, log from the " +
                            "card on its own tab.",
                )
            }
            // Temporary: Fuel eventually carries fasting plus hydration,
            // caffeine, creatine, supplements, glucose and ketones, meals and
            // the macro trend. Fasting is the part that already exists.
            composable(Screen.Fuel.route) {
                val vm: FastingPlanViewModel =
                    viewModel(
                        factory = FastingPlanViewModel.provideFactory(appContainer.trackerRepository)
                    )
                FastingPlanScreen(vm)
            }
            // Temporary: Activity eventually carries grip strength and movement
            // beside the step, grip, pushup and squat trends. The old Trends
            // screen holds four of those six today.
            composable(Screen.Activity.route) {
                val vm: TrendsViewModel =
                    viewModel(
                        factory = TrendsViewModel.provideFactory(appContainer.trackerRepository)
                    )
                TrendsScreen(vm)
            }
            // Temporary: Wellness eventually carries sleep, body, blood
            // pressure, mood and reading with their trends. The old Today screen
            // is where all five of those cards live until they are moved.
            composable(Screen.Wellness.route) {
                val vm: DashboardViewModel =
                    viewModel(
                        factory = DashboardViewModel.provideFactory(appContainer.trackerRepository)
                    )
                DashboardScreen(vm, snackbarHostState)
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
