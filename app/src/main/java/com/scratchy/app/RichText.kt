package com.scratchy.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ReplacementSpan
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan

/*
 * The rich text editor's model. The note is one Editable. Formatting lives in spans, so nobody sees markdown
 * symbols. The note is still saved as markdown: [RichText.fromMarkdown] reads it, [RichText.toMarkdown] writes it.
 *
 * Every line is one paragraph. A paragraph has at most one "paragraph kind" span (heading, list item, quote,
 * code line, raw line). Inline formatting is bold / italic / strike (framework spans), inline code and links.
 */

/** Marks a span as a paragraph attribute (it always covers exactly one whole paragraph). */
interface RPara

class RHeading(val level: Int) : MetricAffectingSpan(), RPara {
    private val factor = floatArrayOf(1.55f, 1.35f, 1.2f, 1.1f, 1.05f, 1f)[(level - 1).coerceIn(0, 5)]
    override fun updateMeasureState(tp: TextPaint) = apply(tp)
    override fun updateDrawState(tp: TextPaint) = apply(tp)
    private fun apply(tp: TextPaint) {
        tp.textSize = tp.textSize * factor
        tp.typeface = Typeface.create(tp.typeface ?: Typeface.DEFAULT, Typeface.BOLD)
    }
}

class RList(
    val kind: Markdown.Kind, val level: Int, val checked: Boolean,
    private val accent: Int, private val onAccent: Int, private val secondary: Int, private val density: Float,
) : LeadingMarginSpan, RPara {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val tick = Path()

    private fun indent() = level * 22f * density
    private fun marker() = (if (kind == Markdown.Kind.NUMBER) 30f else 26f) * density
    fun with(kind: Markdown.Kind = this.kind, level: Int = this.level, checked: Boolean = this.checked) =
        RList(kind, level, checked, accent, onAccent, secondary, density)

    override fun getLeadingMargin(first: Boolean) = (indent() + marker()).toInt()

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout,
    ) {
        if (!first) return
        val size = p.textSize
        val rtl = dir < 0
        // Markers sit at the start edge of the line: the left in English, the right in Arabic.
        val startX = if (rtl) x - indent() else x + indent()
        when (kind) {
            Markdown.Kind.BULLET -> {
                paint.color = secondary; paint.style = Paint.Style.FILL
                c.drawCircle(startX + (if (rtl) -1 else 1) * marker() * 0.4f, baseline - size * 0.3f, size * 0.12f, paint)
            }
            Markdown.Kind.NUMBER -> {
                paint.color = secondary; paint.textSize = size; paint.typeface = p.typeface
                val label = "${RichText.numberOf(text as Spanned, start)}."
                val w = paint.measureText(label)
                c.drawText(label, if (rtl) startX - marker() * 0.8f else startX + marker() * 0.8f - w, baseline.toFloat(), paint)
            }
            Markdown.Kind.TASK -> {
                val s = size * 0.95f
                drawCheckbox(
                    c, paint, box, tick, if (rtl) startX - s - size * 0.04f else startX + size * 0.04f, baseline - size * 0.32f, s,
                    checked, accent, onAccent, secondary, density,
                )
            }
        }
    }
}

class RQuote(private val color: Int, private val density: Float) : CharacterStyle(), LeadingMarginSpan, RPara {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun updateDrawState(tp: TextPaint) {
        tp.color = color
        tp.typeface = Typeface.create(tp.typeface ?: Typeface.DEFAULT, Typeface.ITALIC)
    }
    override fun getLeadingMargin(first: Boolean) = (16f * density).toInt()
    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout,
    ) {
        paint.color = color; paint.style = Paint.Style.FILL
        val w = 3f * density
        val left = if (dir > 0) x + 2f * density else x - 2f * density - w
        c.drawRoundRect(left, top.toFloat(), left + w, bottom.toFloat(), w / 2, w / 2, paint)
    }
}

/** A line of a fenced code block. [lang] is only kept on the first line of the block. */
class RCode(val lang: String, private val bg: Int) : CharacterStyle(), RPara {
    override fun updateDrawState(tp: TextPaint) { tp.typeface = Typeface.MONOSPACE; tp.bgColor = bg }
}

/** A line kept exactly as written (table rows, rules). Shown in a monospace, dimmed. */
class RRaw(private val color: Int) : CharacterStyle(), RPara {
    override fun updateDrawState(tp: TextPaint) { tp.typeface = Typeface.MONOSPACE; tp.color = color }
}

class RInlineCode(private val bg: Int) : CharacterStyle() {
    override fun updateDrawState(tp: TextPaint) { tp.typeface = Typeface.MONOSPACE; tp.bgColor = bg }
}

class RLink(val url: String, private val color: Int) : CharacterStyle() {
    override fun updateDrawState(tp: TextPaint) { tp.color = color; tp.isUnderlineText = true }
}

