package app.pillion.core

/** Guards JPEG delivery until the helper confirms the selected CCU size. */
data class DashSizeRequest(val revision: Int, val width: Int, val height: Int)

class DashFrameGate {
    private var revision = 0
    private var requested: DashSizeRequest? = null
    private var ready = false

    fun request(width: Int, height: Int): DashSizeRequest {
        require(width > 0 && height > 0)
        revision++
        ready = false
        return DashSizeRequest(revision, width, height).also { requested = it }
    }

    fun reconnect(): DashSizeRequest? = requested?.let { request(it.width, it.height) }

    fun acknowledge(revision: Int, width: Int, height: Int): Boolean {
        val req = requested
        ready = req != null && req.revision == revision &&
            req.width == width && req.height == height
        return ready
    }

    fun accepts(width: Int, height: Int): Boolean =
        ready && requested?.let { it.width == width && it.height == height } == true

    fun isReady(): Boolean = ready
    fun pause() { ready = false }

    companion object {
        const val SIZE_READY_MARKER = -1
    }
}
