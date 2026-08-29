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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.prestondihle.healthtracker.di.AppContainer
import com.prestondihle.healthtracker.ui.fuel.FuelScreen
import com.prestondihle.healthtracker.ui.fuel.FuelViewModel
import com.prestondihle.healthtracker.ui.log.LogScreen
import com.prestondihle.healthtracker.ui.reorder.CardOrderViewModel
import com.prestondihle.healthtracker.ui.settings.SettingsScreen
import com.prestondihle.healthtracker.ui.settings.SettingsViewModel
import com.prestondihle.healthtracker.ui.today.TodayScreen
import com.prestondihle.healthtracker.ui.today.TodayViewModel
import com.prestondihle.healthtracker.ui.trends.TrendsScreen
import com.prestondihle.healthtracker.ui.trends.TrendsViewModel
import com.prestondihle.healthtracker.ui.wellness.WellnessScreen
import com.prestondihle.healthtracker.ui.wellness.WellnessViewModel

/**
 * The six tabs, in bar order.
 *
 * Labels are kept to one short word because there are six of them: Material's
 * navigation bar divides the width evenly and truncates whatever does not fit,
 * so "Master Graph" rendered as "Master G...". Nothing here exceeds the eight
 * characters of "Settings", which has always fitted -- which is why Nutrition
 * became Fuel and Wellbeing became Wellness rather than being left to truncate.
 *
 * Log holds the hand-entry controls; Wellness the body and vitals trends;
 * Activity the movement trends. Log and Wellness share one [WellnessViewModel]
 * and Activity, Wellness and Fuel share one [TrendsViewModel], both hoisted
 * below so a tab switch does not spin up a second copy -- and, for Wellness,
 * a second Health Connect sync.
 */
/** One [CardOrderViewModel] per tab, keyed by route so each tab keeps its own saved order. */
@Composable
private fun cardOrderVm(appContainer: AppContainer, route: String): CardOrderViewModel =
    viewModel(
        key = "order-$route",
        factory = CardOrderViewModel.provideFactory(appContainer.trackerRepository, route),
    )

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

    // Hoisted so the tabs that share them get one instance each, not one per tab.
    // Scoped to the host, they outlive individual tab visits, which is what lets
    // Wellness and Log read the same state without a second sync.
    val wellnessViewModel: WellnessViewModel =
        viewModel(factory = WellnessViewModel.provideFactory(appContainer.trackerRepository))
    val trendsViewModel: TrendsViewModel =
        viewModel(factory = TrendsViewModel.provideFactory(appContainer.trackerRepository))

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        // Smaller than the bar's default label so six of them fit on
                        // one line each -- "Wellness" wrapped to a second line at the
                        // stock size, which stole height from every tab's icon.
                        label = { Text(screen.label, fontSize = 10.sp, maxLines = 1) },
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
            composable(Screen.Today.route) {
                val vm: TodayViewModel =
                    viewModel(
                        factory = TodayViewModel.provideFactory(appContainer.trackerRepository)
                    )
                TodayScreen(
                    vm,
                    cardOrderVm(appContainer, Screen.Today.route),
                    trendsViewModel,
                    // Same options as the bottom bar's own taps, so a chip and
                    // the tab button below it land in exactly the same place --
                    // a second navigation path with different flags would leave
                    // the back stack depending on which of the two was used.
                    onOpenTab = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Screen.Log.route) {
                LogScreen(
                    wellnessViewModel,
                    snackbarHostState,
                    cardOrderVm(appContainer, Screen.Log.route),
                )
            }
            // Fuel's own screen: fasting, hydration, caffeine, creatine,
            // supplements, and the macro trend at the foot.
            composable(Screen.Fuel.route) {
                val vm: FuelViewModel =
                    viewModel(
                        factory = FuelViewModel.provideFactory(appContainer.trackerRepository)
                    )
                FuelScreen(
                    vm,
                    snackbarHostState,
                    trendsViewModel,
                    cardOrderVm(appContainer, Screen.Fuel.route),
                )
            }
            // Activity: the movement trends -- steps, grip strength, pushups and
            // air squats.
            composable(Screen.Activity.route) {
                TrendsScreen(trendsViewModel, cardOrderVm(appContainer, Screen.Activity.route))
            }
            // Wellness: the display cards (activity summary, last night's sleep,
            // glucose and ketones) with the body and vitals trends, the mood
            // trend and the movement log.
            composable(Screen.Wellness.route) {
                WellnessScreen(
                    wellnessViewModel,
                    trendsViewModel,
                    cardOrderVm(appContainer, Screen.Wellness.route),
                    snackbarHostState,
                )
            }
            composable(Screen.Settings.route) {
                val vm: SettingsViewModel =
                    viewModel(
                        factory = SettingsViewModel.provideFactory(appContainer.trackerRepository)
                    )
                SettingsScreen(vm, cardOrderVm(appContainer, Screen.Settings.route))
            }
        }
    }
}
