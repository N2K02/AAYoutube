package com.aayoutube.app

/**
 * Which page reports to drop after a watch navigation. Only a late report from
 * the video that was navigated away from is stale. YouTube's address bar can lag
 * behind its player (next Mix/autoplay video), so dropping every ID that differs
 * from the URL froze the play/pause state shown by the app.
 */
internal object PlaybackReportPolicy {
    fun stale(reportId: String, expectedId: String?, navigatedAwayFromId: String?): Boolean =
        expectedId != null && reportId != expectedId && reportId == navigatedAwayFromId
}
