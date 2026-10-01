package com.whispercppdemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.fonts.SystemFonts
import android.graphics.Typeface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercppdemo.ui.theme.InterFamily
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bundled Inter really renders on the phone, and Hindi inside Inter text
 * still renders through the system Noto Sans Devanagari fallback (no tofu).
 */
@RunWith(AndroidJUnit4::class)
class InterFontDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val hindi = "नमस्ते, कल सुबह दस बजे मिलते हैं।"

    private fun paint(typeface: Typeface) = Paint().apply { this.typeface = typeface; textSize = 48f }

    private fun draw(typeface: Typeface, text: String): Bitmap {
        val bmp = Bitmap.createBitmap(900, 120, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, 10f, 90f, paint(typeface).apply { color = android.graphics.Color.WHITE; isAntiAlias = true })
        return bmp
    }

    @Test
    fun interLoadsFromTheApk_inAllFourWeights() {
        val inter = ResourcesCompat.getFont(context, R.font.inter_regular)!!
        val latin = "Turn audio into text · Record audio"
        // Compared with Roboto, not Typeface.DEFAULT: some phones (the Nothing
        // A059 among them) already use an older Inter as their system sans.
        assertNotEquals("Inter must be in use, not Roboto",
                        paint(Typeface.create("roboto", Typeface.NORMAL)).measureText(latin), paint(inter).measureText(latin))
        listOf(R.font.inter_medium, R.font.inter_semibold, R.font.inter_bold)
            .forEach { assertTrue(ResourcesCompat.getFont(context, it) != null) }
    }

    @Test
    fun devanagariFallsBackToTheSystemNotoFont_notTofu() {
        org.junit.Assume.assumeTrue("SystemFonts needs API 29", android.os.Build.VERSION.SDK_INT >= 29)
        assertTrue("the phone has Noto Sans Devanagari",
                   SystemFonts.getAvailableFonts().any { it.file?.name?.contains("NotoSansDevanagari") == true })
        val inter = ResourcesCompat.getFont(context, R.font.inter_regular)!!
        val word = "नमस्ते" // a conjunct (स्ते) exercises shaping, not just glyph lookup
        val throughInter = draw(inter, word)
        // Same pixels as the platform rendering: the Hindi run is shaped and
        // drawn by the system Noto Sans Devanagari, exactly as before Inter.
        assertTrue("Hindi drawn through Inter differs from the platform rendering",
                   throughInter.sameAs(draw(Typeface.DEFAULT, word)))
        var lit = 0
        for (x in 0 until throughInter.width) for (y in 0 until throughInter.height)
            if (android.graphics.Color.alpha(throughInter.getPixel(x, y)) > 128) lit++
        assertTrue("glyphs drawn ($lit px)", lit > 500)
    }

    @Test
    fun themeTypographyIsInter_andMixedHindiTextDrawsInsideItsLineHeight() {
        var family: Any? = null
        var layout: TextLayoutResult? = null
        compose.setContent {
            WhisperCppDemoTheme {
                family = MaterialTheme.typography.bodyLarge.fontFamily
                Column(Modifier.width(360.dp).testTag("hindi")) {
                    Text("Meeting नोट्स · 10:42 · $hindi", style = MaterialTheme.typography.bodyLarge,
                         color = Color.White, onTextLayout = { layout = it })
                }
            }
        }
        compose.waitForIdle()
        assertEquals(InterFamily, family)
        val l = layout!!
        assertTrue("text wraps normally", l.lineCount in 2..4 && !l.hasVisualOverflow)
        val pixels = compose.onNodeWithTag("hindi").captureToImage().toPixelMap()
        var lit = 0
        for (x in 0 until pixels.width step 2) for (y in 0 until pixels.height step 2) if (pixels[x, y].red > 0.5f) lit++
        assertTrue("glyphs were drawn ($lit lit samples)", lit > 200)
    }
}
