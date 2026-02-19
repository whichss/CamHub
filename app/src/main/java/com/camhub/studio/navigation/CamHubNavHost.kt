package com.camhub.studio.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.camhub.studio.ui.camera.CameraHudScreen
import com.camhub.studio.ui.camera.CameraHudViewModel
import com.camhub.studio.ui.connection.ConnectionSetupScreen
import com.camhub.studio.ui.connection.ConnectionViewModel
import com.camhub.studio.ui.director.DirectorScreen
import com.camhub.studio.ui.director.DirectorViewModel
import com.camhub.studio.ui.settings.SettingsScreen
import com.camhub.studio.ui.settings.SettingsViewModel

object CamHubRoutes {
    const val ROLE_SELECT = "role_select"
    const val CONNECTION_SETUP = "connection_setup/{role}"
    const val CAMERA_HUD = "camera_hud"
    const val DIRECTOR = "director"
    const val SETTINGS = "settings"

    fun connectionSetup(role: String): String = "connection_setup/$role"
}

@Composable
fun CamHubNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = CamHubRoutes.ROLE_SELECT
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(CamHubRoutes.ROLE_SELECT) {
            RoleSelectScreen(
                onRoleSelected = { role ->
                    navController.navigate(CamHubRoutes.connectionSetup(role))
                }
            )
        }

        composable(
            route = CamHubRoutes.CONNECTION_SETUP,
            arguments = listOf(
                navArgument("role") { type = NavType.StringType }
            )
        ) {
            val viewModel: ConnectionViewModel = hiltViewModel()
            ConnectionSetupScreen(
                viewModel = viewModel,
                onNavigateToCameraHud = {
                    navController.navigate(CamHubRoutes.CAMERA_HUD) {
                        popUpTo(CamHubRoutes.ROLE_SELECT) { inclusive = false }
                    }
                },
                onNavigateToDirector = {
                    navController.navigate(CamHubRoutes.DIRECTOR) {
                        popUpTo(CamHubRoutes.ROLE_SELECT) { inclusive = false }
                    }
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(CamHubRoutes.CAMERA_HUD) {
            val viewModel: CameraHudViewModel = hiltViewModel()
            CameraHudScreen(
                viewModel = viewModel
            )
        }

        composable(CamHubRoutes.DIRECTOR) {
            val viewModel: DirectorViewModel = hiltViewModel()
            DirectorScreen(
                viewModel = viewModel,
                onNavigateToSettings = {
                    navController.navigate(CamHubRoutes.SETTINGS)
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(CamHubRoutes.SETTINGS) {
            val viewModel: SettingsViewModel = hiltViewModel()
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
