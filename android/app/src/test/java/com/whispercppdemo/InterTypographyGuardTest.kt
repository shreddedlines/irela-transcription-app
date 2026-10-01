package com.whispercppdemo

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.whispercppdemo.ui.theme.appTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Concept A typography: Inter is bundled in the APK (never downloaded), it is
 * the family of every style, the type scale is unchanged, and scripts Inter
 * lacks are left to the system (Noto) fallback rather than to a stand-in.
 */
class InterTypographyGuardTest {

    private val fontDir = File("src/main/res/font")
    private val weights = mapOf(
        "inter_regular.ttf" to "Inter Regular",
        "inter_medium.ttf" to "Inter Medium",
        "inter_semibold.ttf" to "Inter SemiBold",
        "inter_bold.ttf" to "Inter Bold"
    )

    /** Minimal TrueType reader: the name table and the Unicode cmap. */
    private class Ttf(file: File) {
        private val b = java.nio.ByteBuffer.wrap(file.readBytes())
        private fun u16(at: Int) = b.getShort(at).toInt() and 0xFFFF
        private fun u32(at: Int) = b.getInt(at).toLong() and 0xFFFFFFFFL
        private fun table(tag: String): Int = (0 until u16(4)).map { 12 + it * 16 }
            .first { String(ByteArray(4) { i -> b.get(it + i) }, Charsets.US_ASCII) == tag }
            .let { u32(it + 8).toInt() }

        fun name(id: Int): String {
            val t = table("name"); val strings = t + u16(t + 4)
            val rec = (0 until u16(t + 2)).map { t + 6 + it * 12 }
                .first { u16(it) == 3 && u16(it + 6) == id }
            val bytes = ByteArray(u16(rec + 8)) { i -> b.get(strings + u16(rec + 10) + i) }
            return String(bytes, Charsets.UTF_16BE)
        }

        private val codepoints: Set<Int> by lazy {
            val t = table("cmap")
            val sub = (0 until u16(t + 2)).map { t + 4 + it * 8 }
                .first { u16(it) == 3 && u16(it + 2) == 10 }.let { t + u32(it + 4).toInt() }
            check(u16(sub) == 12)
            val out = HashSet<Int>()
            for (g in 0 until u32(sub + 12).toInt()) {
                val at = sub + 16 + g * 12
                for (c in u32(at) .. u32(at + 4)) out += c.toInt()
            }
            out
        }
        fun has(text: String) = text.codePoints().allMatch { it in codepoints }
        fun hasAny(text: String) = text.codePoints().anyMatch { it in codepoints }
    }

    private fun font(name: String) = Ttf(File(fontDir, name))

    @Test
    fun fourStaticInterWeightsAreBundled() {
        assertEquals("only the four Inter files live in res/font", weights.keys, fontDir.list()!!.toSet())
        weights.forEach { (file, fullName) ->
            val font = font(file)
            assertTrue(file, font.name(1).startsWith("Inter"))
            assertEquals(file, fullName, font.name(4))
        }
    }

    @Test
    fun interCoversTheAppsLatinText_andLeavesDevanagariToTheSystemFallback() {
        val regular = font("inter_regular.ttf")
        listOf("Recording · 5:56 pm", "Couldn’t use this audio…", "₹ 1,299", "Part 2 of 3", "60:00")
            .forEach { assertTrue("Inter lacks a glyph in \"$it\"", regular.has(it)) }
        // Inter has no Devanagari: Hindi must come from the device's Noto Sans
        // Devanagari via the platform fallback (verified on device).
        assertFalse(regular.hasAny("नमस्ते"))
    }

    @Test
    fun noRuntimeFontDownloadAnywhere() {
        val gradle = File("build.gradle").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val type = File("src/main/java/com/whispercppdemo/ui/theme/Type.kt").readText()
        assertFalse(gradle.contains("google-fonts") || gradle.contains("googlefonts"))
        assertFalse(manifest.contains("preloaded_fonts"))
        assertFalse(type.contains("GoogleFont") || type.contains("FontProvider") || type.contains("getIdentifier"))
        assertTrue("fonts referenced at compile time so shrinking keeps them", type.contains("R.font.inter_semibold"))
        assertTrue(File("src/main/java/com/whispercppdemo/ui/theme/Theme.kt").readText().contains("appTypography(InterFamily)"))
        assertTrue(File("src/main/assets/licenses/Inter-OFL.txt").readText().contains("SIL Open Font License"))
    }

    @Test
    fun everyStyleUsesTheGivenFamily_andTheScaleIsUnchanged() {
        val family = FontFamily.Monospace // any marker family
        val t = appTypography(family)
        val all = listOf(t.displayLarge, t.displayMedium, t.displaySmall, t.headlineLarge, t.headlineMedium,
                         t.headlineSmall, t.titleLarge, t.titleMedium, t.titleSmall, t.bodyLarge, t.bodyMedium,
                         t.bodySmall, t.labelLarge, t.labelMedium, t.labelSmall)
        all.forEach { assertEquals(family, it.fontFamily) }
        assertEquals(listOf(32, 24, 20, 18, 16, 14, 14, 12).map { it.sp },
                     listOf(t.displayLarge, t.headlineLarge, t.titleLarge, t.bodyLarge, t.bodyMedium,
                            t.bodySmall, t.labelLarge, t.labelSmall).map { it.fontSize })
        assertEquals(listOf(40, 32, 28, 30, 24, 20, 20, 16).map { it.sp },
                     listOf(t.displayLarge, t.headlineLarge, t.titleLarge, t.bodyLarge, t.bodyMedium,
                            t.bodySmall, t.labelLarge, t.labelSmall).map { it.lineHeight })
    }

    @Test
    fun uiTextNeverBypassesTheThemeFamily() {
        // A bare TextStyle(...) without a family silently renders in the
        // platform font. The only intended exception is the serif "T" glyph
        // drawn inside the Home mark (Georgia in the prototype).
        File("src/main/java/com/whispercppdemo/ui").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            Regex("""\bTextStyle\(([^)]*)\)""").findAll(f.readText()).forEach { m ->
                assertTrue("${f.name}: ${m.value}", m.groupValues[1].contains("fontFamily"))
            }
        }
    }
}
