package dev.cleanmytv.core

import java.io.*
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.net.SocketTimeoutException
import javax.crypto.Cipher

/** One authenticated device transport. Implementations must never reconnect to another endpoint. */
interface Shell : Closeable {
    val maxData: Int
    fun shell(command: String, timeoutMs: Int = 30_000): String
}

interface AdbWire : Closeable {
    val input: InputStream
    val output: OutputStream
    fun timeout(ms: Int)
    fun upgradeTls(): AdbWire = error("This endpoint requires Wireless Debugging pairing.")
}

object AdbKey {
    fun sign(privateKey: PrivateKey, token: ByteArray): ByteArray {
        require(token.size == 20) { "The device sent an invalid ADB authentication challenge." }
        val prefix = "3021300906052b0e03021a05000414".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, privateKey); doFinal(prefix + token)
        }
    }
    fun publicBytes(key: RSAPublicKey): ByteArray {
        require(key.modulus.bitLength() == 2048)
        val n = key.modulus
        val word = BigInteger.ONE.shiftLeft(32)
        val out = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(64).putInt(word.subtract(n.mod(word).modInverse(word)).toInt())
        for (value in listOf(n, BigInteger.ONE.shiftLeft(4096).mod(n))) {
            var x = value
            repeat(64) { out.putInt(x.toInt()); x = x.shiftRight(32) }
        }
        return out.putInt(key.publicExponent.toInt()).array()
    }
    fun publicText(key: RSAPublicKey) = Base64.getEncoder().encodeToString(publicBytes(key)) + " CleanMyTV@Android\u0000"
}

class AdbProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** One reader and one active shell stream per authenticated TCP/USB session. */
class AdbClient(private var wire: AdbWire) : Shell {
    override var maxData: Int = 4096; private set
    var banner: String = ""; private set
    private var nextId = 1L // Never reuse a local ID within this transport, including on overflow.
    private val closed = AtomicBoolean(false)
    @Volatile private var connected = false
    private val retired = LinkedHashMap<Int, Int>()
    private class Stream(val local: Int, var remote: Int = 0)
    data class Packet(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = byteArrayOf())

    @Synchronized fun connect(key: PrivateKey, publicKey: RSAPublicKey, onAllow: () -> Unit = {}): AdbClient {
        check(!closed.get() && !connected) { "Start a new transport to reconnect to the TV." }
        try {
            wire.timeout(10_000)
            send(Packet(CNXN, VERSION, LIMIT, "host::\u0000".toByteArray()))
            var p = read()
            if (p.command == STLS) {
                send(Packet(STLS, VERSION, 0))
                wire = wire.upgradeTls()
                p = read()
            }
            var signatures = 0
            while (p.command == AUTH && p.arg0 == 1) {
                require(p.data.size == 20) { "Invalid ADB challenge length." }
                when (signatures++) {
                    0 -> send(Packet(AUTH, 2, 0, AdbKey.sign(key, p.data)))
                    1 -> { send(Packet(AUTH, 3, 0, AdbKey.publicText(publicKey).toByteArray())); onAllow(); wire.timeout(120_000) }
                    else -> error("ADB authorization was rejected. Choose Allow on the TV and reconnect.")
                }
                p = read()
            }
            require(p.command == CNXN && p.arg0 >= VERSION && p.arg1 in 1024..(16 * 1024 * 1024)) { "Invalid ADB connection reply." }
            maxData = minOf(p.arg1, LIMIT)
            banner = p.data.toString(Charsets.UTF_8).trimEnd('\u0000')
            require(banner.startsWith("device::")) { "The endpoint is not an ADB device." }
            check(!closed.get()) { "The TV connection was closed during authentication." }
            connected = true
            return this
        } catch (e: Exception) { close(); throw e }
    }

