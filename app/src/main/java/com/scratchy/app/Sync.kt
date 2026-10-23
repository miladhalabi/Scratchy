package com.scratchy.app

import android.app.PendingIntent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Syncs notes through the hidden app-data folder of the user's Google Drive.
 *
 * Everything that leaves the phone is encrypted first (AES-256-GCM) with a key derived from the vault
 * password, so Google only ever stores unreadable files. The key's salt lives in Drive next to the data,
 * which lets another device derive the same key from the same password.
 */
object Sync {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val ITERATIONS = 120_000
    private const val NOTES_FILE = "notes.enc"          // old single-file layout, only read during migration
    private const val NOTE_PREFIX = "n_"
    private const val NOTE_SUFFIX = ".enc"
    private const val META_FILE = "meta.json"
    private const val IMG_PREFIX = "img_"

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** Set by the notes list while it is on screen, so it can redraw after a sync changed something. */
    @Volatile var onChanged: (() -> Unit)? = null
    /** Same, for the floating card while it is open. */
    @Volatile var onChangedCard: (() -> Unit)? = null

    /** Same, for the full-screen note editor while it is open. */
    @Volatile var onChangedNote: (() -> Unit)? = null

    /** What a sync attempt came to. [consent] is set when Google needs the user to approve access first. */
    class Outcome(val message: String, val ok: Boolean = false, val consent: PendingIntent? = null)

