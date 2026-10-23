package com.scratchy.app

import org.junit.Assert.*
import org.junit.Test

/** Every fixed theme must be readable in light and dark mode. */
class ThemeTest {
    private fun forAll(check: (name: String, dark: Boolean, c: Colors) -> Unit) {
        for (name in Themes.names.filter { it != "wallpaper" })
            for (dark in listOf(false, true)) check(name, dark, Themes.fixed(name, dark))
    }

    @Test fun body_text_is_readable() = forAll { n, d, c ->
        assertTrue("$n dark=$d text/background", Contrast.ratio(c.text, c.background) >= 7.0)
        assertTrue("$n dark=$d text/surface", Contrast.ratio(c.text, c.surface) >= 7.0)
        assertTrue("$n dark=$d text/floating", Contrast.ratio(c.text, c.floating) >= 7.0)
    }

    @Test fun secondary_text_is_readable() = forAll { n, d, c ->
        assertTrue("$n dark=$d secondary/surface", Contrast.ratio(c.secondary, c.surface) >= 4.5)
        assertTrue("$n dark=$d secondary/background", Contrast.ratio(c.secondary, c.background) >= 4.5)
    }

    @Test fun accent_is_readable_as_text_and_under_button_labels() = forAll { n, d, c ->
        assertTrue("$n dark=$d accent/surface", Contrast.ratio(c.accent, c.surface) >= 4.5)
        assertTrue("$n dark=$d accent/background", Contrast.ratio(c.accent, c.background) >= 4.5)
        assertTrue("$n dark=$d onAccent/accent", Contrast.ratio(c.onAccent, c.accent) >= 4.5)
    }

    @Test fun inverse_accent_is_readable_on_the_undo_bar() = forAll { n, d, c ->
        // The undo bar is drawn with the text color as its background.
        assertTrue("$n dark=$d accentInverse/text", Contrast.ratio(c.accentInverse, c.text) >= 3.0)
    }

    @Test fun cards_are_distinguishable_from_the_background() = forAll { n, d, c ->
        assertTrue("$n dark=$d surface vs background", Contrast.ratio(c.surface, c.background) >= 1.05)
    }

    @Test fun onColor_picks_the_better_of_white_and_black() {
        assertEquals(0xFFFFFFFF.toInt(), Contrast.onColor(0xFF1A5FD0.toInt()))
        assertEquals(0xFF101114.toInt(), Contrast.onColor(0xFFFFAB40.toInt()))
    }
}
