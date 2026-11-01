package com.aayoutube.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Saved quality values use YouTube's player codes, not display-dependent pixel sizes. */
internal object VideoQualityPolicy {
    private val levels = setOf("auto", "tiny", "small", "medium", "large",
        "hd720", "hd1080", "hd1440", "hd2160", "highres")
    fun isSupported(value: String): Boolean = value in levels
    fun normalize(value: String?): String = value?.takeIf(::isSupported) ?: "hd720"
}

/** The switches on the Features page. Part of them is read by the page script (see aay.js). */
data class FeatureFlags(
    val blockAds: Boolean = true,
    val hideShorts: Boolean = true,
    val hidePromos: Boolean = true,
    val backgroundPlay: Boolean = true,
    val lowQualityInBackground: Boolean = false,
    /** 720p initially; manual quality picks become the default for subsequent videos. */
    val preferredQuality: String = "hd720",
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
    /** Floating video controls rail on the left instead of the right (phone and car). */
    val controlsOnLeft: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("blockAds", blockAds)
        .put("hideShorts", hideShorts)
        .put("hidePromos", hidePromos)
        .put("backgroundPlay", backgroundPlay)
        .put("lowQuality", lowQualityInBackground)
        .put("preferredQuality", preferredQuality)
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
        // Old installs had background tiny (144p) enabled by default. Migrate once
        // to 720p continuity; users may deliberately re-enable the battery saver.
        val previousQualityPolicy = prefs.getBoolean("qualityPolicyV2", false)
        val lowQuality = if (previousQualityPolicy) prefs.getBoolean("lowQuality", false) else false
        if (!previousQualityPolicy) prefs.edit()
            .putBoolean("lowQuality", false).putBoolean("qualityPolicyV2", true).apply()
        _state.value = FeatureFlags(
            blockAds = prefs.getBoolean("blockAds", d.blockAds),
            hideShorts = prefs.getBoolean("hideShorts", d.hideShorts),
            hidePromos = prefs.getBoolean("hidePromos", d.hidePromos),
            backgroundPlay = prefs.getBoolean("backgroundPlay", d.backgroundPlay),
            lowQualityInBackground = lowQuality,
            preferredQuality = VideoQualityPolicy.normalize(prefs.getString("preferredQuality", d.preferredQuality)),
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
            controlsOnLeft = prefs.getBoolean("controlsOnLeft", d.controlsOnLeft),
        )
    }

    fun update(change: (FeatureFlags) -> FeatureFlags) {
        val n = change(_state.value).let {
            it.copy(topMenuTimeoutSeconds = TopMenuPolicy.normalize(it.topMenuTimeoutSeconds),
                preferredQuality = VideoQualityPolicy.normalize(it.preferredQuality))
        }
        _state.value = n
        prefs.edit()
            .putBoolean("blockAds", n.blockAds)
            .putBoolean("hideShorts", n.hideShorts)
            .putBoolean("hidePromos", n.hidePromos)
            .putBoolean("backgroundPlay", n.backgroundPlay)
            .putBoolean("lowQuality", n.lowQualityInBackground)
            .putString("preferredQuality", n.preferredQuality)
            .putBoolean("autoPip", n.autoPip)
            .putBoolean("autoContinue", n.autoContinue)
            .putBoolean("sponsorBlock", n.sponsorBlock)
            .putBoolean("sponsorExtra", n.sponsorExtra)
            .putBoolean("returnDislike", n.returnDislike)
            .putBoolean("desktopSite", n.desktopSite)
            .putInt("pageZoom", n.pageZoom)
            .putBoolean("resumeLastPage", n.resumeLast)
            .putInt("topMenuTimeoutSeconds", n.topMenuTimeoutSeconds)
            .putBoolean("controlsOnLeft", n.controlsOnLeft)
            .apply()
    }
}
