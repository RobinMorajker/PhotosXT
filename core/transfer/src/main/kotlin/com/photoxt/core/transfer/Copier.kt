package com.photoxt.core.transfer

import com.photoxt.core.common.PxtLog
import com.photoxt.core.drive.DriveFile
import com.photoxt.core.drive.DriveFileWrite
import com.photoxt.core.drive.DriveUploads
import com.photoxt.core.drive.UploadSession
import com.photoxt.core.drive.UploadSessionGoneException
import com.photoxt.core.drive.UploadStatus
import com.photoxt.core.model.ConflictPolicy
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.WaitFor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant

/**
 * The operation matrix (spec §4.7) for one job, and the other jobs that settle a name in a
 * folder first: renames and conversions.
 *
 * | From → To | Copy | Cut (move) |
 * |---|---|---|
 * | Phone → phone | MediaStore copy | `RELATIVE_PATH` on a volume, else copy → verify → trash |
 * | Phone → Drive | resumable upload | upload → verify MD5 → trash the original |
 * | Drive → phone | download into MediaStore | download → verify → bin in Drive |
 * | Drive → Drive, one account | `files.copy` | parents update |
 * | Drive → Drive, two accounts | download streamed into an upload | same, then verify → bin |
 *
 * Every copy is checked by size and MD5 before anything else happens to it, and a cut removes its
 * source only after that check (spec §0 rule 4); a copy that fails it is deleted and retried.
 */
