package com.aayoutube.app

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Links lead to upstream project/release pages, not unverified direct APK downloads. */
internal data class AndroidAutoInstaller(val name: String, val description: String, val url: String)

internal object AndroidAutoInstallHelp {
    val installers = listOf(
        AndroidAutoInstaller("KingInstaller", "Android Auto-focused APK installation methods; may require Shizuku or root on some phones.",
            "https://github.com/fcaronte/KingInstaller/releases"),
        AndroidAutoInstaller("AAEnabler", "Select a compatible APK and open its installation flow; availability still depends on your device.",
            "https://github.com/malebuffy/AAEnabler"),
        AndroidAutoInstaller("LocalADB", "Advanced local ADB tools and diagnostics; normal ADB installation alone may not fix Android Auto visibility.",
            "https://github.com/N2K02/LocalAdb"),
    )

    private const val PREF = "android_auto_install_help"
    private const val SEEN = "seen_version"
    private const val OPT_OUT = "dont_show_again"
    private const val MISSING = "reported_missing_video"

    fun shouldPrompt(context: Context, version: Int): Boolean {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (p.getBoolean(OPT_OUT, false)) return false
        val seen = p.getInt(SEEN, -1)
        return seen == -1 || (seen != version && p.getBoolean(MISSING, false))
    }

    fun acknowledge(context: Context, version: Int, dontShowAgain: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(SEEN, version).putBoolean(OPT_OUT, dontShowAgain).apply()
    }

    fun reportMissing(context: Context, missing: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(MISSING, missing).apply()
    }

    fun isMissing(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(MISSING, false)

    /** Manifest/package-manager self-check only. Never claim the head unit actually displays the app. */
    fun videoServiceDeclaredAndEnabled(context: Context): Boolean {
        val pm = context.packageManager
        val component = ComponentName(context, LegacyProjectionService::class.java)
        val state = pm.getComponentEnabledSetting(component)
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) return false
        val intent = Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
            .addCategory("com.google.android.gms.car.category.CATEGORY_PROJECTION")
        return runCatching {
            pm.queryIntentServices(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
                .any { it.serviceInfo?.name == component.className && it.serviceInfo?.enabled == true }
        }.getOrDefault(false)
    }

    fun openLink(context: Context, url: String) {
        if (installers.none { it.url == url }) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { AppLog.add("CAR", "Installation help link unavailable: " + it.javaClass.simpleName) }
    }
}

@Composable
fun AndroidAutoFirstLaunchHelp(onDismiss: (Boolean) -> Unit, onOpenGuide: (Boolean) -> Unit) {
    var dontShowAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDismiss(dontShowAgain) },
        icon = { Icon(Icons.Filled.InstallMobile, contentDescription = null) },
        title = { Text("Android Auto installation help") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Is AAYT Video missing from Android Auto? Some phones restrict apps installed directly from APK files.")
                Text("Check Android Auto's Customize launcher and Developer settings → Unknown sources first. If needed, use an alternative installer.")
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                    Text("Don't show this again", modifier = Modifier.clickable { dontShowAgain = !dontShowAgain }
                        .padding(top = 12.dp))
                }
            }
        },
        confirmButton = { Button(onClick = { onOpenGuide(dontShowAgain) }) { Text("View options") } },
        dismissButton = { TextButton(onClick = { onDismiss(dontShowAgain) }) { Text("Not now") } },
    )
}

/** Settings submenu; shows only phone-side information and opens trusted external project pages. */
@Composable
fun AndroidAutoInstallationPage() {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(AndroidAutoInstallHelp.isMissing(context)) }
    val declared = remember { AndroidAutoInstallHelp.videoServiceDeclaredAndEnabled(context) }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AAYT Video not appearing?", style = MaterialTheme.typography.titleLarge)
        Text("First check Android Auto → Customize launcher. In Android Auto Developer settings, enable Unknown sources if available, then reconnect your car.")
        Text(
            if (declared) "Video projection service: declared and enabled on this phone."
            else "Video projection service: not discoverable or disabled on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("This phone-side check cannot confirm that Android Auto will show or accept the app.")
        HorizontalDivider()
        Text("Installation options", style = MaterialTheme.typography.titleMedium)
        AndroidAutoInstallHelp.installers.forEach { installer ->
            ListItem(
                headlineContent = { Text(installer.name) },
                supportingContent = { Text(installer.description) },
                leadingContent = { Icon(Icons.Filled.InstallMobile, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = { AndroidAutoInstallHelp.openLink(context, installer.url) }) {
                        Text("Open")
                    }
                },
            )
        }
        HorizontalDivider()
        Text("Updating AAYouTube", style = MaterialTheme.typography.titleMedium)
        Text("If a special installer was needed to make Video visible, installing an update normally may make it disappear again. Download the signed APK from the existing updater/release and install that APK using the same trusted installer. Keep the existing package/signature; do not uninstall unless necessary.")
        Text("These tools do not add Android Auto support to incompatible apps, and results vary by phone and Android Auto version. Video is for parked use.")
        OutlinedButton(onClick = {
            missing = !missing
            AndroidAutoInstallHelp.reportMissing(context, missing)
        }) { Text(if (missing) "Mark Video as showing" else "Video still missing") }
        Text(
            if (missing) "Noted. Installation help may appear after a future app update."
            else "Video visibility issue not reported.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
