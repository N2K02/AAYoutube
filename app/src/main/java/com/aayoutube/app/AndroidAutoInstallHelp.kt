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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
    const val RELEASES_URL = "https://github.com/N2K02/AAYoutube/releases"
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

    fun shouldPromptForVersion(seen: Int, version: Int, optedOut: Boolean, missing: Boolean): Boolean =
        !optedOut && seen != version

    fun shouldPrompt(context: Context, version: Int): Boolean {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return shouldPromptForVersion(
            p.getInt(SEEN, -1), version, p.getBoolean(OPT_OUT, false),
            p.getBoolean(MISSING, false),
        )
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
        if (url != RELEASES_URL && installers.none { it.url == url }) return
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

/** Compact Settings submenu; all diagnostics are phone-side only. */
@Composable
fun AndroidAutoInstallationPage() {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(AndroidAutoInstallHelp.isMissing(context)) }
    val declared = remember { AndroidAutoInstallHelp.videoServiceDeclaredAndEnabled(context) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("AAYT Video not appearing?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Check Android Auto → Customize launcher. In Developer settings, enable Unknown sources if available, then reconnect your car.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (declared) "Projection service: enabled on this phone"
                    else "Projection service: not discoverable or disabled",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "This phone-side check cannot confirm that Android Auto will show or accept the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            "Installation options",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        AndroidAutoInstallHelp.installers.forEach { installer ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.InstallMobile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(installer.name, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = {
                            AndroidAutoInstallHelp.openLink(context, installer.url)
                        }) { Text("Open") }
                    }
                    Text(
                        installer.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Video visibility", style = MaterialTheme.typography.titleMedium)
                Text(
                    "These tools cannot make an incompatible app compatible with Android Auto. Video is intended for parked use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = {
                    missing = !missing
                    AndroidAutoInstallHelp.reportMissing(context, missing)
                }) {
                    Text(if (missing) "Mark Video as showing" else "Video still missing")
                }
                Text(
                    if (missing) "Issue reported. Help can be shown after an update."
                    else "No visibility issue reported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
