package com.aayoutube.app

import kotlin.math.*

/** Settings-time response scan; never called per sample. Includes overlapping bands + bass. */
internal object MusicHeadroom {
    fun gain(bands: List<Float>, bassDb: Double, rate: Int = 48000): Float {
        val centres = intArrayOf(60, 230, 910, 3600, 14000)
        val filters = centres.toList().mapIndexedNotNull { i, hz ->
            val db = bands.getOrElse(i) { 0f }.toDouble()
            if (abs(db) < 0.1 || hz >= rate * 0.45) null else coefficients(hz.toDouble(), db, false, rate)
        }.toMutableList()
        if (bassDb >= 0.1) filters += coefficients(120.0, bassDb, true, rate)
        if (filters.isEmpty()) return 1f
        var peak = 0.0
        // Include DC, Nyquist and exact band centres as well as the log sweep.
        val frequencies = (0..512).map { 10.0 * (rate * 0.049).pow(it / 512.0) } + centres.map(Int::toDouble) + listOf(0.0, rate * 0.5)
        for (hz in frequencies) {
            val w = 2 * PI * hz / rate
            val c = cos(w); val s = sin(w); val c2 = cos(2*w); val s2 = sin(2*w)
            var db = 0.0
            for (f in filters) {
                val nr = f[0] + f[1]*c + f[2]*c2; val ni = -f[1]*s-f[2]*s2
                val dr = 1 + f[3]*c + f[4]*c2; val di = -f[3]*s-f[4]*s2
                db += 10 * log10((nr*nr+ni*ni)/(dr*dr+di*di))
            }
            peak = max(peak, db)
        }
        // Small reserve for peaks between scan points. Cuts never turn into a boost.
        return if (peak < 0.01) 1f else 10.0.pow(-(peak + 0.5)/20).toFloat()
    }

    private fun coefficients(hz: Double, db: Double, shelf: Boolean, rate: Int): DoubleArray {
        val a = 10.0.pow(db/40); val w = 2*PI*hz/rate; val c = cos(w); val s = sin(w)
        val raw = if (!shelf) {
            val alpha = s/(2*0.9)
            doubleArrayOf(1+alpha*a,-2*c,1-alpha*a,1+alpha/a,-2*c,1-alpha/a)
        } else {
            val beta = sqrt(2*a)*s
            doubleArrayOf(a*((a+1)-(a-1)*c+beta),2*a*((a-1)-(a+1)*c),a*((a+1)-(a-1)*c-beta),
                (a+1)+(a-1)*c+beta,-2*((a-1)+(a+1)*c),(a+1)+(a-1)*c-beta)
        }
        return doubleArrayOf(raw[0]/raw[3],raw[1]/raw[3],raw[2]/raw[3],raw[4]/raw[3],raw[5]/raw[3])
    }
}
