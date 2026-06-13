package app.pillion.core

import platform.Foundation.NSDate
import platform.Foundation.NSThread
import platform.Foundation.timeIntervalSince1970

actual fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun sleepMs(ms: Long) {
    NSThread.sleepForTimeInterval(ms / 1000.0)
}
