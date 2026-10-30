package io.trazo.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.trazo.ai.AiOAuth
import io.trazo.ai.AiProfile
import io.trazo.ai.AiProvider
import io.trazo.ai.OAuthTokens
import io.trazo.core.applySavedOrder
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Perfiles del Modo IA y cuál se usa. Se guardan aparte de [TrazoSettings]: restablecer las preferencias no los borra. */
data class AiSettings(val profiles: List<AiProfile> = emptyList(), val defaultId: String? = null) {
    val current: AiProfile? get() = profiles.firstOrNull { it.id == defaultId } ?: profiles.firstOrNull()
}

/**
 * Las claves de API van cifradas con una clave AES del Android Keystore, que nunca sale del equipo.
 * Su archivo queda fuera de las copias de seguridad: en otro equipo no se podría descifrar.
 */
class AiStore(context: Context) {
    private val prefs = context.getSharedPreferences("trazo_ai", Context.MODE_PRIVATE)
    private val keys = context.getSharedPreferences("trazo_ai_keys", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AiSettings> = _settings.asStateFlow()

    private fun read() = AiSettings(AiProfile.decodeList(prefs.getString("profiles", null)), prefs.getString("defaultId", null))

    private fun write(s: AiSettings) {
        prefs.edit().putString("profiles", AiProfile.encodeList(s.profiles)).putString("defaultId", s.defaultId).apply()
        _settings.value = s
    }

    fun newProfile(provider: AiProvider): AiProfile =
        AiProfile(id = UUID.randomUUID().toString(), name = provider.label.substringBefore(" ("), provider = provider)

    /** Agrega o reemplaza (por id). El primero que se guarda queda como el que se usa. */
    fun save(profile: AiProfile) {
        val s = _settings.value
        val list = if (s.profiles.any { it.id == profile.id }) s.profiles.map { if (it.id == profile.id) profile else it } else s.profiles + profile
        write(s.copy(profiles = list, defaultId = s.defaultId ?: profile.id))
    }

    fun delete(id: String) {
        val s = _settings.value
        val list = s.profiles.filterNot { it.id == id }
        keys.edit().remove(id).apply()
        write(AiSettings(list, s.defaultId.takeIf { it != id } ?: list.firstOrNull()?.id))
    }

    /** El array de perfiles ya guarda el orden explícito; los datos viejos conservan su secuencia. */
    fun reorderProfiles(ids: List<String>) {
        val s = _settings.value
        write(s.copy(profiles = applySavedOrder(s.profiles, ids) { it.id }, defaultId = s.current?.id))
    }

    fun select(id: String) = write(_settings.value.copy(defaultId = id))

    fun hasKey(id: String): Boolean = keys.contains(id)

    /** null si no hay clave o si ya no se puede descifrar (por ejemplo, tras restaurar en otro equipo). */
    fun key(id: String): String? {
        val stored = keys.getString(id, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    /** La sesión de un perfil con inicio de sesión (ChatGPT, Google), o null. */
    fun session(id: String): OAuthTokens? = OAuthTokens.decode(key(id))

    /**
     * Lo que se le pasa a [io.trazo.ai.AiClient] como «clave»: la clave de API, o la sesión renovada si venció.
     * Bloquea (puede ir a la red): llamar fuera del hilo de la interfaz.
     */
    fun credential(profile: AiProfile, oauth: AiOAuth): String? {
        if (!profile.provider.signIn) return key(profile.id)
        val t = session(profile.id) ?: return null
        val fresh = oauth.fresh(profile, t)
        if (fresh != t) setKey(profile.id, fresh.encode())
        return fresh.encode()
    }

    /** Vacía = borrar la clave. */
    fun setKey(id: String, key: String) {
        val clean = key.trim()
        if (clean.isEmpty()) { keys.edit().remove(id).apply(); return }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        keys.edit().putString(id, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "trazo_ai_api_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
