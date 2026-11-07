package dev.cleanmytv.core

import java.io.*
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
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

/** Strict, serial ADB client shared by TCP and USB. No desktop ADB executable is involved. */
class AdbClient(private var wire: AdbWire) : Shell {
    override var maxData: Int = 4096; private set
    var banner: String = ""; private set
    private var nextId = 1
    private var closed = false
    data class Packet(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = byteArrayOf())

    fun connect(key: PrivateKey, publicKey: RSAPublicKey, onAllow: () -> Unit = {}): AdbClient {
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
            return this
        } catch (e: Exception) { close(); throw e }
    }

    @Synchronized override fun shell(command: String, timeoutMs: Int): String {
        check(!closed) { "The TV connection is closed. Reconnect in Setup." }
        require(!command.contains('\u0000'))
        val service = "shell:$command\u0000".toByteArray()
        require(service.size <= maxData) { "The TV's ADB payload limit is too small for this command." }
        wire.timeout(timeoutMs)
        val local = nextId++
        var remote = 0
        val out = ByteArrayOutputStream()
        try {
            send(Packet(OPEN, local, 0, service))
            while (true) {
                val p = read()
                require(p.arg1 == local) { "ADB replied on an unexpected stream." }
                when (p.command) {
                    OKAY -> { require(remote == 0 || remote == p.arg0); remote = p.arg0 }
                    WRTE -> {
                        require(remote != 0 && remote == p.arg0) { "Unopened ADB stream." }
                        require(out.size() + p.data.size <= 64 * 1024 * 1024) { "ADB response is too large." }
                        out.write(p.data); send(Packet(OKAY, local, remote))
                    }
                    CLSE -> { send(Packet(CLSE, local, p.arg0)); return out.toString("UTF-8").replace("\r\n", "\n") }
                    else -> error("Unexpected ADB stream packet.")
                }
            }
        } catch (e: Exception) { close(); throw e }
    }

    private fun send(p: Packet) { wire.output.write(encode(p)); wire.output.flush() }
    private fun read() = decode(wire.input)
    override fun close() { closed = true; runCatching { wire.close() } }

    companion object {
        const val VERSION = 0x01000000
        const val LIMIT = 256 * 1024
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