    private class NeedsConsent(val intent: PendingIntent) : Exception()
    private class WrongPassword : Exception()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)

    fun connected(ctx: Context) = prefs(ctx).contains("key")
    fun status(ctx: Context): String = prefs(ctx).getString("status", "") ?: ""

    fun disconnect(ctx: Context) { prefs(ctx).edit().clear().apply(); SyncState.clear(ctx); Live.stop(); unschedule(ctx) }

    /** Name of this account's room on the live relay: a hash of the sync key, so it can't be guessed. */
    fun liveId(ctx: Context): String? = runCatching {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(("scratchy-live-v1".toByteArray() + savedKey(ctx)))
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun setStatus(ctx: Context, s: String) = prefs(ctx).edit().putString("status", s).apply()

    // ---- when to sync ----

    private var pending: Runnable? = null

    private val fastExec = Executors.newSingleThreadExecutor()
    private val fastIds = LinkedHashSet<String>()
    private var fastJob: Runnable? = null

    /**
     * A note changed on this phone. Two things follow:
     *  1. almost at once (0.4 s after the last change) the encrypted note goes straight to the other devices
     *     through the relay, so it shows up there in about a second;
     *  2. a moment later (1.5 s) a normal Drive sync makes it permanent and picks up anything else.
     */
    fun noteChanged(ctx: Context, id: String? = null) {
        if (!connected(ctx)) return
        val app = ctx.applicationContext
        main.post {
            if (id != null) {
                fastIds += id
                fastJob?.let(main::removeCallbacks)
                fastJob = Runnable {
                    val ids = fastIds.toList(); fastIds.clear()
                    fastExec.execute { relayNow(app, ids) }
                }.also { main.postDelayed(it, 400) }
            }
            pending?.let(main::removeCallbacks)
            pending = Runnable { run(app, null) {} }.also { main.postDelayed(it, 1500) }
        }
    }

    /** Sends the current version of each note (or its deletion) to the other devices. Failures don't matter: Drive follows. */
    private fun relayNow(app: Context, ids: List<String>) {
        try {
            val key = savedKey(app)
            val notes = NoteStore.all(app).associateBy { it.id }
            val tombs = NoteStore.tombstones(app)
            val out = ids.mapNotNull { id ->
                val side = notes[id]?.let { Merge.Side(it) } ?: tombs[id]?.let { Merge.Side(null, it) } ?: return@mapNotNull null
                val sealed = seal(key, NoteJson.payload(side, id).toString().toByteArray())
                if (sealed.size <= 200_000) b64(sealed) else null      // big notes go through Drive instead
            }
            if (out.isNotEmpty()) Live.relay(app, out)
        } catch (_: Exception) { }
    }

    /** Sync when the app opens, but not more than once a minute. */
    fun syncIfStale(ctx: Context) {
        if (!connected(ctx)) return
        val last = prefs(ctx).getLong("last", 0L)
        if (System.currentTimeMillis() - last > 60_000) run(ctx.applicationContext, null) {}
    }

    // ---- entry point ----

    /** What the sync is doing, for the status line in the app. */
    enum class State { IDLE, SYNCING, OFFLINE, ATTENTION }

    @Volatile var state = State.IDLE
        private set

    /** Called on the main thread whenever [state] or the status text changed. */
    @Volatile var onStatus: (() -> Unit)? = null

    private fun setState(ctx: Context, s: State, text: String? = null) {
        state = s
        if (text != null) setStatus(ctx, text)
        main.post { onStatus?.invoke() }
    }

    /** One short line: "Synced 17:42", "Syncing…", "Offline", ... Empty when sync isn't connected. */
    fun statusLine(ctx: Context): String = when {
        !connected(ctx) -> ""
        state == State.SYNCING -> "Syncing…"
        else -> status(ctx).ifEmpty { "Connected" }
    }

    /**
     * Runs one sync in the background. With [password] it first connects (derives the key and checks it
     * against the other devices). The result arrives on the main thread.
     */
    fun run(ctx: Context, password: String?, done: (Outcome) -> Unit) {
        val app = ctx.applicationContext
        exec.execute {
            val out = runBlocking(app, password)
            main.post { done(out) }
        }
    }

    /** Same as [run] but on the calling thread (for the background worker). */
    fun runBlocking(app: Context, password: String?): Outcome = synchronized(lock) {
        setState(app, State.SYNCING)
        try {
            var token = token(app)
            if (password != null) connect(app, token, password)
            val r = try { syncNow(app, token) } catch (e: IOException) {
                if (e.message?.startsWith("Drive 401") != true) throw e
                cachedToken = null                       // token expired early: get a new one and retry once
                token = token(app)
                syncNow(app, token)
            }
            val changed = r.first
            prefs(app).edit().putLong("last", System.currentTimeMillis()).putBoolean("needs_pw", false).apply()
            val msg = "Synced " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            setState(app, State.IDLE, msg)
            if (changed) main.post { onChanged?.invoke(); onChangedCard?.invoke(); onChangedNote?.invoke() }
            if (lastConflicts > 0) {
                val n = lastConflicts; lastConflicts = 0
                main.post {
                    android.widget.Toast.makeText(app,
                        if (n == 1) "Two edits clashed. The other one was kept as a note ending in (conflict)."
                        else "$n notes had clashing edits. Copies ending in (conflict) were kept.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
            Outcome(msg, ok = true)
        } catch (e: NeedsConsent) {
            setState(app, State.ATTENTION, "Needs your permission. Open Settings and tap Sync now.")
            Outcome("Google needs your permission", consent = e.intent)
        } catch (e: WrongPassword) {
            if (password == null) prefs(app).edit().putBoolean("needs_pw", true).apply()
            setState(app, State.ATTENTION, "Vault password changed on another device. Enter the new one in Settings.")
            Outcome("That isn't the password used on your other device")
        } catch (e: java.net.UnknownHostException) {
            setState(app, State.OFFLINE, "Offline. Will sync when you're back online.")
            Outcome("Offline")
        } catch (e: java.net.SocketException) {
            setState(app, State.OFFLINE, "Offline. Will sync when you're back online.")
            Outcome("Offline")
        } catch (e: java.net.SocketTimeoutException) {
            setState(app, State.OFFLINE, "Offline. Will sync when you're back online.")
            Outcome("Offline")
        } catch (e: Exception) {
            val m = "Sync failed: " + (e.message ?: e.javaClass.simpleName).take(120)
            setState(app, State.ATTENTION, m)
            Outcome(m)
        }
    }

    // ---- instant changes from the relay ----

    /**
     * Another device handed us an encrypted note change directly. Merge it in right away; a normal sync
     * follows when anything needs publishing or pictures are still to be fetched.
     */
    fun applyLive(ctx: Context, data: String) {
        val app = ctx.applicationContext
        exec.execute {
            var followUp = false
            var applied = false
            synchronized(lock) {
                try {
                    val plain = open(savedKey(app), unb64(data)) ?: return@synchronized
                    val o = JSONObject(String(plain))
                    val id = o.getString("id")
                    val theirs = NoteJson.side(o)
                    val local = NoteStore.all(app).firstOrNull { it.id == id }
                    val ours = local?.let { Merge.Side(it) } ?: NoteStore.tombstones(app)[id]?.let { Merge.Side(null, it) }
                    if (Merge.staleRelay(local, theirs.note)) return@synchronized      // an older change arriving late
                    val res = Merge.resolve(SyncState.load(app)[id]?.base, ours, theirs) ?: return@synchronized
                    if (res.conflictCopy != null) { followUp = true; return@synchronized }   // let the full sync handle clashes
                    val seen = mapOf(id to (local?.rev ?: -1L))
                    val skipped = if (res.note != null) {
                        if (local != null && local.rev == res.note.rev && local.sameContent(res.note)) return@synchronized
                        NoteStore.applySynced(app, mapOf(id to res.note), emptyMap(), seen)
                    } else NoteStore.applySynced(app, emptyMap(), mapOf(id to res.deletedAt), seen)
                    applied = skipped.isEmpty()
                    val merged = res.note
                    // We now hold exactly what Drive has, so that is the new common ground for the next edit.
                    // (Without this, the next edit is compared with an outdated base and looks like a clash.)
                    val adopted = skipped.isEmpty() && when {
                        merged != null -> theirs.note != null && merged.sameContent(theirs.note)
                        else -> theirs.note == null
                    }
                    if (adopted) {
                        val st = SyncState.load(app)
                        st[id] = SyncState.Entry(theirs.note, "", theirs.deletedAt)
                        SyncState.save(app, st)
                    }
                    if (skipped.isNotEmpty() || (merged != null && theirs.note != null && !merged.sameContent(theirs.note))) followUp = true
                    if (merged != null && merged.images.any { !Attachments.file(app, it).exists() }) followUp = true
                } catch (e: Exception) { followUp = true }
            }
            if (applied) main.post { onChanged?.invoke(); onChangedCard?.invoke(); onChangedNote?.invoke() }
            if (followUp) run(app, null) {}
        }
    }

    // ---- changing the vault password ----

    /** True when another device changed the vault password, so this one must enter the new one to continue. */
    fun needsNewPassword(ctx: Context) = prefs(ctx).getBoolean("needs_pw", false)

    /**
     * Re-encrypts everything in Drive with a key made from [newPassword]. Safe to repeat if it was cut short:
     * files already done are recognised and skipped, and the new salt is remembered until the very end.
     */
    fun rekey(ctx: Context, newPassword: String, done: (Outcome) -> Unit) {
        val app = ctx.applicationContext
        exec.execute {
            val out = synchronized(lock) {
                try {
                    setState(app, State.SYNCING, "Changing password…")
                    val token = token(app)
                    val oldKey = savedKey(app)
                    val files = Drive.list(token)
                    val metaId = files[META_FILE]?.id ?: throw IOException("Drive has no sync info yet. Sync once first.")
                    val p = prefs(app)
                    val salt = p.getString("pending_salt", null)?.let(::unb64)
                        ?: ByteArray(16).also { SecureRandom().nextBytes(it); p.edit().putString("pending_salt", b64(it)).apply() }
                    val newKey = derive(newPassword, salt)
                    for ((name, r) in files) {
                        if (!(name.startsWith(NOTE_PREFIX) && name.endsWith(NOTE_SUFFIX)) && !name.startsWith(IMG_PREFIX)) continue
                        val blob = Drive.download(token, r.id)
                        val plain = open(oldKey, blob)
                        if (plain == null) {
                            if (open(newKey, blob) != null) continue            // already done in an earlier attempt
                            throw IOException("Couldn't read $name")
                        }
                        Drive.upload(token, r.id, name, seal(newKey, plain))
                    }
                    val meta = JSONObject(String(Drive.download(token, metaId)))
                    meta.put("salt", b64(salt)).put("check", b64(seal(newKey, "scratchy".toByteArray())))
                        .put("epoch", meta.optInt("epoch") + 1)
                    Drive.upload(token, metaId, META_FILE, meta.toString().toByteArray())
                    files[NOTES_FILE]?.let { Drive.delete(token, it.id) }          // old backup, still readable with the old password
                    p.edit().putString("key", Vault.encrypt(b64(newKey))).remove("pending_salt").apply()
                    SyncState.save(app, SyncState.load(app).mapValues { SyncState.Entry(it.value.base, "", it.value.tomb) })
                    Live.restart(app)
                    setState(app, State.IDLE, "Password changed. Enter it on your other devices.")
                    Outcome("Password changed", ok = true)
                } catch (e: NeedsConsent) {
                    setState(app, State.ATTENTION, "Needs your permission. Open Settings and tap Sync now.")
                    Outcome("Google needs your permission", consent = e.intent)
                } catch (e: Exception) {
                    val m = "Couldn't change the password: " + (e.message ?: e.javaClass.simpleName).take(100)
                    setState(app, State.ATTENTION, m)
                    Outcome(m)
                }
            }
            main.post { done(out) }
        }
    }

    // ---- background schedule ----

    /** Makes sure Android runs a sync about every 15 minutes while it has internet, even if the app is closed. */
    fun schedule(ctx: Context) {
        if (!connected(ctx)) return
        val req = androidx.work.PeriodicWorkRequestBuilder<SyncWorker>(15, java.util.concurrent.TimeUnit.MINUTES)
            .setConstraints(androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .build()
        androidx.work.WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniquePeriodicWork("scratchy-sync", androidx.work.ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun unschedule(ctx: Context) =
        androidx.work.WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork("scratchy-sync")

    // ---- Google sign-in ----

    private var cachedToken: String? = null
    private var cachedAt = 0L

    /** Google access tokens last about an hour; reuse one instead of asking every sync. */
    private fun token(ctx: Context): String {
        cachedToken?.let { if (System.currentTimeMillis() - cachedAt < 45 * 60_000L) return it }
        return freshToken(ctx).also { cachedToken = it; cachedAt = System.currentTimeMillis() }
    }

    private fun freshToken(ctx: Context): String {
        val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
        val r = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(req))
        if (r.hasResolution()) throw NeedsConsent(r.pendingIntent!!)
        return r.accessToken ?: throw IOException("Google gave no access token")
    }

    // ---- key ----

    private fun derive(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)).encoded

    private fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
        return iv + c.doFinal(plain)
    }

    private fun open(key: ByteArray, blob: ByteArray): ByteArray? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12)) }
        c.doFinal(blob, 12, blob.size - 12)
    }.getOrNull()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun savedKey(ctx: Context): ByteArray =
        prefs(ctx).getString("key", null)?.let { Vault.decrypt(it) }?.let(::unb64)
            ?: throw IOException("Not connected")

    /** Derives the key from the vault password (using the salt already in Drive, or a new one) and stores it. */
    private fun connect(ctx: Context, token: String, password: String) {
        val files = Drive.list(token)
        val metaId = files[META_FILE]?.id
        val key: ByteArray
        if (metaId != null) {
            val meta = JSONObject(String(Drive.download(token, metaId)))
            key = derive(password, unb64(meta.getString("salt")))
            if (open(key, unb64(meta.getString("check")))?.let { String(it) } != "scratchy") throw WrongPassword()
        } else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            key = derive(password, salt)
            val meta = JSONObject().put("v", 1).put("layout", 2).put("salt", b64(salt))
                .put("check", b64(seal(key, "scratchy".toByteArray())))
            Drive.upload(token, null, META_FILE, meta.toString().toByteArray())
            prefs(ctx).edit().putBoolean("layout2", true).apply()          // brand-new Drive: nothing to migrate
        }
        prefs(ctx).edit().putString("key", Vault.encrypt(b64(key))).apply()
        schedule(ctx)
    }

    // ---- the sync itself ----

    private class Remote(val id: String, val version: String)

    private fun noteFile(id: String) = "$NOTE_PREFIX$id$NOTE_SUFFIX"

    /** Set by the last sync: how many "(conflict)" copies it had to make. */
    @Volatile var lastConflicts = 0

    /**
     * One pass: every note is merged on its own against the version both sides last agreed on, so edits to
     * different notes never collide and edits to the same note are merged line by line.
     * Returns (this phone's notes changed, something was uploaded).
     */
    private fun syncNow(ctx: Context, token: String): Pair<Boolean, Boolean> {
        val key = savedKey(ctx)
        val files = HashMap(Drive.list(token))
        val listedAt = System.currentTimeMillis()
        val state = SyncState.load(ctx)
        val localNotes = NoteStore.all(ctx).associateBy { it.id }
        val localTombs = NoteStore.tombstones(ctx)
        var uploaded = false
        var conflicts = 0
        val relayed = ArrayList<String>()                       // note changes also sent straight to other devices
        var needDoorbell = false                                 // something went up that the relay doesn't carry

        // Notes written by the old single-file layout are read once, then everything moves to per-note files.
        val legacy = HashMap<String, Merge.Side>()
        var migrating = false
        if (!prefs(ctx).getBoolean("layout2", false)) {
            val metaId = files[META_FILE]?.id
            val layout2 = metaId != null && JSONObject(String(Drive.download(token, metaId))).optInt("layout") >= 2
            if (layout2) prefs(ctx).edit().putBoolean("layout2", true).apply()
            else {
                migrating = true
                backupLocal(ctx)
                files[NOTES_FILE]?.let { f ->
                    val plain = open(key, Drive.download(token, f.id)) ?: throw WrongPassword()
                    val o = JSONObject(String(plain))
                    o.getJSONArray("notes").let { a -> for (i in 0 until a.length()) NoteJson.from(a.getJSONObject(i)).let { legacy[it.id] = Merge.Side(it) } }
                    o.optJSONObject("deleted")?.let { d -> d.keys().forEach { legacy[it] = Merge.Side(null, d.getLong(it)) } }
                }
            }
        }

        // Pictures first, so a note never arrives before the images it shows.
        val localImages = localNotes.values.flatMap { it.images }.toSet()
        for (name in localImages) {
            val f = Attachments.file(ctx, name)
            if (f.exists() && files[IMG_PREFIX + name] == null) {
                files[IMG_PREFIX + name] = Drive.upload(token, null, IMG_PREFIX + name, seal(key, f.readBytes()))
                uploaded = true; needDoorbell = true
            }
        }

        val ids = HashSet<String>().apply {
            addAll(localNotes.keys); addAll(localTombs.keys); addAll(legacy.keys)
            files.keys.filter { it.startsWith(NOTE_PREFIX) && it.endsWith(NOTE_SUFFIX) }
                .forEach { add(it.removePrefix(NOTE_PREFIX).removeSuffix(NOTE_SUFFIX)) }
        }

        val puts = HashMap<String, Note>()
        val removes = HashMap<String, Long>()
        val seenRev = HashMap<String, Long>()
        val newState = HashMap(state)
        val oldEntries = HashMap(state)
        var incomplete = false                       // some note could not be settled this time

        for (id in ids) {
            val name = noteFile(id)
            var attempt = 0
            var settled = false
            while (attempt++ < 3) {
                val ours: Merge.Side? = localNotes[id]?.let { Merge.Side(it) } ?: localTombs[id]?.let { Merge.Side(null, it) }
                val entry = state[id]
                val remote = files[name]
                val unchanged = remote != null && entry != null && entry.ver == remote.version
                val localSame = entry != null && ours != null && run {
                    val n = ours.note
                    if (n != null) entry.base != null && entry.base.rev == n.rev && entry.base.sameContent(n)
                    else entry.base == null && entry.tomb == ours.deletedAt
                }
                if (unchanged && localSame) { settled = true; break }               // nothing to do for this note

                val theirs: Merge.Side? = when {
                    remote == null -> legacy[id]
                    unchanged -> entry!!.side()
                    else -> {
                        val plain = open(key, Drive.download(token, remote.id)) ?: throw WrongPassword()
                        NoteJson.side(JSONObject(String(plain)))
                    }
                }
                val res = Merge.resolve(entry?.base, ours, theirs)
                if (res == null) { settled = true; break }
                val resSide = Merge.Side(res.note, res.deletedAt)

                val same = theirs != null && remote != null && when {
                    res.note != null -> theirs.note != null && theirs.note.rev == res.note.rev && theirs.note.sameContent(res.note)
                    else -> theirs.note == null
                }
                var newVersion = remote?.version ?: ""
                if (!same) {
                    // Safe write: only replace the file if nobody changed it since we read it.
                    if (remote != null && System.currentTimeMillis() - listedAt > 2500 &&
                        Drive.version(token, remote.id) != remote.version) {
                        files[name] = Remote(remote.id, Drive.version(token, remote.id))
                        continue                                                    // changed under us: merge again
                    }
                    val sealed = seal(key, NoteJson.payload(resSide, id).toString().toByteArray())
                    val up = Drive.upload(token, remote?.id, name, sealed)
                    files[name] = up; newVersion = up.version; uploaded = true
                    if (sealed.size <= 200_000) relayed += b64(sealed) else needDoorbell = true
                }

                val oursRev = ours?.note?.rev ?: -1L
                seenRev[id] = oursRev
                if (res.note != null) {
                    if (ours?.note == null || ours.note.rev != res.note.rev || !ours.note.sameContent(res.note)) puts[id] = res.note
                } else if (ours?.note != null) removes[id] = res.deletedAt
                else if (ours == null || ours.deletedAt != res.deletedAt) removes[id] = res.deletedAt
                newState[id] = SyncState.Entry(res.note, newVersion, res.deletedAt)

                res.conflictCopy?.let { copy ->
                    conflicts++
                    val cname = noteFile(copy.id)
                    val sealedCopy = seal(key, NoteJson.payload(Merge.Side(copy), copy.id).toString().toByteArray())
                    val up = Drive.upload(token, null, cname, sealedCopy)
                    files[cname] = up; uploaded = true
                    if (sealedCopy.size <= 200_000) relayed += b64(sealedCopy) else needDoorbell = true
                    puts[copy.id] = copy; seenRev[copy.id] = -1L
                    newState[copy.id] = SyncState.Entry(copy, up.version, 0L)
                }
                settled = true
                break
            }
            if (!settled) incomplete = true
        }

        if (relayed.isNotEmpty()) Live.relay(ctx, relayed)        // the change itself, instantly, to devices that are listening
        if (uploaded && (needDoorbell || relayed.isEmpty())) Live.tick(ctx)   // otherwise a plain "go look at Drive"

        // Apply to this phone. Notes edited meanwhile are skipped and merged on the next sync.
        val skipped = NoteStore.applySynced(ctx, puts, removes, seenRev)
        skipped.forEach { id ->
            val old = oldEntries[id]
            newState[id] = SyncState.Entry(old?.base, "", old?.tomb ?: 0L)      // forces a fresh look next time
        }
        SyncState.save(ctx, newState)
        val changedHere = (puts.keys + removes.keys).any { it !in skipped }
        lastConflicts = conflicts

        // Pictures: fetch missing ones, tidy ones nothing uses any more.
        val now = NoteStore.all(ctx)
        val wanted = now.flatMap { it.images }.toSet()
        run {
            for (name in wanted) {
                val remote = files[IMG_PREFIX + name]
                val f = Attachments.file(ctx, name)
                if (!f.exists() && remote != null) open(key, Drive.download(token, remote.id))?.let { f.writeBytes(it) }
            }
            // Only tidy when every note was settled, so no note's images are ever judged unused by mistake.
            if (skipped.isEmpty() && !incomplete) files.forEach { (n, r) ->
                if (n.startsWith(IMG_PREFIX) && n.removePrefix(IMG_PREFIX) !in wanted) Drive.delete(token, r.id)
            }
        }

        if (migrating) finishMigration(ctx, token, files)
        return changedHere to uploaded
    }

    /** Marks Drive as using per-note files. The old notes.enc is left in Drive untouched as a backup. */
    private fun finishMigration(ctx: Context, token: String, files: Map<String, Remote>) {
        val metaId = files[META_FILE]?.id ?: return
        val meta = JSONObject(String(Drive.download(token, metaId))).put("layout", 2)
        Drive.upload(token, metaId, META_FILE, meta.toString().toByteArray())
        prefs(ctx).edit().putBoolean("layout2", true).apply()
    }

    /** One copy of this phone's notes before the first migration, so nothing can be lost by it. */
    private fun backupLocal(ctx: Context) {
        if (prefs(ctx).getBoolean("backup_done", false)) return
        val dir = java.io.File(ctx.filesDir, "backup-" + System.currentTimeMillis()).apply { mkdirs() }
        listOf("notes.json", "deleted.json").forEach { n ->
            java.io.File(ctx.filesDir, n).takeIf { it.exists() }?.copyTo(java.io.File(dir, n), overwrite = true)
        }
        prefs(ctx).edit().putBoolean("backup_done", true).apply()
    }

    // ---- Google Drive, app-data folder ----

    private object Drive {
        private const val API = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"

        private fun call(
            token: String, method: String, url: String, body: ByteArray? = null,
            type: String? = null, override: String? = null,
        ): ByteArray {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 15000; c.readTimeout = 60000
            c.setRequestProperty("Authorization", "Bearer $token")
            override?.let { c.setRequestProperty("X-HTTP-Method-Override", it) }
            if (body != null) {
                c.doOutput = true
                type?.let { c.setRequestProperty("Content-Type", it) }
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code >= 400) {
                val msg = c.errorStream?.use { String(it.readBytes()) }.orEmpty()
                throw IOException("Drive $code ${msg.take(160)}")
            }
            return c.inputStream.use { it.readBytes() }
        }

        /** name -> (id, version) for everything in the app-data folder. */
        fun list(token: String): Map<String, Remote> {
            val out = LinkedHashMap<String, Remote>()
            var page: String? = null
            do {
                val url = "$API?spaces=appDataFolder&pageSize=1000&fields=nextPageToken,files(id,name,version)" +
                    (page?.let { "&pageToken=$it" } ?: "")
                val o = JSONObject(String(call(token, "GET", url)))
                o.optJSONArray("files")?.let { a ->
                    for (i in 0 until a.length()) a.getJSONObject(i).let { out[it.getString("name")] = Remote(it.getString("id"), it.optString("version")) }
                }
                page = o.optString("nextPageToken").ifEmpty { null }
            } while (page != null)
            return out
        }

        fun download(token: String, id: String) = call(token, "GET", "$API/$id?alt=media")

        /** The file's current version number, to detect that someone else changed it. */
        fun version(token: String, id: String): String =
            JSONObject(String(call(token, "GET", "$API/$id?fields=version"))).optString("version")

        /** Creates the file, or replaces its content when [id] is given. Returns its id and new version. */
        fun upload(token: String, id: String?, name: String, data: ByteArray): Remote {
            val answer: ByteArray
            if (id != null) {
                answer = call(token, "POST", "$UPLOAD/$id?uploadType=media&fields=id,version", data,
                    "application/octet-stream", override = "PATCH")
            } else {
                val boundary = "scratchy" + System.nanoTime()
                val meta = JSONObject().put("name", name).put("parents", JSONArray().put("appDataFolder"))
                val body = ByteArrayOutputStream().apply {
                    write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n".toByteArray())
                    write("--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                    write(data)
                    write("\r\n--$boundary--".toByteArray())
                }.toByteArray()
                answer = call(token, "POST", "$UPLOAD?uploadType=multipart&fields=id,version", body,
                    "multipart/related; boundary=$boundary")
            }
            return JSONObject(String(answer)).let { Remote(it.getString("id"), it.optString("version")) }
        }

        fun delete(token: String, id: String) { call(token, "DELETE", "$API/$id") }
    }
}
