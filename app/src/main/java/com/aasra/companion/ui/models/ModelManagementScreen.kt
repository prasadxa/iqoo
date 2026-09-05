package com.aasra.companion.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aasra.companion.R
import com.aasra.companion.ui.components.PageLayout

@Composable
fun ModelManagementScreen(onBack: () -> Unit, onModelsChanged: () -> Unit) {
    var leaving by remember { mutableStateOf(false) }
    BackHandler { leaving = true }
    PageLayout(stringResource(R.string.setup_models_title), stringResource(R.string.setup_back),
        onBack = { leaving = true }) {
        ModelDownloadPanel(
            onModelsChanged = onModelsChanged,
            stopRequested = leaving,
            onStopped = onBack,
        )
    }
}
