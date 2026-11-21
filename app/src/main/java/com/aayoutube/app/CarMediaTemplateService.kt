package com.aayoutube.app

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator

/**
 * Modern Car App Library MEDIA entry, separate from the legacy video projection.
 * Uses the already-existing PlaybackService as the only MediaBrowser/media
 * session owner. Never tries to move the WebView to a templated car surface.
 */
class CarMediaTemplateService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = MediaInfoScreen(carContext)
    }

    private class MediaInfoScreen(context: CarContext) : Screen(context) {
        override fun onGetTemplate(): Template =
            MessageTemplate.Builder(
                "Use AAYT Music for YouTube playback, playlists and media controls. " +
                    "Video projection is a separate parked-only Android Auto entry."
            ).setTitle("AAYT Music")
                .setHeaderAction(Action.APP_ICON)
                .build()
    }
}