/** A markdown table: [rows] is every row including the header (row 0), all the same width. Cell text is markdown. */
class TableData(rows: List<List<String>>) {
    val cols = (rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
    val rows: List<List<String>> = rows.ifEmpty { listOf(listOf("")) }.map { r -> List(cols) { r.getOrElse(it) { "" } } }
    companion object { fun blank(rows: Int = 3, cols: Int = 3) = TableData(List(rows) { r -> List(cols) { c -> if (r == 0) "Column ${c + 1}" else "" } }) }
}

/** A table drawn as a real grid in place of one placeholder character (the whole line is that character). */
class RTable(val data: TableData, private val pal: Palette, private val density: Float, private val avail: () -> Int) : ReplacementSpan(), RPara {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cell = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val head = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var key = -1
    private var colW = IntArray(0)
    private var rowH = IntArray(0)
    private var layouts: List<List<StaticLayout>> = emptyList()
    private var height = 0
    private val pad get() = (8 * density).toInt()

    private fun prepare(width: Int, base: Paint) {
        if (key == width) return
        key = width
        val size = base.textSize * 0.9f
        cell.textSize = size; cell.color = pal.text; cell.typeface = Typeface.DEFAULT
        head.textSize = size; head.color = pal.text; head.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val texts = data.rows.mapIndexed { ri, row -> row.map { Markdown.render(it, pal, density) to (if (ri == 0) head else cell) } }
        val nat = DoubleArray(data.cols) { c -> texts.maxOf { Layout.getDesiredWidth(it[c].first, it[c].second).toDouble() } + 2 * pad }
        val minW = 56 * density.toDouble()
        val w = DoubleArray(data.cols) { nat[it] * width / nat.sum() }
        val fixed = BooleanArray(data.cols)
        repeat(2) {
            for (c in w.indices) if (!fixed[c] && w[c] < minW) { w[c] = minW; fixed[c] = true }
            val rest = width - w.indices.filter { fixed[it] }.sumOf { w[it] }
            val flex = w.indices.filter { !fixed[it] }.sumOf { nat[it] }
            if (flex > 0) for (c in w.indices) if (!fixed[c]) w[c] = nat[c] * rest / flex
        }
        colW = IntArray(data.cols) { w[it].toInt().coerceAtLeast(1) }
        colW[colW.size - 1] += width - colW.sum()
        layouts = texts.map { row -> row.mapIndexed { c, (t, p) -> StaticLayout.Builder.obtain(t, 0, t.length, p, (colW[c] - 2 * pad).coerceAtLeast(1)).build() } }
        rowH = IntArray(layouts.size) { r -> layouts[r].maxOf { it.height } + 2 * pad }
        height = rowH.sum()
    }

    private fun width() = avail().takeIf { it > 0 } ?: (300 * density).toInt()

    override fun getSize(p: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val w = width()
        prepare(w, p)
        fm?.let { it.ascent = -height; it.top = -height; it.descent = 0; it.bottom = 0 }
        return w
    }

    override fun draw(c: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, p: Paint) {
        val w = width()
        prepare(w, p)
        val line = (pal.secondary and 0xFFFFFF) or 0x66000000
        val r = 10 * density
        c.save()
        c.translate(x, (y - height).toFloat())
        val box = RectF(0f, 0f, w.toFloat(), height.toFloat())
        c.save()
        val clip = Path().apply { addRoundRect(box, r, r, Path.Direction.CW) }
        c.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.color = (pal.accent and 0xFFFFFF) or 0x26000000
        c.drawRect(0f, 0f, w.toFloat(), rowH[0].toFloat(), paint)
        paint.color = line; paint.strokeWidth = density
        var yy = 0f
        for (ri in layouts.indices) {
            var xx = 0f
            for (ci in layouts[ri].indices) {
                c.save(); c.translate(xx + pad, yy + pad); layouts[ri][ci].draw(c); c.restore()
                xx += colW[ci]
                if (ci < colW.size - 1) c.drawLine(xx, yy, xx, yy + rowH[ri], paint)
            }
            yy += rowH[ri]
            if (ri < layouts.size - 1) c.drawLine(0f, yy, w.toFloat(), yy, paint)
        }
        c.restore()
        paint.style = Paint.Style.STROKE; paint.color = line; paint.strokeWidth = 1.2f * density
        c.drawRoundRect(box.apply { inset(0.6f * density, 0.6f * density) }, r, r, paint)
        c.restore()
    }
}

enum class Inline { BOLD, ITALIC, STRIKE, CODE }

object RichText {
    private const val FLAGS_TEXT = Spanned.SPAN_EXCLUSIVE_INCLUSIVE      // typing at the end continues the style
    private const val FLAGS_PARA = Spanned.SPAN_INCLUSIVE_INCLUSIVE
    private const val TABLE_CHAR = '\uFFFC'
    private val paraClasses = listOf(RTable::class.java, RCode::class.java, RRaw::class.java, RHeading::class.java, RList::class.java, RQuote::class.java)

    // ------------------------------------------------------------------ paragraphs

