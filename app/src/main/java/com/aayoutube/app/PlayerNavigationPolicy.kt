package com.aayoutube.app

/**
 * Home / Back decisions for the player, matching the YouTube app:
 * a video never stops because of navigation, it shrinks to the mini player;
 * fullscreen and the landscape video are left one step at a time on Back,
 * while Home always ends on YouTube Home with the mini player.
 */
internal object PlayerNavigationPolicy {
    enum class Home { ExitFullscreenThenMini, Mini, BrowseHome, OpenHome }

    fun home(fullPlayer: Boolean, miniPlayer: Boolean, onWatchPage: Boolean, fullscreen: Boolean): Home = when {
        fullPlayer && onWatchPage && fullscreen -> Home.ExitFullscreenThenMini
        fullPlayer && onWatchPage -> Home.Mini
        miniPlayer -> Home.BrowseHome
        else -> Home.OpenHome
    }

    enum class Back {
        CloseEqualizer, ExitFullscreen, ShowWatchPage, ToWatchTab, ToSettingsTab,
        /** Mini / closed player: back through the browse page, else leave (never on the car). */
        BrowseBack,
        Mini,
        /** No video page: the page's own history, else leave (never on the car). */
        PageBack,
    }

    fun back(
        equalizerOpen: Boolean,
        fullscreen: Boolean,
        landscapeFill: Boolean,
        onSettingsTab: Boolean,
        onWatchTab: Boolean,
        fullPlayer: Boolean,
        onWatchPage: Boolean,
    ): Back = when {
        equalizerOpen -> Back.CloseEqualizer
        fullscreen -> Back.ExitFullscreen
        landscapeFill -> Back.ShowWatchPage
        onSettingsTab -> Back.ToWatchTab
        !onWatchTab -> Back.ToSettingsTab
        !fullPlayer -> Back.BrowseBack
        onWatchPage -> Back.Mini
        else -> Back.PageBack
    }
}
