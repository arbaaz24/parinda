package com.example.parinda

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import org.maplibre.android.MapLibre
import com.example.parinda.ui.theme.ParindaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)
        enableEdgeToEdge()
        setContent {
            ParindaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RouteMapScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}