package com.photoxt.core.vault

import android.content.Context
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.integration.android.AndroidKeystore
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.photoxt.core.common.PxtLog
import com.photoxt.core.model.MediaKind
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the vault keeps about one item, encrypted beside it (spec §4.8). */
@Serializable
data class VaultMeta(
    val name: String,
    val mime: String,
    val kind: MediaKind,
    val bytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0,
    val durationMs: Long? = null,
    val takenAt: Long,
    val addedAt: Long,
    /** The phone folder it came from, `<volume>:<relative path>`, to go back to. */
    val origin: String? = null,
    /** The plaintext's MD5, checked when the item leaves the vault. */
    val md5: String = ""
)

/** One item in the vault. */
data class VaultEntry(val id: String, val meta: VaultMeta)

/** The vault's key: Tink's streaming AEAD (spec §4.8: Tink AEAD, key in Android Keystore). */
fun interface VaultKeys {
    fun streamingAead(): StreamingAead
}

/**
 * The vault's keyset (AES-256-GCM-HKDF, 1 MB segments), stored encrypted by an Android Keystore
 * key that never leaves the Keystore: the files can't be read outside PhotoXT, not even with a
 * copy of them and of the keyset. Uninstalling PhotoXT removes both, so the vault is this phone's
 * only (spec §8: no cloud vault in v1).
 */
class KeystoreVaultKeys(private val dir: File) : VaultKeys {
    private val aead by lazy { load() }

    override fun streamingAead(): StreamingAead = aead

    // ponytail: the Keystore key works without a fresh unlock; PhotoXT's own unlock guards the
    // screens. Bind it to user authentication (setUserAuthenticationParameters) if needed.
    private fun load(): StreamingAead {
        StreamingAeadConfig.register()
        if (!AndroidKeystore.hasKey(MASTER_KEY)) AndroidKeystore.generateNewAes256GcmKey(MASTER_KEY)
        val master = AndroidKeystore.getAead(MASTER_KEY)
        val config = RegistryConfiguration.get()
        val file = File(dir, KEYSET)
        val handle = if (file.isFile) {
            TinkProtoKeysetFormat.parseEncryptedKeyset(file.readBytes(), master, KEYSET_AD, config)
        } else {
            val made = KeysetHandle.generateNew(
                PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB,
                config
            )
            dir.mkdirs()
            val part = File(dir, "$KEYSET.part")
            part.writeBytes(
                TinkProtoKeysetFormat.serializeEncryptedKeyset(made, master, KEYSET_AD, config)
            )
            if (!part.renameTo(file)) throw IOException("The vault key couldn't be stored")
            made
        }
        return handle.getPrimitive(config, StreamingAead::class.java)
    }

    private companion object {
        const val MASTER_KEY = "photoxt_vault"
        const val KEYSET = "keyset.tink"
        val KEYSET_AD = "photoxt-vault-keyset".encodeToByteArray()
    }
}

/**
 * The vault (spec §4.8): each item's bytes, thumbnail and details in app-private storage
 * (`filesDir/vault`), every file encrypted and bound to its item. Its items are in no album, so
 * Pictures, search, history, backup and casting never see them.
 */
