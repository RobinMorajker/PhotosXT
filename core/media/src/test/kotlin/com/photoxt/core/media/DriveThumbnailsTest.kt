package com.photoxt.core.media

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.auth.DriveTokens
import com.photoxt.core.database.AccountEntity
import com.photoxt.core.database.AlbumEntity
import com.photoxt.core.database.MediaEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.drive.DriveHttp
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Drive's thumbnail links are short-lived: an expired one is renewed once (spec §4.3). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriveThumbnailsTest {
    private val server = MockWebServer()
    private lateinit var database: PxtDatabase
    private lateinit var store: ThumbnailStore
    private lateinit var thumbnails: DriveThumbnails

    @Before
    fun setUp() {
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.startsWith("/old") -> MockResponse.Builder().code(403).build()

                    path == "/drive/v3/files/f1" -> {
                        // Like Drive, answer with only the fields asked for.
                        val file = mapOf(
                            "id" to "\"f1\"",
                            "thumbnailLink" to "\"${server.url("/new=s220")}\"",
                            "hasThumbnail" to "true"
                        )
                        val asked = request.url.queryParameter("fields").orEmpty().split(',')
                        val body = asked.mapNotNull { name -> file[name]?.let { "\"$name\":$it" } }
                        MockResponse.Builder().body(body.joinToString(",", "{", "}")).build()
                    }

                    path == "/new=s512" -> MockResponse.Builder().body(Buffer().write(png)).build()

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PxtDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val tokens = object : DriveTokens {
            override suspend fun token(accountId: String) = "test-token"

            override suspend fun invalidate(accountId: String, token: String) = Unit
        }
        store = ThumbnailStore(File(context.cacheDir, "thumbs-test").apply { deleteRecursively() })
        thumbnails =
            DriveThumbnails(database.driveDao(), DriveHttp(tokens, server.url("/")), tokens, store)
    }

    @After
    fun tearDown() {
        database.close()
        server.close()
    }

    @Test
    fun anExpiredLinkIsRenewedAndTheThumbnailSaved() = runTest {
        insert(link = server.url("/old=s220").toString())

        thumbnails.enqueue(ID, ThumbnailPriority.VISIBLE).await()

        assertTrue(store.grid("f1", 5).exists())
        val dao = database.driveDao()
        assertEquals(server.url("/new=s220").toString(), dao.driveItem(ID)?.thumbnailLink)
        assertNotNull(dao.blurhash(ID))
    }

    private suspend fun insert(link: String) {
        database.driveDao().upsertAccount(
            AccountEntity("a", "a@example.com", null, null, "", false, null, null, 1)
        )
        database.mediaIndexDao().upsertAlbums(
            listOf(
                AlbumEntity(
                    "drive:a:r",
                    AlbumType.DRIVE_FOLDER,
                    "drive:R/",
                    "R",
                    "a",
                    null,
                    "drive:a:r"
                )
            )
        )
        database.mediaIndexDao().upsertMedia(
            listOf(
                MediaEntity(
                    id = ID, source = SourceType.DRIVE, volume = null, contentUri = null,
                    albumId = "drive:a:r", name = "f1.png", mime = "image/png",
                    kind = MediaKind.IMAGE, bytes = 1_000, width = 64, height = 48,
                    rotation = 0, durationMs = null, takenAt = 1, modifiedAt = 1, dayKey = 0,
                    monthKey = 0, favorite = false, accountId = "a", remoteId = "f1",
                    remoteVersion = 5, thumbnailLink = link
                )
            )
        )
    }

    private companion object {
        const val ID = "drive:a:f1"
    }
}
