package com.spoolcheck.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.spoolcheck.app.ui.SpoolCheckTheme
import com.spoolcheck.app.ui.screens.DebugLogScreen
import com.spoolcheck.app.ui.screens.HomeScreen
import com.spoolcheck.app.ui.screens.ImportScreen
import com.spoolcheck.app.ui.screens.ScannerScreen
import com.spoolcheck.app.ui.screens.SettingsScreen
import com.spoolcheck.app.ui.screens.StatusBoardScreen
import com.spoolcheck.app.ui.screens.UnchartedScreen

// AppCompatActivity (instead of ComponentActivity) so AppCompatDelegate's
// per-app locale machinery actually applies + persists when the user
// picks Dutch/English in Settings. Pairs with Theme.AppCompat parent.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpoolCheckTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav()
                }
            }
        }
    }
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") { HomeScreen(nav) }
        composable("import") { ImportScreen(nav) }
        composable(
            "board/{deliveryId}",
            arguments = listOf(navArgument("deliveryId") { type = NavType.StringType }),
        ) { backStack ->
            StatusBoardScreen(nav, backStack.arguments?.getString("deliveryId") ?: return@composable)
        }
        composable(
            "scan/{deliveryId}",
            arguments = listOf(navArgument("deliveryId") { type = NavType.StringType }),
        ) { backStack ->
            ScannerScreen(nav, backStack.arguments?.getString("deliveryId") ?: return@composable)
        }
        composable(
            "uncharted/{deliveryId}",
            arguments = listOf(navArgument("deliveryId") { type = NavType.StringType }),
        ) { backStack ->
            UnchartedScreen(nav, backStack.arguments?.getString("deliveryId") ?: return@composable)
        }
        composable("settings") { SettingsScreen(nav) }
        composable("debug-log") { DebugLogScreen(nav) }
    }
}
