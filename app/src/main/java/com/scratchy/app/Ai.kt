package com.scratchy.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** AI settings, kept in the app's private storage. The key is typed in by the user; it is never in the code. */
object AiSettings {
    const val DEFAULT_MODEL = "deepseek/deepseek-v4.1-flash-1017"
    private fun p(ctx: Context) = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)
    fun apiKey(ctx: Context) = p(ctx).getString("key", "").orEmpty().trim()
    fun textModel(ctx: Context) = p(ctx).getString("textModel", DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
    fun imageModel(ctx: Context) = p(ctx).getString("imageModel", "").orEmpty().ifBlank { textModel(ctx) }
    /** Offer a category for a note you are writing (a quiet chip, never a popup). */
    fun suggestCategories(ctx: Context) = p(ctx).getBoolean("suggestCategories", true)
    fun setSuggestCategories(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("suggestCategories", on).apply()
    fun set(ctx: Context, name: String, value: String) = p(ctx).edit().putString(name, value.trim()).apply()
    fun raw(ctx: Context, name: String) = p(ctx).getString(name, "").orEmpty()
}

/** Title suggestions through OpenRouter's chat API. Called only when the user taps the sparkle button. */
object Ai {
    private const val URL_CHAT = "https://openrouter.ai/api/v1/chat/completions"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun configured(ctx: Context) = AiSettings.apiKey(ctx).isNotEmpty()

    /** [done] runs on the main thread with (titles, null) or (null, error message). */
    fun suggestTitles(ctx: Context, body: String, image: File?, done: (List<String>?, String?) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val result = runCatching { requestTitles(app, body, image) }
            main.post {
                result.fold(
                    { done(it, null) },
                    { done(null, it.message ?: "Something went wrong") }
                )
            }
        }
    }

    /**
     * Picks a category for each note: an existing one when it fits, otherwise a new short name (a note
     * that fits nothing gets a category of its own). [done] runs on the main thread with (index to name, null)
     * or (null, error message). Indexes refer to [texts].
     */
    fun categorize(ctx: Context, texts: List<String>, existing: List<String>, done: (Map<Int, String>?, String?) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val result = runCatching { requestCategories(app, texts, existing) }
            main.post {
                result.fold({ done(it, null) }, { done(null, it.message ?: "Something went wrong") })
            }
        }
    }

    private fun requestCategories(ctx: Context, texts: List<String>, existing: List<String>): Map<Int, String> {
        val prompt = "You file notes into categories. Existing categories: " +
            (if (existing.isEmpty()) "none yet" else existing.joinToString(", ") { "\"$it\"" }) + ". " +
            "For each numbered note below choose the best existing category. If no existing category fits, " +
            "make a new one: a short, general name of 1 to 2 words (like \"Work\", \"Shopping\", \"Ideas\", " +
            "\"Health\"), never a sentence. A note that is unlike the others still gets a category of its own " +
            "with a fitting new name. Reuse a category for similar notes instead of making near-duplicates. " +
            "Write new names in the same language as the note. " +
            "Reply with only a JSON object that maps each note number to its category, like {\"1\":\"Work\"}."
        val body = texts.mapIndexed { i, t -> "[${i + 1}] ${t.take(300).replace('\n', ' ')}" }.joinToString("\n")
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "$prompt\n\nNotes:\n$body"))
        val reply = chat(ctx, AiSettings.textModel(ctx), messages, 2000)
        val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
        if (start !in 0 until end) error("No categories came back")
        val o = JSONObject(reply.substring(start, end + 1))
        val out = HashMap<Int, String>()
        o.keys().forEach { k -> k.trim().toIntOrNull()?.let { out[it - 1] = o.optString(k) } }
        if (out.isEmpty()) error("No categories came back")
        return out
    }

    /**
     * Adds markdown structure to [md] without touching its words. [done] runs on the main thread with (markdown, null) or
     * (null, message). The answer is only accepted when it has exactly the same words in the same order.
     */
    fun formatMarkdown(ctx: Context, md: String, done: (String?, String?) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val result = runCatching { requestFormat(app, md) }
            main.post { result.fold({ done(it, null) }, { done(null, it.message ?: "Something went wrong") }) }
        }
    }

    private fun requestFormat(ctx: Context, md: String): String {
        var extra = ""
        var diff: Pair<String, String>? = null
        repeat(2) {
            val prompt = "You are a formatter. Turn the text below into well-structured Markdown WITHOUT changing its words.\n" +
                "Rules:\n" +
                "1. Keep every word, number, name and symbol exactly as written, in the same order and the same language. " +
                "Never add, remove, translate, correct, summarize or reorder anything. Do not fix typos.\n" +
                "2. Only add Markdown structure: # and ## headings for titles and section names, - bullet lists, 1. numbered lists for steps, " +
                "- [ ] for to-do items (and - [x] for ones clearly done), **bold** for key labels, `code` for commands and code, > for quotes, " +
                "and a Markdown table when the text is clearly tabular.\n" +
                "3. Keep any Markdown that is already there.\n" +
                "4. Do not add any new text: no title, no intro, no notes.\n" +
                "5. Reply with only the Markdown, with no code fence around it.\n$extra\nText:\n$md"
            val reply = chat(ctx, AiSettings.textModel(ctx), JSONArray().put(JSONObject().put("role", "user").put("content", prompt)), 4000, 0.1)
            val out = reply.trim().removePrefix("```markdown").removePrefix("```md").removePrefix("```").removeSuffix("```").trim('\n')
            diff = Markdown.firstDifference(md, out)
            android.util.Log.i("ScratchyAI", "attempt reply=${reply.length} chars, differs=${diff != null}")
            if (out.isNotBlank() && diff == null) return out
            extra = "Your last answer changed, added or dropped some words. This time keep every single word exactly as written.\n"
        }
        error("The AI changed the text (“${diff?.first}” became “${diff?.second}”), so your note was left as it was.")
    }

    /** Sends a tiny message to check the key and model. */
    fun ping(ctx: Context, done: (String) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val msg = runCatching {
                val reply = chat(app, AiSettings.textModel(app), JSONArray().put(
                    JSONObject().put("role", "user").put("content", "Reply with the single word: ok")), 20)
                "Works. Model said: ${reply.trim().take(40)}"
            }.getOrElse { it.message ?: "Failed" }
            main.post { done(msg) }
        }
    }

    private fun requestTitles(ctx: Context, body: String, image: File?): List<String> {
        val prompt = "You name notes. Give 3 title options for this note, from shortest to most detailed: " +
            "(1) 3 to 5 words, (2) 6 to 9 words, (3) 10 to 16 words that capture the main subject plus the key " +
            "specifics in the note (who, what, numbers, dates, places, decisions). Be concrete, never generic: " +
            "\"Meeting notes\" is too vague. No quotes and no trailing punctuation. Write in the same language " +
            "as the note text; for example Arabic text gets Arabic titles. If an image is attached, use what " +
            "it shows, including any visible text, the subject and the setting. " +
            "Reply with only a JSON array of 3 strings."
        val text = if (body.isBlank()) prompt else "$prompt\n\nNote text:\n${body.take(4000)}"
        val content: Any = if (image != null && image.exists()) {
            val mime = if (image.name.endsWith(".png")) "image/png" else "image/jpeg"
            val b64 = Base64.encodeToString(image.readBytes(), Base64.NO_WRAP)
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", text))
                .put(JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:$mime;base64,$b64")))
        } else text
        val model = if (image != null) AiSettings.imageModel(ctx) else AiSettings.textModel(ctx)
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val reply = chat(ctx, model, messages, 1500)
        val titles = parseTitles(reply)
        if (titles.isEmpty()) error("No suggestions came back")
        return titles.take(3)
    }

    private fun chat(ctx: Context, model: String, messages: JSONArray, maxTokens: Int, temperature: Double = 0.7): String {
        val key = AiSettings.apiKey(ctx)
        if (key.isEmpty()) error("Add your OpenRouter key in Settings")
        val payload = JSONObject().put("model", model).put("messages", messages)
            .put("max_tokens", maxTokens).put("temperature", temperature)
            // Thinking models (Qwen3, DeepSeek R1...) can spend the whole budget reasoning and return no
            // answer. Titles don't need that, so ask for no reasoning; models without it ignore this.
            .put("reasoning", JSONObject().put("enabled", false))
        val conn = (URL(URL_CHAT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000; readTimeout = 30000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Title", "Scratchy")
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                error(
                    when {
                        code == 401 -> "The key was rejected. Check it in Settings."
                        code == 402 -> "Out of credits on OpenRouter."
                        code == 429 -> "Too many requests. Try again in a moment."
                        msg != null -> msg.take(140)
                        else -> "HTTP $code"
                    }
                )
            }
            val choice = JSONObject(text).getJSONArray("choices").getJSONObject(0)
            val content = contentText(choice.optJSONObject("message"))
            if (content.isBlank()) {
                error(
                    if (choice.optString("finish_reason") == "length")
                        "The model ran out of tokens while thinking. Pick a non-thinking model in Settings."
                    else "The model sent an empty reply. Try another model in Settings."
                )
            }
            return content
        } finally {
            conn.disconnect()
        }
    }

    /** The reply text. JSON null (thinking models) and content made of parts are both handled. */
    private fun contentText(message: JSONObject?): String {
        val c = message?.opt("content") ?: return ""
        return when (c) {
            JSONObject.NULL -> ""
            is String -> c
            is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text").orEmpty() }
            else -> ""
        }
    }

    /** Accepts a JSON array, or falls back to one title per line. */
    private fun parseTitles(reply: String): List<String> {
        val start = reply.indexOf('[')
        val end = reply.lastIndexOf(']')
        val fromJson = if (start in 0 until end) runCatching {
            val a = JSONArray(reply.substring(start, end + 1))
            (0 until a.length()).map { a.getString(it) }
        }.getOrNull() else null
        val raw = fromJson ?: reply.lines()
        return raw.map { it.trim().trimStart('-', '*', '•', ' ').trim('"', '\'', ' ', '.', ',') }
            .filter { it.isNotEmpty() && it.length <= 140 && !it.equals("null", ignoreCase = true) }
            .distinct()
    }
}
