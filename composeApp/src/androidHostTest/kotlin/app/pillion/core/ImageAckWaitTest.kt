package app.pillion.core

import app.pillion.protocol.ServiceType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ImageAckWaitTest {
    private fun ack(seq: Int) = NaviFrameView(
        ServiceType.IMAGE_ACK,
        byteArrayOf((seq and 0xff).toByte(), ((seq ushr 8) and 0xff).toByte()),
    )

    @Test fun matchingSequenceAcknowledgesFrame() = runBlocking {
        val incoming = Channel<NaviFrameView>(Channel.UNLIMITED)
        incoming.send(ack(0x1234))
        assertTrue(awaitImageAck(incoming, 0x1234, 500))
    }

    @Test fun lateAndMalformedAcksCannotAcknowledgeNextFrame() = runBlocking {
        val incoming = Channel<NaviFrameView>(Channel.UNLIMITED)
        val ignored = mutableListOf<Int?>()
        incoming.send(ack(100))
        incoming.send(NaviFrameView(ServiceType.IMAGE_ACK, byteArrayOf(1)))
        incoming.send(ack(102))
        assertTrue(awaitImageAck(incoming, 102, 500) { ignored += it })
        assertEquals(listOf(100, null), ignored)
    }

    @Test fun unrelatedMessagesDoNotResetDeadline() = runBlocking {
        val incoming = Channel<NaviFrameView>(Channel.UNLIMITED)
        repeat(20) { incoming.send(NaviFrameView(ServiceType.IMAGE, byteArrayOf())) }
        assertFalse(awaitImageAck(incoming, 17, 30))
    }

    @Test fun missingAckTimesOutWithoutClosingReader() = runBlocking {
        val incoming = Channel<NaviFrameView>(Channel.UNLIMITED)
        assertFalse(awaitImageAck(incoming, 7, 30))
        incoming.send(ack(7))
        incoming.send(ack(8))
        assertTrue(awaitImageAck(incoming, 8, 500))
    }

    @Test fun closedReceiverPropagatesTransportFailure() {
        // JUnit4 requires a void test method; assertFailsWith returns the caught exception.
        runBlocking {
            val incoming = Channel<NaviFrameView>(Channel.UNLIMITED)
            incoming.close(IllegalStateException("connection lost"))
            assertFailsWith<IllegalStateException> {
                awaitImageAck(incoming, 9, 500)
            }
        }
    }

    @Test fun ackSequenceUsesUnsignedLittleEndian() {
        assertEquals(0xffff, imageAckSequence(ack(0xffff).payload))
        assertEquals(null, imageAckSequence(byteArrayOf(1)))
    }
}
