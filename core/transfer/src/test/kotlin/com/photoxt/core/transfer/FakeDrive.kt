package com.photoxt.core.transfer

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

/**
 * A Drive in memory behind MockWebServer (spec §0 rule 6, "fake Drive server"): files of several
 * accounts, each seeing only its own, with the REST calls file operations make (get, list by
 * name or bin, generateIds, copy, update with parents, create, delete, media with Range) and
 * resumable uploads in chunks. Switches make it corrupt what it stores, so verification can be
 * tested, or hold a chunk, so a test can kill an upload mid-way.
 */
class FakeDrive : Dispatcher() {
    @Suppress("LongParameterList") // The fields of a Drive file the engine reads.
    class File(
        val id: String,
        val account: String,
        var name: String,
        val mime: String,
        val parents: MutableList<String>,
        var content: ByteArray,
        var trashed: Boolean = false,
        var md5: String? = md5(content)
    )

    val server = MockWebServer().also { it.dispatcher = this }
    val files = ConcurrentHashMap<String, File>()

    /** Every request as "METHOD path", in order. */
    val requests = CopyOnWriteArrayList<String>()

    /** The Range header of every media request ("bytes=N-"), null for whole files. */
    val ranges = CopyOnWriteArrayList<String?>()

    /** Bytes received by upload chunks, all sessions together. */
    val uploadedBytes = java.util.concurrent.atomic.AtomicLong()

    /** Store a wrong checksum for uploads, or for server-side copies. */
    @Volatile var corruptUploads = false

    @Volatile var corruptCopies = false

    /** Files the user doesn't own: binning them answers 403. */
    val notOwned = ConcurrentHashMap.newKeySet<String>()

    /** Holds the [holdChunk]th chunk PUT (1-based) until [release], after [held] completes. */
    @Volatile var holdChunk: Int? = null
    val held = CompletableDeferred<Unit>()
    private val release = CountDownLatch(1)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val counter = AtomicInteger()
    private val chunks = AtomicInteger()

    private class Session(
        val account: String,
        val metadata: JsonObject,
        val size: Long,
        val mime: String,
        val buffer: Buffer = Buffer(),
        var fileId: String? = null
    )

    fun start() = server.start()

    override fun close() {
        release.countDown()
        server.close()
    }

    fun releaseHeldChunk() = release.countDown()

    @Suppress("LongParameterList") // A file is placed with everything Drive would say about it.
    fun add(
        account: String,
        id: String,
        name: String,
        parent: String,
        content: ByteArray,
        mime: String = "image/jpeg"
    ) = File(id, account, name, mime, mutableListOf(parent), content).also { files[id] = it }

    fun folder(account: String, id: String, name: String, parent: String) =
        File(id, account, name, FOLDER, mutableListOf(parent), ByteArray(0), md5 = null)
            .also { files[id] = it }

    fun inFolder(account: String, folder: String): List<File> =
        files.values.filter { it.account == account && folder in it.parents && !it.trashed }

