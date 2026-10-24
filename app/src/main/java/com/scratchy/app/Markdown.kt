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

/** Draws a round bullet in place of the "-" / "*" / "+" that starts a list item. */
class MdBullet(private val color: Int) : ReplacementSpan(), Md {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun getSize(p: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) = (p.textSize * 0.8f).toInt()
    override fun draw(c: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, p: Paint) {
        paint.color = color
        c.drawCircle(x + p.textSize * 0.28f, y - p.textSize * 0.3f, p.textSize * 0.11f, paint)
    }
}

/**
 * Notes are markdown. The text stored (and synced, and copied out) is exactly what was typed or pasted.
 * [style] draws it while you edit (markers stay, dimmed); [render] draws it for reading (markers hidden).
 * Everything above [style] is pure Kotlin and unit tested.
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

    // ---- plain text of one line (note headings, pickers) ----

    private val heading = Regex("^(#{1,6})(\\s+)(.*)$")
    private val bold = Regex("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*")
    private val italic = Regex("(?<![*\\w])\\*(?=[^\\s*])([^*\\n]+?)(?<=[^\\s*])\\*(?![*\\w])")
    private val strike = Regex("~~(?=\\S)(.+?)(?<=\\S)~~")
    private val code = Regex("`([^`\\n]+)`")
    private val link = Regex("\\[([^\\]\\n]+)]\\(([^)\\s]+)\\)")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val tableRow = Regex("^\\s*\\|.*\\|\\s*$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$")

    fun plain(line: String): String {
        var t = line.trim()
        heading.find(t)?.let { t = it.groupValues[3] }
        listItem(t)?.let { t = t.substring(it.length) }
        t = t.replace(Regex("^>\\s?"), "")
        t = link.replace(t, "$1"); t = bold.replace(t, "$1"); t = strike.replace(t, "$1")
        t = code.replace(t, "$1"); t = italic.replace(t, "$1")
        return t.trim()
    }

    // ---- editing: styled, markers kept ----

    private val levelSize = floatArrayOf(1.4f, 1.25f, 1.12f, 1.05f, 1f, 1f)

    fun style(s: Spannable, pal: Palette, density: Float) {
        for (sp in s.getSpans(0, s.length, Md::class.java)) s.removeSpan(sp)
        for (sp in s.getSpans(0, s.length, CheckSpan::class.java)) s.removeSpan(sp)
        for (sp in s.getSpans(0, s.length, DoneStrike::class.java)) s.removeSpan(sp)
        for (sp in s.getSpans(0, s.length, DoneColor::class.java)) s.removeSpan(sp)
        val dim = (pal.secondary and 0xFFFFFF) or 0x99000000.toInt()
        val codeBg = (pal.secondary and 0xFFFFFF) or 0x26000000
        fun span(what: Any, from: Int, to: Int) { if (to > from) s.setSpan(what, from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }

        fun inline(line: String, base: Int) {
            for (m in code.findAll(line)) {
                span(MdMono(), base + m.range.first, base + m.range.last + 1)
                span(MdCodeBg(codeBg), base + m.range.first, base + m.range.last + 1)
                span(MdDim(dim), base + m.range.first, base + m.range.first + 1)
                span(MdDim(dim), base + m.range.last, base + m.range.last + 1)
            }
            for (m in bold.findAll(line)) {
                span(MdBold(), base + m.range.first, base + m.range.last + 1)
                span(MdDim(dim), base + m.range.first, base + m.range.first + 2)
                span(MdDim(dim), base + m.range.last - 1, base + m.range.last + 1)
            }
            for (m in italic.findAll(line)) {
                span(MdItalic(), base + m.range.first, base + m.range.last + 1)
                span(MdDim(dim), base + m.range.first, base + m.range.first + 1)
                span(MdDim(dim), base + m.range.last, base + m.range.last + 1)
            }
            for (m in strike.findAll(line)) {
                span(MdStrike(), base + m.range.first, base + m.range.last + 1)
                span(MdDim(dim), base + m.range.first, base + m.range.first + 2)
                span(MdDim(dim), base + m.range.last - 1, base + m.range.last + 1)
            }
            for (m in link.findAll(line)) {
                val text = m.groups[1]!!.range
                span(MdLink(pal.accent), base + text.first, base + text.last + 1)
                span(MdUnderline(), base + text.first, base + text.last + 1)
                span(MdDim(dim), base + m.range.first, base + text.first)
                span(MdDim(dim), base + text.last + 1, base + m.range.last + 1)
            }
        }

        var fenced = false
        var i = 0
        while (i <= s.length) {
            val nl = s.indexOf('\n', i).let { if (it < 0) s.length else it }
            val line = s.substring(i, nl)
            when {
                line.trimStart().startsWith("```") -> { fenced = !fenced; span(MdDim(dim), i, nl) }
                fenced -> { span(MdMono(), i, nl); span(MdCodeBg(codeBg), i, nl) }
                rule.matches(line) -> span(MdDim(dim), i, nl)
                tableRow.matches(line) -> { span(MdMono(), i, nl); inline(line, i) }
                else -> {
                    val h = heading.find(line)
                    if (h != null) {
                        val level = h.groupValues[1].length
                        val markerEnd = i + h.groupValues[1].length + h.groupValues[2].length
                        span(MdDim(dim), i, markerEnd)
                        span(MdBold(), markerEnd, nl)
                        span(MdSize(levelSize[level - 1]), markerEnd, nl)
                        inline(line.substring(markerEnd - i), markerEnd)
                    } else {
                        if (line.startsWith(">")) span(MdDim(dim), i, i + 1)
                        val li = listItem(line)
                        if (li != null) {
                            val at = i + li.indent.length
                            when (li.kind) {
                                Kind.TASK -> {
                                    val closeAt = i + line.indexOf(']', li.indent.length)
                                    span(CheckSpan(li.checked, pal.accent, pal.onAccent, pal.secondary, density), at, closeAt + 1)
                                    if (li.checked && nl > i + li.length) {
                                        span(DoneStrike(), i + li.length, nl)
                                        span(DoneColor(pal.secondary), i + li.length, nl)
                                    }
                                }
                                Kind.BULLET -> span(MdBullet(pal.secondary), at, at + 1)
                                Kind.NUMBER -> span(MdDim(dim), at, i + li.length - 1)
                            }
                        }
                        inline(line, i)
                    }
                }
            }
            i = nl + 1
        }
    }

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
                for ((k, re) in listOf(1 to code, 2 to bold, 3 to strike, 4 to italic, 5 to link)) {
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
            if (!first) sb.append('\n')
            first = false
            when {
                fenced -> { val s0 = sb.length; sb.append(line); span(MdMono(), s0, sb.length); span(MdCodeBg(codeBg), s0, sb.length) }
                isTable -> {
                    val s0 = sb.length
                    sb.append(line.trim().trim('|').split("|").joinToString("  │  ") { it.trim() })
                    span(MdMono(), s0, sb.length)
                    if (!prevTable) span(MdBold(), s0, sb.length)
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
