package com.mbafford.maskedemail

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private fun prefs(context: Context) = context.getSharedPreferences("state", Context.MODE_PRIVATE)

/** The Fastmail API token, kept encrypted with a key held in the Android Keystore. */
object TokenStore {
    private const val KEY_ALIAS = "fastmail-token"
    private const val PREF = "token"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }
            .generateKey()
    }

    fun save(context: Context, token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(token.toByteArray())
        val text = encode(cipher.iv) + ":" + encode(sealed)
        prefs(context).edit().putString(PREF, text).apply()
    }

    /** The saved token, or null if there is none (or it can no longer be decrypted). */
    fun load(context: Context): String? {
        val text = prefs(context).getString(PREF, null) ?: return null
        return try {
            val (iv, sealed) = text.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
                .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            cipher.doFinal(sealed).decodeToString()
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(PREF).apply()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
}

/** [id] is Fastmail's id for the address; empty for entries saved before the app recorded it. */
data class Entry(
    val email: String,
    val description: String,
    val createdAt: Long,
    val pending: Boolean,
    val id: String = "",
)

/** The addresses made with this app on this phone, newest first. */
object History {
    private const val PREF = "history"
    private const val LIMIT = 200

    fun load(context: Context): List<Entry> {
        val saved = JSONArray(prefs(context).getString(PREF, "[]"))
        return List(saved.length()) { i ->
            val o = saved.getJSONObject(i)
            Entry(o.getString("email"), o.optString("description"), o.getLong("createdAt"), o.optBoolean("pending"), o.optString("id"))
        }
    }

    @Synchronized
    fun add(context: Context, entry: Entry) = save(context, (listOf(entry) + load(context)).take(LIMIT))

    @Synchronized
    fun remove(context: Context, email: String) = save(context, load(context).filter { it.email != email })

    private fun save(context: Context, entries: List<Entry>) {
        val saved = JSONArray()
        for (e in entries) {
            saved.put(
                JSONObject().put("email", e.email).put("description", e.description)
                    .put("createdAt", e.createdAt).put("pending", e.pending).put("id", e.id)
            )
        }
        prefs(context).edit().putString(PREF, saved.toString()).apply()
    }
}
