package com.photoxt.core.transfer

import com.photoxt.core.database.CollageDao
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.database.TransferJobEntity
import com.photoxt.core.model.CollageOutput
import com.photoxt.core.model.ExportFormat
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TransferOp
import com.photoxt.core.model.TransferState
import com.photoxt.core.model.WaitFor
import com.photoxt.core.render.CollageExporter
import com.photoxt.core.render.CollageJson
import com.photoxt.core.render.CollageRequest
import java.io.File
import java.io.FileOutputStream
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** Where a collage's photos are: how many on this phone, and how many in Google Drive. */
data class SourceCounts(val phone: Int, val drive: Int)

/**
 * Collages for screens (spec §4.11, Export): the default album, the export dialog's counts, and
 * the export itself, queued as one batch of the transfer queue, so it resumes and waits for
 * consents like every other change.
 */
@Singleton
class CollageOps @Inject constructor(
    database: PxtDatabase,
    private val queue: TransferQueue,
    private val clock: Clock
) {
    private val planner = PastePlanner(database.fileOpsDao())

    /** The album of the first photo (spec: "the default is the album of the first photo"). */
    suspend fun defaultAlbum(mediaIds: List<String>): String? =
        mediaIds.firstOrNull()?.let { planner.mediaRows(listOf(it)).firstOrNull()?.first?.albumId }

    suspend fun counts(mediaIds: List<String>): SourceCounts {
        val rows = planner.mediaRows(mediaIds.distinct())
        val drive = rows.count { it.first.source == SourceType.DRIVE }
        return SourceCounts(rows.size - drive, drive)
    }

    /**
     * Queues [project] (JSON) to be written as [output] into album [albumId]. With
     * [deleteSources] its photos go to the trash, once the collage is saved and checked. Null
     * when that album can't take a new photo.
     */
    @Suppress("LongParameterList") // The export dialog's answers, one each.
    suspend fun export(
        project: String,
        output: CollageOutput,
        albumId: String,
        deleteSources: Boolean,
        draftId: String?,
        title: String
    ): Long? {
        val target = planner.destinationOf(albumId) ?: return null
        val format = output.format.takeIf { it in CollageOutput.FORMATS } ?: ExportFormat.JPEG
        val now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())
        val stamp = now.format(NAME_TIME)
        val made = FileRef(
            source = target.source,
            name = "Collage_$stamp.${format.extension}",
            mime = checkNotNull(format.mime),
            account = target.account,
            takenAt = clock.millis()
        )
        val trash = if (deleteSources) {
            planner.mediaRows(CollageJson.decode(project).mediaIds).map { (item, album) ->
                item.toRef(album)
            }
        } else {
            emptyList()
        }
        val payload = CollagePayload(project, output.copy(format = format), draftId, trash)
        return queue.enqueue(
            TransferOp.COLLAGE,
            title,
            listOf(
                Planned(TransferOp.COLLAGE, made, target.copy(albumId = albumId, collage = payload))
            )
        )
    }

    private companion object {
        val NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}

/**
 * The collage job (spec §4.11): the photos gathered (a phone photo copied out, a Drive photo
 * from the cache or downloaded), the collage rendered and decoded back, then written into its
 * album and checked against what was rendered. Only then does the engine add the trash jobs of
 * *delete sources*, in the transaction that marks this job done ([followUps]).
 */
