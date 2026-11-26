package app.pairline.android.files

import app.pairline.android.protocol.RequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** D-088: the photo list's paging cursor and limits. */
class PhotoCursorTest {
    @Test
    fun cursors() {
        assertEquals(null to 0L, PhotoLibrary.parseCursor(null))
        assertEquals(1_764_000_000L to 42L, PhotoLibrary.parseCursor("1764000000:42"))
        for (bad in listOf("x", "1:2:3", "a:1", "1:b")) {
            try {
                PhotoLibrary.parseCursor(bad)
                fail("accepted $bad")
            } catch (_: RequestException) {
            }
        }
    }

    @Test
    fun limits() {
        assertEquals(30, PhotoLibrary.limit(null))
        assertEquals(60, PhotoLibrary.limit(500))
        assertEquals(1, PhotoLibrary.limit(0))
    }
}
