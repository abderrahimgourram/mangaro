package eu.kanade.presentation.util

import androidx.compose.runtime.Composable
import uy.kohesive.injekt.api.get
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun rememberRequestPackageInstallsPermissionState(initialValue: Boolean = false): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var installGranted by remember { mutableStateOf(initialValue) }

    DisposableEffect(lifecycleOwner.lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                installGranted = context.packageManager.canRequestPackageInstalls()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return installGranted
}

/** Public image exports need a user-initiated grant only on Android 8/9; MediaStore handles 10+. */
@Composable
fun rememberImageSaveAction(onGranted: () -> Unit, onDenied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val grantedAction by androidx.compose.runtime.rememberUpdatedState(onGranted)
    val deniedAction by androidx.compose.runtime.rememberUpdatedState(onDenied)
    var explanation by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> requesting = false; if (granted) grantedAction() else deniedAction() }
    if (explanation) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { explanation = false },
                containerColor = eu.kanade.presentation.theme.MangaroDesignSystem.SurfaceDark,
                titleContentColor = eu.kanade.presentation.theme.MangaroDesignSystem.TextPrimary,
                textContentColor = eu.kanade.presentation.theme.MangaroDesignSystem.TextSecondary,
                title = { androidx.compose.material3.Text("حفظ الصورة") },
                text = { androidx.compose.material3.Text("على هذا الإصدار من Android، نحتاج إذنًا لحفظ الصورة في مجلد الصور. يمكنك إلغاء الطلب ومتابعة القراءة. الإصدارات الأحدث تحفظ الصور دون هذا الإذن.") },
                confirmButton = { androidx.compose.material3.TextButton(onClick = {
                    explanation = false; requesting = true
                    launcher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }) { androidx.compose.material3.Text("متابعة", color = eu.kanade.presentation.theme.MangaroDesignSystem.GoldPrimary) } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { explanation = false }) { androidx.compose.material3.Text("إلغاء") } },
            )
        }
    }
    return {
        if (android.os.Build.VERSION.SDK_INT >= 29 || androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            grantedAction()
        } else if (!requesting) explanation = true
    }
}

/** Notification grants are initiated by an alert preference/action, never app launch. */
@Composable
fun rememberNotificationPermissionAction(): () -> Unit {
    val context = LocalContext.current
    val preferences = remember { uy.kohesive.injekt.Injekt.get<eu.kanade.domain.base.BasePreferences>() }
    val requestHistory = remember(context) { context.getSharedPreferences("mangaro_notification_permission", android.content.Context.MODE_PRIVATE) }
    val activity = remember(context) {
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
    }
    var requesting by remember { mutableStateOf(false) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { requesting = false }
    return {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !requesting &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val previouslyRequested = requestHistory.getBoolean("requested", false)
            if (previouslyRequested && activity?.shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS) == false) {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
            } else {
                preferences.notificationPromptHandled.set(true)
                requestHistory.edit().putBoolean("requested", true).apply()
                requesting = true
                launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
