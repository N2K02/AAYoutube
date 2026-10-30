package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class FeaturesPageContractTest {
    private fun source(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun playbackDisplaySwitchesAreStoredAndReachThePage() {
        val features = source("app/src/main/java/com/aayoutube/app/Features.kt")
        val screen = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        val web = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        val js = source("app/src/main/assets/aay.js")
        for (flag in listOf("blockAds", "hideShorts", "hidePromos", "backgroundPlay",
            "lowQuality", "autoContinue", "sponsorBlock", "sbExtra", "returnDislike")) {
            assertTrue("Missing persisted flag $flag", features.contains("putBoolean(\"$flag\""))
            assertTrue("Missing script option $flag", features.contains(".put(\"$flag\""))
        }
        assertTrue(features.contains("putBoolean(\"autoPip\""))
        assertTrue(screen.contains("LaunchedEffect(snap.playing, flags.autoPip, playerMode, tab, inPip)"))
        assertTrue(web.contains("w.settings.textZoom = f.pageZoom"))
        assertTrue(web.contains("if (desktopChanged)"))
        assertTrue(screen.contains("Features.update { it.copy(pageZoom = z) }"))
        assertTrue(js.contains("A.config = function (c)"))
        assertTrue(js.contains("if (CFG.hidePromos) css += CSS_PROMO"))
        assertTrue(js.contains("if (CFG.hideShorts) css += CSS_SHORTS"))
        assertTrue(js.contains("if (CFG.blockAds) css += CSS_ADS"))
        assertTrue(js.contains("if (CFG.autoContinue === false) continue"))
    }

    @Test fun signOutOnlyAppearsWhenYouTubeConfirmedLogin() {
        val ui = source("app/src/main/java/com/aayoutube/app/Screens.kt")
        val features = ui.substringAfter("fun FeaturesPage()").substringBefore("// ---------------------------------------------------------------------------- update")
        assertTrue(features.contains("if (account == YouTubeAccountState.SIGNED_IN)"))
        assertTrue(features.contains("WebHost.clearSession()"))
        assertTrue(features.contains("enabled = f.backgroundPlay"))
        assertTrue(features.contains("enabled = f.sponsorBlock"))
        val host = source("app/src/main/java/com/aayoutube/app/WebHost.kt")
        assertTrue(host.contains("youtubeAccount.value = YouTubeAccountState.CHECKING"))
        assertTrue(host.contains("removeAllCookies {"))
        assertTrue(host.contains("BrowseHost.onSessionCleared()"))
        val script = source("app/src/main/assets/aay.js")
        assertTrue(script.contains("cfg.get('LOGGED_IN')"))
        assertTrue(script.contains("if (typeof signedIn !== 'boolean') return"))
        assertTrue(script.contains("if (CFG.hidePromos) css += CSS_OPEN_APP"))
        assertTrue(script.contains("oldSponsor !== CFG.sponsorBlock || oldExtra !== CFG.sbExtra"))
        assertTrue(script.contains("request !== sb.request"))
        assertTrue(script.contains("document.querySelectorAll('.aay-ryd')"))
        assertTrue(script.contains("CFG.backgroundPlay !== false && !lowered"))
    }
}
