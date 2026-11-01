package com.aayoutube.app

/** Only an explicit YouTube LOGGED_IN flag may reveal the Sign out action. */
enum class YouTubeAccountState {
    CHECKING,
    SIGNED_IN,
    SIGNED_OUT;

    companion object {
        fun fromScript(value: String): YouTubeAccountState? = when (value) {
            "signed_in" -> SIGNED_IN
            "signed_out" -> SIGNED_OUT
            else -> null
        }
    }
}
