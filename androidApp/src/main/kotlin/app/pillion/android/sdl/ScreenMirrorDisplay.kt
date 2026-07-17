package app.pillion.android.sdl

import android.content.Context
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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
 */
class ScreenMirrorDisplay(context: Context, display: Display) : SdlRemoteDisplay(context, display) {

    private var virtualDisplay: VirtualDisplay? = null

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
                    virtualDisplay = mp.createVirtualDisplay(
                        "pillion-sdl-mirror", size.x, size.y, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface, null, null,
                    )
                    Logger.d("screen mirror started -> dash ${size.x}x${size.y}")
                } catch (t: Throwable) {
                    Logger.e("screen mirror createVirtualDisplay failed", t)
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                virtualDisplay?.release()
                virtualDisplay = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    override fun onStop() {
        virtualDisplay?.release()
        virtualDisplay = null
        super.onStop()
    }

    override fun onViewResized(width: Int, height: Int) {
        Logger.d("dash video surface: ${width}x$height")
    }
}
