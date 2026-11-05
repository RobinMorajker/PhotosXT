package com.photoxt.core.media

// Parsing of the metadata behind ImageTraits. Pure functions over bytes and text, so they are
// tested without files.

/** The XMP packet in [head] (the first bytes of a JPEG or HEIF file), or null. */
internal fun findXmp(head: ByteArray): String? {
    val text = String(head, Charsets.ISO_8859_1)
    val start = text.indexOf("<x:xmpmeta")
    if (start < 0) return null
    val end = text.indexOf("</x:xmpmeta>", start)
    return if (end < 0) null else text.substring(start, end + XMP_END.length)
}

/**
 * The video of a motion photo, from its XMP: the Motion Photo format (`Camera:MotionPhoto` with a
 * container directory whose MotionPhoto item is the file's tail) or the older Pixel format
 * (`GCamera:MicroVideoOffset`, the video's distance from the end of the file).
 */
internal fun motionVideoFromXmp(xmp: String, fileLength: Long): EmbeddedVideo? {
    val microVideo = xmpValue(xmp, "GCamera:MicroVideoOffset")?.toLongOrNull()
    if (microVideo != null && xmpValue(xmp, "GCamera:MicroVideo") == "1") {
        return tail(fileLength, microVideo)
    }
    val motion = xmpValue(xmp, "Camera:MotionPhoto") ?: xmpValue(xmp, "GCamera:MotionPhoto")
    return if (motion == "1") motionPhotoItem(containerItems(xmp), fileLength) else null
}

/** Secondary items follow the primary image in directory order; the last one ends the file. */
private fun motionPhotoItem(items: List<ContainerItem>, fileLength: Long): EmbeddedVideo? {
    var end = fileLength
    for (item in items.asReversed()) {
        val length = item.length ?: break
        if (item.semantic == "MotionPhoto") return tail(end, length)
        end -= length
    }
    return null
}

/** A photo sphere from its Google Photo Sphere XMP (`GPano`), or null for a flat photo. */
internal fun panoramaFromXmp(xmp: String): Panorama? {
    if (!xmpValue(xmp, "GPano:ProjectionType").equals("equirectangular", ignoreCase = true)) {
        return null
    }
    val number = { name: String -> xmpValue(xmp, "GPano:$name")?.toFloatOrNull() }
    val full = size(number("FullPanoWidthPixels"), number("FullPanoHeightPixels"))
    val crop = size(number("CroppedAreaImageWidthPixels"), number("CroppedAreaImageHeightPixels"))
    // Without its crop, a photo sphere is taken to cover the whole sphere.
    if (full == null || crop == null) return Panorama()
    val (fullWidth, fullHeight) = full
    val (width, height) = crop
    val left = number("CroppedAreaLeftPixels") ?: 0f
    val top = number("CroppedAreaTopPixels") ?: 0f
    return Panorama(left / fullWidth, top / fullHeight, width / fullWidth, height / fullHeight)
}

/** A width and height, both positive, or null. */
private fun size(width: Float?, height: Float?): Pair<Float, Float>? {
    val w = width?.takeIf { it > 0f } ?: return null
    val h = height?.takeIf { it > 0f } ?: return null
    return w to h
}

private class ContainerItem(val semantic: String?, val length: Long?)

/** The items of an XMP container directory, in order, from their attributes. */
private fun containerItems(xmp: String): List<ContainerItem> = ELEMENT.findAll(xmp)
    .map { it.value }
    .filter { it.contains(":Semantic=") }
    .map { element ->
        ContainerItem(
            semantic = attribute(element, "Semantic"),
            length = attribute(element, "Length")?.toLongOrNull()
        )
    }
    .toList()

private fun attribute(element: String, name: String): String? =
    Regex("""\w+:$name="([^"]*)"""").find(element)?.groupValues?.get(1)

/** An XMP property written either as an attribute or as an element. */
private fun xmpValue(xmp: String, name: String): String? {
    val quoted = Regex.escape(name)
    return Regex("""$quoted="([^"]*)"""").find(xmp)?.groupValues?.get(1)
        ?: Regex("""<$quoted>([^<]*)</$quoted>""").find(xmp)?.groupValues?.get(1)?.trim()
}

private fun tail(end: Long, length: Long): EmbeddedVideo? =
    if (length in 1..end) EmbeddedVideo(end - length, length) else null

private const val XMP_END = "</x:xmpmeta>"
private val ELEMENT = Regex("""<[^<>]+>""")
