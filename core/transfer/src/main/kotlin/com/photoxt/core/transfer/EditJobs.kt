package com.photoxt.core.transfer

import com.photoxt.core.common.PxtLog
import com.photoxt.core.database.EditDao
import com.photoxt.core.database.EditRecipeEntity
import com.photoxt.core.model.EDIT_RECIPE_VERSION
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.ExportFormat
import com.photoxt.core.model.ExportOptions
import com.photoxt.core.model.Resize
import com.photoxt.core.model.WaitFor
import com.photoxt.core.render.EditJson
import com.photoxt.core.render.ExportRequest
import com.photoxt.core.render.PhotoExporter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Clock

/**
 * Saving and reverting edits (spec §4.10, Output and *Revert to original — any time*), as jobs
 * of the transfer queue, so they resume, wait for MediaStore's or Drive's consent, and offer
 * Undo like every other change (spec §0 rule 4).
 *
 * *Save* keeps the original before anything is written: a phone item's bytes are copied into
 * app storage and read back; a Drive file's current revision is marked `keepForever`. The edit
 * is rendered from that original, never from an earlier edit, so edits don't pile up. *Revert*
 * writes the original back and checks the item's bytes against its MD5: bit for bit the same.
 */
internal class EditJobs(
    private val dao: EditDao,
    private val copier: Copier,
    private val exporter: PhotoExporter,
    private val originals: File,
    private val clock: Clock
) {
    private val device get() = copier.device
    private val drive get() = copier.drive
    private val revisions get() = copier.drive.revisions
    private val env get() = copier.env

    suspend fun save(run: JobRun): Outcome {
        val src = run.src
        val mediaId = checkNotNull(src.mediaId) { "An edit of an item PhotoXT doesn't know" }
        // MediaStore's consent before anything is rendered, so the photos of a pasted batch reach
        // the wait together and are asked for in one screen, not one after each render.
        src.uri?.takeUnless { src.isDrive }?.let { uri ->
            run.deviceWrite(WaitFor.DEVICE_WRITE, uri) { device.checkWrite(uri) }
        }
        val payload = payloadOf(run)
        val edit = dao.edit(mediaId)
        // What Undo of this save goes back to.
        if (run.progress.previous == null && edit != null) {
            run.save(run.progress.copy(previous = edit.opsJson))
        }
        val original = originalOf(run, edit)
        // Save keeps the item's own format and size; only Save as copy changes them.
        val own = ExportFormat.SAME
        val options = payload.options.copy(format = own, resize = Resize(), maxBytes = null)
        val recipe = completed(run, original, payload.recipe)
        val (file, md5) = rendered(run, original, recipe, options)
        if (src.isDrive) {
            copier.uploadRevision(run, file, src.mime, md5)
            env.driveWritten(checkNotNull(src.account))
        } else {
            overwrite(run, checkNotNull(src.uri), file, md5)
        }
        dao.save(
            EditRecipeEntity(
                mediaId = mediaId,
                opsJson = recipe,
                schemaVersion = EDIT_RECIPE_VERSION,
                baseRevisionId = original.revision,
                originalPath = original.kept?.path,
                originalMd5 = original.md5,
                originalBytes = original.file.length(),
                originalMime = edit?.originalMime ?: src.mime,
                originalName = edit?.originalName ?: src.name,
                editedMd5 = md5,
                savedAt = clock.millis()
            )
        )
        finish(run, original)
        return Outcome.Done(src.copy(md5 = md5, bytes = file.length()))
    }

    suspend fun saveCopy(run: JobRun): Outcome {
        val src = run.src
        val payload = payloadOf(run)
        val edit = src.mediaId?.let { dao.edit(it) }
        val original = originalOf(run, edit, keep = false)
        val recipe = completed(run, original, payload.recipe)
        val (file, _) = rendered(run, original, recipe, payload.options)
        // The format the exporter wrote: the one asked for, or the photo's own.
        val format = ExportFormat.written(payload.options.format, src.mime)
        val target = run.job.target?.takeIf { it.volume != null || it.folderId != null }
        val here = target ?: copier.folderFor(src)
        val name = copier.nameIn(run, here, payload.name ?: copyName(src.name, format))
        val copy = src.copy(mime = format.mime ?: src.mime, bytes = file.length(), md5 = null)
        val made = copier.place(run, here, name, copy, file)
        here.account?.let { env.driveWritten(it) }
        finish(run, original)
        return Outcome.Done(made)
    }

    suspend fun revert(run: JobRun): Outcome {
        val src = run.src
        val mediaId = checkNotNull(src.mediaId)
        val edit = dao.edit(mediaId) ?: return Outcome.Done(src)
        if (src.isDrive) {
            val account = checkNotNull(src.account)
            val fileId = checkNotNull(src.fileId)
            env.requireDriveWrite(account)
            val revision = checkNotNull(edit.baseRevisionId) { "No kept revision" }
            val file = downloaded(run, edit.originalMd5) { offset ->
                revisions.download(account, fileId, revision, offset)
            }
            // Checked against the original's MD5, so the kept revision is a duplicate after it.
            copier.uploadRevision(run, file, edit.originalMime, edit.originalMd5)
            env.driveWritten(account)
            file.delete()
            dao.delete(mediaId)
            // Drive never unsets keepForever (400), so the kept revision is deleted instead.
            runCatching { revisions.delete(account, fileId, revision) }
                .onFailure { PxtLog.w(TAG, "Couldn't delete the kept revision", it) }
        } else {
            val kept = File(checkNotNull(edit.originalPath) { "No kept original" })
            if (!kept.isFile || !kept.md5().equals(edit.originalMd5, ignoreCase = true)) {
                throw PermanentException("The kept original is missing or damaged")
            }
            overwrite(run, checkNotNull(src.uri), kept, edit.originalMd5)
            // The row goes first: a revert stopped after it finds nothing left to do.
            dao.delete(mediaId)
            kept.delete()
        }
        return Outcome.Done(src.copy(md5 = edit.originalMd5, bytes = edit.originalBytes))
    }

    /** Leftovers of a failed or cancelled edit job. */
    suspend fun discard(run: JobRun) {
        run.progress.rendered?.let { File(it).delete() }
        workFile(run, "orig").delete()
        // A kept phone original stays only while an edit row points at it.
        val kept = run.progress.original ?: return
        val edit = run.src.mediaId?.let { dao.edit(it) }
        if (edit?.originalPath != kept) File(kept).delete()
    }

    private fun payloadOf(run: JobRun): EditPayload =
        checkNotNull(run.job.target?.edit) { "An edit job without its recipe" }

    /**
     * The original an edit is rendered from: its [file] and what proves it, and where it is
     * kept for Revert: [kept] in app storage (phone), or [revision] in Drive.
     */
    private class Original(
        val file: File,
        val md5: String,
        val mime: String,
        val kept: File? = null,
        val revision: String? = null
    )

    private suspend fun originalOf(
        run: JobRun,
        edit: EditRecipeEntity?,
        keep: Boolean = true
    ): Original {
        val src = run.src
        return when {
            edit == null -> if (src.isDrive) driveOriginal(run, keep) else phoneOriginal(run, keep)

            src.isDrive -> {
                val account = checkNotNull(src.account)
                val fileId = checkNotNull(src.fileId)
                val revision = checkNotNull(edit.baseRevisionId)
                val file = downloaded(run, edit.originalMd5) { offset ->
                    revisions.download(account, fileId, revision, offset)
                }
                Original(file, edit.originalMd5, edit.originalMime, revision = revision)
            }

            else -> {
                val kept = File(checkNotNull(edit.originalPath))
                if (!kept.isFile) throw PermanentException("The kept original is missing")
                Original(kept, edit.originalMd5, edit.originalMime, kept = kept)
            }
        }
    }

    /** A Drive file before its first edit: its revision kept forever, its bytes downloaded. */
    private suspend fun driveOriginal(run: JobRun, keep: Boolean): Original {
        val src = run.src
        val account = checkNotNull(src.account)
        val fileId = checkNotNull(src.fileId)
        if (keep) env.requireDriveWrite(account)
        val head = revisions.head(account, fileId)
        val saved = run.progress
        val md5 = saved.originalMd5 ?: checkNotNull(head.md5Checksum) { "Drive gave no checksum" }
        val revision = saved.revision ?: head.headRevisionId
        if (keep) {
            checkNotNull(revision) { "Drive gave no revision" }
            revisions.keep(account, fileId, revision)
            // A resumed job keeps the revision it first saw, not the edit it uploaded since.
            if (saved.revision == null) run.save(saved.copy(revision = revision, originalMd5 = md5))
        }
        val cached = src.mediaId?.let { drive.cachedOriginal(it) }
            ?.takeIf { it.md5().equals(md5, ignoreCase = true) }
        val file = cached
            ?: downloaded(run, md5) { offset -> drive.download(account, fileId, offset) }
        return Original(file, md5, src.mime, revision = revision)
    }

    /**
     * A phone item before its first edit: copied into app storage and read back, so a Save
     * that fails half-way never leaves the user without it. Location tags come too: PhotoXT
     * reads the unredacted original ([TransferEnvironment.readsOriginals]).
     */
    private suspend fun phoneOriginal(run: JobRun, keep: Boolean): Original {
        val src = run.src
        if (keep && !env.readsOriginals) {
            throw PermanentException("PhotoXT needs access to photo locations to keep the original")
        }
        // A resumed job has its copy already.
        val saved = run.progress
        val had = saved.original?.let(::File)?.takeIf { it.isFile }
        var file = had
        var md5 = saved.originalMd5
        if (file == null || md5 == null) {
            file = if (keep) keptFile(src) else workFile(run, "orig")
            md5 = copyChecked(checkNotNull(src.uri), file)
            run.save(saved.copy(original = file.path, originalMd5 = md5))
        }
        return Original(file, md5, src.mime, kept = file.takeIf { keep })
    }

    /** Phone item [uri] copied into [target] and read back; the MD5 both readings agree on. */
    private fun copyChecked(uri: String, target: File): String {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val digest = MessageDigest.getInstance("MD5")
        device.open(uri).use { input -> copyInto(input, part, digest) }
        val md5 = digest.hex()
        if (!part.md5().equals(md5, ignoreCase = true)) {
            part.delete()
            throw VerificationException("The kept original doesn't read back the same")
        }
        if (!part.renameTo(target)) throw IOException("Couldn't keep the original")
        return md5
    }

    /**
     * [json] with what a pasted edit must find again in this photo: its subject (for a new
     * background, background blur or subject masks) and its faces (for skin smoothing). Found
     * once; a resumed job keeps what it found.
     */
    private suspend fun completed(run: JobRun, original: Original, json: String): String {
        run.progress.recipe?.let { return it }
        var recipe = EditJson.decode(json)
        if (recipe.needsSubject && recipe.subjectMask == null) {
            recipe = recipe.copy(subjectMask = env.subjectMask(original.file))
        }
        val retouch = recipe.retouch
        if (retouch.skinSmoothing > 0f && retouch.faces.isEmpty()) {
            recipe = recipe.copy(retouch = retouch.copy(faces = env.faces(original.file)))
        }
        val done = EditJson.encode(recipe)
        run.save(run.progress.copy(recipe = done))
        return done
    }

    /** The edit rendered once into the work folder; a resumed job reuses it. */
    private suspend fun rendered(
        run: JobRun,
        original: Original,
        recipeJson: String,
        options: ExportOptions
    ): Pair<File, String> {
        val out = workFile(run, "edit")
        val done = run.progress.rendered
        val doneMd5 = run.progress.renderedMd5
        if (done == out.path && doneMd5 != null && out.isFile) return out to doneMd5
        val recipe = EditJson.decode(recipeJson)
        out.parentFile?.mkdirs()
        exporter.export(ExportRequest(original.file, original.mime, recipe, options, out))
        val md5 = out.md5()
        run.save(run.progress.copy(rendered = out.path, renderedMd5 = md5))
        return out to md5
    }

    /** Writes [file] over phone item [uri], once MediaStore allows it, and checks it landed. */
    private suspend fun overwrite(run: JobRun, uri: String, file: File, md5: String) {
        run.deviceWrite(WaitFor.DEVICE_WRITE, uri) {
            device.openOutput(uri).use { out -> file.inputStream().use { it.copyTo(out, BUFFER) } }
        }
        val (bytes, landed) = device.open(uri).use(::sizeAndMd5Of)
        if (bytes != file.length() || !landed.equals(md5, ignoreCase = true)) {
            throw VerificationException("The item doesn't read back as written")
        }
    }

    /** A download into the work folder that resumes, checked against [md5]. */
    private suspend fun downloaded(
        run: JobRun,
        md5: String,
        open: suspend (Long) -> InputStream
    ): File {
        val file = workFile(run, "orig")
        if (file.isFile && file.md5().equals(md5, ignoreCase = true)) return file
        file.parentFile?.mkdirs()
        val have = if (file.isFile) file.length() else 0L
        open(have).use { input ->
            FileOutputStream(file, have > 0).use { input.copyTo(it, BUFFER) }
        }
        if (!file.md5().equals(md5, ignoreCase = true)) {
            file.delete()
            throw VerificationException("The original from Drive doesn't match its checksum")
        }
        return file
    }

    /** The job's own files go: the rendered edit, and an original it downloaded to work on. */
    private fun finish(run: JobRun, original: Original) {
        run.progress.rendered?.let { File(it).delete() }
        if (original.file.path == workFile(run, "orig").path) original.file.delete()
    }

    private fun keptFile(src: FileRef): File {
        val key = MessageDigest.getInstance("SHA-1").digest(checkNotNull(src.mediaId).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val extension = splitName(src.name).second.ifEmpty { ".bin" }
        return File(originals, key + extension)
    }

    private fun workFile(run: JobRun, kind: String) = File(env.workDir, "${run.job.id}.$kind")

    private fun copyInto(input: InputStream, out: File, digest: MessageDigest) {
        FileOutputStream(out).use { output ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                output.write(buffer, 0, n)
            }
            output.fd.sync()
        }
    }

    private fun copyName(name: String, format: ExportFormat): String {
        val (base, extension) = splitName(name)
        val ext = format.extension?.let { ".$it" } ?: extension
        return "${base}_edited$ext"
    }

    private companion object {
        const val TAG = "Edits"
        const val BUFFER = 256 * 1024
    }
}
