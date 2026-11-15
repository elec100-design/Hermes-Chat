package com.hermes.chatandroid

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import com.hermes.chatandroid.notify.OpenRequest
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hermes.chatandroid.ui.AppNav
import com.hermes.chatandroid.ui.theme.HermesTheme

class MainActivity : ComponentActivity() {
    /** 알림 탭으로 열린 경우 이동할 곳 — AppNav 가 처리 후 비운다. */
    private val openRequest = mutableStateOf<OpenRequest?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        OpenRequest.from(intent)?.let { openRequest.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as HermesApp).container
        if (savedInstanceState == null) openRequest.value = OpenRequest.from(intent)
        setContent {
            HermesTheme {
                AppNav(container, openRequest.value, onOpenHandled = { openRequest.value = null })
            }
        }
    }
}
