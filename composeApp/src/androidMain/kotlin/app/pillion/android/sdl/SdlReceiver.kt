package app.pillion.android.sdl

import android.content.Context
import android.content.Intent
import android.os.Build
import com.smartdevicelink.transport.SdlBroadcastReceiver
import com.smartdevicelink.transport.SdlRouterService as LibSdlRouterService

/** Starts the SDL session service when the head unit connects over USB. */
class SdlReceiver : SdlBroadcastReceiver() {

    override fun onSdlEnabled(context: Context, intent: Intent) {
        // Respect a user Stop — don't auto-revive while they've turned mirroring off.
        if (SdlService.isUserStopped(context)) return
        intent.setClass(context, SdlService::class.java)
        // The USB auto-attach IS the Motorize-replacement path → register as Garmin Motorize. Without
        // this the service would default to a generic appId and ping-pong against any Garmin session.
        intent.putExtra(SdlService.EXTRA_APP_ID, SdlService.APP_ID_GARMIN)
        intent.putExtra(SdlService.EXTRA_AUTO, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    override fun defineLocalSdlRouterClass(): Class<out LibSdlRouterService> = SdlRouterService::class.java
}
