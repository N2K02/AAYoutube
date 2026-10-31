package com.aayoutube.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** The switches on the Features page. Part of them is read by the page script (see aay.js). */
data class FeatureFlags(
    val blockAds: Boolean = true,
    val hideShorts: Boolean = true,
    val hidePromos: Boolean = true,
    val backgroundPlay: Boolean = true,
    val lowQualityInBackground: Boolean = true,
    val autoPip: Boolean = true,
    val autoContinue: Boolean = true,
    val sponsorBlock: Boolean = true,
    val sponsorExtra: Boolean = false,
    val returnDislike: Boolean = true,
    val desktopSite: Boolean = false,
    /** Page text size in percent (bigger helps on a car screen). */
    val pageZoom: Int = 100,
    /** Open the last page you were on when the app starts. */
    val resumeLast: Boolean = false,
    /** App's watch-screen top header on phone and car; 0 keeps it visible. */
    val topMenuTimeoutSeconds: Int = TopMenuPolicy.DEFAULT_SECONDS,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("blockAds", blockAds)
        .put("hideShorts", hideShorts)
        .put("hidePromos", hidePromos)
        .put("backgroundPlay", backgroundPlay)
        .put("lowQuality", lowQualityInBackground)
        .put("autoContinue", autoContinue)
        .put("sponsorBlock", sponsorBlock)
        .put("sbExtra", sponsorExtra)
        .put("returnDislike", returnDislike)
}

/** Saved settings (web process only). */
object Features {
    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(FeatureFlags())
    val state: StateFlow<FeatureFlags> = _state
    val current: FeatureFlags get() = _state.value

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("aay_features", Context.MODE_PRIVATE)
        val d = FeatureFlags()
        _state.value = FeatureFlags(
            blockAds = prefs.getBoolean("blockAds", d.blockAds),
            hideShorts = prefs.getBoolean("hideShorts", d.hideShorts),
            hidePromos = prefs.getBoolean("hidePromos", d.hidePromos),
            backgroundPlay = prefs.getBoolean("backgroundPlay", d.backgroundPlay),
            lowQualityInBackground = prefs.getBoolean("lowQuality", d.lowQualityInBackground),
            autoPip = prefs.getBoolean("autoPip", d.autoPip),
            autoContinue = prefs.getBoolean("autoContinue", d.autoContinue),
            sponsorBlock = prefs.getBoolean("sponsorBlock", d.sponsorBlock),
            sponsorExtra = prefs.getBoolean("sponsorExtra", d.sponsorExtra),
            returnDislike = prefs.getBoolean("returnDislike", d.returnDislike),
            desktopSite = prefs.getBoolean("desktopSite", d.desktopSite),
            pageZoom = prefs.getInt("pageZoom", d.pageZoom).coerceIn(50, 200),
            resumeLast = prefs.getBoolean("resumeLastPage", d.resumeLast),
            topMenuTimeoutSeconds = TopMenuPolicy.normalize(
                prefs.getInt("topMenuTimeoutSeconds", d.topMenuTimeoutSeconds)
            ),
        )
    }

    fun update(change: (FeatureFlags) -> FeatureFlags) {
        val n = change(_state.value).let {
            it.copy(topMenuTimeoutSeconds = TopMenuPolicy.normalize(it.topMenuTimeoutSeconds))
        }
        _state.value = n
        prefs.edit()
            .putBoolean("blockAds", n.blockAds)
            .putBoolean("hideShorts", n.hideShorts)
            .putBoolean("hidePromos", n.hidePromos)
            .putBoolean("backgroundPlay", n.backgroundPlay)
            .putBoolean("lowQuality", n.lowQualityInBackground)
            .putBoolean("autoPip", n.autoPip)
            .putBoolean("autoContinue", n.autoContinue)
            .putBoolean("sponsorBlock", n.sponsorBlock)
            .putBoolean("sponsorExtra", n.sponsorExtra)
            .putBoolean("returnDislike", n.returnDislike)
            .putBoolean("desktopSite", n.desktopSite)
            .putInt("pageZoom", n.pageZoom)
            .putBoolean("resumeLastPage", n.resumeLast)
            .putInt("topMenuTimeoutSeconds", n.topMenuTimeoutSeconds)
            .apply()
    }
}
