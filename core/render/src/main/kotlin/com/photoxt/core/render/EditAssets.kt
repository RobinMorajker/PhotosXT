package com.photoxt.core.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * The files recipes point at (spec §4.10): subject masks, erased patches, imported LUTs, sticker
 * and logo pictures, replacement backgrounds. Each is named by its content's SHA-256, so the
 * same file is stored once, a name never changes meaning, and copying a recipe copies nothing.
 */
class EditAssets(val dir: File) {
    private val luts = HashMap<String, Lut3d>()

    fun file(name: String): File {
        require(NAME.matches(name)) { "Not an asset name" }
        return File(dir, name)
    }

    fun exists(name: String): Boolean = NAME.matches(name) && File(dir, name).isFile

    /** Stores [bytes] as `<sha256>.<extension>`; returns the name. */
    fun put(bytes: ByteArray, extension: String): String {
        val name = digest(bytes) + "." + extension
        val target = File(dir, name)
        if (!target.exists()) {
            dir.mkdirs()
            val part = File(dir, "$name.part")
            part.writeBytes(bytes)
            check(part.renameTo(target)) { "Couldn't store an asset" }
        }
        return name
    }

    /** Stores [bitmap] as a PNG, keeping its transparency. */
    fun putPng(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        return put(out.toByteArray(), "png")
    }

    /** Stores a one-channel mask, deflated, as `.mask`. */
    fun putMask(mask: MaskRaster): String {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(MASK_MAGIC)
            data.writeInt(mask.width)
            data.writeInt(mask.height)
            DeflaterOutputStream(data).use { it.write(mask.bytes) }
        }
        return put(out.toByteArray(), "mask")
    }

    fun mask(name: String): MaskRaster? = runCatching {
        DataInputStream(file(name).inputStream().buffered()).use { data ->
            require(data.readInt() == MASK_MAGIC)
            val w = data.readInt()
            val h = data.readInt()
            require(w in 1..MAX_SIDE && h in 1..MAX_SIDE)
            val bytes = ByteArray(w * h)
            InflaterInputStream(data).use { input ->
                var read = 0
                while (read < bytes.size) {
                    val n = input.read(bytes, read, bytes.size - read)
                    if (n < 0) break
                    read += n
                }
                require(read == bytes.size)
            }
            MaskRaster(w, h, bytes)
        }
    }.getOrNull()

    /** A picture asset decoded with its long edge at most about [maxEdge]. */
    fun bitmap(name: String, maxEdge: Int): Bitmap? = runCatching {
        val path = file(name).path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (long / (sample * 2) >= maxEdge) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** An imported `.cube` LUT, parsed once. */
    @Synchronized
    fun lut(name: String): Lut3d? = luts[name] ?: runCatching {
        file(name).reader().use(CubeLut::parse)
    }.getOrNull()?.also { luts[name] = it }

    /** Deletes every asset not in [referenced] that is older than [graceMs] (still being made). */
    fun keepOnly(referenced: Set<String>, now: Long, graceMs: Long = GRACE_MS): Int {
        val files = dir.listFiles() ?: return 0
        return files.count { file ->
            file.name !in referenced && now - file.lastModified() > graceMs && file.delete()
        }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }.take(HASH_CHARS)

    companion object {
        private val NAME = Regex("[0-9a-f]{40}\\.(png|jpg|webp|mask|cube)")
        private const val HASH_CHARS = 40
        private const val MASK_MAGIC = 0x50584d4b
        private const val MAX_SIDE = 8192
        private const val PNG_QUALITY = 100
        private const val GRACE_MS = 24L * 60 * 60 * 1000
    }
}
