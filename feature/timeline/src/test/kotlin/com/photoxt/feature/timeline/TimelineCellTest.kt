package com.photoxt.feature.timeline

import com.photoxt.core.database.TimelineRow
import com.photoxt.core.model.MediaKind
import com.photoxt.core.ui.GridCell
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineCellTest {
    @Test
    fun aSidewaysPhotoReportsItsShownSize() {
        // MediaStore stores the sensor's 4080 x 3060 with an orientation of 90 degrees.
        val upright = row(rotation = 0).toCell() as GridCell.Item
        val turned = row(rotation = 90).toCell() as GridCell.Item
        assertEquals(4080 to 3060, upright.width to upright.height)
        assertEquals(3060 to 4080, turned.width to turned.height)
    }

    private fun row(rotation: Int) = TimelineRow(
        isHeader = false,
        groupKey = 1,
        id = "device:1",
        contentUri = "content://media/external/images/media/1",
        kind = MediaKind.IMAGE,
        durationMs = null,
        favorite = false,
        modifiedAt = 1,
        takenAt = 1,
        width = 4080,
        height = 3060,
        rotation = rotation
    )
}
