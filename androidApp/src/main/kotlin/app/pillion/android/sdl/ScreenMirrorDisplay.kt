package app.pillion.android.sdl

import android.content.Context
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Bundle
import android.view.Display
import android.view.Surface
import android.view.TextureView
import app.pillion.core.Logger
import com.smartdevicelink.streaming.video.SdlRemoteDisplay

/**
 * Casts the phone's live screen to the dash — the Pillion idea over SDL. MediaProjection mirrors the
 * device display (AUTO_MIRROR) into a TextureView inside this SdlRemoteDisplay; SDL encodes that to
 * H.264 and streams it to the head unit. The MediaProjection is supplied via [ProjectionHolder].
 *
 * Android 14+ enforces ONE virtual display per MediaProjection instance: a second
 * createVirtualDisplay() on the same grant throws and kills the whole projection. The SDL remote
 * display window can be recreated (texture destroyed/rebound) several times per session, so the
 * virtual display lives at process level, bound to the projection instance that paid for it;
 * rebinds just re-point its surface at the new TextureView.
 */
class ScreenMirrorDisplay(context: Context, display: Display) : SdlRemoteDisplay(context, display) {

    companion object {
        @Volatile private var sharedVd: VirtualDisplay? = null
        @Volatile private var sharedFor: MediaProjection? = null

        /** Drop the cached mirror display (projection died / new grant); the next bind re-creates it. */
        fun releaseShared() {
            val vd = sharedVd
            sharedVd = null
            sharedFor = null
            runCatching { vd?.release() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val texture = TextureView(context)
        setContentView(texture)

        val size = Point()
        display.getSize(size)
        val dpi = context.resources.displayMetrics.densityDpi

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val mp = ProjectionHolder.projection
                if (mp == null) {
                    Logger.d("screen mirror: no MediaProjection — grant screen capture first")
                    return
                }
                st.setDefaultBufferSize(size.x, size.y)
                val surface = Surface(st)
                try {
                    val existing = sharedVd
                    if (existing != null && sharedFor === mp) {
                        // Rebind: the projection already paid for its one-and-only virtual display.
                        existing.resize(size.x, size.y, dpi)
                        existing.surface = surface
                        Logger.d("screen mirror: re-bound existing virtual display -> dash ${size.x}x${size.y}")
                    } else {
                        runCatching { sharedVd?.release() }
                        sharedVd = mp.createVirtualDisplay(
                            "pillion-sdl-mirror", size.x, size.y, dpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            surface, null, null,
                        )
                        sharedFor = mp
                        Logger.d("screen mirror started -> dash ${size.x}x${size.y}")
                    }
                } catch (t: Throwable) {
                    Logger.e("screen mirror createVirtualDisplay failed", t)
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                // Keep the shared virtual display alive: releasing it here would force a second
                // createVirtualDisplay on rebind, which Android 14+ rejects and which kills the
                // projection for the whole session.
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    override fun onStop() {
        // The shared VD intentionally outlives this window (see class doc).
        super.onStop()
    }

    override fun onViewResized(width: Int, height: Int) {
        Logger.d("dash video surface: ${width}x$height")
    }
}
