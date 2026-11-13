package com.hermes.chatandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hermes.chatandroid.ui.AppNav
import com.hermes.chatandroid.ui.theme.HermesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as HermesApp).container
        setContent {
            HermesTheme {
                AppNav(container)
            }
        }
    }
}
