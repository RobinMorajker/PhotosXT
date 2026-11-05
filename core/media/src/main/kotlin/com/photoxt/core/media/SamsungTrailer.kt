package com.photoxt.core.media

/** Reads up to `count` bytes of a file at `position`. */
internal typealias ReadAt = (position: Long, count: Int) -> ByteArray

/**
 * The video of a Samsung motion photo, from the trailer Samsung appends to its JPEGs: the file
 * ends with `<directory length>"SEFT"`, the directory starts with `"SEFH"`, and each of its
 * entries points back at a block that starts with the block's name; the video's block is
 * `MotionPhoto_Data`.
 */
internal fun samsungMotionVideo(fileLength: Long, read: ReadAt): EmbeddedVideo? {
    val (start, directory) = trailerDirectory(fileLength, read) ?: return null
    val entries = (0 until directory.intLe(COUNT_AT))
        .map { HEADER + it * ENTRY }
        .takeWhile { it + ENTRY <= directory.size }
    return entries.firstNotNullOfOrNull { at ->
        motionBlock(start - directory.intLe(at + WORD), directory.intLe(at + 2 * WORD), read)
    }
}

/** The trailer's directory and where it starts, or null for a file without one. */
private fun trailerDirectory(fileLength: Long, read: ReadAt): Pair<Long, ByteArray>? {
    val footer = if (fileLength >= FOOTER) read(fileLength - FOOTER, FOOTER.toInt()) else EMPTY
    val length = if (footer.ascii(WORD, WORD) == "SEFT") footer.intLe(0) else -1
    val start = fileLength - FOOTER - length
    if (length < HEADER || start < 0) return null
    val directory = read(start, length)
    return (start to directory).takeIf { directory.ascii(0, WORD) == "SEFH" }
}

/** The video in the block at [blockStart], if that block is `MotionPhoto_Data`. */
private fun motionBlock(blockStart: Long, blockLength: Int, read: ReadAt): EmbeddedVideo? {
    val head = read(blockStart, BLOCK_HEAD + MOTION_BLOCK.length)
    val nameLength = if (head.size >= BLOCK_HEAD) head.intLe(WORD) else -1
    val skip = BLOCK_HEAD + nameLength
    return if (nameLength > 0 && head.ascii(BLOCK_HEAD, nameLength) == MOTION_BLOCK) {
        EmbeddedVideo(blockStart + skip, (blockLength - skip).toLong())
    } else {
        null
    }
}

private fun ByteArray.ascii(at: Int, count: Int): String =
    if (at < 0 || count < 0 || at + count > size) "" else String(this, at, count, Charsets.US_ASCII)

/** A little-endian 32-bit integer; 0 past the end. */
private fun ByteArray.intLe(at: Int): Int {
    if (at < 0 || at + WORD > size) return 0
    return (0 until WORD).fold(0) { value, i ->
        value or ((this[at + i].toInt() and BYTE) shl (BITS * i))
    }
}

private const val WORD = 4
private const val BITS = 8
private const val BYTE = 0xFF
private const val FOOTER = 8L
private const val HEADER = 12
private const val COUNT_AT = 8
private const val ENTRY = 12
private const val BLOCK_HEAD = 8
private const val MOTION_BLOCK = "MotionPhoto_Data"
private val EMPTY = ByteArray(0)
