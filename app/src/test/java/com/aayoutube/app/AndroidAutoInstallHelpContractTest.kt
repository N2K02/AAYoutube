package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Regression guards for installation guidance; never alter video/music services. */
class AndroidAutoInstallHelpContractTest {
    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    private val help = src("app/src/main/java/com/aayoutube/app/AndroidAutoInstallHelp.kt")
    private val screens = src("app/src/main/java/com/aayoutube/app/Screens.kt")
    private val manifest = src("app/src/main/AndroidManifest.xml")

    @Test fun trustedProjectLinksAndExplanations() {
        assertTrue(help.contains("https://github.com/fcaronte/KingInstaller/releases"))
        assertTrue(help.contains("https://github.com/malebuffy/AAEnabler"))
        assertTrue(help.contains("https://github.com/N2K02/LocalAdb"))
        assertTrue(help.contains("normal ADB installation alone may not fix"))
        assertTrue(help.contains("installers.none { it.url == url }"))
    }

    @Test fun firstLaunchPromptAndSettingsAlwaysReachable() {
        assertTrue(help.contains("seen_version"))
        assertTrue(help.contains("dont_show_again"))
        assertTrue(help.contains("reported_missing_video"))
        assertTrue(help.contains("shouldPromptForVersion("))
        assertTrue(AndroidAutoInstallHelp.shouldPromptForVersion(-1, 100, false, false))
        assertFalse(AndroidAutoInstallHelp.shouldPromptForVersion(-1, 100, true, true))
        assertFalse(AndroidAutoInstallHelp.shouldPromptForVersion(100, 100, false, true))
        assertFalse(AndroidAutoInstallHelp.shouldPromptForVersion(100, 101, false, false))
        assertTrue(AndroidAutoInstallHelp.shouldPromptForVersion(100, 101, false, true))
        assertFalse(AndroidAutoInstallHelp.shouldPromptForVersion(100, 101, true, true))
        assertTrue(screens.contains("phoneDisplay && AndroidAutoInstallHelp.shouldPrompt"))
        assertTrue(screens.contains("onOpenGuide = { hide ->"))
        assertTrue(screens.contains("Tab.InstallationHelp -> AndroidAutoInstallationPage()"))
        assertTrue(screens.contains("Tab.ExperimentalVideo, Tab.InstallationHelp"))
    }

    @Test fun visibleStatusIsNotMisrepresentedAndServiceRemainsUnchanged() {
        assertTrue(help.contains("cannot confirm that Android Auto will show or accept"))
        assertTrue(help.contains("videoServiceDeclaredAndEnabled"))
        assertTrue(help.contains("CATEGORY_PROJECTION"))
        assertTrue(manifest.contains("android:name=\".LegacyProjectionService\""))
        assertTrue(manifest.contains("android:name=\".PlaybackService\""))
    }

    @Test fun updatesRetainExistingUpdaterAndOfferInstallerGuidance() {
        assertTrue(help.contains("install an update normally may make it disappear again"))
        assertTrue(help.contains("Download the signed APK from the existing updater/release"))
        assertTrue(screens.contains("dev.n2k02.updater.N2K02UpdatePage("))
    }
}
