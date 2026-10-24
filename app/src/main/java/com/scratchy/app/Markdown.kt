package com.scratchy.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/** Marker spans so the markdown styling can be found and replaced on every edit. */
interface Md
class MdBold : StyleSpan(Typeface.BOLD), Md
class MdItalic : StyleSpan(Typeface.ITALIC), Md
class MdMono : TypefaceSpan("monospace"), Md
class MdSize(f: Float) : RelativeSizeSpan(f), Md
class MdDim(c: Int) : ForegroundColorSpan(c), Md
class MdLink(c: Int) : ForegroundColorSpan(c), Md
class MdUnderline : UnderlineSpan(), Md
class MdStrike : StrikethroughSpan(), Md
class MdCodeBg(c: Int) : BackgroundColorSpan(c), Md

/**
 * Notes are markdown. The text stored (and synced, and copied out) is exactly what was typed or pasted.
 * [render] draws it for reading (grid and previews); [RichText] is the editor. The list and plain-text helpers here are pure Kotlin.
 */
object Markdown {
    // ---- lists ----

    enum class Kind { TASK, BULLET, NUMBER }

    /** The start of a list line: indent, the marker, and how many characters the whole prefix takes. */
    class Item(val indent: String, val kind: Kind, val marker: String, val checked: Boolean, val number: Int, val length: Int) {
        /** What the next line of the list starts with. */
        fun next() = indent + when (kind) {
            Kind.TASK -> "$marker [ ] "
            Kind.BULLET -> "$marker "
            Kind.NUMBER -> "${number + 1}. "
        }
    }

    private val item = Regex("^([ \\t]*)(?:([-*+])\\s+\\[([ xX])]\\s|([-*+])\\s|(\\d{1,3})[.)]\\s)")

    fun listItem(line: String): Item? {
        val m = item.find(line) ?: return null
        val indent = m.groupValues[1]
        return when {
            m.groups[2] != null -> Item(indent, Kind.TASK, m.groupValues[2], m.groupValues[3] != " ", 0, m.value.length)
            m.groups[4] != null -> Item(indent, Kind.BULLET, m.groupValues[4], false, 0, m.value.length)
            else -> Item(indent, Kind.NUMBER, "", false, m.groupValues[5].toInt(), m.value.length)
        }
    }

    /** Index of the character inside "[ ]" of a task line, or -1. */
    fun checkIndex(line: String): Int {
        val it = listItem(line) ?: return -1
        if (it.kind != Kind.TASK) return -1
        return line.indexOf('[', it.indent.length) + 1
    }

    /** The line with its checkbox flipped (non-task lines come back unchanged). */
    fun toggled(line: String): String {
        val i = checkIndex(line)
        if (i < 0) return line
        return line.substring(0, i) + (if (line[i] == ' ') "x" else " ") + line.substring(i + 1)
    }

    /** The checklist button: plain or bullet line becomes a task, a task becomes a plain line. */
    fun toggleTaskLine(line: String): String {
        val it = listItem(line)
        return when {
            it == null -> { val ind = line.takeWhile { c -> c == ' ' || c == '\t' }; ind + "- [ ] " + line.substring(ind.length) }
            it.kind == Kind.TASK -> it.indent + line.substring(it.length)
            else -> it.indent + "- [ ] " + line.substring(it.length)
        }
    }

    /** Notes saved before markdown used ☐ / ☑ at the start of a line. Idempotent. */
    fun legacy(body: String): String {
        if ('☐' !in body && '☑' !in body) return body
        return body.split("\n").joinToString("\n") { l ->
            when {
                l.startsWith("☐ ") || l == "☐" -> "- [ ] " + l.drop(2)
                l.startsWith("☑ ") || l == "☑" -> "- [x] " + l.drop(2)
                else -> l
            }
        }
    }

    // ---- comparing texts ignoring the markdown on them ----

    private val wordRe = Regex("[\\p{L}\\p{M}\\p{N}]+")

    /** Just the words of a text, with all markdown symbols, list markers and link addresses left out. */
    fun words(md: String): List<String> {
        var t = md.replace(Regex("]\\([^)\\s]*\\)"), "]")
        t = t.replace(Regex("(?m)^\\s*[-*+]\\s+\\[[ xX]]\\s"), "")
        t = t.replace(Regex("(?m)^\\s*\\d{1,3}[.)]\\s"), "")
        return wordRe.findAll(t).map { it.value }.toList()
    }

    /** Every letter, mark and digit of a text joined together: equal for two texts that differ only in spacing or markdown. */
    fun letters(md: String) = words(md).joinToString("")

    /** Where two texts first differ (as short snippets), or null when their letters are the same. */
    fun firstDifference(original: String, other: String): Pair<String, String>? {
        val a = letters(original); val b = letters(other)
        if (a == b) return null
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        fun snip(s: String) = (if (i > 6) "…" else "") + s.substring((i - 6).coerceAtLeast(0), (i + 14).coerceAtMost(s.length)).ifEmpty { "(nothing)" }
        return snip(a) to snip(b)
    }

    // ---- plain text of one line (note headings, pickers) ----

    internal val heading = Regex("^(#{1,6})(\\s+)(.*)$")
    internal val boldItalic = Regex("\\*\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*\\*")
    internal val bold = Regex("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*")
    internal val italic = Regex("(?<![*\\w])\\*(?=[^\\s*])([^*\\n]+?)(?<=[^\\s*])\\*(?![*\\w])")
    internal val strike = Regex("~~(?=\\S)(.+?)(?<=\\S)~~")
    internal val code = Regex("`([^`\\n]+)`")
    internal val link = Regex("\\[([^\\]\\n]+)]\\(([^)\\s]+)\\)")
    internal val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    internal val tableRow = Regex("^\\s*\\|.*\\|\\s*$")
    internal val tableSep = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$")

