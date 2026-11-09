package io.github.zhajitui.biliplay.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.zhajitui.biliplay.data.model.Account
import io.github.zhajitui.biliplay.data.model.SavedCookie
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("biliplay.session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<SavedCookie> {
        val payload = prefs.getString(KEY_COOKIES, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<SavedCookie>>(decrypt(payload))
        }.getOrElse { emptyList() }
    }

    fun save(cookies: List<SavedCookie>) {
        if (cookies.isEmpty()) {
            prefs.edit().remove(KEY_COOKIES).apply()
            return
        }
        runCatching {
            prefs.edit().putString(KEY_COOKIES, encrypt(json.encodeToString(cookies))).apply()
        }
    }

    fun loadAccount(): Account? {
        val raw = prefs.getString(KEY_ACCOUNT, null) ?: return null
        return runCatching { json.decodeFromString<Account>(raw) }.getOrNull()
    }

    fun saveAccount(account: Account) {
        prefs.edit().putString(KEY_ACCOUNT, json.encodeToString(account)).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val combined = Base64.decode(payload, Base64.NO_WRAP)
        require(combined.size > GCM_IV_LENGTH) { "会话数据无效" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, combined, 0, GCM_IV_LENGTH),
        )
        return String(
            cipher.doFinal(combined, GCM_IV_LENGTH, combined.size - GCM_IV_LENGTH),
            Charsets.UTF_8,
        )
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "biliplay_session_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
        const val KEY_COOKIES = "cookies"
        const val KEY_ACCOUNT = "account"
    }
}
