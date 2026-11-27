package com.aayoutube.app

/** Optional process-wide component: reuse a successful instance and retry failures. */
internal object UpdaterInitialization {
    fun <T : Any> ensure(
        existing: T?,
        create: () -> T,
        onFailure: (Exception) -> Unit,
    ): T? {
        if (existing != null) return existing
        return try {
            create()
        } catch (error: Exception) {
            onFailure(error)
            null
        }
    }
}
