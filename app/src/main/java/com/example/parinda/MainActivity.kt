package com.example.parinda

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import org.maplibre.android.MapLibre
import com.example.parinda.ui.theme.ParindaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)
        enableEdgeToEdge()
        setContent {
            ParindaTheme {
                var currentScreen by rememberSaveable { mutableStateOf(AppScreen.Home) }
                var selectedMotovlogger by remember { mutableStateOf<Motovlogger?>(null) }

                BackHandler(enabled = currentScreen == AppScreen.RouteMap) {
                    currentScreen = AppScreen.Home
                }

                BackHandler(enabled = currentScreen == AppScreen.ExploreDetail) {
                    currentScreen = AppScreen.Explore
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (currentScreen != AppScreen.RouteMap) {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = currentScreen == AppScreen.Home,
                                    onClick = { currentScreen = AppScreen.Home },
                                    icon = { Text("H") },
                                    label = { Text("Home") }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == AppScreen.Explore,
                                    onClick = { currentScreen = AppScreen.Explore },
                                    icon = { Text("E") },
                                    label = { Text("Explore") }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    when (currentScreen) {
                        AppScreen.Home -> HomeScreen(
                            modifier = Modifier.padding(innerPadding),
                            onOpenUnlockedRoutes = { currentScreen = AppScreen.RouteMap }
                        )
                        AppScreen.Explore -> ExploreScreen(
                            modifier = Modifier.padding(innerPadding),
                            onOpenMotovlogger = {
                                selectedMotovlogger = it
                                currentScreen = AppScreen.ExploreDetail
                            }
                        )
                        AppScreen.ExploreDetail -> {
                            val item = selectedMotovlogger
                            if (item != null) {
                                ExploreDetailScreen(
                                    motovlogger = item,
                                    modifier = Modifier.padding(innerPadding)
                                )
                            } else {
                                currentScreen = AppScreen.Explore
                            }
                        }
                        AppScreen.RouteMap -> RouteMapScreen(modifier = Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }
}

private enum class AppScreen {
    Home,
    Explore,
    ExploreDetail,
    RouteMap
}

@androidx.compose.runtime.Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenUnlockedRoutes: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onOpenUnlockedRoutes() },
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Unlocked routes",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}