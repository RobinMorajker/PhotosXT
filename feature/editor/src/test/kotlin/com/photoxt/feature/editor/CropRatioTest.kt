package com.photoxt.feature.editor

import com.photoxt.core.model.CropAspect
import com.photoxt.core.model.Geometry
import com.photoxt.core.render.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropRatioTest {
    @Test
    fun typedRatiosAreReadAndNonsenseIsNot() {
        assertEquals(1.5f, typedRatio(" 3:2 ")!!, 1e-6f)
        assertEquals(1.5f, typedRatio("3 x 2")!!, 1e-6f)
        assertEquals(1.91f, typedRatio("1,91/1")!!, 1e-6f)
        val nonsense = listOf("", "3", "3:0", "0:3", "a:b", "-3:2", "30:1")
        nonsense.forEach { assertNull(it, typedRatio(it)) }
    }

    @Test
    fun eachAspectLocksItsOwnRatio() {
        val frame = Size(4000, 3000)
        assertNull(Geometry().lockedRatio(frame))
        assertEquals(4f / 3f, Geometry(aspect = CropAspect.ORIGINAL).lockedRatio(frame)!!, 1e-6f)
        assertEquals(1f, Geometry(aspect = CropAspect.SQUARE).lockedRatio(frame)!!, 1e-6f)
        val custom = Geometry(aspect = CropAspect.CUSTOM, customRatio = 2.35f)
        assertEquals(2.35f, custom.lockedRatio(frame)!!, 1e-6f)
    }
}
