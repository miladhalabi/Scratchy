package com.scratchy.app

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * The lock in front of secret notes: a password (stored only as a salted PBKDF2 hash), an optional
 * fingerprint, and the encryption of secret note text with a key that never leaves the Android Keystore.
 */
object Vault {
    private const val KEY_ALIAS = "scratchy_vault"
    private const val ITERATIONS = 120_000
    /** How long the vault stays open after you leave the secret screens. */
    private const val GRACE_MS = 30_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE)

    // ---- password ----

    fun isSetUp(ctx: Context) = prefs(ctx).contains("hash")

    private fun hash(pw: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pw.toCharArray(), salt, ITERATIONS, 256)).encoded

    fun setPassword(ctx: Context, pw: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(pw, salt), Base64.NO_WRAP))
            .commit()
    }

    fun checkPassword(ctx: Context, pw: String): Boolean {
        val p = prefs(ctx)
        val salt = p.getString("salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val want = p.getString("hash", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return MessageDigest.isEqual(want, hash(pw, salt))
    }

    // ---- fingerprint ----

    fun biometricAvailable(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return runCatching { canAuthenticate(ctx) }.getOrDefault(false)
    }

    private fun canAuthenticate(ctx: Context): Boolean {
        val m = ctx.getSystemService(BiometricManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 30)
            m.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        else @Suppress("DEPRECATION") (m.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)
    }

    fun biometricEnabled(ctx: Context) = prefs(ctx).getBoolean("bio", false) && biometricAvailable(ctx)
    fun setBiometric(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("bio", on).apply()

    /** Shows the system fingerprint sheet. [onFail] is called when it is cancelled or fails for good. */
    fun askFingerprint(activity: Activity, onOk: () -> Unit, onFail: () -> Unit) {
        if (Build.VERSION.SDK_INT < 28) { onFail(); return }
        val exec = activity.mainExecutor
        runCatching { askNow(activity, exec, onOk, onFail) }.onFailure { onFail() }
    }

    private fun askNow(activity: Activity, exec: java.util.concurrent.Executor, onOk: () -> Unit, onFail: () -> Unit) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("Secret notes")
            .setSubtitle("Unlock with your fingerprint")
            .setNegativeButton("Use password", exec) { _, _ -> onFail() }
            .build()
        prompt.authenticate(CancellationSignal(), exec, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onOk()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onFail()
        })
    }

    // ---- open / closed ----

    private var open = false
    private var leftAt = 0L

    /** True while the vault is unlocked (and was not left alone for longer than the grace period). */
    fun isOpen(): Boolean {
        if (open && leftAt != 0L && System.currentTimeMillis() - leftAt > GRACE_MS) open = false
        return open
    }

    fun unlock() { open = true; leftAt = 0L }
    fun lock() { open = false }
    /** Call when a secret screen is hidden; the vault locks if you stay away. */
    fun left() { if (open) leftAt = System.currentTimeMillis() }
    fun returned() { if (isOpen()) leftAt = 0L }

    // ---- encryption of secret note text ----

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Returns null when the text can't be read (for example the key was lost). */
    fun decrypt(blob: String): String? = runCatching {
        val all = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12)) }
        String(c.doFinal(all, 12, all.size - 12))
    }.getOrNull()
}