    fun paraStart(t: CharSequence, i: Int): Int = if (i <= 0) 0 else t.lastIndexOf('\n', i - 1) + 1
    /** Index just after the paragraph's newline, or the text length for the last paragraph. */
    fun paraEnd(t: CharSequence, i: Int): Int { val n = t.indexOf('\n', i); return if (n < 0) t.length else n + 1 }

    /** The words between [a] and [b], for copying: no list markers or symbols, and each table as its cells' text. */
    fun plainText(e: Spanned, a: Int, b: Int): String {
        val out = StringBuilder()
        var i = a
        for (t in e.getSpans(a, b, RTable::class.java).sortedBy { e.getSpanStart(it) }) {
            val s = e.getSpanStart(t)
            if (s < i || s >= b) continue
            out.append(e, i, s).append(Markdown.tableText(t.data.rows))
            i = s + 1
        }
        out.append(e, i, b)
        return out.toString().replace(TABLE_CHAR.toString(), "")
    }

    /** The table whose line contains [pos], with the offset of its placeholder character. */
    fun tableAt(e: Spanned, pos: Int): Pair<RTable, Int>? {
        val ps = paraStart(e, pos)
        val t = e.getSpans(ps, minOf(ps + 1, e.length), RTable::class.java).firstOrNull { e.getSpanStart(it) == ps } ?: return null
        return t to ps
    }

    fun replaceTable(e: Editable, at: Int, old: RTable, data: TableData, pal: Palette, density: Float, width: () -> Int) {
        e.removeSpan(old)
        e.setSpan(RTable(data, pal, density, width), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Puts a new table on its own line after the line at [pos]; returns the offset of its placeholder. */
    fun insertTable(e: Editable, pos: Int, data: TableData, pal: Palette, density: Float, width: () -> Int): Int {
        val ps = paraStart(e, pos)
        val end = e.indexOf('\n', ps).let { if (it < 0) e.length else it }
        val at: Int
        if (end == ps) { e.replace(ps, ps, TABLE_CHAR.toString()); at = ps }
        else { e.insert(end, "\n$TABLE_CHAR"); at = end + 1 }
        if (at + 1 >= e.length || e[at + 1] != '\n') e.insert(at + 1, "\n")
        for (c in paraClasses) for (s in e.getSpans(at, at + 1, c)) if (s !is RTable) e.removeSpan(s)
        e.setSpan(RTable(data, pal, density, width), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return at
    }

    /** The paragraph kind (heading, list, quote, ...) of the line at [pos], or null for plain text. */
    fun kindAt(e: Spanned, pos: Int): Any? = kindSpan(e, paraStart(e, pos), paraEnd(e, pos))

    private fun kindSpan(e: Spanned, ps: Int, pe: Int): Any? {
        for (c in paraClasses) {
            for (s in e.getSpans(ps, pe, c)) {
                val st = e.getSpanStart(s); val en = e.getSpanEnd(s)
                if (ps == pe) { if (st == ps && en == ps) return s } else if (st < pe && en > ps) return s
            }
        }
        return null
    }

    /** Which number a numbered list line has (counts the unbroken run of numbered lines above it at the same depth). */
    fun numberOf(e: Spanned, ps: Int): Int {
        val me = e.getSpans(ps, ps, RList::class.java).firstOrNull { e.getSpanStart(it) == ps } ?: return 1
        var n = 1
        var at = ps
        while (at > 0) {
            val prevStart = paraStart(e, at - 1)
            val s = e.getSpans(prevStart, at, RList::class.java).firstOrNull { e.getSpanStart(it) == prevStart } ?: break
            if (s.level > me.level) { at = prevStart; continue }                 // a nested item: skip over it
            if (s.level < me.level || s.kind != Markdown.Kind.NUMBER) break
            n++; at = prevStart
        }
        return n
    }

    // ------------------------------------------------------------------ markdown -> rich

    private class Rec(val what: Any, val s: Int, val e: Int)
    private const val PUA = 0xE000
    private val escape = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!>~|])")

    fun fromMarkdown(md: String, pal: Palette, density: Float, tableWidth: () -> Int = { 0 }): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val dim = (pal.secondary and 0xFFFFFF) or 0xAA000000.toInt()
        val codeBg = (pal.secondary and 0xFFFFFF) or 0x26000000
        var n = 0
        var fence: String? = null
        var fenceFirst = false
        val queue = ArrayList<Rec>()          // spans are applied at the end, so appending later text can't stretch them
        val lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trimStart()
            if (t.startsWith("```")) {
                if (fence == null) { fence = t.removePrefix("```").trim(); fenceFirst = true } else fence = null
                i++; continue
            }
            if (n++ > 0) sb.append('\n')
            val ps = sb.length
            fun para(what: Any) { queue += Rec(what, ps, sb.length) }
            if (fence != null) {
                sb.append(line)
                para(RCode(if (fenceFirst) fence else "", codeBg)); fenceFirst = false
                i++; continue
            }
            if (Markdown.tableRow.matches(line) && i + 1 < lines.size && Markdown.tableSep.matches(lines[i + 1])) {
                val rows = ArrayList<List<String>>()
                rows += splitRow(line)
                var j = i + 2
                while (j < lines.size && Markdown.tableRow.matches(lines[j])) { rows += splitRow(lines[j]); j++ }
                sb.append(TABLE_CHAR)
                queue += Rec(RTable(TableData(rows), pal, density, tableWidth), ps, ps + 1)
                i = j; continue
            }
            val h = Markdown.heading.find(line)
            val li = Markdown.listItem(line)
            when {
                Markdown.rule.matches(line) || Markdown.tableRow.matches(line) -> { sb.append(line); para(RRaw(dim)) }
                h != null -> {
                    appendInline(sb, queue, h.groupValues[3], pal, allowBold = false)
                    para(RHeading(h.groupValues[1].length))
                }
                li != null -> {
                    appendInline(sb, queue, line.substring(li.length), pal, allowBold = true)
                    para(RList(li.kind, (li.indent.replace("\t", "  ").length / 2).coerceAtMost(3), li.checked,
                        pal.accent, pal.onAccent, pal.secondary, density))
                }
                line.startsWith(">") -> {
                    appendInline(sb, queue, line.removePrefix(">").removePrefix(" "), pal, allowBold = true)
                    para(RQuote(pal.secondary, density))
                }
                else -> appendInline(sb, queue, line, pal, allowBold = true)
            }
            i++
        }
        for (r in queue) if (r.e > r.s || r.what is RPara) sb.setSpan(r.what, r.s, r.e,
            if (r.what is RTable) Spanned.SPAN_EXCLUSIVE_EXCLUSIVE else if (r.what is RPara) FLAGS_PARA else FLAGS_TEXT)
        normalize(sb)
        return sb
    }