    @Synchronized override fun shell(command: String, timeoutMs: Int): String {
        check(!closed.get() && connected) { "The TV connection is closed or unauthenticated. Reconnect in Setup." }
        require(!command.contains('\u0000'))
        require(timeoutMs > 0) { "The ADB command timeout must be positive." }
        val service = "shell:$command\u0000".toByteArray()
        require(service.size <= maxData) { "The TV's ADB payload limit is too small for this command." }
        val out = ByteArrayOutputStream()
        try {
            protocol(nextId <= Int.MAX_VALUE) { "ADB stream IDs exhausted. Reconnect to the TV." }
            val stream = Stream((nextId++).toInt())
            val deadline = System.nanoTime() + timeoutMs.toLong() * 1_000_000
            var latePackets = 0
            wire.timeout(timeoutMs)
            send(Packet(OPEN, stream.local, 0, service))
            while (true) {
                val p = read(deadline)
                protocol(p.command in setOf(OKAY, WRTE, CLSE) && p.arg1 > 0) { "Unexpected ADB stream packet." }
                protocol(p.command == WRTE || p.data.isEmpty()) { "ADB control packet contained data." }
                if (p.arg1 != stream.local) {
                    // Only exact, recently closed pairs can receive late control notifications.
                    // Never acknowledge again (close ping-pong), reopen a stream, or discard data.
                    val oldRemote = retired[p.arg1]
                    // adbd may answer our final OKAY after deleting its socket with CLSE(0, local).
                    val knownClose = p.command == CLSE && (p.arg0 == oldRemote || p.arg0 == 0)
                    val knownOkay = p.command == OKAY && p.arg0 == oldRemote
                    protocol(oldRemote != null && (knownClose || knownOkay)) {
                        "ADB replied on an unknown or mismatched stream. Reconnect to the TV."
                    }
                    protocol(++latePackets <= RETIRED_LIMIT) { "Too many late ADB notifications." }
                    continue
                }
                when (p.command) {
                    OKAY -> {
                        protocol(stream.remote == 0 && p.arg0 != 0) { "ADB stream opened more than once or with an invalid ID." }
                        stream.remote = p.arg0
                    }
                    WRTE -> {
                        protocol(stream.remote != 0 && stream.remote == p.arg0) { "ADB data arrived before OPEN acknowledgement or from a different peer." }
                        protocol(out.size() + p.data.size <= 64 * 1024 * 1024) { "ADB response is too large." }
                        out.write(p.data); send(Packet(OKAY, stream.local, stream.remote))
                    }
                    CLSE -> {
                        protocol(stream.remote != 0) { "The TV rejected the ADB shell stream." }
                        protocol(stream.remote == p.arg0) { "ADB close arrived from a different peer." }
                        send(Packet(CLSE, stream.local, stream.remote))
                        retired[stream.local] = stream.remote
                        if (retired.size > RETIRED_LIMIT) retired.remove(retired.keys.first())
                        if (closed.get()) throw IOException("The TV connection was closed.")
                        return out.toString("UTF-8").replace("\r\n", "\n")
                    }
                }
            }
        } catch (e: Exception) { close(); throw e }
    }

    private fun send(p: Packet) { wire.output.write(encode(p)); wire.output.flush() }
    private inline fun protocol(valid: Boolean, message: () -> String) {
        if (!valid) throw AdbProtocolException(message())
    }
    private fun read() = decode(wire.input)
    private fun read(deadline: Long): Packet {
        // Reapply a decreasing deadline before *each* fragmented read, including late packets.
        val input = object : FilterInputStream(wire.input) {
            private fun remaining() {
                if (closed.get()) throw IOException("The TV connection is closed.")
                val nanos = deadline - System.nanoTime()
                if (nanos <= 0) throw SocketTimeoutException("The ADB command timed out.")
                wire.timeout(((nanos + 999_999) / 1_000_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            override fun read(): Int { remaining(); return `in`.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int { remaining(); return `in`.read(b, off, len) }
        }
        val packet = try { decode(input) } catch (e: IllegalArgumentException) {
            throw AdbProtocolException(e.message ?: "Invalid ADB packet.", e)
        }
        protocol(packet.data.size <= maxData) { "ADB packet exceeded the negotiated payload limit." }
        return packet
    }
    // Do not take the shell monitor: closing must interrupt a blocked read from another thread.
    override fun close() { if (closed.compareAndSet(false, true)) { connected = false; runCatching { wire.close() } } }

    companion object {
        const val VERSION = 0x01000000
        const val LIMIT = 256 * 1024
        private const val RETIRED_LIMIT = 128
        const val CNXN = 0x4e584e43; const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f; const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43; const val WRTE = 0x45545257; const val STLS = 0x534c5453
        fun encode(p: Packet): ByteArray = ByteBuffer.allocate(24 + p.data.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(p.command).putInt(p.arg0).putInt(p.arg1).putInt(p.data.size)
            .putInt(p.data.sumOf { it.toInt() and 255 }).putInt(p.command.inv()).put(p.data).array()
        fun decode(input: InputStream): Packet {
            val source = DataInputStream(input)
            val head = ByteArray(24); source.readFully(head)
            val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = b.int; val a = b.int; val c = b.int; val size = b.int; val sum = b.int; val magic = b.int
            require(cmd in setOf(CNXN, AUTH, OPEN, OKAY, CLSE, WRTE, STLS) && magic == cmd.inv()) { "Damaged ADB packet header." }
            require(size in 0..LIMIT) { "Oversized ADB packet." }
            val data = ByteArray(size); source.readFully(data)
            require(data.sumOf { it.toInt() and 255 } == sum) { "Damaged ADB packet checksum." }
            return Packet(cmd, a, c, data)
        }
    }
}
