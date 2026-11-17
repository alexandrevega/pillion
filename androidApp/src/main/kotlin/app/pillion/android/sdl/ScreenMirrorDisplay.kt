package app.pillion.android.sdl

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import app.pillion.core.Logger
import com.smartdevicelink.streaming.video.SdlRemoteDisplay

/**
 * Casts the phone's live screen to the dash — the Pillion idea over SDL. MediaProjection mirrors the
 * device display (AUTO_MIRROR) into a TextureView inside this SdlRemoteDisplay; SDL encodes that to
 * H.264 and streams it to the head unit. The MediaProjection is supplied via [ProjectionHolder].
 * While the phone is locked and dash mode is on, frames from [SdlDashFeed] cover the mirror instead.
 */
class ScreenMirrorDisplay(context: Context, display: Display) : SdlRemoteDisplay(context, display) {

    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dashView: ImageView? = null
    private var lastDashFrame: ByteArray? = null

    // Poll the dash feed at the helper's frame rate; a no-op unless the phone is locked.
    private val dashTick = object : Runnable {
        override fun run() {
            showDashFrame()
            handler.postDelayed(this, DASH_FRAME_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val texture = TextureView(context)
        val dash = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        dashView = dash
        setContentView(
            FrameLayout(context).apply {
                addView(texture, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                addView(dash, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            },
        )
        handler.post(dashTick)

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

    private fun showDashFrame() {
        val view = dashView ?: return
        val feed = SdlDashFeed.current
        if (feed == null || !feed.active) {
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
                view.setImageDrawable(null)
                lastDashFrame = null
                Logger.d("sdl dash: back to phone mirror")
            }
            return
        }
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
            Logger.d("sdl dash: phone locked — showing dedicated dash on the bike")
        }
        val jpeg = feed.latestFrame() ?: return
        if (jpeg === lastDashFrame) return
        lastDashFrame = jpeg
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let(view::setImageBitmap)
    }

    override fun onStop() {
        handler.removeCallbacks(dashTick)
        virtualDisplay?.release()
        virtualDisplay = null
        super.onStop()
    }

    override fun onViewResized(width: Int, height: Int) {
        Logger.d("dash video surface: ${width}x$height")
    }

    private companion object {
        const val DASH_FRAME_MS = 66L // ~15 fps, the helper's encode rate
    }
}