internal class Copier(
    private val device: DeviceStore,
    private val drive: DriveFiles,
    private val env: TransferEnvironment
) {
    /** Copies or cuts one item; a cut whose copy is verified has only its source left to remove. */
    suspend fun transfer(run: JobRun): Outcome {
        val copy = run.progress.copy ?: return transferAnew(run)
        return Outcome.Done(withoutSource(run, copy))
    }

    /** Drive → Drive in one account, a whole folder cut: one parents update. */
    suspend fun moveFolder(run: JobRun): Outcome {
        val target = checkNotNull(run.destination().folderId)
        val src = run.src
        env.requireDriveWrite(checkNotNull(src.account))
        return Outcome.Done(if (src.parentId == target) src else moveInDrive(run, target, null))
    }

    /** Renames in place; a name already used in the folder gets a " (1)" suffix. */
    suspend fun rename(run: JobRun): Outcome {
        val src = run.src
        val wanted = checkNotNull(run.job.target?.name) { "A rename without a name" }
        if (wanted == src.name) return Outcome.Done(src)
        src.account?.let { env.requireDriveWrite(it) }
        // Drive folders may share a name; only items take a suffix.
        val name = if (src.isFolder) wanted else nameIn(run, folderOf(src), wanted)
        if (src.isDrive) {
            drive.rename(checkNotNull(src.account), checkNotNull(src.fileId), name)
        } else {
            val uri = checkNotNull(src.uri)
            run.deviceWrite(WaitFor.DEVICE_WRITE, uri) { device.rename(uri, name) }
        }
        return Outcome.Done(src.copy(name = name))
    }

    /** A photo saved as a JPEG beside itself (spec §4.7, batch tools: HEIC → JPEG). */
    suspend fun convert(run: JobRun): Outcome {
        val src = run.src
        val here = folderOf(src)
        here.account?.let { env.requireDriveWrite(it) }
        val jpeg = jpegOf(run)
        val name = nameIn(run, here, splitName(src.name).first + ".jpg")
        val meta = src.copy(mime = JPEG, bytes = jpeg.length(), md5 = null)
        val input = fileInput(jpeg, null)
        val made = if (here.isDrive) {
            upload(run, here, name, meta, input)
        } else {
            writeToPhone(run, here, name, meta, input)
        }
        jpeg.delete()
        return Outcome.Done(made)
    }

    /**
     * Bins Drive file [file], or takes it out of its folder when the user chose that because it
     * isn't theirs to bin (spec §4.7, Trash).
     */
    suspend fun binInDrive(run: JobRun, file: FileRef) {
        val account = checkNotNull(file.account)
        val fileId = checkNotNull(file.fileId)
        if (run.progress.removeInstead) {
            drive.removeFrom(account, fileId, checkNotNull(file.parentId))
        } else {
            runCatching { drive.setTrashed(account, fileId, trashed = true) }.onFailure { error ->
                // The user decides about a file they can't bin: leave it, or take it out.
                throw if (error.refusal == Refusal.FILE) {
                    WaitException(WaitFor.NOT_OWNED, error)
                } else {
                    error
                }
            }
        }
    }

    /** What a failed or cancelled job leaves behind: a pending item, a part download. */
    fun discardPartial(run: JobRun) {
        run.progress.pendingUri?.let { runCatching { device.discard(it) } }
        run.progress.tempPath?.let { File(it).delete() }
        partFile(run).delete()
    }

    // --- Copy and move -------------------------------------------------------------------

    private suspend fun transferAnew(run: JobRun): Outcome {
        val src = run.src
        val dst = run.destination()
        dst.account?.let { env.requireDriveWrite(it) }
        if (run.isMove && src.isDrive) env.requireDriveWrite(checkNotNull(src.account))
        val sameFolder = sameFolder(src, dst)
        // A cut into the folder it is in changes nothing.
        if (run.isMove && sameFolder) return Outcome.Done(src)
        val name = settleName(run, dst, keepBoth = sameFolder)
        return if (name == null) Outcome.Skipped else Outcome.Done(place(run, dst, name))
    }

    /** Puts the item into [dst] as [name] by the shortest route the matrix has. */
    private suspend fun place(run: JobRun, dst: Destination, name: String): FileRef {
        val src = run.src
        val sameAccount = src.isDrive && dst.isDrive && src.account == dst.account
        val sameVolume = !src.isDrive && !dst.isDrive && src.volume == dst.volume
        return when {
            run.isMove && sameAccount -> moveInDrive(run, checkNotNull(dst.folderId), name)
            run.isMove && sameVolume -> moveOnPhone(run, dst, name)
            sameAccount -> copied(run, copyInDrive(run, dst, name))
            dst.isDrive -> copied(run, upload(run, dst, name, src, inputOf(run, toPhone = false)))
            else -> copied(run, writeToPhone(run, dst, name, src, inputOf(run, toPhone = true)))
        }
    }

    /** Records the verified [copy], so a resumed cut only removes its source. */
    private suspend fun copied(run: JobRun, copy: FileRef): FileRef {
        run.save(run.progress.copy(copy = copy, tempPath = null))
        partFile(run).delete()
        return withoutSource(run, copy)
    }

    /** A cut removes its source once its [copy] is verified; a copy keeps it. */
    private suspend fun withoutSource(run: JobRun, copy: FileRef): FileRef {
        if (run.isMove) removeSource(run)
        return copy
    }

    private fun sameFolder(src: FileRef, dst: Destination): Boolean = when {
        src.isDrive && dst.isDrive -> src.account == dst.account && src.parentId == dst.folderId

        !src.isDrive && !dst.isDrive ->
            src.volume == dst.volume && src.path.equals(dst.path, ignoreCase = true)

        else -> false
    }

    /**
     * The name the item takes in [dst] after any name conflict (spec §4.7): asked once, then kept
     * in the job. Keep both adds " (1)", Replace bins the item there first (it stays in the bin
     * for 30 days), Skip leaves this one out (null).
     */
    private suspend fun settleName(run: JobRun, dst: Destination, keepBoth: Boolean): String? {
        run.progress.name?.let { return it }
        val wanted = dst.name ?: run.src.name
        val existing = existing(dst, wanted)
        val name = if (existing == null) {
            wanted
        } else {
            val policy = if (keepBoth) {
                ConflictPolicy.KEEP_BOTH
            } else {
                run.job.conflictPolicy ?: run.batch.conflictPolicy
            }
            when (policy) {
                ConflictPolicy.ASK -> throw WaitException(WaitFor.CONFLICT)

                ConflictPolicy.SKIP -> null

                ConflictPolicy.KEEP_BOTH -> freeName(dst, wanted)

                ConflictPolicy.REPLACE -> {
                    binForReplace(run, existing)
                    wanted
                }
            }
        }
        if (name != null) run.save(run.progress.copy(name = name))
        return name
    }

    /** [wanted], or its first free " (n)" form in [folder]: chosen once, then kept in the job. */
    private suspend fun nameIn(run: JobRun, folder: Destination, wanted: String): String {
        run.progress.name?.let { return it }
        val name = if (existing(folder, wanted) == null) wanted else freeName(folder, wanted)
        run.save(run.progress.copy(name = name))
        return name
    }

    private suspend fun existing(dst: Destination, name: String): FileRef? = if (dst.isDrive) {
        val account = checkNotNull(dst.account)
        drive.findByName(account, checkNotNull(dst.folderId), name)?.toRef(account)
    } else {
        device.find(checkNotNull(dst.volume), checkNotNull(dst.path), name)?.toRef()
    }

    private suspend fun freeName(dst: Destination, wanted: String): String =
        (1..MAX_SUFFIX).asSequence().map { suffixed(wanted, it) }
            .firstOrNull { existing(dst, it) == null }
            ?: throw PermanentException("Too many items named like this one")

    private suspend fun binForReplace(run: JobRun, existing: FileRef) {
        run.save(run.progress.copy(replaces = existing))
        if (existing.isDrive) {
            drive.setTrashed(checkNotNull(existing.account), checkNotNull(existing.fileId), true)
        } else {
            val uri = checkNotNull(existing.uri)
            run.deviceWrite(WaitFor.DEVICE_TRASH, uri) { device.trash(uri) }
        }
    }

    /** The folder [src] is in, as a destination. */
    private fun folderOf(src: FileRef): Destination = if (src.isDrive) {
        Destination(SourceType.DRIVE, src.name, account = src.account, folderId = src.parentId)
    } else {
        Destination(SourceType.DEVICE, src.name, volume = src.volume, path = src.path)
    }

    /** Drive → Drive in one account, cut: a parents update, instant (spec §4.7). */
    private suspend fun moveInDrive(run: JobRun, target: String, name: String?): FileRef {
        val src = run.src
        val account = checkNotNull(src.account)
        val fileId = checkNotNull(src.fileId)
        val from = src.parentId ?: drive.parentOf(account, fileId)
        run.save(run.progress.copy(fromParent = from))
        val moved = drive.move(account, fileId, from, target, name?.takeIf { it != src.name })
        if (target !in moved.parents) throw VerificationException("Drive didn't move it")
        return src.copy(name = moved.name, parentId = target)
    }

    /** Phone → phone on one volume, cut: MediaStore moves the file (`RELATIVE_PATH`). */
    private suspend fun moveOnPhone(run: JobRun, dst: Destination, name: String): FileRef {
        val src = run.src
        val uri = checkNotNull(src.uri)
        val path = checkNotNull(dst.path)
        run.deviceWrite(WaitFor.DEVICE_WRITE, uri) {
            device.move(uri, path, name.takeIf { it != src.name })
        }
        val moved = device.file(uri) ?: throw IOException("The item went while moving")
        if (!moved.path.equals(path, ignoreCase = true)) {
            throw VerificationException("MediaStore didn't move the item")
        }
        return src.copy(name = moved.name, path = moved.path)
    }

    /** Drive → Drive in one account, copy: on Drive's side, nothing downloaded (spec §4.7). */
    private suspend fun copyInDrive(run: JobRun, dst: Destination, name: String): FileRef {
        val src = run.src
        val account = checkNotNull(dst.account)
        val id = run.driveId { drive.newId(account) }
        val made = drive.find(account, id)?.takeUnless { it.trashed }
            ?: drive.copy(
                account,
                checkNotNull(src.fileId),
                DriveFileWrite(id = id, name = name, parents = listOf(checkNotNull(dst.folderId)))
            )
        return verifiedOnDrive(run, account, made, src.md5, src.bytes)
    }

    /** Where a cut's source goes once the copy is verified: the phone's trash or Drive's bin. */
    private suspend fun removeSource(run: JobRun) {
        val src = run.src
        if (src.isDrive) {
            binInDrive(run, src)
        } else {
            val uri = checkNotNull(src.uri)
            run.deviceWrite(WaitFor.DEVICE_TRASH, uri) { device.trash(uri) }
        }
    }

    // --- Uploads ---------------------------------------------------------------------------

    /**
     * A phone item or another account's Drive file uploaded to [dst] through a resumable session
     * (spec §4.7). A killed upload asks Drive how much it holds and goes on from there.
     */
    private suspend fun upload(
        run: JobRun,
        dst: Destination,
        name: String,
        meta: FileRef,
        input: Input
    ): FileRef {
        val account = checkNotNull(dst.account)
        val size = input.size()
        val md5 = input.md5()
        val id = run.driveId { drive.newId(account) }
        // An upload that finished before the job could record it is there under its id.
        drive.find(account, id)?.takeUnless { it.trashed }?.let {
            return verifiedOnDrive(run, account, it, md5, size)
        }
        val metadata = DriveFileWrite(
            id = id,
            name = name,
            parents = listOf(checkNotNull(dst.folderId)),
            modifiedTime = meta.modifiedAt?.let { Instant.ofEpochMilli(it).toString() }
        )
        val (session, status) = session(run, account, metadata, meta.mime, size)
        run.bytesTotal = size
        val file = input.use { send(run, session, it, status) }
        return verifiedOnDrive(run, account, file, md5, size)
    }

    /**
     * The upload session to send through, and how much of it Drive holds: the saved one, which
     * outlives the app, or a new one when there is none or Drive forgot it (after about a week).
     */
    private suspend fun session(
        run: JobRun,
        account: String,
        metadata: DriveFileWrite,
        mime: String,
        size: Long
    ): Pair<UploadSession, UploadStatus> {
        run.progress.session?.let { url ->
            val saved = UploadSession(account, url, size)
            statusOf(saved)?.let { return saved to it }
        }
        val session = drive.uploads.start(account, metadata, mime, size)
        run.save(run.progress.copy(session = session.url))
        return session to UploadStatus.Partial(0)
    }

    /** How much of [session] Drive holds; null when Drive no longer knows the session. */
    private suspend fun statusOf(session: UploadSession): UploadStatus? = try {
        drive.uploads.status(session)
    } catch (gone: UploadSessionGoneException) {
        PxtLog.i(TAG, "Upload session expired (${gone.javaClass.simpleName}); new one")
        null
    }

    /** Sends [input] chunk by chunk from where [first] says Drive is, until Drive has it all. */
    private suspend fun send(
        run: JobRun,
        session: UploadSession,
        input: Input,
        first: UploadStatus
    ): DriveFile {
        val buffer = ByteArray(DriveUploads.CHUNK)
        var status = first
        while (status is UploadStatus.Partial) {
            val offset = status.received
            run.advance(offset)
            val count = input.read(offset, buffer)
            if (count == 0 && offset < session.size) {
                // The item shrank since the session began: start over with its new size.
                run.save(run.progress.copy(session = null, md5 = null))
                throw RetryException("The item changed while it was uploading")
            }
            status = drive.uploads.put(session, offset, buffer, count)
        }
        return (status as UploadStatus.Done).file
    }

    /**
     * Checks a new Drive file against what was sent: Drive's own MD5 and size. A file that fails
     * is deleted, and the job starts over with a new id on its next attempt.
     */
    private suspend fun verifiedOnDrive(
        run: JobRun,
        account: String,
        made: DriveFile,
        md5: String?,
        size: Long
    ): FileRef {
        val sizeOk = size <= 0 || made.size?.toLongOrNull() == size
        val md5Ok = md5 != null && md5.equals(made.md5Checksum, ignoreCase = true)
        if (!sizeOk || !md5Ok) {
            drive.delete(account, made.id)
            run.save(run.progress.copy(generatedId = null, session = null))
            throw VerificationException("The copy in Drive doesn't match the original")
        }
        return made.toRef(account)
    }

    // --- Writing to the phone --------------------------------------------------------------

    /**
     * A phone item or a Drive file written into folder [dst] of the phone through MediaStore: a
     * pending item that only PhotoXT sees until its bytes, read back, match the source's.
     */
    private suspend fun writeToPhone(
        run: JobRun,
        dst: Destination,
        name: String,
        meta: FileRef,
        input: Input
    ): FileRef = input.use {
        val size = input.size()
        run.bytesTotal = size
        val uri = pendingItem(run, dst, name, meta)
        val sent = writeAll(run, uri, input, size)
        // A phone source's own checksum is what was read while copying; Drive's is known.
        val expected = if (meta.isDrive || meta.md5 != null) input.md5() ?: sent else sent
        if (sent != expected || !landed(uri, expected, size)) {
            runCatching { device.discard(uri) }
            run.save(run.progress.copy(pendingUri = null))
            throw VerificationException("The copy on the phone doesn't match the original")
        }
        device.publish(uri, meta.takenAt)
        val made = device.file(uri) ?: throw IOException("The new item went")
        made.toRef().copy(md5 = expected, takenAt = meta.takenAt)
    }

    /** The pending item this job writes: the one it made before it was stopped, or a new one. */
    private suspend fun pendingItem(
        run: JobRun,
        dst: Destination,
        name: String,
        meta: FileRef
    ): String = run.progress.pendingUri?.takeIf { device.file(it) != null }
        ?: device.create(
            checkNotNull(dst.volume),
            checkNotNull(dst.path),
            name,
            meta.mime,
            meta.takenAt
        ).also { run.save(run.progress.copy(pendingUri = it)) }

    /** Writes [size] bytes of [input] into pending item [uri] from the start; returns their MD5. */
    private suspend fun writeAll(run: JobRun, uri: String, input: Input, size: Long): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(BUFFER)
        device.openOutput(uri).use { output ->
            var done = 0L
            var count = input.read(0, buffer)
            while (count > 0) {
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                done += count
                run.advance(done)
                count = if (size == 0L || done < size) input.read(done, buffer) else 0
            }
        }
        return digest.hex()
    }

    /** Whether the item at [uri], read back, is [size] bytes long with checksum [md5]. */
    private fun landed(uri: String, md5: String, size: Long): Boolean {
        // Read back, not MediaStore's size column, which a pending item may not have yet.
        val (bytes, landed) = device.open(uri).use(::sizeAndMd5Of)
        return (size <= 0 || bytes == size) && landed == md5
    }

    // --- Inputs ----------------------------------------------------------------------------

    /**
     * The source's bytes, from wherever they are, with their size and MD5. A Drive file going to
     * the phone downloads into a file first, so a killed download resumes with a Range request;
     * one going to another account streams into the upload, which resumes at Drive's offset.
     */
    private suspend fun inputOf(run: JobRun, toPhone: Boolean): Input {
        val src = run.src
        // A Drive original PhotoXT already has needs no download.
        val cached = src.mediaId?.takeIf { src.isDrive }?.let { drive.cachedOriginal(it) }
        return when {
            !src.isDrive -> phoneInput(run, checkNotNull(src.uri))

            cached != null -> fileInput(cached, src.md5)

            toPhone || src.md5 == null -> fileInput(downloaded(run), src.md5)

            else -> {
                val account = checkNotNull(src.account)
                val fileId = checkNotNull(src.fileId)
                Input(
                    size = { src.bytes },
                    md5 = { src.md5 },
                    open = { offset -> drive.download(account, fileId, offset) }
                )
            }
        }
    }

    private fun phoneInput(run: JobRun, uri: String) = Input(
        size = { device.file(uri)?.bytes ?: throw IOException("The item is gone") },
        md5 = {
            run.progress.md5 ?: device.open(uri).use(::md5Of).also {
                run.save(run.progress.copy(md5 = it))
            }
        },
        open = { offset -> device.open(uri).apply { skipExactly(offset) } }
    )

    private fun fileInput(file: File, md5: String?) = Input(
        size = { file.length() },
        md5 = { md5 ?: file.md5() },
        open = { offset -> file.inputStream().apply { skipExactly(offset) } }
    )

    /** Downloads a Drive source into the work folder, resuming a partial one, then checks it. */
    private suspend fun downloaded(run: JobRun): File {
        val src = run.src
        val part = partFile(run)
        part.parentFile?.mkdirs()
        if (run.progress.tempPath != part.path) run.save(run.progress.copy(tempPath = part.path))
        val have = if (part.exists()) part.length() else 0L
        if (src.bytes <= 0 || have < src.bytes) appendDownload(run, part, have)
        val sizeOk = src.bytes <= 0 || part.length() == src.bytes
        if (!sizeOk || src.md5?.equals(part.md5(), ignoreCase = true) == false) {
            part.delete()
            throw VerificationException("The download doesn't match Drive's checksum")
        }
        return part
    }

    /** Appends the rest of the Drive source to [part], which holds its first [have] bytes. */
    private suspend fun appendDownload(run: JobRun, part: File, have: Long) {
        val src = run.src
        drive.download(checkNotNull(src.account), checkNotNull(src.fileId), have).use { input ->
            FileOutputStream(part, have > 0).use { output ->
                val buffer = ByteArray(BUFFER)
                var done = have
                var count = input.read(buffer)
                while (count >= 0) {
                    output.write(buffer, 0, count)
                    done += count
                    run.advance(done)
                    count = input.read(buffer)
                }
            }
        }
    }

    /** The source as a JPEG in the work folder, converted once: a resumed job reuses it. */
    private suspend fun jpegOf(run: JobRun): File {
        val jpeg = File(env.workDir, "${run.job.id}.jpg")
        if (run.progress.tempPath == jpeg.path && jpeg.exists()) return jpeg
        jpeg.parentFile?.mkdirs()
        val src = run.src
        val local = if (src.isDrive) {
            src.mediaId?.let { drive.cachedOriginal(it) } ?: downloaded(run)
        } else {
            null
        }
        JpegConverter.convert({ local?.inputStream() ?: device.open(checkNotNull(src.uri)) }, jpeg)
        partFile(run).delete()
        run.save(run.progress.copy(tempPath = jpeg.path))
        return jpeg
    }

    private fun partFile(run: JobRun) = File(env.workDir, "${run.job.id}.part")

    private companion object {
        const val TAG = "Copier"
        const val BUFFER = 256 * 1024
        const val MAX_SUFFIX = 999
        const val JPEG = "image/jpeg"
    }
}