    private val cellSplit = Regex("(?<!\\\\)\\|")
    private fun splitRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.drop(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        return t.split(cellSplit).map { it.replace("\\|", "|").trim() }
    }

    private fun joinRow(cells: List<String>) = "| " + cells.joinToString(" | ") { it.replace("|", "\\|").replace("\n", " ") } + " |"

    private fun appendInline(sb: SpannableStringBuilder, queue: MutableList<Rec>, src: String, pal: Palette, allowBold: Boolean) {
        val text = StringBuilder()
        val recs = ArrayList<Rec>()
        val codeBg = (pal.secondary and 0xFFFFFF) or 0x26000000
        fun go(s: String) {
            var pos = 0
            while (pos < s.length) {
                var best: MatchResult? = null
                var kind = 0
                for ((k, re) in listOf(1 to Markdown.code, 8 to Markdown.boldItalic, 2 to Markdown.bold, 3 to Markdown.strike, 4 to Markdown.italic, 5 to Markdown.link, 6 to underBold, 7 to underItalic)) {
                    val m = re.find(s, pos) ?: continue
                    if (best == null || m.range.first < best.range.first) { best = m; kind = k }
                }
                if (best == null) { text.append(s, pos, s.length); break }
                text.append(s, pos, best.range.first)
                val start = text.length
                when (kind) {
                    1 -> { text.append(best.groupValues[1]); recs += Rec(RInlineCode(codeBg), start, text.length) }
                    2, 6 -> { go(best.groupValues[1]); if (allowBold) recs += Rec(StyleSpan(Typeface.BOLD), start, text.length) }
                    3 -> { go(best.groupValues[1]); recs += Rec(StrikethroughSpan(), start, text.length) }
                    8 -> { go(best.groupValues[1]); if (allowBold) recs += Rec(StyleSpan(Typeface.BOLD), start, text.length); recs += Rec(StyleSpan(Typeface.ITALIC), start, text.length) }
                    4, 7 -> { go(best.groupValues[1]); recs += Rec(StyleSpan(Typeface.ITALIC), start, text.length) }
                    else -> { go(best.groupValues[1]); recs += Rec(RLink(unescape(best.groupValues[2]), pal.accent), start, text.length) }
                }
                pos = best.range.last + 1
            }
        }
        go(escape.replace(src) { (PUA + it.groupValues[1][0].code).toChar().toString() })
        val base = sb.length
        sb.append(unescape(text.toString()))
        for (r in recs) if (r.e > r.s) queue += Rec(r.what, base + r.s, base + r.e)
    }

    private val underBold = Regex("(?<![\\w])__(?=\\S)(.+?)(?<=\\S)__(?![\\w])")
    private val underItalic = Regex("(?<![\\w])_(?=[^\\s_])([^_\\n]+?)(?<=[^\\s_])_(?![\\w])")
    private fun unescape(s: String): String {
        if (s.none { it.code in PUA..PUA + 127 }) return s
        return buildString { for (ch in s) append(if (ch.code in PUA..PUA + 127) (ch.code - PUA).toChar() else ch) }
    }

    // ------------------------------------------------------------------ rich -> markdown

