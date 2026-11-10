package com.photoxt.core.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * MediaStore in memory. Items PhotoXT made, and items in [granted], can be changed; any other
 * app's item refuses with [SecurityException] until granted, as Android 11+ does. New items are
 * pending until published, and a new name already in its folder gets " (1)", like MediaStore.
 */
class FakeDevice : DeviceStore {
    @Suppress("LongParameterList") // The MediaStore columns the engine reads.
    class Item(
        var volume: String,
        var path: String,
        var name: String,
        val mime: String,
        var bytes: ByteArray,
        val ours: Boolean,
        var trashed: Boolean = false,
        var pending: Boolean = false,
        val takenAt: Long? = null
    )

    val items = LinkedHashMap<String, Item>()
    val granted = HashSet<String>()

    /** Flip a byte of everything written, as a failing card would. */
    var corruptWrites = false
    private var next = 1

    /** Another app's photo (the camera's, say), already published. */
    fun add(
        volume: String,
        path: String,
        name: String,
        bytes: ByteArray,
        mime: String = "image/jpeg"
    ): String {
        val uri = "content://media/$volume/images/media/${next++}"
        items[uri] =
            Item(volume, path, name, mime, bytes, ours = false, takenAt = 1_700_000_000_000)
        return uri
    }

    fun live(path: String): List<Item> = items.values.filter {
        it.path == path && !it.trashed &&
            !it.pending
    }

    override fun file(uri: String): DeviceFile? = items[uri]?.let { it.toFile(uri) }

    override fun find(volume: String, path: String, name: String): DeviceFile? =
        items.entries.firstOrNull { (_, item) ->
            !item.trashed && item.volume == volume && item.path.equals(path, true) &&
                item.name.equals(name, true)
        }?.let { (uri, item) -> item.toFile(uri) }

    override fun open(uri: String): InputStream =
        ByteArrayInputStream(items[uri]?.bytes ?: throw FileNotFoundException(uri))

    override fun create(
        volume: String,
        path: String,
        name: String,
        mime: String,
        takenAt: Long?
    ): String {
        var unique = name
        var n = 1
        while (find(volume, path, unique) != null) unique = suffixed(name, n++)
        val uri = "content://media/$volume/images/media/${next++}"
        items[uri] =
            Item(
                volume,
                path,
                unique,
                mime,
                ByteArray(0),
                ours = true,
                pending = true,
                takenAt = takenAt
            )
        return uri
    }

    override fun openOutput(uri: String): OutputStream {
        val item = items[uri] ?: throw FileNotFoundException(uri)
        return object : ByteArrayOutputStream() {
            override fun close() {
                val written = toByteArray()
                if (corruptWrites && written.isNotEmpty()) written[0] = (written[0] + 1).toByte()
                item.bytes = written
            }
        }
    }

    override fun publish(uri: String, takenAt: Long?) {
        items.getValue(uri).pending = false
    }

    override fun discard(uri: String) {
        items.remove(uri)
    }

    override fun move(uri: String, path: String, name: String?) {
        val item = writable(uri)
        item.path = path
        name?.let { item.name = it }
    }

    override fun rename(uri: String, name: String) {
        writable(uri).name = name
    }

    override fun trash(uri: String) {
        writable(uri).trashed = true
    }

    override fun restore(uri: String) {
        writable(uri).trashed = false
    }

    override fun delete(uri: String) {
        writable(uri)
        items.remove(uri)
    }

    override fun trashed(): List<DeviceFile> =
        items.filter { it.value.trashed }.map { (uri, item) -> item.toFile(uri) }

    override fun bucketId(volume: String, path: String): Long =
        "$volume/$path".lowercase().hashCode().toLong()

    private fun writable(uri: String): Item {
        val item = items[uri] ?: throw FileNotFoundException(uri)
        if (!item.ours &&
            uri !in granted
        ) {
            throw SecurityException("MediaStore wants the user's consent")
        }
        return item
    }

    private fun Item.toFile(uri: String) = DeviceFile(
        uri = uri,
        volume = volume,
        path = path,
        name = name,
        mime = mime,
        bytes = bytes.size.toLong(),
        takenAt = takenAt,
        trashed = trashed
    )
}
