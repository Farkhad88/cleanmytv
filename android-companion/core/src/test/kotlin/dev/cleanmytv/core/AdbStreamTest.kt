package dev.cleanmytv.core

import dev.cleanmytv.core.AdbClient.Packet
import dev.cleanmytv.core.AdbClient.Companion.CNXN
import dev.cleanmytv.core.AdbClient.Companion.VERSION
import dev.cleanmytv.core.AdbClient.Companion.OPEN
import dev.cleanmytv.core.AdbClient.Companion.OKAY
import dev.cleanmytv.core.AdbClient.Companion.WRTE
import dev.cleanmytv.core.AdbClient.Companion.CLSE
import dev.cleanmytv.core.AdbClient.Companion.decode
import dev.cleanmytv.core.AdbClient.Companion.encode
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Real loopback byte transport: worker assertions and timeouts fail the test, never disappear. */
internal class FakeAdb(private val script: Peer.() -> Unit) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private var accepted: Socket? = null
    private val result = FutureTask {
        server.accept().use { socket ->
            accepted = socket
            socket.soTimeout = 4000
            socket.tcpNoDelay = true
            val peer = Peer(socket)
            assertEquals(CNXN, peer.read().command)
            peer.send(Packet(CNXN, VERSION, 4096, "device::fake\u0000".toByteArray()))
            peer.script()
        }
    }
    private val worker = thread(name = "fake-adb", isDaemon = true) { result.run() }
    private val socket = Socket(InetAddress.getLoopbackAddress(), server.localPort).apply { tcpNoDelay = true }
    val wire = object : AdbWire {
        override val input = socket.getInputStream()
        override val output = socket.getOutputStream()
        override fun timeout(ms: Int) { socket.soTimeout = ms }
        override fun close() { socket.close() }
    }
    val client = AdbClient(wire)
    fun connect(): AdbClient = client.connect(keys.private, keys.public as RSAPublicKey)
    fun finish() { result.get(6, TimeUnit.SECONDS) }
    override fun close() {
        client.close(); accepted?.close(); server.close(); worker.join(1000)
        // Propagate peer assertion failures even when the client failed first.
        if (result.isDone) result.get(1, TimeUnit.SECONDS)
    }
    class Peer(private val socket: Socket) {
        fun read() = decode(socket.getInputStream())
        fun send(p: Packet, fragmented: Boolean = false) {
            val bytes = encode(p)
            if (fragmented) bytes.forEach { socket.getOutputStream().write(it.toInt()) }
            else socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }
        fun raw(bytes: ByteArray) { socket.getOutputStream().write(bytes); socket.getOutputStream().flush() }
        fun open(): Packet = read().also {
            assertEquals(OPEN, it.command); assertTrue(it.arg0 > 0); assertEquals(0, it.arg1)
        }
        fun reply(open: Packet, text: String, remote: Int = 7, fragments: List<ByteArray> = listOf(text.toByteArray())) {
            send(Packet(OKAY, remote, open.arg0))
            fragments.forEach {
                send(Packet(WRTE, remote, open.arg0, it), fragmented = true)
                val ack = read(); assertEquals(OKAY, ack.command)
                assertEquals(open.arg0, ack.arg0); assertEquals(remote, ack.arg1)
            }
            send(Packet(CLSE, remote, open.arg0))
            val close = read(); assertEquals(CLSE, close.command)
            assertEquals(open.arg0, close.arg0); assertEquals(remote, close.arg1)
        }
        fun awaitDisconnect() { assertEquals(-1, socket.getInputStream().read()) }
    }
    companion object {
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}

class AdbStreamTest {
    @Test fun multipleConsecutiveCommandsAndRemoteIdReuse() {
        FakeAdb { repeat(8) { reply(open(), "response $it\n") } }.use { fake ->
            val client = fake.connect()
            repeat(8) { assertEquals("response $it\n", client.shell("echo $it")) }
            fake.finish()
        }
    }

    @Test fun lateAndDuplicateCloseAreRoutedToTheirRetiredStream() {
        FakeAdb {
            val previous = open(); reply(previous, "first")
            val current = open()
            repeat(2) { send(Packet(CLSE, 7, previous.arg0)) }
            reply(current, "second")
        }.use { fake ->
            val client = fake.connect()
            assertEquals("first", client.shell("first"))
            assertEquals("second", client.shell("second"))
            fake.finish()
        }
    }

    @Test fun delayedOkayForRetiredStreamDoesNotOpenCurrentStream() {
        FakeAdb {
            val previous = open(); reply(previous, "first")
            val current = open()
            send(Packet(OKAY, 7, previous.arg0))
            reply(current, "second", 8)
        }.use { fake ->
            val client = fake.connect()
            assertEquals("first", client.shell("first"))
            assertEquals("second", client.shell("second"))
            fake.finish()
        }
    }

    @Test fun lateZeroSenderCloseAfterRemoteSocketDeletionIsAcceptedOnlyForRetiredId() {
        FakeAdb {
            val previous = open(); reply(previous, "first")
            val current = open()
            repeat(2) { send(Packet(CLSE, 0, previous.arg0)) }
            reply(current, "second")
        }.use { fake ->
            val client = fake.connect(); assertEquals("first", client.shell("first"))
            assertEquals("second", client.shell("second")); fake.finish()
        }
    }

