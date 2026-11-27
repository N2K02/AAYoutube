package com.googlevideo.sabr.datasource

import java.io.IOException

/** Status only: never include signed URLs, request bodies or server response contents. */
internal class SabrHttpException(val status: Int) : IOException("SABR HTTP $status")
