package com.photoxt.core.vault

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.photoxt.core.model.MediaKind
import java.io.File
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The vault (spec §4.8; Phase 6, Done when: vault files can't be read outside the app). What
 * goes in reads back the same, but on disk every file is ciphertext bound to its item and part:
 * no plain bytes or names, no reading with another key, no swapping files between items.
 */
@RunWith(AndroidJUnit4::class)
class VaultTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.filesDir, "vault")
    private val vault = Vault(context, newKeys())

    @Before
    fun clean() {
        dir.deleteRecursively()
    }

    @Test
    fun anItemReadsBackAsItWentIn() = runTest {
        val bytes = Random(1).nextBytes(3_000_000)
        val stored = vault.put("one", meta("a.jpg"), bytes.inputStream(), byteArrayOf(1, 2, 3))

        assertEquals(bytes.size.toLong(), stored.bytes)
        assertArrayEquals(bytes, vault.open("one").use { it.readBytes() })
        assertArrayEquals(byteArrayOf(1, 2, 3), vault.thumbnail("one"))
        assertEquals(listOf("one" to "a.jpg"), vault.entries().map { it.id to it.meta.name })
    }

    @Test
    fun theFilesHoldNoPlainBytesOrNames() = runTest {
        val marker = "PLAIN-MARKER "
        vault.put(
            "one",
            meta("holiday-secret.jpg"),
            marker.repeat(50_000).encodeToByteArray().inputStream(),
            null
        )

        val files = dir.listFiles().orEmpty()
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            val text = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(file.name, text.contains(marker))
            assertFalse(file.name, text.contains("holiday-secret"))
            assertFalse(file.name, file.name.contains("holiday"))
        }
    }

    @Test
    fun anotherKeyReadsNothing() = runTest {
        vault.put("one", meta("a.jpg"), Random(2).nextBytes(100_000).inputStream(), byteArrayOf(9))
        val stranger = Vault(context, newKeys())

        assertThrows(IOException::class.java) { stranger.open("one").use { it.readBytes() } }
        assertTrue(stranger.entries().isEmpty())
    }

    @Test
    fun filesCantBeSwappedBetweenItems() = runTest {
        vault.put("one", meta("a.jpg"), Random(3).nextBytes(50_000).inputStream(), null)
        vault.put("two", meta("b.jpg"), Random(4).nextBytes(50_000).inputStream(), null)
        File(dir, "two.data").copyTo(File(dir, "one.data"), overwrite = true)

        assertThrows(IOException::class.java) { vault.open("one").use { it.readBytes() } }
    }

    @Test
    fun aVideoSeeksAsItDecrypts() = runTest {
        val bytes = Random(5).nextBytes(2_500_000)
        vault.put("clip", meta("c.mp4", MediaKind.VIDEO), bytes.inputStream(), null)
        val source = VaultDataSource(vault, "clip")

        val length = source.open(DataSpec(Uri.parse("vault:clip"), 1_234_567, 300_000))
        val read = ByteArray(length.toInt())
        var at = 0
        while (at < read.size) at += source.read(read, at, read.size - at)
        source.close()

        assertEquals(300_000L, length)
        assertArrayEquals(bytes.copyOfRange(1_234_567, 1_534_567), read)
    }

    @Test
    fun aDeletedItemIsGone() = runTest {
        vault.put("one", meta("a.jpg"), Random(6).nextBytes(10_000).inputStream(), byteArrayOf(1))
        vault.delete("one")

        assertFalse(vault.has("one"))
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    private companion object {
        fun meta(name: String, kind: MediaKind = MediaKind.IMAGE) = VaultMeta(
            name = name,
            mime = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg",
            kind = kind,
            takenAt = 1,
            addedAt = 2
        )

        /** A throwaway key: Robolectric has no Android Keystore. */
        fun newKeys(): VaultKeys {
            StreamingAeadConfig.register()
            val config = RegistryConfiguration.get()
            val aead = KeysetHandle.generateNew(
                PredefinedStreamingAeadParameters.AES128_GCM_HKDF_4KB,
                config
            )
                .getPrimitive(config, StreamingAead::class.java)
            return VaultKeys { aead }
        }
    }
}
