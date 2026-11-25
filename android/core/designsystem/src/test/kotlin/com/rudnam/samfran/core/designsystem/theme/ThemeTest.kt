package com.rudnam.samfran.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The colours are the website's (the tokens at the top of web/src/styles.css),
 * copied by hand: a colour changed there is changed here, or the app and the
 * website drift apart.
 */
class ThemeTest {
    private val css = File("../../../web/src/styles.css").readText()

    /** The `--name: #rrggbb` tokens in the first block that starts with [selector]. */
    private fun tokens(selector: String): Map<String, String> {
        val start = css.indexOf("$selector {")
        check(start >= 0) { "no $selector block in styles.css" }
        val block = css.substring(start).substringBefore("\n}")
        return Regex("""--([a-z-]+):\s*(#[0-9a-fA-F]{6})\b""").findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2].lowercase() }
    }

    private fun hex(c: Color) = "#%06x".format(c.toArgb() and 0xFFFFFF)

    private fun SamFranColors.asTokens(): Map<String, String> {
        val m = linkedMapOf(
            "bg" to bg, "surface" to surface, "sunken" to sunken, "highlight" to highlight,
            "text" to text, "muted" to muted, "border" to border, "divider" to divider,
            "accent" to accent, "accent-text" to accentText, "accent-ink" to accentInk,
            "heart" to heart, "heart-text" to heartText, "star" to star, "star-off" to starOff, "danger" to danger,
        )
        for ((p, c) in listOf("a" to a, "b" to b)) {
            m["$p-bg"] = c.bg; m["$p-text"] = c.text; m["$p-solid"] = c.solid; m["$p-on"] = c.on
        }
        for ((name, t) in listOf(
            "yellow" to tileYellow, "green" to tileGreen, "red" to tileRed,
            "orange" to tileOrange, "blue" to tileBlue, "purple" to tilePurple,
        )) {
            m["tile-$name"] = t.bg; m["tile-$name-ink"] = t.ink
        }
        for ((name, t) in listOf(
            "early" to calEarly, "middle" to calMiddle, "late" to calLate,
            "night" to calNight, "training" to calTraining, "leave" to calLeave,
        )) {
            m["cal-$name"] = t.bg; m["cal-$name-ink"] = t.ink
        }
        return m.mapValues { hex(it.value) }
    }

    @Test
    fun `light colours are the website's`() {
        val app = LightSamFranColors.asTokens()
        assertEquals(tokens(":root").filterKeys { it in app }, app)
    }

    @Test
    fun `shift dots are the website's`() {
        val app = mapOf(
            "shift-early" to ShiftDotColors.early, "shift-middle" to ShiftDotColors.middle, "shift-late" to ShiftDotColors.late,
            "shift-night" to ShiftDotColors.night, "shift-off" to ShiftDotColors.off, "shift-leave" to ShiftDotColors.leave,
            "shift-training" to ShiftDotColors.training,
        ).mapValues { hex(it.value) }
        assertEquals(tokens(":root").filterKeys { it.startsWith("shift-") }, app)
    }

    @Test
    fun `dark colours are the website's`() {
        val app = DarkSamFranColors.asTokens()
        // Dark only lists what changes; the rest (the star) stays as in light.
        val website = tokens(":root").filterKeys { it in app } + tokens(""":root[data-theme="dark"]""").filterKeys { it in app }
        assertEquals(website, app)
    }
}
