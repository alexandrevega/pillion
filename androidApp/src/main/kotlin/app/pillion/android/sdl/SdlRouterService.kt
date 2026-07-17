package app.pillion.android.sdl

/**
 * Required by SDL: hosts (or joins) the multiplex transport to the head unit. Empty subclass — all
 * behaviour is in the library. MUST be declared in the manifest in its own process
 * (`android:process="com.smartdevicelink.router"`) or SDL self-destructs ("Not using correct process").
 */
class SdlRouterService : com.smartdevicelink.transport.SdlRouterService()
