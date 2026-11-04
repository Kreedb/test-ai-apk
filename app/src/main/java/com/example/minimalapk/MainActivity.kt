package com.example.minimalapk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.minimalapk.ui.MinimalApkApp
import com.example.minimalapk.ui.theme.MinimalApkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MinimalApkTheme {
                MinimalApkApp()
            }
        }
    }
}
