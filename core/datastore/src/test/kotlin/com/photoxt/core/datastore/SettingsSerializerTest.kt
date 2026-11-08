package com.photoxt.core.datastore

import androidx.datastore.core.CorruptionException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSerializerTest {
    @Test
    fun roundTripsAndReadsOlderOrNewerFilesWithDefaults() = runTest {
        val changed = PxtSettings(StorageSettings(retentionDays = 0, prefetch = 4))
        val out = ByteArrayOutputStream()
        SettingsSerializer.writeTo(changed, out)
        assertEquals(changed, read(out.toString()))

        // An older file lacks keys, a newer one has keys this version does not know.
        val partial = read("""{"storage":{"retentionDays":7,"futureKey":true}}""")
        assertEquals(StorageSettings(retentionDays = 7), partial.storage)
        assertEquals(null, partial.storage.copy(retentionDays = 0).retentionMs)
    }

    @Test(expected = CorruptionException::class)
    fun aDamagedFileIsReportedAsCorrupt() = runTest {
        read("{not json")
    }

    private suspend fun read(text: String): PxtSettings =
        SettingsSerializer.readFrom(ByteArrayInputStream(text.encodeToByteArray()))
}
