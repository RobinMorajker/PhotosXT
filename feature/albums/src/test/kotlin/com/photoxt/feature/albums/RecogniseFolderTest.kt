package com.photoxt.feature.albums

import org.junit.Assert.assertEquals
import org.junit.Test

class RecogniseFolderTest {
    @Test
    fun recognisesTheFoldersEveryPhoneHas() {
        val cases = mapOf(
            "DCIM/Camera/" to DeviceFolder.CAMERA,
            "DCIM/Screenshots/" to DeviceFolder.SCREENSHOTS,
            "Pictures/Screenshots/" to DeviceFolder.SCREENSHOTS,
            "Download/" to DeviceFolder.DOWNLOADS,
            "Download/Telegram/" to DeviceFolder.DOWNLOADS,
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/" to DeviceFolder.WHATSAPP,
            "DCIM/Camera2/" to DeviceFolder.OTHER,
            "Pictures/" to DeviceFolder.OTHER,
            "" to DeviceFolder.OTHER
        )
        cases.forEach { (path, folder) -> assertEquals(path, folder, recogniseFolder(path)) }
    }
}
