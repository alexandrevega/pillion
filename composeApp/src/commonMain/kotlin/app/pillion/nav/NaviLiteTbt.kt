package app.pillion.nav

import app.pillion.protocol.FRAME_TYPE_PHONE
import app.pillion.protocol.NaviLiteCodec
import app.pillion.protocol.PDT_POINTER
import app.pillion.protocol.PDT_VALUE
import app.pillion.protocol.ServiceType

/**
 * Bridges a [Route]'s maneuvers onto NaviLite turn-by-turn frames the dash renders natively.
 *
 * These frames drive the structured overlay around the map image (content-update `01 00`) AND the
 * image-free screen-off path (`02 00`, which pauses JPEG frames). Icon ordinals are Garmin
 * StreetCross's `NavInfo.TurnArrowIconType`; see docs/PROTOCOL.md.
 */
object NaviLiteTbt {

    /** StreetCross turn-arrow icon ordinals (docs/PROTOCOL.md). */
    object Icon {
        const val ARRIVING = 0
        const val ARRIVING_L = 1
        const val ARRIVING_R = 2
        const val BEAR_KEEP_L = 6
        const val BEAR_KEEP_R = 7
        const val CONTINUE = 8
        const val DRIVE_TO = 9
        const val EXIT_L = 10
        const val EXIT_R = 11
        const val EXIT_UNSPEC = 12
        const val FERRY = 13
        const val RNDABT_GENERIC = 14
        const val SHARPTURN_L = 32
        const val SHARPTURN_R = 33
        const val TURN_L = 34
        const val TURN_R = 35
        const val UTURN_L = 36
        const val UTURN_R = 37
        const val INVALID = 69
    }

    /** Canonical [Maneuver] -> dash turn-icon ordinal. */
    fun iconOf(m: Maneuver): Int = when (m) {
        Maneuver.DEPART, Maneuver.CONTINUE, Maneuver.MERGE, Maneuver.FORK -> Icon.CONTINUE
        Maneuver.KEEP_LEFT, Maneuver.SLIGHT_LEFT -> Icon.BEAR_KEEP_L
        Maneuver.KEEP_RIGHT, Maneuver.SLIGHT_RIGHT -> Icon.BEAR_KEEP_R
        Maneuver.TURN_LEFT -> Icon.TURN_L
        Maneuver.TURN_RIGHT -> Icon.TURN_R
        Maneuver.SHARP_LEFT -> Icon.SHARPTURN_L
        Maneuver.SHARP_RIGHT -> Icon.SHARPTURN_R
        Maneuver.UTURN_LEFT -> Icon.UTURN_L
        Maneuver.UTURN_RIGHT -> Icon.UTURN_R
        Maneuver.ROUNDABOUT -> Icon.RNDABT_GENERIC
        Maneuver.EXIT_LEFT -> Icon.EXIT_L
        Maneuver.EXIT_RIGHT -> Icon.EXIT_R
        Maneuver.EXIT -> Icon.EXIT_UNSPEC
        Maneuver.FERRY -> Icon.FERRY
        Maneuver.ARRIVE -> Icon.ARRIVING
        Maneuver.ARRIVE_LEFT -> Icon.ARRIVING_L
        Maneuver.ARRIVE_RIGHT -> Icon.ARRIVING_R
        Maneuver.UNKNOWN -> Icon.INVALID
    }

    /**
     * Content-update frame (service 55). [tbtOnly] = true sends `02 00` (dash renders turn-by-turn
     * natively, JPEG frames pause — the screen-off path); false sends `01 00` (nav image mode).
     */
    fun contentUpdate(tbtOnly: Boolean): ByteArray =
        frame(ServiceType.CONTENT_UPDATE, PDT_VALUE, byteArrayOf(if (tbtOnly) 0x02 else 0x01, 0x00))

    /** CUR_ROAD_NAME_UPDATE (3): raw UTF-8 road name, no length prefix. */
    fun roadName(name: String): ByteArray =
        frame(ServiceType.ROAD, PDT_POINTER, name.encodeToByteArray())

    /** SPEED_LIMIT_UPDATE (17): limit Float32 LE + unit string. Pass 0f for "no known limit". */
    fun speedLimit(limit: Float, unit: String = "km/h"): ByteArray =
        frame(ServiceType.SPEED_LIMIT, PDT_POINTER, floatLe(limit) + unit.encodeToByteArray())

    /**
     * NEXT_TURN_DIST_UPDATE (4): turnIcon UInt8, distance Float32 LE, unit string, next-road string.
     *
     * The exact framing of the two trailing strings is not fully pinned down in docs/PROTOCOL.md.
     * We emit `unit`, a 0x00 separator, then `nextRoad`; CONFIRM on real hardware (Owain) before
     * relying on it. [icon] comes from [iconOf].
     */
    fun nextTurn(icon: Int, distanceMeters: Float, unit: String = "m", nextRoad: String = ""): ByteArray {
        val payload = byteArrayOf(icon.toByte()) +
            floatLe(distanceMeters) +
            unit.encodeToByteArray() + byteArrayOf(0) +
            nextRoad.encodeToByteArray()
        return frame(ServiceType.NEXT_TURN_DIST, PDT_POINTER, payload)
    }

    /**
     * Convenience: the frames to push for [step] given the [remainingMeters] to its maneuver.
     * Order matches StreetCross's setup-style burst; callers send them in sequence.
     */
    fun framesFor(step: RouteStep, remainingMeters: Float, speedLimitKph: Float? = null): List<ByteArray> =
        buildList {
            step.roadName?.let { add(roadName(it)) }
            add(nextTurn(iconOf(step.maneuver), remainingMeters, nextRoad = step.roadName.orEmpty()))
            speedLimitKph?.let { add(speedLimit(it)) }
        }

    private fun frame(service: Int, pdt: Int, payload: ByteArray): ByteArray =
        NaviLiteCodec.build(FRAME_TYPE_PHONE, service, pdt, payload)

    private fun floatLe(f: Float): ByteArray {
        val bits = f.toRawBits()
        return byteArrayOf(
            (bits and 0xff).toByte(),
            ((bits ushr 8) and 0xff).toByte(),
            ((bits ushr 16) and 0xff).toByte(),
            ((bits ushr 24) and 0xff).toByte(),
        )
    }
}
