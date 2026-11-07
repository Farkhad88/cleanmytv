package dev.cleanmytv.core
import org.junit.Test
import org.junit.Assert.*
import java.net.*
import java.io.*
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import kotlin.concurrent.thread
class AdbIntegrationTest {
 @Test fun directSocketReadsFragmentedShellResponse() {
  val server=ServerSocket(0,1,InetAddress.getLoopbackAddress())
  val worker=thread {
   server.accept().use { socket ->
    val input=socket.getInputStream();val out=socket.getOutputStream()
    assertEquals(AdbClient.CNXN,AdbClient.decode(input).command)
    out.write(AdbClient.encode(AdbClient.Packet(AdbClient.CNXN,AdbClient.VERSION,4096,"device::test\u0000".toByteArray())));out.flush()
    val open=AdbClient.decode(input);assertTrue(open.data.toString(Charsets.UTF_8).startsWith("shell:getprop"))
    out.write(AdbClient.encode(AdbClient.Packet(AdbClient.OKAY,7,open.arg0)));out.flush()
    val bytes=AdbClient.encode(AdbClient.Packet(AdbClient.WRTE,7,open.arg0,"Test TV\n".toByteArray()))
    bytes.forEach { out.write(it.toInt());out.flush() };assertEquals(AdbClient.OKAY,AdbClient.decode(input).command)
    out.write(AdbClient.encode(AdbClient.Packet(AdbClient.CLSE,7,open.arg0)));out.flush()
    AdbClient.decode(input)
   }
  }
  val socket=Socket(InetAddress.getLoopbackAddress(),server.localPort)
  val wire=object:AdbWire {override val input=socket.getInputStream();override val output=socket.getOutputStream();override fun timeout(ms:Int){socket.soTimeout=ms};override fun close(){socket.close()} }
  val keys=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
  AdbClient(wire).connect(keys.private,keys.public as RSAPublicKey).use { assertEquals("Test TV\n",it.shell("getprop ro.product.model")) }
  worker.join(3000);assertFalse(worker.isAlive);server.close()
 }
 @Test fun homeInstallationPlanStartsBeforeLauncherDisable() {
  val fake=FakeTv();val tv=SelectedTv(fake,fake.id,"fake:5555")
  // Already installed 1.7.1 needs only the safe launch and handover steps.
  val apk=byteArrayOf(1,2,3);val plan=Installer(tv).install(apk,sha256(apk),fake.home)
  assertTrue(plan.steps[0].commands.single().startsWith("am start"))
  assertTrue(plan.steps[0].after.any{it.command.startsWith("pidof")})
  assertTrue(plan.steps[1].commands[0].startsWith("pm disable-user"))
  assertTrue(fake.writes.isEmpty())
 }
}
