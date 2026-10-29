package com.aayoutube.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class FeaturesPageContractTest {
    private fun source(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

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
