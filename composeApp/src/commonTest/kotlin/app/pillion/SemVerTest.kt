package app.pillion

import app.pillion.core.SemVer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemVerTest {
    @Test fun patch_minor_major_bumps_are_newer() {
        assertTrue(SemVer.isNewer("0.1.1-alpha", "0.1.0-alpha"))
        assertTrue(SemVer.isNewer("0.2.0", "0.1.9"))
        assertTrue(SemVer.isNewer("1.0.0", "0.9.9"))
        assertTrue(SemVer.isNewer("v0.1.0-alpha", "0.0.9"))
    }

    @Test fun same_or_older_is_not_newer() {
        assertFalse(SemVer.isNewer("0.1.0-alpha", "0.1.0-alpha"))
        assertFalse(SemVer.isNewer("0.1.0-alpha", "0.1.0")) // stable outranks alpha
        assertFalse(SemVer.isNewer("0.1.0", "0.2.0"))
    }
}
