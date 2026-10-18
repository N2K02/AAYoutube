package com.aayoutube.app

import android.content.Context
import android.media.MediaDrm
import android.os.Build
import android.webkit.WebSettings
import androidx.media3.common.MediaLibraryInfo
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.UUID

/** What the app runs on, for the Update & Version page (web process). */
object Versions {
    class Row(val label: String, val value: String)
    class Group(val title: String, val rows: List<Row>)

    private val WIDEVINE = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

    fun collect(context: Context): List<Group> {
        val pm = context.packageManager
        val webPkg = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        val chrome = runCatching {
            Regex("Chrome/([\\d.]+)").find(WebSettings.getDefaultUserAgent(context))?.groupValues?.get(1)
        }.getOrNull()
        val androidAuto = runCatching {
            @Suppress("DEPRECATION") pm.getPackageInfo("com.google.android.projection.gearhead", 0).versionName
        }.getOrNull()
        val widevine = runCatching {
            val drm = MediaDrm(WIDEVINE)
            try {
                val level = runCatching { drm.getPropertyString("securityLevel") }.getOrDefault("?")
                val ver = runCatching { drm.getPropertyString(MediaDrm.PROPERTY_VERSION) }.getOrDefault("?")
                "$level (version $ver)"
            } finally {
                if (Build.VERSION.SDK_INT >= 28) drm.close() else @Suppress("DEPRECATION") drm.release()
            }
        }.getOrElse { "not available" }

        fun yes(feature: String) = if (WebViewFeature.isFeatureSupported(feature)) "Yes" else "No"

        return listOf(
            Group(
                "Device",
                listOf(
                    Row("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
                    Row("Phone", "${Build.MANUFACTURER} ${Build.MODEL}"),
                    Row("Android Auto", androidAuto ?: "not installed"),
                    Row(
                        "Car screen mode",
                        if (Build.VERSION.SDK_INT >= 35) "App screen on the car display" else "Drawing surface (Android 14 and older)",
                    ),
                ),
            ),
            Group(
                "WebView",
                listOf(
                    Row("Provider", webPkg?.packageName ?: "unknown"),
                    Row("Version", webPkg?.versionName ?: "unknown"),
                    Row("Chromium engine", chrome ?: "unknown"),
                    Row("Widevine (protected video)", widevine),
                    Row("Script before page load", yes(WebViewFeature.DOCUMENT_START_SCRIPT)),
                    Row("Hide app header from Google", yes(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)),
                ),
            ),
            Group(
                "Libraries",
                listOf(
                    Row("Compose Material 3 (Expressive)", BuildConfig.LIB_MATERIAL3),
                    Row("Media3 session", MediaLibraryInfo.VERSION),
                    Row("AndroidX WebKit", BuildConfig.LIB_WEBKIT),
                    Row("Car App library", BuildConfig.LIB_CARAPP),
                    Row("Activity Compose", BuildConfig.LIB_ACTIVITYCOMPOSE),
                    Row("Kotlin", KotlinVersion.CURRENT.toString()),
                    Row("Coroutines", BuildConfig.LIB_COROUTINES),
                ),
            ),
        )
    }
}