    @Test fun remoteIdsUseAllUnsigned32Bits() {
        FakeAdb { reply(open(), "high-bit remote ID", remote = Int.MIN_VALUE) }.use { fake ->
            assertEquals("high-bit remote ID", fake.connect().shell("probe")); fake.finish()
        }
    }

    @Test fun fragmentedWritesPreserveSplitUtf8AndAcknowledgeEachChunk() {
        val text = "TV: \u20ac\r\nready\n".toByteArray()
        FakeAdb { reply(open(), "", fragments = text.map { byteArrayOf(it) }) }.use { fake ->
            assertEquals("TV: \u20ac\nready\n", fake.connect().shell("getprop"))
            fake.finish()
        }
    }

    private fun rejects(packet: (Packet) -> Packet, afterOkay: Boolean = false) {
        FakeAdb {
            val open = open()
            if (afterOkay) send(Packet(OKAY, 7, open.arg0))
            send(packet(open)); awaitDisconnect()
        }.use { fake ->
            val client = fake.connect()
            assertThrows(AdbProtocolException::class.java) { client.shell("probe") }
            assertThrows(IllegalStateException::class.java) { client.shell("must not reopen") }
            fake.finish()
        }
    }
    @Test fun writeBeforeOkayIsFatal() = rejects({ Packet(WRTE, 7, it.arg0, "bad".toByteArray()) })
    @Test fun unknownStreamIsFatal() = rejects({ Packet(CLSE, 7, it.arg0 + 99) })
    @Test fun changedRemoteIdIsFatal() = rejects({ Packet(WRTE, 8, it.arg0) }, afterOkay = true)
    @Test fun mismatchedCloseIsFatal() = rejects({ Packet(CLSE, 8, it.arg0) }, afterOkay = true)
    @Test fun duplicateActiveOkayIsFatal() = rejects({ Packet(OKAY, 7, it.arg0) }, afterOkay = true)
    @Test fun rejectedOpenIsNotSuccessfulEmptyOutput() = rejects({ Packet(CLSE, 0, it.arg0) })
    @Test fun anonymousCloseCannotCloseAnActiveStream() = rejects({ Packet(CLSE, 0, it.arg0) }, afterOkay = true)
    @Test fun controlPayloadIsFatal() = rejects({ Packet(OKAY, 7, it.arg0, byteArrayOf(1)) })

    @Test fun lateControlBetweenWritesDoesNotContaminateOutput() {
        FakeAdb {
            val previous = open(); reply(previous, "old")
            val current = open(); send(Packet(OKAY, 8, current.arg0))
            send(Packet(WRTE, 8, current.arg0, "one".toByteArray())); assertEquals(OKAY, read().command)
            send(Packet(CLSE, 7, previous.arg0)); send(Packet(OKAY, 7, previous.arg0))
            send(Packet(WRTE, 8, current.arg0, "two".toByteArray())); assertEquals(OKAY, read().command)
            send(Packet(CLSE, 8, current.arg0)); assertEquals(CLSE, read().command)
        }.use { fake ->
            val client = fake.connect(); assertEquals("old", client.shell("first"))
            assertEquals("onetwo", client.shell("second")); fake.finish()
        }
    }

    private fun rejectsRetiredPacket(command: Int, remote: Int) {
        FakeAdb {
            val previous = open(); reply(previous, "old"); open()
            send(Packet(command, remote, previous.arg0)); awaitDisconnect()
        }.use { fake ->
            val client = fake.connect(); client.shell("first")
            assertThrows(AdbProtocolException::class.java) { client.shell("second") }; fake.finish()
        }
    }
    @Test fun oldIdWithWrongRemoteIsFatal() = rejectsRetiredPacket(CLSE, 99)
    @Test fun dataForClosedStreamIsFatal() = rejectsRetiredPacket(WRTE, 7)

    @Test fun retiredHistoryAndLatePacketProcessingAreBounded() {
        FakeAdb {
            val previous = open(); reply(previous, "old"); open()
            // One TCP write avoids racing the expected client teardown on the final packet.
            raw((1..129).flatMap { encode(Packet(CLSE, 7, previous.arg0)).asIterable() }.toByteArray())
            awaitDisconnect()
        }.use { fake ->
            val client = fake.connect(); client.shell("first")
            assertThrows(AdbProtocolException::class.java) { client.shell("second") }; fake.finish()
        }
    }

    @Test fun evictedStreamIsNoLongerTrusted() {
        FakeAdb {
            val first = open(); reply(first, "0")
            repeat(128) { reply(open(), "next") }
            open(); send(Packet(CLSE, 7, first.arg0)); awaitDisconnect()
        }.use { fake ->
            val client = fake.connect(); repeat(129) { client.shell("probe") }
            assertThrows(AdbProtocolException::class.java) { client.shell("last") }; fake.finish()
        }
    }

