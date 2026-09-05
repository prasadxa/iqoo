package com.aasra.companion

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.aasra.companion.navigation.NavGraph
import com.aasra.companion.ui.theme.AasraTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            AasraTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NavGraph()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as AasraApp).setAppForeground(true)
    }

    override fun onResume() {
        super.onResume()
        // Permission can change in Settings without a preference emission.
        (application as AasraApp).setAppForeground(true)
    }

    override fun onStop() {
        (application as AasraApp).setAppForeground(false)
        super.onStop()
    }
}
