@file:OptIn(UnstableApi::class)

package com.photoxt.core.vault

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * A vault video's bytes for the player, decrypted as they are read, with seeking: nothing
 * decrypted ever lands on disk (spec §4.8).
 */
class VaultDataSource(private val vault: Vault, private val id: String) : BaseDataSource(false) {
    private var channel: SeekableByteChannel? = null
    private var uri: Uri? = null
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val opened = try {
            vault.channel(id)
        } catch (error: java.security.GeneralSecurityException) {
            throw IOException("The vault video doesn't decrypt", error)
        }
        // Tink's channel finds its key on the first read; only then does it know the size.
        opened.read(ByteBuffer.allocate(1))
        opened.position(dataSpec.position)
        channel = opened
        uri = dataSpec.uri
        val left = opened.size() - dataSpec.position
        remaining =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) left else minOf(dataSpec.length, left)
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val open = channel ?: throw IOException("Not open")
        val count = open.read(
            ByteBuffer.wrap(buffer, offset, minOf(length.toLong(), remaining).toInt())
        )
        if (count < 0) return C.RESULT_END_OF_INPUT
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val open = channel
        channel = null
        uri = null
        if (open != null) {
            open.close()
            transferEnded()
        }
    }
}
