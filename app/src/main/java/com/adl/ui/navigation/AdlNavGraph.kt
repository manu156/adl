package com.adl.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.adl.domain.BrowserNavigationManager
import com.adl.ui.browser.BrowserScreen
import com.adl.ui.downloads.DownloadsScreen
import com.adl.ui.gallery.GalleryDetailScreen
import com.adl.ui.gallery.GalleryScreen
import com.adl.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Browser : Screen("browser")
    object Gallery : Screen("gallery")
    object Downloads : Screen("downloads")
    object Settings : Screen("settings")
    object GalleryDetail : Screen("gallery_detail/{downloadId}") {
        fun createRoute(downloadId: Long) = "gallery_detail/$downloadId"
    }
}

@Composable
fun AdlNavGraph(
    navController: NavHostController,
    browserNavigationManager: BrowserNavigationManager,
    modifier: Modifier = Modifier,
    startDestination: String = Screen.Browser.route,
) {
    val openInBrowser: (String) -> Unit = { url ->
        browserNavigationManager.openUrl(url)
        navController.navigate(Screen.Browser.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(Screen.Browser.route) {
            BrowserScreen()
        }
        composable(Screen.Gallery.route) {
            GalleryScreen(
                onOpenDetail = { id -> navController.navigate(Screen.GalleryDetail.createRoute(id)) },
            )
        }
        composable(Screen.Downloads.route) {
            DownloadsScreen(
                onOpenGallery = { id -> navController.navigate(Screen.GalleryDetail.createRoute(id)) },
                onOpenInBrowser = openInBrowser,
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen()
        }
        composable(
            route = Screen.GalleryDetail.route,
            arguments = listOf(
                navArgument("downloadId") { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            GalleryDetailScreen(
                downloadId = backStackEntry.arguments!!.getLong("downloadId"),
                onBack = { navController.popBackStack() },
                onOpenInBrowser = openInBrowser,
            )
        }
    }
}