@Singleton
class Vault @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val keys: VaultKeys
) {
    private val dir: File get() = File(context.filesDir, DIR)
    private val changes = MutableStateFlow(0L)

    /** Every item, newest addition first, read again after each change. */
    val items: Flow<List<VaultEntry>> = changes.map { entries() }

    /**
     * Encrypts [content] as item [id] with [meta] and its [thumbnail], then reads it back: the
     * returned details carry the plaintext's size and MD5, which match what was read. Throws when
     * the copy doesn't read back the same, leaving nothing behind.
     */
    suspend fun put(
        id: String,
        meta: VaultMeta,
        content: InputStream,
        thumbnail: ByteArray?
    ): VaultMeta = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val aead = keys.streamingAead()
        val digest = MessageDigest.getInstance("MD5")
        val part = File(dir, "$id.$DATA.part")
        var bytes = 0L
        FileOutputStream(part).use { file ->
            aead.newEncryptingStream(file, ad(id, DATA)).use { out ->
                val buffer = ByteArray(BUFFER)
                var count = content.read(buffer)
                while (count >= 0) {
                    out.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    bytes += count
                    count = content.read(buffer)
                }
            }
        }
        if (!part.renameTo(file(id, DATA))) throw IOException("The vault copy couldn't be kept")
        val md5 = digest.hex()
        val (readBytes, readMd5) = open(id).use(::sizeAndMd5)
        if (readBytes != bytes || readMd5 != md5) {
            delete(id)
            throw IOException("The vault copy doesn't read back the same")
        }
        val stored = meta.copy(bytes = bytes, md5 = md5)
        thumbnail?.let { seal(id, THUMB, it) }
        // The details go last: an item is in the vault once they are there.
        seal(id, META, json.encodeToString(VaultMeta.serializer(), stored).encodeToByteArray())
        changes.value++
        stored
    }

    /** Whether item [id] is complete in the vault. */
    fun has(id: String): Boolean = file(id, META).isFile && file(id, DATA).isFile

    /** Every item, newest addition first. Items whose details can't be read are skipped. */
    suspend fun entries(): List<VaultEntry> = withContext(Dispatchers.IO) {
        dir.listFiles { file -> file.name.endsWith(".$META") }.orEmpty().mapNotNull { file ->
            val id = file.name.removeSuffix(".$META")
            runCatching {
                val text = unseal(id, META).decodeToString()
                VaultEntry(id, json.decodeFromString(VaultMeta.serializer(), text))
            }.onFailure { PxtLog.w(TAG, "A vault item couldn't be read", it) }.getOrNull()
        }.sortedByDescending { it.meta.addedAt }
    }

    suspend fun entry(id: String): VaultEntry? = withContext(Dispatchers.IO) {
        if (!has(id)) return@withContext null
        val text = unseal(id, META).decodeToString()
        VaultEntry(id, json.decodeFromString(VaultMeta.serializer(), text))
    }

    /** Item [id]'s bytes, decrypted as they are read. */
    fun open(id: String): InputStream =
        keys.streamingAead().newDecryptingStream(FileInputStream(file(id, DATA)), ad(id, DATA))

    /** Item [id]'s bytes with random access, for video playback ([VaultDataSource]). */
    fun channel(id: String): SeekableByteChannel = keys.streamingAead()
        .newSeekableDecryptingChannel(FileInputStream(file(id, DATA)).channel, ad(id, DATA))

    suspend fun thumbnail(id: String): ByteArray? = withContext(Dispatchers.IO) {
        if (file(id, THUMB).isFile) runCatching { unseal(id, THUMB) }.getOrNull() else null
    }

    /** Deletes item [id] and its files for good. */
    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        listOf(META, THUMB, DATA, "$DATA.part").forEach { file(id, it).delete() }
        changes.value++
    }

    /** What the vault takes on the phone. */
    suspend fun size(): Long = withContext(Dispatchers.IO) {
        dir.listFiles().orEmpty().sumOf { it.length() }
    }

    private fun seal(id: String, part: String, bytes: ByteArray) {
        val target = file(id, part)
        val tmp = File(dir, "$id.$part.part")
        FileOutputStream(tmp).use { file ->
            keys.streamingAead().newEncryptingStream(file, ad(id, part)).use { it.write(bytes) }
        }
        if (!tmp.renameTo(target)) throw IOException("A vault file couldn't be kept")
    }

    private fun unseal(id: String, part: String): ByteArray = try {
        keys.streamingAead().newDecryptingStream(FileInputStream(file(id, part)), ad(id, part))
            .use { it.readBytes() }
    } catch (error: GeneralSecurityException) {
        throw IOException("A vault file doesn't decrypt", error)
    }

    private fun file(id: String, part: String) = File(dir, "$id.$part")

    private companion object {
        const val TAG = "Vault"
        const val DIR = "vault"
        const val DATA = "data"
        const val THUMB = "thumb"
        const val META = "meta"
        const val BUFFER = 256 * 1024
        val json = Json { ignoreUnknownKeys = true }

        /** Binds a file to its item and part, so files can't be swapped round. */
        fun ad(id: String, part: String) = "photoxt-vault:$id:$part".encodeToByteArray()
    }
}

/** The size and MD5 (hex) of everything [input] holds. */
internal fun sizeAndMd5(input: InputStream): Pair<Long, String> {
    val digest = MessageDigest.getInstance("MD5")
    val buffer = ByteArray(VAULT_READ_BUFFER)
    var bytes = 0L
    var count = input.read(buffer)
    while (count >= 0) {
        digest.update(buffer, 0, count)
        bytes += count
        count = input.read(buffer)
    }
    return bytes to digest.hex()
}

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

private const val VAULT_READ_BUFFER = 256 * 1024

@Module
@InstallIn(SingletonComponent::class)
internal object VaultModule {
    @Provides
    @Singleton
    fun keys(@ApplicationContext context: Context): VaultKeys =
        KeystoreVaultKeys(File(context.noBackupFilesDir, "vault-key"))
}
