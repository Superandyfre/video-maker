package com.example.videomaker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.videomaker.ui.screens.AboutScreen
import com.example.videomaker.ui.screens.CreateVideoScreen
import com.example.videomaker.ui.screens.GenerateScreen
import com.example.videomaker.ui.screens.HistoryDetailScreen
import com.example.videomaker.ui.screens.HistoryScreen
import com.example.videomaker.ui.screens.HomeScreen
import com.example.videomaker.ui.screens.ResultScreen
import com.example.videomaker.ui.screens.SettingsScreen
import com.example.videomaker.ui.theme.VideoMakerTheme
import com.example.videomaker.viewmodel.AboutViewModel
import com.example.videomaker.viewmodel.CreateVideoViewModel
import com.example.videomaker.viewmodel.GenerateViewModel
import com.example.videomaker.viewmodel.HistoryViewModel
import com.example.videomaker.viewmodel.SettingsViewModel

private const val PageTransitionDurationMillis = 280

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VideoMakerApp()
        }
    }
}

@Composable
fun VideoMakerApp() {
    val navController = rememberNavController()
    val settingsViewModel: SettingsViewModel = viewModel()
    val createVideoViewModel: CreateVideoViewModel = viewModel()
    val generateViewModel: GenerateViewModel = viewModel()
    val historyViewModel: HistoryViewModel = viewModel()
    val generateState by generateViewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.uiState.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(generateState.resumeRoute, currentRoute) {
        val resumeRoute = generateState.resumeRoute ?: return@LaunchedEffect
        if (currentRoute == "home") {
            navController.navigate(resumeRoute) {
                launchSingleTop = true
            }
            generateViewModel.consumeResumeRoute()
        }
    }

    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (settingsState.themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    VideoMakerTheme(darkTheme = darkTheme, dynamicColor = false) {
        NavHost(
            navController = navController,
            startDestination = "home",
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(PageTransitionDurationMillis)
                ) + fadeIn(animationSpec = tween(PageTransitionDurationMillis / 2))
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(PageTransitionDurationMillis)
                ) + fadeOut(animationSpec = tween(PageTransitionDurationMillis / 2))
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(PageTransitionDurationMillis)
                ) + fadeIn(animationSpec = tween(PageTransitionDurationMillis / 2))
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(PageTransitionDurationMillis)
                ) + fadeOut(animationSpec = tween(PageTransitionDurationMillis / 2))
            }
        ) {
            composable("home") {
                HomeScreen(
                    settingsViewModel = settingsViewModel,
                    createVideoViewModel = createVideoViewModel,
                    isGenerating = generateState.isRunning,
                    onGenerate = { input ->
                        generateViewModel.start(input)
                        navController.navigate("generate")
                    },
                    onHistory = { navController.navigate("history") },
                    onSettings = { navController.navigate("settings") }
                )
            }
            composable("settings") {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onAbout = { navController.navigate("about") },
                    onBack = { navController.popBackStack() }
                )
            }
            composable("about") {
                val aboutViewModel: AboutViewModel = viewModel()
                AboutScreen(
                    viewModel = aboutViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("create") {
                CreateVideoScreen(
                    viewModel = createVideoViewModel,
                    onBack = { navController.popBackStack() },
                    onGenerate = { input ->
                        generateViewModel.start(input)
                        navController.navigate("generate")
                    }
                )
            }
            composable("generate") {
                GenerateScreen(
                    viewModel = generateViewModel,
                    onDone = {
                        navController.navigate("result") {
                            launchSingleTop = true
                        }
                    },
                    onBack = {
                        if (!generateState.isRunning && generateState.status == "failed") {
                            generateViewModel.clearPersistedState()
                        }
                        navController.popBackStack()
                    }
                )
            }
            composable("result") {
                ResultScreen(
                    generateViewModel = generateViewModel,
                    settingsViewModel = settingsViewModel,
                    onBackHome = {
                        generateViewModel.clearPersistedState()
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                        }
                    }
                )
            }
            composable("history") {
                HistoryScreen(
                    viewModel = historyViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenRecord = { jobId ->
                        navController.navigate("history/$jobId")
                    }
                )
            }
            composable("history/{jobId}") { backStackEntry ->
                HistoryDetailScreen(
                    viewModel = historyViewModel,
                    settingsViewModel = settingsViewModel,
                    jobId = backStackEntry.arguments?.getString("jobId").orEmpty(),
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