    fun plain(line: String): String {
        var t = line.trim()
        heading.find(t)?.let { t = it.groupValues[3] }
        listItem(t)?.let { t = t.substring(it.length) }
        t = t.replace(Regex("^>\\s?"), "")
        t = link.replace(t, "$1"); t = boldItalic.replace(t, "$1"); t = bold.replace(t, "$1"); t = strike.replace(t, "$1")
        t = code.replace(t, "$1"); t = italic.replace(t, "$1")
        return t.trim()
    }

    private val levelSize = floatArrayOf(1.4f, 1.25f, 1.12f, 1.05f, 1f, 1f)

    // ---- reading: rendered, markers hidden ----

    /** The note as it reads: headings, bold, lists, checkboxes, code, quotes, tables and links drawn, no markdown symbols. */
    fun render(text: String, pal: Palette, density: Float): CharSequence {
        val sb = SpannableStringBuilder()
        val dim = (pal.secondary and 0xFFFFFF) or 0xAA000000.toInt()
        val codeBg = (pal.secondary and 0xFFFFFF) or 0x26000000
        fun span(what: Any, from: Int, to: Int) { if (to > from) sb.setSpan(what, from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }

        fun inline(src: String) {
            var pos = 0
            while (pos < src.length) {
                var best: MatchResult? = null
                var kind = 0
                for ((k, re) in listOf(1 to code, 8 to boldItalic, 2 to bold, 3 to strike, 4 to italic, 5 to link)) {
                    val m = re.find(src, pos) ?: continue
                    if (best == null || m.range.first < best.range.first) { best = m; kind = k }
                }
                if (best == null) { sb.append(src, pos, src.length); break }
                sb.append(src, pos, best.range.first)
                val start = sb.length
                when (kind) {
                    1 -> { sb.append(best.groupValues[1]); span(MdMono(), start, sb.length); span(MdCodeBg(codeBg), start, sb.length) }
                    2 -> { inline(best.groupValues[1]); span(MdBold(), start, sb.length) }
                    3 -> { inline(best.groupValues[1]); span(MdStrike(), start, sb.length) }
                    8 -> { inline(best.groupValues[1]); span(MdBold(), start, sb.length); span(MdItalic(), start, sb.length) }
                    4 -> { inline(best.groupValues[1]); span(MdItalic(), start, sb.length) }
                    else -> { inline(best.groupValues[1]); span(MdLink(pal.accent), start, sb.length); span(MdUnderline(), start, sb.length) }
                }
                pos = best.range.last + 1
            }
        }

        val lines = text.replace("\r\n", "\n").split("\n")
        var fenced = false
        var prevTable = false
        var first = true
        for (line in lines) {
            val isFence = line.trimStart().startsWith("```")
            val isTable = !fenced && !isFence && tableRow.matches(line)
            if (isFence) { fenced = !fenced; prevTable = false; continue }
            if (isTable && tableSep.matches(line)) { prevTable = true; continue }
            val cells = if (isTable) line.trim().trim('|').split("|").map { it.trim() } else emptyList()
            if (isTable && cells.all { it.isEmpty() }) { prevTable = true; continue }      // empty rows are not worth a preview line
            if (!first) sb.append('\n')
            first = false
            when {
                fenced -> { val s0 = sb.length; sb.append(line); span(MdMono(), s0, sb.length); span(MdCodeBg(codeBg), s0, sb.length) }
                isTable -> {
                    val s0 = sb.length
                    cells.filter { it.isNotEmpty() }.forEachIndexed { i, cell -> if (i > 0) sb.append("  ·  "); inline(cell) }
                    if (!prevTable) span(MdBold(), s0, sb.length)                 // the header row
                }
                rule.matches(line) -> { val s0 = sb.length; sb.append("────────────"); span(MdDim(dim), s0, sb.length) }
                else -> {
                    val h = heading.find(line)
                    val li = listItem(line)
                    when {
                        h != null -> {
                            val s0 = sb.length
                            inline(h.groupValues[3])
                            span(MdBold(), s0, sb.length)
                            span(MdSize(levelSize[h.groupValues[1].length - 1]), s0, sb.length)
                        }
                        line.startsWith(">") -> {
                            val s0 = sb.length
                            sb.append("│ "); span(MdDim(dim), s0, sb.length)
                            val q = sb.length
                            inline(line.removePrefix(">").trimStart())
                            span(MdItalic(), q, sb.length)
                        }
                        li != null -> {
                            sb.append(li.indent)
                            when (li.kind) {
                                Kind.TASK -> {
                                    val b = sb.length
                                    sb.append(if (li.checked) '☑' else '☐')
                                    span(CheckSpan(li.checked, pal.accent, pal.onAccent, pal.secondary, density), b, b + 1)
                                    sb.append(' ')
                                    val r = sb.length
                                    inline(line.substring(li.length))
                                    if (li.checked) { span(DoneStrike(), r, sb.length); span(DoneColor(pal.secondary), r, sb.length) }
                                }
                                Kind.BULLET -> { sb.append("• "); inline(line.substring(li.length)) }
                                Kind.NUMBER -> { sb.append("${li.number}. "); inline(line.substring(li.length)) }
                            }
                        }
                        else -> inline(line)
                    }
                }
            }
            prevTable = isTable
        }
        return sb
    }
}
