package com.dompka.dashlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The colour maths and the corner mirroring behind the glass look; everything here runs without a device. */
class DesignTokensTest {

    @Test
    fun alphaFactorScalesTheColoursOwnAlphaAndKeepsItsRgb() {
        assertEquals(0x80112233.toInt(), withAlpha(0xFF112233.toInt(), 0.5f))
        assertEquals(0x00112233, withAlpha(0xFF112233.toInt(), 0f))
        // A colour that is already translucent is scaled, not replaced.
        assertEquals(0x40, withAlpha(0x80112233.toInt(), 0.5f) ushr 24)
    }

    @Test
    fun alphaFactorOutsideZeroToOneIsClamped() {
        assertEquals(0xFF, withAlpha(0xFF000000.toInt(), 3f) ushr 24)
        assertEquals(0, withAlpha(0xFF000000.toInt(), -1f) ushr 24)
    }

    @Test
    fun blendEndsAreTheInputsAndTheMiddleIsTheMean() {
        assertEquals(0xFF102030.toInt(), blend(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), blend(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 1f))
        assertEquals(0xFF808080.toInt(), blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
    }

    @Test
    fun darkFieldIsTheNeutralMixedThirtyFivePercentTowardBlack() {
        // 0x40 * 0.65 = 41.6, rounded to 0x2A.
        assertEquals(0xFF2A2A2A.toInt(), blend(0xFF404040.toInt(), 0xFF000000.toInt(), 0.35f))
    }

    @Test
    fun contrastAndBatterySaverPickTheGlassBoost() {
        assertEquals(1.0f, Tokens.glassBoostFor(0f, false), 0f)
        assertEquals(1.15f, Tokens.glassBoostFor(0.5f, false), 0f)
        assertEquals(1.3f, Tokens.glassBoostFor(1f, false), 0f)
        assertEquals(1.2f, Tokens.glassBoostFor(0f, true), 0f)
        // Battery saver never lowers a boost that high contrast already gave.
        assertEquals(1.3f, Tokens.glassBoostFor(1f, true), 0f)
    }

    @Test
    fun cornersAreMirroredForRightToLeftSoTheTightCornerStaysAtTheBottomStart() {
        val ltr = Corners.CARD.radii(1f, rtl = false)
        val rtl = Corners.CARD.radii(1f, rtl = true)
        // Order of the array: TL, TR, BR, BL with x and y each.
        assertArrayEquals(floatArrayOf(28f, 28f, 28f, 28f, 28f, 28f, 8f, 8f), ltr, 0f)
        assertArrayEquals(floatArrayOf(28f, 28f, 28f, 28f, 8f, 8f, 28f, 28f), rtl, 0f)
    }

    @Test
    fun cornerRadiiAreScaledByTheScreenDensity() {
        assertArrayEquals(floatArrayOf(56f, 56f, 56f, 56f, 56f, 56f, 16f, 16f), Corners.CARD.radii(2f, false), 0f)
    }

    @Test
    fun fallbackPalettesHaveReadableTextOnTheirGlass() {
        for (dark in listOf(false, true)) {
            val p = Tokens.fallbackPalette(dark)
            val glass = blend(p.fieldBase, p.glassBase, if (dark) 0.64f else 0.78f)
            assertTrue("body text on glass, dark=$dark", contrast(p.text2, glass) >= 4.5)
            assertTrue("button text, dark=$dark", contrast(p.onAccent, p.accent) >= 4.5)
        }
    }

    /** WCAG contrast ratio of two opaque colours. */
    private fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun ch(shift: Int): Double {
                val v = ((c ushr shift) and 0xFF) / 255.0
                return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
        }
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
