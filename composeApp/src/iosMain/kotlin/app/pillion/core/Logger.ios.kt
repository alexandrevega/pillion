package app.pillion.core

actual object Logger {
    actual fun d(message: String) {
        println("Pillion: $message")
    }

    actual fun e(message: String, error: Throwable?) {
        println("Pillion ERROR: $message" + (error?.let { " — $it" } ?: ""))
    }
}
