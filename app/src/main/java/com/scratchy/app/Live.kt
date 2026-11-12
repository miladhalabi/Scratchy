package com.scratchy.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Real-time updates. Devices of one account keep a WebSocket open to a small Cloudflare relay, which hands each
 * sealed note change straight to the others (and rings "go look at Drive" for what it doesn't carry). It can't read
 * the notes and keeps none of them. The room name is a hash of the sync key.
 */
object Live {
    /** The relay everyone uses, unless they set their own in Settings → Sync → Advanced. */
    const val DEFAULT_RELAY = "https://scratchy-sync.halabimilad.workers.dev"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("live", Context.MODE_PRIVATE)

    /** The relay in use: the one set in Settings, else [DEFAULT_RELAY]. */
    fun relayUrl(ctx: Context): String = prefs(ctx).getString("relay", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_RELAY

    /** "https://host" (a relay sits at the root of its address, as the desktop requires), or null when it isn't one. */
    fun cleanRelay(text: String): String? {
        val host = text.trim().trimEnd('/').removePrefix("https://").takeIf { it != text.trim().trimEnd('/') } ?: return null
        return "https://$host".takeIf { host.isNotEmpty() && host.none { it == '/' || it.isWhitespace() || it == '?' || it == '#' } }
    }

    /**
     * Checks that [url] answers like a Scratchy relay (its WebSocket address wants an upgrade), then uses it and
     * reconnects. Null or blank goes back to [DEFAULT_RELAY]. [done] gets null on success, else what went wrong.
     */
    fun setRelay(ctx: Context, url: String?, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        if (url.isNullOrBlank()) {
            prefs(app).edit().remove("relay").apply(); restart(app); done(null); return
        }
        val clean = cleanRelay(url) ?: return done("Enter just the relay's address, like https://my-relay.example.workers.dev")
        val probe = Request.Builder().url("$clean/v1/${"0".repeat(64)}/ws").build()
        http.newCall(probe).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                main.post { done("Couldn't reach it: ${e.message ?: "no connection"}") }
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                val ok = response.code == 426
                response.close()
                main.post {
                    if (!ok) done("That address isn't a Scratchy relay (it answered ${response.code}).")
                    else { prefs(app).edit().putString("relay", clean).apply(); restart(app); done(null) }
                }
            }
        })
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val owners = HashSet<String>()
    private var socket: WebSocket? = null
    private var app: Context? = null
    private var retry = 0
    private var reconnect: Runnable? = null

    private fun device(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("device", null) ?: UUID.randomUUID().toString().also { p.edit().putString("device", it).apply() }
    }

    /** A screen or service that wants live updates. The connection stays open while anyone holds it. */
    fun acquire(ctx: Context, owner: String) = main.post {
        owners += owner
        app = ctx.applicationContext
        connect()
    }

    fun release(owner: String) = main.post {
        owners -= owner
        if (owners.isEmpty()) stop()
    }

    /** The room name changed (new key): drop the old connection and open one in the new room. */
    fun restart(ctx: Context) {
        stop()
        main.postDelayed({ app = ctx.applicationContext; connect() }, 400)
    }

    fun stop() = main.post {
        reconnect?.let(main::removeCallbacks); reconnect = null
        socket?.close(1000, null); socket = null
    }

    private fun connect() {
        val ctx = app ?: return
        if (socket != null || owners.isEmpty() || !Sync.connected(ctx)) return
        val id = Sync.liveId(ctx) ?: return
        val me = device(ctx)
        socket = http.newWebSocket(
            Request.Builder().url("${relayUrl(ctx).replaceFirst("https", "wss")}/v1/$id/ws").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    retry = 0
                    Sync.run(ctx, null) {}                         // catch up on anything missed while offline
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                    if (o.optString("from") == me) return
                    if (o.optString("type") == "note") Sync.applyLive(ctx, o.optString("data"))
                    else Sync.run(ctx, null) {}
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) { dropped(ws) }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { dropped(ws) }
            }
        )
    }

    private fun dropped(ws: WebSocket) = main.post {
        if (socket !== ws) return@post
        socket = null
        if (owners.isEmpty()) return@post
        val wait = minOf(60L, 3L shl minOf(retry++, 5))          // 3 s, 6 s, 12 s ... up to a minute
        reconnect = Runnable { connect() }.also { main.postDelayed(it, wait * 1000) }
    }

    /** Hand encrypted note changes straight to the other devices, so they needn't wait for Drive. */
    fun relay(ctx: Context, payloads: List<String>) {
        val id = Sync.liveId(ctx) ?: return
        for (p in payloads) {
            val req = Request.Builder().url("${relayUrl(ctx)}/v1/$id/relay")
                .header("x-device", device(ctx))
                .post(p.toRequestBody())
                .build()
            http.newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
                override fun onResponse(call: okhttp3.Call, response: Response) { response.close() }
            })
        }
    }

    /** Tell the other devices something changed in Drive. Fire and forget. */
    fun tick(ctx: Context) {
        val id = Sync.liveId(ctx) ?: return
        val req = Request.Builder().url("${relayUrl(ctx)}/v1/$id/tick")
            .header("x-device", device(ctx))
            .post(ByteArray(0).toRequestBody())
            .build()
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: Response) { response.close() }
        })
    }
}
