package dev.cleanmytv.core

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.nio.file.Files
import java.io.*
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

class FakeTv : Shell {
    override val maxData = 4096
    var id = Identity("Test TV", "test_tv", "Test", "SERIAL001", "1234567890abcdef", "test/firmware/1", 30, "1", true)
    val settings = mutableMapOf("window_animation_scale" to "1", "transition_animation_scale" to "1", "animator_duration_scale" to "1")
    val packages = mutableMapOf("com.vendor.ads" to true,"com.android.shell" to true,"com.vendor.launcher" to true)
    var failNextWrite = false
    var home = "com.vendor.launcher/.Main"
    val writes = mutableListOf<String>()
    override fun close() {}
    override fun shell(command: String, timeoutMs: Int): String {
        if(command == Identity.PROBE) return listOf(id.model,id.device,id.manufacturer,id.serial,id.androidId,id.fingerprint,id.sdk.toString(),id.secure,"feature:android.software.leanback").joinToString("\n")
        if(command.startsWith("(")) {
            val c = command.substring(1,command.indexOf("); __cmtv_rc=")); writes += c
            if(failNextWrite) { failNextWrite=false; throw IOException("network interrupted") }
            if(c.startsWith("settings put")) { val p=c.split(' '); settings[p[3]]=p.drop(4).joinToString(" ").trim('\'') }
            if(c.startsWith("settings delete")) settings[c.split(' ')[3]]="null"
            if(c.startsWith("pm disable-user")) packages[c.substringAfterLast(' ')]=false
            if(c.startsWith("pm enable")) packages[c.substringAfterLast(' ')]=true
            return "\n__CMTV_EXIT__:0\n"
        }
        if(command == HOME_QUERY) return home
        if(command == HOME_CLAIMS) return home
        if(command == PACKAGE_QUERY) return packages.keys.joinToString("\n"){"package:$it"}+"\n__DISABLED__\n"+packages.filterValues{!it}.keys.joinToString("\n"){"package:$it"}
        if(command.startsWith("pm list packages")) return packages.filter { !command.contains(" -d") || !it.value }.keys.joinToString("\n"){"package:$it"}
        if(command.startsWith("settings get")) return settings[command.substringAfterLast(' ')] ?: "null"
        if(command.startsWith("dumpsys package")) return "versionName=1.7.1"
        if(command == provider("get")) return "Result: Bundle[{config={\"slides_on_tv\":1,\"themes_on_tv\":28,\"lock_pin_set\":true}}]"
        return ""
    }
}
class SafetyTest {
    private fun setup(fake:FakeTv=FakeTv()): Triple<FakeTv,SelectedTv,OperationEngine> {
        val tv=SelectedTv(fake,fake.id,"local:5555")
        return Triple(fake,tv,OperationEngine(tv,RecoveryStore(Files.createTempDirectory("cmtv-tests").toFile())))
    }
    @Test fun preparationDoesNotMutate() { val(f,t,e)=setup(); e.prepare(Planner(t).animation("0.5")); assertTrue(f.writes.isEmpty()) }
    @Test fun approvedSettingsAreVerifiedAndRestoreOriginal() {
        val(f,t,e)=setup(); val p=e.prepare(Planner(t).animation("0.5")); assertTrue(e.execute(p.id,p.digest).success)
        val restore=e.prepareRestore(e.store.get(p.id)); assertTrue(e.execute(restore.id,restore.digest).success); assertEquals("1",f.settings["window_animation_scale"])
    }
    @Test fun approvalsCannotReplay() { val(_,t,e)=setup();val p=e.prepare(Planner(t).animation("0.5"));e.execute(p.id,p.digest);assertThrows(IllegalArgumentException::class.java){e.execute(p.id,p.digest)} }
    @Test fun wrongDigestIsRejected() { val(f,t,e)=setup(); val p=e.prepare(Planner(t).animation("0"));assertThrows(IllegalArgumentException::class.java){e.execute(p.id,"bad")};assertTrue(f.writes.isEmpty()) }
    @Test fun identitySwapBlocksAllWrites() { val(f,t,e)=setup();val p=e.prepare(Planner(t).animation("0"));f.id=f.id.copy(serial="OTHER");assertThrows(IllegalArgumentException::class.java){e.execute(p.id,p.digest)};assertTrue(f.writes.isEmpty()) }
    @Test fun protectedOwnerTvIsReadOnly() { val f=FakeTv();f.id=f.id.copy(model="MiTV-MZTU1");val(_,t,e)=setup(f);assertThrows(IllegalArgumentException::class.java){e.prepare(Planner(t).animation("0"))};assertThrows(IllegalArgumentException::class.java){RemoteControl.send(t,19)};assertTrue(f.writes.isEmpty()) }
    @Test fun changedStateRejectsStaleSnapshot() { val(f,t,e)=setup();val p=e.prepare(Planner(t).animation("0"));f.settings["window_animation_scale"]="2";assertFalse(e.execute(p.id,p.digest).success);assertTrue(f.writes.isEmpty()) }
    @Test fun interruptedWriteRunsCheckedRecovery() { val(f,t,e)=setup();val p=e.prepare(Planner(t).animation("0"));f.failNextWrite=true;val result=e.execute(p.id,p.digest);assertFalse(result.success);assertTrue(result.rollbackVerified);assertEquals("rolled-back",e.store.get(p.id).phase) }
    @Test fun corePackagesAndLaunchersCannotBeDisabled() { val(f,t,_)=setup();for(pkg in listOf("com.android.shell","com.vendor.launcher"))assertThrows(IllegalArgumentException::class.java){Planner(t).packageChanges(setOf(pkg),emptySet())};assertTrue(f.writes.isEmpty()) }
    @Test fun packageRestorationPreservesOriginalState() { val(f,t,e)=setup();val p=e.prepare(Planner(t).packageChanges(setOf("com.vendor.ads"),emptySet()));assertTrue(e.execute(p.id,p.digest).success);assertFalse(f.packages.getValue("com.vendor.ads"));val r=e.prepareRestore(e.store.get(p.id));assertTrue(e.execute(r.id,r.digest).success);assertTrue(f.packages.getValue("com.vendor.ads")) }
    @Test fun profileRequiresExactModelAndSdk() { val(_,t,_)=setup();val p=Profile("other","other","Test",listOf("Other TV"),30,emptyList());assertFalse(p.matches(t.identity));assertThrows(IllegalArgumentException::class.java){Planner(t).packageChanges(setOf("com.vendor.ads"),emptySet(),p)} }
    @Test fun existingPrivateMediaAndPinCannotBeOverwritten() { val(_,t,_)=setup();assertThrows(IllegalArgumentException::class.java){Planner(t).pin("1234")};assertThrows(IllegalArgumentException::class.java){Media.slides(t,listOf(byteArrayOf(-1,-40,0,0)))} }
    @Test fun recordCorruptionIsDetected() { val(_,t,e)=setup();val p=e.prepare(Planner(t).animation("0"));val file=File(e.store.directory,p.id+".json");val envelope=JSONObject(file.readText());file.writeText(envelope.put("sha256","wrong").toString());assertThrows(IllegalArgumentException::class.java){e.store.get(p.id)} }
    @Test fun mediaChunksFitLegacyPayloadAndRoundTrip() { val bytes=ByteArray(19000){(it%256).toByte()};val chunks=Media.pieces(bytes,4096);assertTrue(chunks.all { it.length+800<=4096 });assertArrayEquals(bytes,java.util.Base64.getDecoder().decode(chunks.joinToString(""))) }
    @Test fun fireplaceHashesRejectDamagedAssets() { assertThrows(IllegalArgumentException::class.java){Media.verify(Media.fireplace[0],byteArrayOf(1,2))} }
    @Test fun homeSchemaRejectsUnsupportedAndUnsafeValues() { for(text in listOf("{\"columns\":99}","{\"layout\":\"invented\"}","{\"order\":[\"foo;reboot\"]}","{\"unknown\":true}"))assertThrows(IllegalArgumentException::class.java){HomeSchema.validate(JSONObject(text))} }
    @Test fun jsonProviderHandlesQuotedBraces() { val o=config("Result: Bundle[{config={\"greeting\":\"hello } { \\\"world\\\"\",\"layout\":\"shelf\"}}]");assertEquals("shelf",o.getString("layout")) }
    @Test fun packetCodecRejectsDamageAndFragmentationWorks() {
        val p=AdbClient.Packet(AdbClient.WRTE,2,3,"payload".toByteArray());val bytes=AdbClient.encode(p)
        val fragmented=object:FilterInputStream(bytes.inputStream()){override fun read(b:ByteArray,off:Int,len:Int)=super.read(b,off,minOf(1,len))}
        assertArrayEquals(p.data,AdbClient.decode(fragmented).data);bytes[20]=0;assertThrows(IllegalArgumentException::class.java){AdbClient.decode(bytes.inputStream())}
    }
    @Test fun rsaSignsOnlyTwentyByteAdbChallenge() { val k=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair();val token=ByteArray(20){it.toByte()};val signature=AdbKey.sign(k.private,token);val decoded=Cipher.getInstance("RSA/ECB/PKCS1Padding").run{init(Cipher.DECRYPT_MODE,k.public);doFinal(signature)};assertArrayEquals(token,decoded.takeLast(20).toByteArray());assertEquals(524,AdbKey.publicBytes(k.public as RSAPublicKey).size);assertThrows(IllegalArgumentException::class.java){AdbKey.sign(k.private,ByteArray(21))} }
}