    fun toMarkdown(e: Spanned): String {
        val out = ArrayList<String>()
        var inFence = false
        var ps = 0
        while (true) {
            val nl = e.indexOf('\n', ps)
            val end = if (nl < 0) e.length else nl
            val pe = if (nl < 0) e.length else nl + 1
            val kind = kindSpan(e, ps, pe)
            val raw = e.substring(ps, end)
            if (kind is RTable && raw.contains(TABLE_CHAR)) {
                if (inFence) { out += "```"; inFence = false }
                out += joinRow(kind.data.rows[0])
                out += "| " + List(kind.data.cols) { "---" }.joinToString(" | ") + " |"
                for (row in kind.data.rows.drop(1)) out += joinRow(row)
            } else if (kind is RCode) {
                if (!inFence) { out += "```" + kind.lang; inFence = true }
                out += raw
            } else {
                if (inFence) { out += "```"; inFence = false }
                out += when (kind) {
                    is RRaw -> raw
                    is RHeading -> "#".repeat(kind.level) + " " + inline(e, ps, end, allowBold = false)
                    is RList -> "  ".repeat(kind.level) + when (kind.kind) {
                        Markdown.Kind.TASK -> if (kind.checked) "- [x] " else "- [ ] "
                        Markdown.Kind.BULLET -> "- "
                        Markdown.Kind.NUMBER -> "${numberOf(e, ps)}. "
                    } + inline(e, ps, end, true)
                    is RQuote -> "> " + inline(e, ps, end, true)
                    else -> guardLineStart(inline(e, ps, end, true))
                }
            }
            if (nl < 0) break
            ps = pe
        }
        if (inFence) out += "```"
        return out.joinToString("\n")
    }

    private val lineStartTrap = Regex("^(\\s*)(#{1,6}\\s|[-*+]\\s|\\d{1,3}[.)]\\s|>|```|([-*_])(\\s*\\3){2,}\\s*$)")

    /** A plain line that would be read as a heading, list or quote gets its first marker character escaped. */
    private fun guardLineStart(s: String): String {
        val m = lineStartTrap.find(s) ?: return s
        val at = m.groupValues[1].length
        if (s[at].isDigit()) { val dot = s.indexOfFirst { it == '.' || it == ')' }; return s.substring(0, dot) + "\\" + s.substring(dot) }
        return s.substring(0, at) + "\\" + s.substring(at)
    }

    private class Attr(val bold: Boolean, val italic: Boolean, val strike: Boolean, val code: Boolean, val link: String?)

