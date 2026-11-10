package com.photoxt.core.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Batch rename patterns and PDF page layout (spec §4.7, batch tools). */
@RunWith(AndroidJUnit4::class)
class BatchToolsTest {
    private val taken = Instant.parse("2026-11-08T09:05:07Z").toEpochMilli()

    @Test
    fun patternsNumberDateAndKeepTheExtension() {
        assertEquals(
            "20261108_03.HEIC",
            RenamePattern("{date}_{n}", ZoneOffset.UTC).nameFor("IMG_1.HEIC", taken, 2, 12)
        )
        assertEquals(
            "Goa 090507 IMG_1 007.jpg",
            RenamePattern("Goa {time} {name} {n}", ZoneOffset.UTC)
                .nameFor("IMG_1.jpg", taken, 6, 150)
        )
        // Characters a file name can't hold, and an empty result.
        assertEquals("a_b.jpg", RenamePattern("a/b", ZoneOffset.UTC).nameFor("x.jpg", taken, 0, 1))
        assertEquals("x.jpg", RenamePattern("  ", ZoneOffset.UTC).nameFor("x.jpg", taken, 0, 1))
    }

    @Test
    fun keepBothSuffixesBeforeTheExtension() {
        assertEquals("IMG_1 (2).jpg", suffixed("IMG_1.jpg", 2))
        assertEquals("notes (1)", suffixed("notes", 1))
        assertEquals(".hidden (1)", suffixed(".hidden", 1))
    }

    @Test
    fun photosFitTheirPageCentred() {
        val wide = fitInPage(4000, 3000, 842, 595, 24)
        assertEquals(24f, wide.top, 0.01f)
        assertEquals(595f - 24f, wide.bottom, 0.01f)
        assertEquals(842f / 2, wide.centerX(), 0.01f)
        val tall = fitInPage(1000, 4000, 595, 842, 24)
        assertEquals(24f, tall.top, 0.01f)
        assertEquals(595f / 2, tall.centerX(), 0.01f)
    }

    @Test
    fun hugePhotosAreSampledDownToTheLimit() {
        assertEquals(1, sampleFor(12_000_000, 50_000_000))
        assertEquals(2, sampleFor(200_000_000, 50_000_000))
        assertEquals(4, sampleFor(201_000_000, 50_000_000))
    }
}
