package com.aasra.companion.ui.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aasra.companion.R
import com.aasra.companion.ui.theme.AasraTheme

/** Accessible from Health Connect on both Android 13 and Android 14+. Never reads data. */
class HealthPermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AasraTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                            .verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text(stringResource(R.string.health_privacy_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.health_privacy_purpose))
                        Text(stringResource(R.string.health_privacy_storage))
                        Text(stringResource(R.string.health_privacy_control))
                        Text(stringResource(R.string.health_watch_explanation))
                        Text(stringResource(R.string.health_medical_notice))
                        HealthButton(stringResource(R.string.health_close), onClick = { finish() })
                    }
                }
            }
        }
    }
}
