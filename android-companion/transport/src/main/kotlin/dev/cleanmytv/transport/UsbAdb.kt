package dev.cleanmytv.transport

import android.hardware.usb.*
import dev.cleanmytv.core.*
import java.io.InputStream
import java.io.OutputStream

/** Android USB Host bulk transfers. The OS permission dialog precedes any connection. */
class UsbAdb(private val manager: UsbManager, private val credentials: Credentials) {
    fun devices(): List<UsbDevice> = manager.deviceList.values.filter { find(it) != null }
    fun connect(device: UsbDevice, onAllow: () -> Unit = {}): AdbClient {
        require(manager.hasPermission(device)) { "Allow access to this USB TV in the Android permission dialog first." }
        val intf = find(device) ?: error("This USB device has no ADB interface.")
        val connection = manager.openDevice(device) ?: error("Android could not open the USB device.")
        require(connection.claimInterface(intf, true)) { "The USB ADB interface is busy." }
        val key = credentials.identity()
        return AdbClient(UsbWire(connection, intf)).connect(key.key, key.publicKey, onAllow)
    }
    private fun find(d: UsbDevice) = (0 until d.interfaceCount).map(d::getInterface).firstOrNull {
        it.interfaceClass == 255 && it.interfaceSubclass == 66 && it.interfaceProtocol == 1 && it.endpointCount >= 2
    }
    private class UsbWire(private val connection: UsbDeviceConnection, private val intf: UsbInterface) : AdbWire {
        private val endpoints = (0 until intf.endpointCount).map(intf::getEndpoint)
        private val incoming = endpoints.first { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        private val outgoing = endpoints.first { it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        private var timeout = 30_000
        override fun timeout(ms: Int) { timeout = ms }
        override val input = object : InputStream() {
            private val buffer = ByteArray(256 * 1024)
            private var at = 0; private var end = 0
            override fun read(): Int { val b = ByteArray(1); return if(read(b, 0, 1) == 1) b[0].toInt() and 255 else -1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if(len == 0) return 0
                if(at == end) { end = connection.bulkTransfer(incoming, buffer, buffer.size, timeout); at = 0; if(end <= 0) throw java.io.IOException("USB connection ended or timed out.") }
                val count = minOf(len, end - at); buffer.copyInto(b, off, at, at + count); at += count; return count
            }
        }
        override val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray, off: Int, len: Int) {
                var sent = 0
                while(sent < len) {
                    val count = connection.bulkTransfer(outgoing, b, off + sent, minOf(16 * 1024, len - sent), timeout)
                    if(count <= 0) throw java.io.IOException("USB write failed.")
                    sent += count
                }
            }
        }
        override fun close() { connection.releaseInterface(intf); connection.close() }
    }
}