internal class CollageJobs(
    database: PxtDatabase,
    private val copier: Copier,
    private val exporter: CollageExporter,
    private val clock: Clock
) {
    private val planner = PastePlanner(database.fileOpsDao())
    private val drafts: CollageDao = database.collageDao()
    private val device get() = copier.device
    private val drive get() = copier.drive
    private val env get() = copier.env

    suspend fun export(run: JobRun): Outcome {
        val target = checkNotNull(run.job.target) { "A collage job without its album" }
        val payload = checkNotNull(target.collage) { "A collage job without its project" }
        // Binning Drive photos needs full Drive access: asked before anything is made.
        payload.trash.mapNotNull {
            it.account
        }.distinct().firstOrNull { !env.canWriteDrive(it) }?.let {
            run.save(run.progress.copy(scopeAccount = it))
            throw WaitException(WaitFor.DRIVE_SCOPE)
        }
        val out = File(env.workDir, "${run.job.id}.collage")
        val md5 =
            run.progress.renderedMd5?.takeIf { run.progress.rendered == out.path && out.isFile }
                ?: rendered(run, payload, out)
        val name = copier.nameIn(run, target, run.src.name)
        val meta = run.src.copy(
            bytes = out.length(),
            md5 = md5,
            takenAt =
                run.src.takenAt ?: clock.millis()
        )
        val made = copier.place(run, target, name, meta, out)
        target.account?.let { env.driveWritten(it) }
        payload.draftId?.let { draft -> mediaIdOf(made)?.let { drafts.exported(draft, it) } }
        discard(run)
        return Outcome.Done(made)
    }

    /** The collage rendered into [out], checked by the exporter; returns its MD5. */
    private suspend fun rendered(run: JobRun, payload: CollagePayload, out: File): String {
        val project = CollageJson.decode(payload.project)
        val photos = gathered(run, project.mediaIds)
        try {
            out.parentFile?.mkdirs()
            exporter.export(CollageRequest(project, photos, payload.output, out))
        } finally {
            sourceFiles(run).forEach { it.delete() }
        }
        val md5 = out.md5()
        run.save(run.progress.copy(rendered = out.path, renderedMd5 = md5))
        return md5
    }

    /** Each photo's original as a file: a Drive one from the cache when it's there. */
    private suspend fun gathered(run: JobRun, mediaIds: List<String>): Map<String, File> {
        val rows = planner.mediaRows(mediaIds).associateBy { it.first.id }
        return mediaIds.mapIndexed { i, id ->
            val (item, album) = rows[id]
                ?: throw PermanentException("A photo of the collage is gone")
            val ref = item.toRef(album)
            id to if (ref.isDrive) {
                drive.cachedOriginal(id) ?: downloaded(run, ref, sourceFile(run, i))
            } else {
                copied(checkNotNull(ref.uri), sourceFile(run, i))
            }
        }.toMap()
    }

    private fun copied(uri: String, file: File): File {
        file.parentFile?.mkdirs()
        device.open(uri).use { input -> FileOutputStream(file).use { input.copyTo(it, BUFFER) } }
        return file
    }

    /** A Drive original into [file], resumed where an earlier run stopped, checked by MD5. */
    private suspend fun downloaded(run: JobRun, ref: FileRef, file: File): File {
        file.parentFile?.mkdirs()
        val have = if (file.isFile) file.length() else 0L
        if (ref.bytes <= 0 || have < ref.bytes) copier.appendDownload(run, file, have, ref)
        if (ref.md5 != null && !file.md5().equals(ref.md5, ignoreCase = true)) {
            file.delete()
            throw VerificationException("A photo from Drive doesn't match its checksum")
        }
        return file
    }

    /** The job's files: the collage and the photos gathered for it. */
    fun discard(run: JobRun) {
        File(env.workDir, "${run.job.id}.collage").delete()
        sourceFiles(run).forEach { it.delete() }
    }

    private fun sourceFile(run: JobRun, index: Int) = File(env.workDir, "${run.job.id}-$index.src")

    private fun sourceFiles(run: JobRun): List<File> = env.workDir.listFiles { f ->
        f.name.startsWith("${run.job.id}-") && f.name.endsWith(".src")
    }
        ?.toList().orEmpty()

    /** PhotoXT's id of a photo just made: MediaStore's id on the phone, or the Drive file's. */
    private fun mediaIdOf(made: FileRef): String? = when {
        made.isDrive -> made.fileId?.let { "drive:${made.account}:$it" }
        else -> made.uri?.substringAfterLast('/')?.let { "device:$it" }
    }

    companion object {
        private const val BUFFER = 256 * 1024

        /**
         * What a finished job adds to its batch, in the transaction that marks it done: a
         * collage's trash jobs for *delete sources* (spec §4.11: "only after the collage file has
         * been written and verified"). Nothing for any other job.
         */
        fun followUps(job: TransferJobEntity, now: Long): List<TransferJobEntity> {
            if (job.op != TransferOp.COLLAGE) return emptyList()
            return job.target?.collage?.trash.orEmpty().map { ref ->
                TransferJobEntity(
                    batchId = job.batchId,
                    op = TransferOp.TRASH,
                    src = ref.encode(),
                    dst = null,
                    state = TransferState.QUEUED,
                    bytesTotal = ref.bytes,
                    updatedAt = now
                )
            }
        }
    }
}
