package com.aayoutube.app

import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Floating controls move along their edge only, and remember where they were left. */
class ControlsEdgeDragTest {
    private fun src(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun centreDefaultMatchesPreviousLayout() {
        assertEquals(Alignment.CenterStart, ControlsSidePolicy.alignment(true, false))
        assertEquals(Alignment.CenterEnd, ControlsSidePolicy.alignment(false, false))
        assertEquals(Alignment.TopCenter, ControlsSidePolicy.alignment(true, true))
        assertEquals(0.5f, FeatureFlags().controlsPosPortrait)
        assertEquals(0.5f, FeatureFlags().controlsPosLandscape)
    }

    @Test fun portraitMovesUpDownOnTheChosenSideOnly() {
        assertEquals(BiasAlignment(1f, -1f), ControlsSidePolicy.alignment(false, false, 0f))
        assertEquals(BiasAlignment(1f, 1f), ControlsSidePolicy.alignment(false, false, 1f))
        assertEquals(BiasAlignment(-1f, -1f), ControlsSidePolicy.alignment(true, false, 0f))
    }

    @Test fun landscapeMovesAlongTheTopOnly() {
        assertEquals(BiasAlignment(-1f, -1f), ControlsSidePolicy.alignment(false, true, 0f))
        assertEquals(BiasAlignment(1f, -1f), ControlsSidePolicy.alignment(true, true, 1f))
    }

    @Test fun dragIsClampedToTheEdge() {
        assertEquals(0.75f, ControlsSidePolicy.drag(0.5f, 250f, 1000f), 1e-6f)
        assertEquals(1f, ControlsSidePolicy.drag(0.9f, 5000f, 1000f))
        assertEquals(0f, ControlsSidePolicy.drag(0.1f, -5000f, 1000f))
        assertEquals("no free travel: stays put", 0.3f, ControlsSidePolicy.drag(0.3f, 100f, 0f))
        assertEquals(0.5f, ControlsSidePolicy.normalize(Float.NaN))
    }

    @Test fun positionSavedPerOrientation() {
        val f = FeatureFlags()
        assertEquals(0.2f, ControlsSidePolicy.save(f, false, 0.2f).controlsPosPortrait)
        assertEquals(0.5f, ControlsSidePolicy.save(f, false, 0.2f).controlsPosLandscape)
        assertEquals(0.9f, ControlsSidePolicy.save(f, true, 0.9f).controlsPosLandscape)
        val feat = src("app/src/main/java/com/aayoutube/app/Features.kt")
        assertTrue(feat.contains(".putFloat(\"controlsPosPortrait\", n.controlsPosPortrait)"))
        assertTrue(feat.contains(".putFloat(\"controlsPosLandscape\", n.controlsPosLandscape)"))
    }

    @Test fun phoneAndCarUseTheDrag() {
        val gesture = src("app/src/main/java/com/aayoutube/app/ControlsEdgeDrag.kt")
        assertTrue(gesture.contains("detectDragGestures(onDragEnd = { commit() }, onDragCancel = { commit() })"))
        assertTrue(gesture.contains("if (landscape) d.x else d.y"))
        for (f in listOf("Screens.kt", "CarVideoPresentation.kt"))
            assertTrue(f, src("app/src/main/java/com/aayoutube/app/$f").contains(".then(railDrag).zIndex(20f)"))
    }
}
