package com.scratchy.app

import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    private fun kind(line: String) = Markdown.listItem(line)?.kind

    @Test fun recognises_list_lines() {
        assertEquals(Markdown.Kind.TASK, kind("- [ ] milk"))
        assertEquals(Markdown.Kind.TASK, kind("  * [x] done"))
        assertEquals(Markdown.Kind.BULLET, kind("- milk"))
        assertEquals(Markdown.Kind.BULLET, kind("+ milk"))
        assertEquals(Markdown.Kind.NUMBER, kind("12. twelve"))
        assertEquals(Markdown.Kind.NUMBER, kind("3) three"))
    }

    @Test fun not_lists() {
        assertNull(kind("-5 degrees"))
        assertNull(kind("**bold** start"))
        assertNull(kind("*italic* start"))
        assertNull(kind("2024.5 was a year"))
        assertNull(kind("-"))
        assertNull(kind("plain text"))
    }

    @Test fun prefix_length_covers_the_marker() {
        assertEquals(6, Markdown.listItem("- [ ] x")!!.length)
        assertEquals(2, Markdown.listItem("- x")!!.length)
        assertEquals(5, Markdown.listItem("  1. x")!!.length)
    }

    @Test fun next_item_continues_the_list() {
        assertEquals("- [ ] ", Markdown.listItem("- [x] done")!!.next())
        assertEquals("  - ", Markdown.listItem("  - nested")!!.next())
        assertEquals("* ", Markdown.listItem("* a")!!.next())
        assertEquals("4. ", Markdown.listItem("3. c")!!.next())
    }

    @Test fun toggling_a_checkbox() {
        assertEquals("- [x] milk", Markdown.toggled("- [ ] milk"))
        assertEquals("- [ ] milk", Markdown.toggled("- [x] milk"))
        assertEquals("  * [ ] a", Markdown.toggled("  * [X] a"))
        assertEquals("plain", Markdown.toggled("plain"))
        assertEquals(3, Markdown.checkIndex("- [ ] a"))
    }

    @Test fun checklist_button_cycles_a_line() {
        assertEquals("- [ ] milk", Markdown.toggleTaskLine("milk"))
        assertEquals("  - [ ] milk", Markdown.toggleTaskLine("  milk"))
        assertEquals("milk", Markdown.toggleTaskLine("- [ ] milk"))
        assertEquals("- [ ] milk", Markdown.toggleTaskLine("- milk"))
        assertEquals("- [ ] first", Markdown.toggleTaskLine("1. first"))
    }

    @Test fun old_checkbox_notes_become_markdown() {
        assertEquals("a\n- [ ] one\n- [x] two\nb", Markdown.legacy("a\n☐ one\n☑ two\nb"))
        assertEquals("- [ ] one", Markdown.legacy("- [ ] one"))
        assertEquals("x ☐ y", Markdown.legacy("x ☐ y"))
        assertEquals(Markdown.legacy("☐ a"), Markdown.legacy(Markdown.legacy("☐ a")))
    }

    @Test fun plain_text_for_headings_and_pickers() {
        assertEquals("Weekly plan", Markdown.plain("## Weekly plan"))
        assertEquals("Buy milk", Markdown.plain("- [ ] Buy milk"))
        assertEquals("Read the docs now", Markdown.plain("1. Read the **docs** now"))
        assertEquals("see site", Markdown.plain("see [site](https://x.dev)"))
        assertEquals("run make", Markdown.plain("run `make`"))
        assertEquals("quoted", Markdown.plain("> quoted"))
    }

    @Test fun note_heading_skips_the_markdown_symbols() {
        val n = Note("1", "", "\n# My big idea\nmore text", 1, 1)
        assertEquals("My big idea", n.heading)
        assertEquals("Titled", Note("1", "Titled", "# other", 1, 1).heading)
    }

    @Test fun words_ignore_markdown_but_not_wording() {
        val plain = "Shopping list\nmilk, eggs\nCall Sam at 5"
        val rich = "# Shopping list\n\n- [ ] milk\n- [ ] **eggs**\n1. Call [Sam](https://x.dev) at 5"
        assertEquals(Markdown.words(plain), Markdown.words(rich))
        assertNotEquals(Markdown.words(plain), Markdown.words(rich.replace("eggs", "egg")))
        assertNotEquals(Markdown.words(plain), Markdown.words("$rich\nExtra line"))
    }

    @Test fun a_table_keeps_the_same_words() {
        val plain = "Name Age\nBob 4"
        assertEquals(Markdown.words(plain), Markdown.words("| Name | Age |\n| --- | --- |\n| Bob | 4 |"))
    }

    @Test fun arabic_prefixes_may_be_spaced_differently_but_letters_must_match() {
        val a = "اخر فترة وشتغلنا على مشاريع IoT والاتصالات"
        assertEquals(Markdown.letters(a), Markdown.letters("# اخر فترة\n- و شتغلنا على مشاريع **IoT** والاتصالات"))
        assertNull(Markdown.firstDifference(a, "اخر فترة وشتغلنا على مشاريع IoT والاتصالات"))
        assertNotNull(Markdown.firstDifference(a, "اخر فترة وشتغلنا على مشاريع IoT والاتصالات."+"ا"))
    }

    @Test fun diacritics_count_as_changes() {
        assertNotNull(Markdown.firstDifference("كتب", "كَتَبَ"))
    }

    @Test fun first_difference_shows_where() {
        val d = Markdown.firstDifference("one two three", "one two tree")!!
        assertTrue(d.first.contains("three") || d.first.contains("thr"))
    }
}
