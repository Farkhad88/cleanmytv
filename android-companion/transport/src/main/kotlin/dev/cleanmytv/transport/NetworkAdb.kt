package dev.cleanmytv.transport

import dev.cleanmytv.core.*
import io.github.muntashirakon.adb.PairingConnectionCtx
import org.conscrypt.Conscrypt
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.*

object NetworkAddress {
    fun validate(host: String, port: Int): InetAddress {
        require(port in 1..65535 && host.length in 1..253 && Regex("[A-Za-z0-9.:_%_-]+").matches(host)) { "Enter a valid local IP address and port (1–65535)." }
        val addresses = InetAddress.getAllByName(host.removePrefix("[").removeSuffix("]"))
        require(addresses.isNotEmpty() && addresses.all { it.isSiteLocalAddress || it.isLinkLocalAddress || it.address.firstOrNull()?.toInt()?.and(0xfe) == 0xfc || it.isLoopbackAddress }) {
            "Choose a TV on your local network. Public internet ADB endpoints are not supported."
        }
        return addresses.first()
    }
}

class NetworkAdb(private val credentials: Credentials) {
    fun connect(host: String, port: Int, onAllow: () -> Unit = {}): AdbClient {
        val address = NetworkAddress.validate(host, port)
        val key = credentials.identity()
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(address, port), 8000); socket.tcpNoDelay = true
            return AdbClient(TcpWire(socket, host, port, credentials.pin(host), key)).connect(key.key, key.publicKey, onAllow)
        } catch (e: Exception) { socket.close(); throw e }
    }
    fun pair(host: String, port: Int, code: String) {
        NetworkAddress.validate(host, port)
        require(Regex("[0-9]{6}").matches(code)) { "Use the six-digit code shown in the TV's pairing window." }
        val key = credentials.identity()
        // Pairing uses TLS exporter-bound SPAKE2; the server key is trusted only AFTER PAKE succeeds.
        PairingConnectionCtx(host, port, code.toByteArray(), key.key, key.certificate, "CleanMyTV Android").use { pairing ->
            pairing.start()
            credentials.savePin(host, pairing.peerCertificate)
        }
    }
    private class TcpWire(private val socket: Socket, val host: String, val port: Int, val pin: String?, val key: Credentials.Identity) : AdbWire {
        override val input get() = socket.getInputStream()
        override val output get() = socket.getOutputStream()
        override fun timeout(ms: Int) { socket.soTimeout = ms }
        override fun close() = socket.close()
        override fun upgradeTls(): AdbWire {
            require(pin != null) { "Pair with a code first, then connect using the port on the main Wireless debugging screen." }
            val manager = object : X509ExtendedKeyManager() {
                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("adb")
                override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "adb"
                override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
                override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
                override fun getCertificateChain(alias: String?) = arrayOf(key.certificate)
                override fun getPrivateKey(alias: String?): PrivateKey = key.key
            }
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw java.security.cert.CertificateException("Not a server.")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if(chain.isNullOrEmpty() || sha256(chain[0].publicKey.encoded) != pin) throw java.security.cert.CertificateException("The TV's paired key changed. Pair with the TV again.")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider())
            context.init(arrayOf(manager), arrayOf(trust), java.security.SecureRandom())
            val tls = context.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tls.soTimeout = 15_000; tls.enabledProtocols = arrayOf("TLSv1.3"); tls.startHandshake()
            return TcpWire(tls, host, port, pin, key)
        }
    }
}
