package app.pillion.android

import android.app.Application
import app.pillion.core.Logger
import com.smartdevicelink.util.DebugTool

/**
 * Application for the Android app. Enables SDL's internal logging and installs a guard against a known
 * SDL library crash (the `RouterServiceMessageEmitter` NPE that fires when the router Messenger drops
 * mid-send during transport churn). Without the guard that uncaught exception kills the whole app.
 */
class PillionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugTool.enableDebugTool()
        installSdlCrashGuard()
    }

    private fun installSdlCrashGuard() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            if (isSdlEmitterNpe(thread, ex)) {
                Logger.d("ignored SDL RouterServiceMessageEmitter NPE (transport churn)")
                return@setDefaultUncaughtExceptionHandler
            }
            prev?.uncaughtException(thread, ex)
        }
    }

    private fun isSdlEmitterNpe(thread: Thread, ex: Throwable): Boolean {
        if (ex !is NullPointerException) return false
        if (thread.name?.contains("RouterServiceMessageEmitter") == true) return true
        return ex.stackTrace.any {
            it.className.contains("TransportBroker") || it.className.contains("RouterServiceMessageEmitter")
        }
    }
}