    @Test fun concurrentCallersAreSerializedThroughTheWholeCloseHandshake() {
        val firstOpened = CountDownLatch(1); val release = CountDownLatch(1)
        FakeAdb {
            val first = open(); firstOpened.countDown(); check(release.await(3, TimeUnit.SECONDS))
            reply(first, "first"); reply(open(), "second")
        }.use { fake ->
            val client = fake.connect()
            val a = FutureTask { client.shell("first") }; val b = FutureTask { client.shell("second") }
            thread(isDaemon = true) { a.run() }; check(firstOpened.await(3, TimeUnit.SECONDS))
            thread(isDaemon = true) { b.run() }; release.countDown()
            assertEquals("first", a.get(4, TimeUnit.SECONDS)); assertEquals("second", b.get(4, TimeUnit.SECONDS))
            fake.finish()
        }
    }

    @Test fun closeInterruptsBlockedReadAndNewTransportCanReconnect() {
        val opened = CountDownLatch(1)
        FakeAdb { open(); opened.countDown(); awaitDisconnect() }.use { fake ->
            val client = fake.connect()
            val read = FutureTask { assertThrows(IOException::class.java) { client.shell("waiting") } }
            thread(isDaemon = true) { read.run() }; check(opened.await(3, TimeUnit.SECONDS))
            client.close(); client.close(); read.get(2, TimeUnit.SECONDS)
            assertThrows(IllegalStateException::class.java) { client.shell("after close") }; fake.finish()
        }
        FakeAdb { reply(open(), "reconnected") }.use { fake ->
            assertEquals("reconnected", fake.connect().shell("probe")); fake.finish()
        }
    }

    @Test fun delayedOpenAcknowledgementIsAcceptedBeforeWrites() {
        val opened = CountDownLatch(1); val release = CountDownLatch(1)
        FakeAdb {
            val open = open(); opened.countDown(); check(release.await(3, TimeUnit.SECONDS)); reply(open, "ready")
        }.use { fake ->
            val client = fake.connect(); val task = FutureTask { client.shell("probe") }
            thread(isDaemon = true) { task.run() }; check(opened.await(3, TimeUnit.SECONDS))
            assertFalse(task.isDone); release.countDown(); assertEquals("ready", task.get(4, TimeUnit.SECONDS)); fake.finish()
        }
    }

    @Test fun invalidChecksumClosesSession() {
        FakeAdb {
            val open = open(); send(Packet(OKAY, 7, open.arg0))
            val bytes = encode(Packet(WRTE, 7, open.arg0, byteArrayOf(1))); bytes[16] = 0
            raw(bytes); awaitDisconnect()
        }.use { fake ->
            assertThrows(AdbProtocolException::class.java) { fake.connect().shell("probe") }; fake.finish()
        }
    }

    @Test fun staleOkayDoesNotAuthorizeWriteOnUnopenedCurrentStream() {
        FakeAdb {
            val previous = open(); reply(previous, "first")
            val current = open(); send(Packet(OKAY, 7, previous.arg0))
            send(Packet(WRTE, 7, current.arg0, "must not accept".toByteArray())); awaitDisconnect()
        }.use { fake ->
            val client = fake.connect(); client.shell("first")
            assertThrows(AdbProtocolException::class.java) { client.shell("second") }; fake.finish()
        }
    }

    @Test fun noOpenAcknowledgementTimesOutAndClosesTheTransport() {
        FakeAdb { open(); awaitDisconnect() }.use { fake ->
            assertThrows(java.net.SocketTimeoutException::class.java) { fake.connect().shell("probe", 150) }
            assertThrows(IllegalStateException::class.java) { fake.client.shell("after timeout") }; fake.finish()
        }
    }

    @Test fun oversizedFrameHeaderIsRejectedBeforeReadingBody() {
        FakeAdb {
            val open = open()
            val head = encode(Packet(WRTE, 7, open.arg0))
            java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(12, AdbClient.LIMIT + 1)
            raw(head); awaitDisconnect()
        }.use { fake ->
            assertThrows(AdbProtocolException::class.java) { fake.connect().shell("probe") }; fake.finish()
        }
    }

    @Test fun negotiatedPayloadLimitIsEnforced() = rejects({ Packet(WRTE, 7, it.arg0, ByteArray(4097)) }, afterOkay = true)
    @Test fun zeroRemoteIdIsInvalid() = rejects({ Packet(OKAY, 0, it.arg0) })
    @Test fun negativeLocalIdIsInvalid() = rejects({ Packet(CLSE, 7, -1) })
    @Test fun connectionPacketsDuringShellAreInvalid() = rejects({ Packet(CNXN, VERSION, 4096) })

    @Test fun shellCannotRunBeforeAuthentication() {
        val wire = object : AdbWire {
            override val input = byteArrayOf().inputStream()
            override val output = java.io.ByteArrayOutputStream()
            override fun timeout(ms: Int) = Unit
            override fun close() = Unit
        }
        AdbClient(wire).use { assertThrows(IllegalStateException::class.java) { it.shell("probe") } }
        assertEquals(0, wire.output.size())
    }
}
