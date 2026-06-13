package app.pillion.ios

import app.pillion.core.Logger
import app.pillion.core.ScreenSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIImage
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.Foundation.NSData
import platform.ReplayKit.RPScreenRecorder
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy
import kotlin.concurrent.Volatile

/**
 * Captures the device screen with ReplayKit and exposes the most recent frame as a
 * JPEG, matching the [ScreenSource] contract the shared [app.pillion.core.MirrorEngine]
 * pulls from — the iOS equivalent of Android's MediaProjection source.
 *
 * Each ReplayKit video sample is downscaled and JPEG-encoded on the capture thread; the
 * engine reads [latestFrame] at its own (throttled) pace and drops anything in between,
 * so encode cost never backs up the capture pipeline.
 */
@OptIn(ExperimentalForeignApi::class)
class ReplayKitScreenSource(
    private val targetWidth: Int = 480,
    private val jpegQuality: Double = 0.6,
) : ScreenSource {
    private val recorder = RPScreenRecorder.sharedRecorder()

    @Volatile
    private var latest: ByteArray? = null

    override fun start() {
        recorder.startCaptureWithHandler(
            captureHandler = { sampleBuffer, _, error ->
                if (error != null) {
                    Logger.e("replaykit: ${error.localizedDescription}")
                    return@startCaptureWithHandler
                }
                // Audio samples carry no image buffer, so encode() returns null for them —
                // that's our video filter, which avoids depending on the RPSampleBufferType enum.
                if (sampleBuffer != null) encode(sampleBuffer)?.let { latest = it }
            },
            completionHandler = { error ->
                if (error != null) Logger.e("replaykit start failed: ${error.localizedDescription}")
                else Logger.d("replaykit: capture started")
            },
        )
    }

    override fun latestFrame(): ByteArray? = latest

    override fun stop() {
        recorder.stopCaptureWithHandler { error ->
            if (error != null) Logger.e("replaykit stop: ${error.localizedDescription}")
        }
        latest = null
    }

    private fun encode(sampleBuffer: CMSampleBufferRef): ByteArray? {
        val pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) ?: return null
        val srcWidth = CVPixelBufferGetWidth(pixelBuffer).toDouble()
        val srcHeight = CVPixelBufferGetHeight(pixelBuffer).toDouble()
        if (srcWidth <= 0.0 || srcHeight <= 0.0) return null
        val targetW = targetWidth.toDouble()
        val targetH = targetW * srcHeight / srcWidth // preserve aspect ratio

        // Draw the (CIImage-backed) frame into a fresh bitmap context, then JPEG-encode that —
        // a bitmap-backed UIImage is what UIImageJPEGRepresentation needs (a raw CIImage has none).
        val frame = UIImage.imageWithCIImage(CIImage.imageWithCVPixelBuffer(pixelBuffer))
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(targetW, targetH), true, 1.0)
        frame.drawInRect(CGRectMake(0.0, 0.0, targetW, targetH))
        val bitmap = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        val data = bitmap?.let { UIImageJPEGRepresentation(it, jpegQuality) } ?: return null
        return data.toByteArray()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { memcpy(it.addressOf(0), bytes, len.convert()) }
    return out
}