    // One branch per Drive endpoint the engine calls, as Drive's own router would have.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    override fun dispatch(request: RecordedRequest): MockResponse {
        val account = request.headers["Authorization"]?.removePrefix("Bearer token-")
            ?: return status(401)
        val url = request.url
        val path = url.encodedPath
        requests += "${request.method} $path"
        val segments = path.removePrefix("/").split('/')
        return when {
            path == "/drive/v3/files/generateIds" -> {
                val count = url.queryParameter("count")?.toInt() ?: 1
                val ids = (1..count).joinToString(",") { "\"gen${counter.incrementAndGet()}\"" }
                json("""{"ids":[$ids]}""")
            }

            request.method == "GET" && path == "/drive/v3/files" -> list(
                account,
                url.queryParameter("q").orEmpty()
            )

            request.method == "POST" && path == "/drive/v3/files" -> create(account, body(request))

            request.method == "POST" && segments.size == 5 && segments[4] == "copy" ->
                copy(account, segments[3], body(request))

            request.method == "GET" && path.startsWith("/drive/v3/files/") -> {
                val file = visible(account, segments[3]) ?: return status(404)
                if (url.queryParameter("alt") ==
                    "media"
                ) {
                    media(file, request)
                } else {
                    json(file.json())
                }
            }

            request.method == "PATCH" && path.startsWith("/drive/v3/files/") ->
                update(
                    account,
                    segments[3],
                    body(request),
                    url.queryParameter("addParents"),
                    url.queryParameter("removeParents")
                )

            request.method == "DELETE" && path.startsWith("/drive/v3/files/") -> {
                val file = visible(account, segments[3]) ?: return status(404)
                files.remove(file.id)
                status(204)
            }

            request.method == "POST" && path == "/upload/drive/v3/files" -> {
                val id = "s${counter.incrementAndGet()}"
                sessions[id] = Session(
                    account,
                    body(request),
                    request.headers["X-Upload-Content-Length"]!!.toLong(),
                    request.headers["X-Upload-Content-Type"]!!
                )
                MockResponse.Builder().setHeader(
                    "Location",
                    server.url("/upload/session/$id")
                ).build()
            }

            request.method == "PUT" && path.startsWith(
                "/upload/session/"
            ) -> chunk(segments[2], request)

            else -> status(404)
        }
    }

    private fun visible(account: String, id: String): File? = files[id]?.takeIf {
        it.account ==
            account
    }

    private fun list(account: String, q: String): MockResponse {
        val mine = files.values.filter { it.account == account }
        val matches = when {
            q.startsWith("trashed = true") -> mine.filter { it.trashed && it.mime != FOLDER }

            " in parents" in q -> {
                val parent = Regex("'([^']+)' in parents").find(q)!!.groupValues[1]
                val name = Regex("name = '((?:[^'\\\\]|\\\\.)*)'").find(q)?.groupValues?.get(1)
                    ?.replace("\\'", "'")?.replace("\\\\", "\\")
                mine.filter {
                    parent in it.parents && !it.trashed &&
                        (name == null || it.name == name)
                }
            }

            else -> emptyList()
        }
        return json("""{"files":[${matches.joinToString(",") { it.json() }}]}""")
    }

    private fun create(account: String, body: JsonObject): MockResponse {
        val id = body.string("id") ?: "f${counter.incrementAndGet()}"
        if (files.containsKey(id)) return status(409)
        val file = File(
            id,
            account,
            body.string("name").orEmpty(),
            body.string("mimeType") ?: "application/octet-stream",
            body.parents().toMutableList(),
            ByteArray(0),
            md5 = null
        )
        files[id] = file
        return json(file.json())
    }

    private fun copy(account: String, sourceId: String, body: JsonObject): MockResponse {
        val source = visible(account, sourceId) ?: return status(404)
        val id = body.string("id") ?: "f${counter.incrementAndGet()}"
        if (files.containsKey(id)) return status(409)
        val copy = File(
            id,
            account,
            body.string("name") ?: source.name,
            source.mime,
            body.parents().ifEmpty { source.parents }.toMutableList(),
            source.content.copyOf(),
            md5 = if (corruptCopies) "0000" else source.md5
        )
        files[id] = copy
        return json(copy.json())
    }

    private fun update(
        account: String,
        id: String,
        body: JsonObject,
        add: String?,
        remove: String?
    ): MockResponse {
        val file = visible(account, id) ?: return status(404)
        body["trashed"]?.let { trashed ->
            if (id in notOwned) {
                return MockResponse.Builder().code(403)
                    .body(
                        """{"error":{"errors":[{"reason":"insufficientFilePermissions"}]}}"""
                    )
                    .build()
            }
            file.trashed = trashed.jsonPrimitive.boolean
        }
        body.string("name")?.let { file.name = it }
        remove?.split(',')?.forEach { file.parents.remove(it) }
        add?.split(',')?.forEach { if (it !in file.parents) file.parents += it }
        return json(file.json())
    }

    private fun media(file: File, request: RecordedRequest): MockResponse {
        val range = request.headers["Range"]
        ranges += range
        val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
        return MockResponse.Builder()
            .code(if (range != null) 206 else 200)
            .body(Buffer().write(file.content, from, file.content.size - from))
            .build()
    }

    @Suppress("ReturnCount") // Drive's answers to a chunk: gone, held, partial, done, conflict.
    private fun chunk(sessionId: String, request: RecordedRequest): MockResponse {
        val session = sessions[sessionId] ?: return status(404)
        val range = request.headers["Content-Range"]!!.removePrefix("bytes ")
        val bytes = request.body?.toByteArray() ?: ByteArray(0)
        if (!range.startsWith("*")) {
            val index = chunks.incrementAndGet()
            if (index == holdChunk) {
                // The client is "killed" while this chunk is in flight: it is never stored.
                held.complete(Unit)
                release.await(HOLD_SECONDS, TimeUnit.SECONDS)
                return status(500)
            }
            val start = range.substringBefore('-').toLong()
            check(start == session.buffer.size) { "Chunk at $start, have ${session.buffer.size}" }
            session.buffer.write(bytes)
            uploadedBytes.addAndGet(bytes.size.toLong())
        }
        session.fileId?.let { return json(files.getValue(it).json()) }
        if (session.buffer.size < session.size) {
            val received = session.buffer.size
            return MockResponse.Builder().code(308)
                .apply { if (received > 0) setHeader("Range", "bytes=0-${received - 1}") }
                .build()
        }
        val metadata = session.metadata
        val id = metadata.string("id") ?: "f${counter.incrementAndGet()}"
        if (files.containsKey(id)) return status(409)
        val content = session.buffer.readByteArray()
        val file = File(
            id,
            session.account,
            metadata.string("name").orEmpty(),
            session.mime,
            metadata.parents().toMutableList(),
            content,
            md5 = if (corruptUploads) "0000" else md5(content)
        )
        files[id] = file
        session.fileId = id
        return json(file.json())
    }

    private fun File.json(): String = JsonObject(
        buildMap {
            put("id", JsonPrimitive(id))
            put("name", JsonPrimitive(name))
            put("mimeType", JsonPrimitive(mime))
            put("parents", JsonArray(parents.map(::JsonPrimitive)))
            put("trashed", JsonPrimitive(trashed))
            put("version", JsonPrimitive("1"))
            put("ownedByMe", JsonPrimitive(id !in notOwned))
            if (mime != FOLDER) put("size", JsonPrimitive(content.size.toString()))
            md5?.let { put("md5Checksum", JsonPrimitive(it)) }
            if (trashed) put("trashedTime", JsonPrimitive("2026-11-01T10:00:00Z"))
        }
    ).toString()

    private fun body(request: RecordedRequest): JsonObject = request.body?.utf8()?.takeIf {
        it.isNotBlank()
    }?.let { Json.parseToJsonElement(it).jsonObject }
        ?: JsonObject(emptyMap())

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.parents(): List<String> =
        this["parents"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun json(body: String) = MockResponse.Builder().body(body).build()

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    companion object {
        const val FOLDER = "application/vnd.google-apps.folder"
        private const val HOLD_SECONDS = 30L

        fun md5(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