    private fun inline(e: Spanned, from: Int, to: Int, allowBold: Boolean): String {
        if (from >= to) return ""
        val cuts = sortedSetOf(from, to)
        val spans = e.getSpans(from, to, Any::class.java).filter {
            it is StyleSpan || it is StrikethroughSpan || it is RInlineCode || it is RLink
        }
        for (s in spans) { cuts += e.getSpanStart(s).coerceIn(from, to); cuts += e.getSpanEnd(s).coerceIn(from, to) }
        val points = cuts.toList()
        val out = StringBuilder()
        val open = ArrayList<String>()           // markers currently open, outermost first
        val closers = ArrayList<String>()

        fun close(downTo: Int) {
            while (open.size > downTo) {
                val tail = out.length
                var ws = tail
                while (ws > 0 && out[ws - 1].isWhitespace()) ws--
                val spaces = out.substring(ws)
                out.setLength(ws)
                out.append(closers.removeAt(closers.size - 1))
                out.append(spaces)
                open.removeAt(open.size - 1)
            }
        }
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            if (a == b) continue
            val text = e.substring(a, b)
            val here = spans.filter { e.getSpanStart(it) <= a && e.getSpanEnd(it) >= b }
            val attr = Attr(
                bold = allowBold && here.any { it is StyleSpan && (it.style and Typeface.BOLD) != 0 },
                italic = here.any { it is StyleSpan && (it.style and Typeface.ITALIC) != 0 },
                strike = here.any { it is StrikethroughSpan },
                code = here.any { it is RInlineCode },
                link = (here.firstOrNull { it is RLink } as? RLink)?.url,
            )
            val want = ArrayList<Pair<String, String>>()     // opener to closer
            if (attr.link != null) want += "[" to "](${attr.link})"
            if (attr.strike) want += "~~" to "~~"
            if (attr.bold) want += "**" to "**"
            if (attr.italic) want += "*" to "*"
            if (attr.code) want += "`" to "`"
            if (text.isBlank()) {                              // blank gaps stay inside whatever is open, never open anything
                out.append(if (attr.code) text else esc(text, e, b, to)); continue
            }
            var common = 0
            while (common < open.size && common < want.size && open[common] == want[common].first + "@" + want[common].second) common++
            close(common)
            val lead = text.takeWhile { it.isWhitespace() }
            var body = text.substring(lead.length)
            if (open.size < want.size) out.append(lead) else body = text
            for (k in open.size until want.size) { out.append(want[k].first); open += want[k].first + "@" + want[k].second; closers += want[k].second }
            out.append(if (attr.code) body else esc(body, e, b, to))
        }
        close(0)
        return out.toString()
    }

    /** Escapes characters that would be read as formatting. [absEnd]/[paraEnd] are only used to look ahead for "](". */
    private fun esc(s: String, e: Spanned, absEnd: Int, paraEnd: Int): String {
        val ahead = e.substring(absEnd.coerceAtMost(e.length), paraEnd.coerceAtMost(e.length))
        val sb = StringBuilder()
        for ((i, ch) in s.withIndex()) {
            val prev = if (i > 0) s[i - 1] else ' '
            val next = if (i + 1 < s.length) s[i + 1] else ' '
            when {
                ch == '\\' || ch == '*' || ch == '`' -> sb.append('\\').append(ch)
                ch == '~' && (next == '~' || prev == '~') -> sb.append('\\').append(ch)
                ch == '_' && (!prev.isLetterOrDigit() || !next.isLetterOrDigit()) -> sb.append('\\').append(ch)
                ch == '[' && (s.indexOf("](", i) >= 0 || ahead.contains("](")) -> sb.append('\\').append(ch)
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ keeping paragraph spans tidy

    private fun <T : Any> fix(e: Editable, ps: Int, pe: Int, cls: Class<T>) {
        val cands = e.getSpans(ps, pe, cls).filter { s ->
            val st = e.getSpanStart(s); val en = e.getSpanEnd(s)
            if (ps == pe) st == ps && en == ps else st < pe && en > ps
        }.filter { cls != RTable::class.java || e.getSpanStart(it) >= ps }.sortedBy { e.getSpanStart(it) }
        if (cands.isEmpty()) return
        val keep = cands.firstOrNull { e.getSpanStart(it) <= ps }
        for (s in cands) if (s !== keep) e.removeSpan(s)
        if (keep is RTable) {                                   // exactly the one placeholder character, never the newline
            if (ps >= e.length || e[ps] != TABLE_CHAR) e.removeSpan(keep)
            else if (e.getSpanStart(keep) != ps || e.getSpanEnd(keep) != ps + 1) e.setSpan(keep, ps, ps + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }
        if (keep != null && (e.getSpanStart(keep) != ps || e.getSpanEnd(keep) != pe)) e.setSpan(keep, ps, pe, FLAGS_PARA)
    }

    /** A table owns its whole line: any text typed next to the placeholder is moved onto its own line. */
    private fun isolateTables(e: Editable) {
        for (t in e.getSpans(0, e.length, RTable::class.java)) {
            val st = e.getSpanStart(t)
            if (st < 0 || st >= e.length || e[st] != TABLE_CHAR) { e.removeSpan(t); continue }
            if (st > 0 && e[st - 1] != '\n') { e.insert(st, "\n"); continue }
            val en = st + 1
            if (en < e.length && e[en] != '\n') e.insert(en, "\n")
        }
    }

    /** Snaps every paragraph span in [from, to] to exactly one paragraph, and allows only one kind per paragraph. */
    fun normalize(e: Editable, from: Int = 0, to: Int = e.length) {
        isolateTables(e)
        var ps = paraStart(e, from.coerceIn(0, e.length))
        val last = paraEnd(e, to.coerceIn(0, e.length))
        while (true) {
            val pe = paraEnd(e, ps)
            for (c in paraClasses) fix(e, ps, pe, c)
            val kinds = paraClasses.mapNotNull { c -> e.getSpans(ps, pe, c).firstOrNull { e.getSpanStart(it) == ps }?.let { c to it } }
            for ((_, s) in kinds.drop(1)) e.removeSpan(s)            // highest-priority class wins
            if (ps >= e.length || pe > last) break
            ps = pe
        }
    }

    /**
     * Call after each text change. [newline] is where a "\n" was just typed (or -1), [at]/[len] the changed range.
     * Continues lists and quotes on Enter, ends them on an empty item, turns "- ", "# " ... typed at the start of a line
     * into the matching format, and re-snaps paragraph spans.
     */
    fun afterEdit(e: Editable, pal: Palette, density: Float, newline: Int, at: Int, len: Int, inserted: Boolean, setCursor: (Int) -> Unit) {
        if (newline >= 0 && newline < e.length && e[newline] == '\n') {
            val ps = paraStart(e, newline)
            val k = kindSpan(e, ps, newline + 1)
            val firstEmpty = ps == newline && (newline + 1 >= e.length || e[newline + 1] == '\n')   // Enter on an empty item
            when {
                k is RList && firstEmpty && k.level > 0 -> {                 // back out of a nested list one level
                    e.delete(newline, newline + 1)
                    e.removeSpan(k)
                    e.setSpan(k.with(level = k.level - 1), ps, paraEnd(e, ps), FLAGS_PARA)
                    setCursor(ps)
                }
                (k is RList || k is RQuote) && firstEmpty -> { e.removeSpan(k); e.delete(newline, newline + 1); setCursor(ps) }
                k is RList -> e.setSpan(k.with(checked = false), newline + 1, paraEnd(e, newline + 1), FLAGS_PARA)
                k is RQuote -> e.setSpan(RQuote(pal.secondary, density), newline + 1, paraEnd(e, newline + 1), FLAGS_PARA)
                k is RCode -> e.setSpan(RCode("", (pal.secondary and 0xFFFFFF) or 0x26000000), newline + 1, paraEnd(e, newline + 1), FLAGS_PARA)
            }
        } else if (inserted && len == 1 && at < e.length && e[at] == ' ') shortcut(e, pal, density, at, setCursor)
        normalize(e, (at - 1).coerceAtLeast(0), (at + len + 1).coerceAtMost(e.length))
    }

    private val shortcuts = listOf("- ", "* ", "+ ", "[] ", "> ", "1. ", "# ", "## ", "### ")

    /** "- " at the start of a line becomes a bullet, "# " a heading, and so on, like Notion. */
    private fun shortcut(e: Editable, pal: Palette, density: Float, spaceAt: Int, setCursor: (Int) -> Unit) {
        val ps = paraStart(e, spaceAt)
        if (kindSpan(e, ps, paraEnd(e, ps)) != null) return
        val typed = e.substring(ps, spaceAt + 1)
        val numbered = typed.length in 3..5 && typed.dropLast(2).all { it.isDigit() } && typed.endsWith(". ")
        if (typed !in shortcuts && !numbered) return
        val span: Any = when {
            typed[0] == '#' -> RHeading(typed.trimEnd().length)
            typed == "> " -> RQuote(pal.secondary, density)
            typed == "[] " -> RList(Markdown.Kind.TASK, 0, false, pal.accent, pal.onAccent, pal.secondary, density)
            numbered -> RList(Markdown.Kind.NUMBER, 0, false, pal.accent, pal.onAccent, pal.secondary, density)
            else -> RList(Markdown.Kind.BULLET, 0, false, pal.accent, pal.onAccent, pal.secondary, density)
        }
        e.delete(ps, spaceAt + 1)
        e.setSpan(span, ps, paraEnd(e, ps), FLAGS_PARA)
        setCursor(ps)
    }

    // ------------------------------------------------------------------ the formatting bar's actions

    private fun styled(e: Spanned, kind: Inline, a: Int, b: Int): List<Any> = when (kind) {
        Inline.BOLD -> e.getSpans(a, b, StyleSpan::class.java).filter { (it.style and Typeface.BOLD) != 0 }
        Inline.ITALIC -> e.getSpans(a, b, StyleSpan::class.java).filter { (it.style and Typeface.ITALIC) != 0 }
        Inline.STRIKE -> e.getSpans(a, b, StrikethroughSpan::class.java).toList()
        Inline.CODE -> e.getSpans(a, b, RInlineCode::class.java).toList()
    }

    /** True when the selection is fully inside [kind]; for a bare cursor, true when typing there continues it. */
    fun isActive(e: Spanned, kind: Inline, a: Int, b: Int): Boolean {
        if (a == b) return styled(e, kind, a, b).any { val s = e.getSpanStart(it); val en = e.getSpanEnd(it); (s < a && a <= en) || (s == a && en == a) }
        var at = a
        for (s in styled(e, kind, a, b).sortedBy { e.getSpanStart(it) }) {
            if (e.getSpanStart(s) > at) return false
            at = maxOf(at, e.getSpanEnd(s))
        }
        return at >= b
    }

    private fun make(kind: Inline, pal: Palette): Any = when (kind) {
        Inline.BOLD -> StyleSpan(Typeface.BOLD)
        Inline.ITALIC -> StyleSpan(Typeface.ITALIC)
        Inline.STRIKE -> StrikethroughSpan()
        Inline.CODE -> RInlineCode((pal.secondary and 0xFFFFFF) or 0x26000000)
    }

    fun toggleInline(e: Editable, kind: Inline, a: Int, b: Int, pal: Palette) {
        if (a == b) {
            if (isActive(e, kind, a, b)) {                       // stop the style at the cursor
                for (s in styled(e, kind, a, b)) {
                    val st = e.getSpanStart(s); val en = e.getSpanEnd(s)
                    if (st == a && en == a) { e.removeSpan(s); continue }
                    if (en == a) { e.setSpan(s, st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    else if (st < a) { e.setSpan(s, st, a, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); e.setSpan(make(kind, pal), a, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                }
            } else e.setSpan(make(kind, pal), a, a, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            return
        }
        if (isActive(e, kind, a, b)) {
            for (s in styled(e, kind, a, b)) {
                val st = e.getSpanStart(s); val en = e.getSpanEnd(s)
                e.removeSpan(s)
                if (st < a) e.setSpan(make(kind, pal), st, a, FLAGS_TEXT)
                if (en > b) e.setSpan(make(kind, pal), b, en, FLAGS_TEXT)
            }
        } else e.setSpan(make(kind, pal), a, b, FLAGS_TEXT)
    }

    fun linkAt(e: Spanned, a: Int, b: Int): String? = e.getSpans(a, b, RLink::class.java).firstOrNull()?.url

    fun setLink(e: Editable, a: Int, b: Int, url: String, pal: Palette) {
        for (s in e.getSpans(a, b, RLink::class.java)) e.removeSpan(s)
        if (url.isBlank()) return
        var from = a; var to = b
        if (a == b) { e.insert(a, url); to = a + url.length }
        e.setSpan(RLink(url.trim(), pal.accent), from, to, FLAGS_TEXT)
    }

    /** The paragraphs a selection touches, as (start, end) pairs. */
    fun paragraphs(e: Spanned, a: Int, b: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var ps = paraStart(e, a)
        val last = if (b > a && b > 0 && e[b - 1] == '\n') b - 1 else b
        while (true) {
            val pe = paraEnd(e, ps)
            out += ps to pe
            if (pe >= e.length || pe > last) break
            ps = pe
        }
        return out
    }

    private fun clearKind(e: Editable, ps: Int, pe: Int) {
        for (c in paraClasses) for (s in e.getSpans(ps, pe, c)) e.removeSpan(s)
    }

    /** Applies [make] to each selected paragraph, or removes that format when all of them already have it. */
    fun setParagraph(e: Editable, a: Int, b: Int, same: (Any?) -> Boolean, make: (Any?) -> Any) {
        val ps = paragraphs(e, a, b).filter { (s, en) -> kindSpan(e, s, en) !is RTable }
        val all = ps.isNotEmpty() && ps.all { (s, en) -> same(kindSpan(e, s, en)) }
        for ((s, en) in ps) {
            val old = kindSpan(e, s, en)
            clearKind(e, s, en)
            if (!all) e.setSpan(make(old), s, en, FLAGS_PARA)
        }
    }

    fun setHeading(e: Editable, a: Int, b: Int, level: Int) =
        setParagraph(e, a, b, { it is RHeading && it.level == level }) { RHeading(level) }

    fun setList(e: Editable, a: Int, b: Int, kind: Markdown.Kind, pal: Palette, density: Float) =
        setParagraph(e, a, b, { it is RList && it.kind == kind }) { old ->
            RList(kind, (old as? RList)?.level ?: 0, false, pal.accent, pal.onAccent, pal.secondary, density)
        }

    fun setQuote(e: Editable, a: Int, b: Int, pal: Palette, density: Float) =
        setParagraph(e, a, b, { it is RQuote }) { RQuote(pal.secondary, density) }

    fun setCode(e: Editable, a: Int, b: Int, pal: Palette) =
        setParagraph(e, a, b, { it is RCode }) { RCode("", (pal.secondary and 0xFFFFFF) or 0x26000000) }

    /** Indent (+1) or outdent (-1) the list items in the selection. */
    fun shift(e: Editable, a: Int, b: Int, by: Int) {
        for ((s, en) in paragraphs(e, a, b)) {
            val k = kindSpan(e, s, en) as? RList ?: continue
            e.removeSpan(k)
            e.setSpan(k.with(level = (k.level + by).coerceIn(0, 3)), s, en, FLAGS_PARA)
        }
    }

    /** Taps on a task's checkbox. Returns true if the line at [offset] was a task and was toggled. */
    fun toggleTaskAt(e: Editable, offset: Int): Boolean {
        val ps = paraStart(e, offset)
        val pe = paraEnd(e, ps)
        val k = kindSpan(e, ps, pe) as? RList ?: return false
        if (k.kind != Markdown.Kind.TASK) return false
        e.removeSpan(k)
        e.setSpan(k.with(checked = !k.checked), ps, pe, FLAGS_PARA)
        return true
    }

    /** Is the paragraph at [offset] a task, and how far from the text edge does its checkbox reach (px)? */
    fun taskMarginAt(e: Spanned, offset: Int): Int? {
        val ps = paraStart(e, offset)
        val k = kindSpan(e, ps, paraEnd(e, ps)) as? RList ?: return null
        return if (k.kind == Markdown.Kind.TASK) k.getLeadingMargin(true) else null
    }

    /**
     * Backspace at the very start of a formatted paragraph removes the format (one list level first)
     * instead of joining it to the line above. Returns true if it handled the key.
     */
    fun backspaceAtStart(e: Editable, cursor: Int): Boolean {
        val ps = paraStart(e, cursor)
        if (cursor != ps) return false
        val pe = paraEnd(e, ps)
        val k = kindSpan(e, ps, pe) ?: return false
        e.removeSpan(k)
        if (k is RList && k.level > 0) e.setSpan(k.with(level = k.level - 1), ps, pe, FLAGS_PARA)
        return true
    }

    /** Parses markdown and inserts it at the selection. The first line joins the current line as plain inline text. */
    fun insertMarkdown(e: Editable, a: Int, b: Int, md: String, pal: Palette, density: Float, tableWidth: () -> Int = { 0 }) {
        val parsed = fromMarkdown(md, pal, density, tableWidth)
        val joinFirst = a > paraStart(e, a)                                 // pasting mid-line: the first line keeps the line's own format
        if (joinFirst) {
            val firstEnd = paraEnd(parsed, 0)
            for (c in paraClasses) for (s in parsed.getSpans(0, firstEnd, c)) parsed.removeSpan(s)
        }
        e.replace(a, b, parsed)
    }
}
