package com.aayoutube.app

import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService

/**
 * AABrowser's classic projection entry point (browser/CarService.kt).
 * This is distinct from the AndroidX Car App navigation-template fallback.
 *
 * Android Auto owns CarActivity creation; do not launch it as an Android Activity.
 */
class LegacyProjectionService : CarActivityService() {
    override fun getCarActivity(): Class<out CarActivity?> = LegacyProjectionActivity::class.java
}
