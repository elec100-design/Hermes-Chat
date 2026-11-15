package com.hermes.chatandroid.glasses

import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesRotationTest {
    @Test fun exifWins() {
        assertEquals(90, GlassesCamera.rotationFor(ExifInterface.ORIENTATION_ROTATE_90, 1440, 1080))
        assertEquals(270, GlassesCamera.rotationFor(ExifInterface.ORIENTATION_ROTATE_270, 1080, 1440))
        assertEquals(0, GlassesCamera.rotationFor(ExifInterface.ORIENTATION_NORMAL, 1440, 1080))
    }

    @Test fun missingExifLandscapeTurnsClockwise() {
        // 11-15 실측: EXIF 없는 1440x1080 이 왼쪽으로 누워 옴
        assertEquals(90, GlassesCamera.rotationFor(ExifInterface.ORIENTATION_UNDEFINED, 1440, 1080))
        assertEquals(0, GlassesCamera.rotationFor(ExifInterface.ORIENTATION_UNDEFINED, 1080, 1440))
    }
}
