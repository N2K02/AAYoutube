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
    val lowQualityInBackground: Boolean = true,
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
    /** Where the controls sit along their edge (0..1); dragged by the user. */
    val controlsPosPortrait: Float = 0.5f,
    val controlsPosLandscape: Float = 0.5f,
    /** Opt-in car-only surface recovery; never changes the media service. */
    val experimentalVideo: Boolean = false,
    /** Speed every video starts at; a speed picked in YouTube's menu replaces it when remembered. */
    val playbackSpeed: Float = 1f,
    val rememberSpeed: Boolean = true,
    val loopVideo: Boolean = false,
    /** "default" leaves YouTube's own caption choice alone. */
    val captions: String = "default",
    /** Double-tap seek length; 10 is YouTube's own gesture. */
    val seekSeconds: Int = PlayerPrefsPolicy.YOUTUBE_SEEK_SECONDS,
    val hideComments: Boolean = false,
    val hideEndCards: Boolean = false,
    /** av1, vp9 or h264; the default is chosen per device on first start. */
    val videoCodec: String = "h264",
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
        .put("speed", playbackSpeed.toDouble())
        .put("rememberSpeed", rememberSpeed)
        .put("loop", loopVideo)
        .put("captions", captions)
        .put("seekSeconds", seekSeconds)
        .put("hideComments", hideComments)
        .put("hideEndCards", hideEndCards)
        .put("videoCodec", videoCodec)
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
        // V3: the background saver now waits 60 s (144p), never runs in PiP/car/ads/live and
        // restores quality if audio stalls, so it is on by default once. A later Off by
        // the user is kept.
        val lowQuality = if (prefs.getBoolean("qualityPolicyV3", false)) {
            prefs.getBoolean("lowQuality", true)
        } else {
            prefs.edit().putBoolean("lowQuality", true).putBoolean("qualityPolicyV2", true)
                .putBoolean("qualityPolicyV3", true).apply()
            true
        }
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
            controlsPosPortrait = ControlsSidePolicy.normalize(prefs.getFloat("controlsPosPortrait", d.controlsPosPortrait)),
            controlsPosLandscape = ControlsSidePolicy.normalize(prefs.getFloat("controlsPosLandscape", d.controlsPosLandscape)),
            experimentalVideo = prefs.getBoolean("experimentalVideo", d.experimentalVideo),
            playbackSpeed = PlayerPrefsPolicy.normalizeSpeed(prefs.getFloat("playbackSpeed", d.playbackSpeed)),
            rememberSpeed = prefs.getBoolean("rememberSpeed", d.rememberSpeed),
            loopVideo = prefs.getBoolean("loopVideo", d.loopVideo),
            captions = PlayerPrefsPolicy.normalizeCaptions(prefs.getString("captions", d.captions)),
            seekSeconds = PlayerPrefsPolicy.normalizeSeek(prefs.getInt("seekSeconds", d.seekSeconds)),
            hideComments = prefs.getBoolean("hideComments", d.hideComments),
            hideEndCards = prefs.getBoolean("hideEndCards", d.hideEndCards),
            videoCodec = VideoCodecPolicy.normalize(
                prefs.getString("videoCodec", null) ?: VideoCodecPolicy.deviceDefault().also {
                    prefs.edit().putString("videoCodec", it).apply()
                }
            ),
        )
    }

    fun update(change: (FeatureFlags) -> FeatureFlags) {
        val n = change(_state.value).let {
            it.copy(topMenuTimeoutSeconds = TopMenuPolicy.normalize(it.topMenuTimeoutSeconds),
                preferredQuality = VideoQualityPolicy.normalize(it.preferredQuality),
                playbackSpeed = PlayerPrefsPolicy.normalizeSpeed(it.playbackSpeed),
                seekSeconds = PlayerPrefsPolicy.normalizeSeek(it.seekSeconds),
                captions = PlayerPrefsPolicy.normalizeCaptions(it.captions),
                videoCodec = VideoCodecPolicy.normalize(it.videoCodec))
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
            .putFloat("controlsPosPortrait", n.controlsPosPortrait)
            .putFloat("controlsPosLandscape", n.controlsPosLandscape)
            .putBoolean("experimentalVideo", n.experimentalVideo)
            .putFloat("playbackSpeed", n.playbackSpeed)
            .putBoolean("rememberSpeed", n.rememberSpeed)
            .putBoolean("loopVideo", n.loopVideo)
            .putString("captions", n.captions)
            .putInt("seekSeconds", n.seekSeconds)
            .putBoolean("hideComments", n.hideComments)
            .putBoolean("hideEndCards", n.hideEndCards)
            .putString("videoCodec", n.videoCodec)
            .apply()
    }
}
