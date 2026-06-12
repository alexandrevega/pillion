package app.pillion.core

import android.util.Log

actual object Logger {
    actual fun d(message: String) { Log.d(TAG, message) }
    actual fun e(message: String, error: Throwable?) { Log.e(TAG, message, error) }
    private const val TAG = "Pillion"
}
