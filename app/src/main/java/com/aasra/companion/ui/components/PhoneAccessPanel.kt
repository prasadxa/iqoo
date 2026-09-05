package com.aasra.companion.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aasra.companion.R
import com.aasra.companion.service.PhoneAccessBus

@Composable
fun PhoneAccessPanel(showSms: Boolean = true) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var contactsAllowed by remember { mutableStateOf(granted(Manifest.permission.READ_CONTACTS)) }
    var callsAllowed by remember { mutableStateOf(granted(Manifest.permission.CALL_PHONE)) }
    var smsAllowed by remember { mutableStateOf(granted(Manifest.permission.SEND_SMS)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    fun refresh() {
        contactsAllowed = granted(Manifest.permission.READ_CONTACTS)
        callsAllowed = granted(Manifest.permission.CALL_PHONE)
        smsAllowed = granted(Manifest.permission.SEND_SMS)
        if (contactsAllowed && callsAllowed && PhoneAccessBus.request.value == PhoneAccessBus.Request.CONTACTS_AND_CALLS ||
            smsAllowed && PhoneAccessBus.request.value == PhoneAccessBus.Request.SMS) PhoneAccessBus.clear()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        denied = result.values.any { !it }
        refresh()
    }
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.phone_access_explanation), style = MaterialTheme.typography.bodyMedium)
        if (!contactsAllowed || !callsAllowed) {
            Button(onClick = { launcher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)) },
                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text(stringResource(R.string.phone_access_allow))
            }
        } else Text(stringResource(R.string.phone_access_ready), style = MaterialTheme.typography.bodyMedium)
        if (showSms && !smsAllowed) {
            TextButton(onClick = { launcher.launch(arrayOf(Manifest.permission.SEND_SMS)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(stringResource(R.string.phone_access_sms)) }
        }
        if (denied) {
            Text(stringResource(R.string.phone_access_denied), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(stringResource(R.string.home_permissions)) }
        }
    }
}
