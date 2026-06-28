package app.pillion.android.sdl

import android.content.Intent
import android.media.projection.MediaProjection

/** Hands the screen-capture grant + live MediaProjection from the Activity to the SDL service/display. */
object ProjectionHolder {
    @Volatile var resultCode: Int = 0
    @Volatile var resultData: Intent? = null
    @Volatile var projection: MediaProjection? = null
}
