package com.adl.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.adl.ui.navigation.AdlNavGraph
import com.adl.ui.navigation.Screen

import com.adl.domain.BrowserNavigationManager

data class NavItem(
    val label: String,
    val screen: Screen,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val navItems = listOf(
    NavItem("Browser", Screen.Browser, Icons.Filled.Public, Icons.Outlined.Public),
    NavItem("Gallery", Screen.Gallery, Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary),
    NavItem("Downloads", Screen.Downloads, Icons.Filled.Download, Icons.Outlined.Download),
    NavItem("Settings", Screen.Settings, Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun AdlApp(
    browserNavigationManager: BrowserNavigationManager,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Hide bottom nav on gallery detail screen
    val showBottomNav = currentDestination?.route != Screen.GalleryDetail.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomNav) {
                NavigationBar {
                    navItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == item.screen.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label,
                                )
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        AdlNavGraph(
            navController = navController,
            browserNavigationManager = browserNavigationManager,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
