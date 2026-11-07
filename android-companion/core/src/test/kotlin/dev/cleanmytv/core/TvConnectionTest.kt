package dev.cleanmytv.core

import dev.cleanmytv.core.AdbClient.Packet
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TvConnectionTest {
    private val id = Identity("MiTV-MZTU1", "MZTU1", "Xiaomi", "TEST-SERIAL", "1234567890abcdef",
        "Xiaomi/test/Android14", 34, "1", true)
    private val endpoint = "loopback-fake-only:5555"
    private val commands = listOf(Identity.PROBE, "pm list packages --user 0", "pm list packages -d --user 0",
        HOME_QUERY, "dumpsys package $HOME", provider("get"), HOME_CLAIMS, provider("apps"), Identity.PROBE)

    private fun identityText(identity: Identity) = listOf(identity.model, identity.device, identity.manufacturer,
        identity.serial, identity.androidId, identity.fingerprint, identity.sdk.toString(), identity.secure,
        if (identity.tv) "feature:android.software.leanback" else "feature:android.hardware.bluetooth").joinToString("\n") + "\n"
    private fun response(command: String): String = when (command) {
        Identity.PROBE -> identityText(id)
        "pm list packages --user 0" -> "package:com.android.shell\npackage:com.vendor.launcher\npackage:$HOME\n"
        "pm list packages -d --user 0" -> ""
        HOME_QUERY, HOME_CLAIMS -> "com.vendor.launcher/.Main\n"
        "dumpsys package $HOME" -> "versionName=1.7.1\n"
        provider("get") -> "Result: Bundle[{config={\"layout\":\"shelf\"}}]\n"
        provider("apps") -> "Result: Bundle[{apps=[{\"pkg\":\"com.vendor.launcher\",\"label\":\"Stock\"}]}]\n"
        else -> error("Unexpected command, including any attempted mutation: $command")
    }

    private fun FakeAdb.Peer.serve(expected: List<String>, override: (Int, String) -> String = { _, command -> response(command) }) {
        var previous: Packet? = null
        expected.forEachIndexed { index, command ->
            val current = open()
            assertEquals("shell:$command\u0000", current.data.toString(Charsets.UTF_8))
            previous?.let {
                // Reproduce the reported connect -> confirm boundary and every subsequent shell boundary.
                send(Packet(AdbClient.CLSE, 7, it.arg0))
                send(Packet(AdbClient.CLSE, 0, it.arg0))
                send(Packet(AdbClient.OKAY, 7, it.arg0))
            }
            reply(current, override(index, command))
            previous = current
        }
    }

    @Test fun connectConfirmAndRefreshProtectedTvWithLatePackets() {
        FakeAdb { serve(listOf(Identity.PROBE) + commands + commands + listOf(Identity.PROBE, Identity.PROBE)) }.use { fake ->
            val candidate = SelectedTv.connect(fake.connect(), endpoint)
            assertEquals(id, candidate.identity)
            // The app uses this exact verified snapshot path before publishing its selection.
            val confirmed = candidate.readVerifiedState()
            assertEquals("1.7.1", confirmed.homeVersion)
            assertEquals("shelf", confirmed.cfg!!.getString("layout"))
            assertEquals(listOf("com.vendor.launcher" to "Stock"), confirmed.apps)
            assertEquals(confirmed.packages, candidate.readVerifiedState().packages)
            assertTrue(candidate.identity.protected)
            assertThrows(IllegalArgumentException::class.java) { candidate.write("reboot") }
            candidate.checkIdentity() // A refused mutation leaves the protected read-only session usable.
            fake.finish()
        }
    }

    @Test fun repeatedIdentityVerificationUsesIndependentStreams() {
        FakeAdb { serve(List(12) { Identity.PROBE }) }.use { fake ->
            val tv = SelectedTv.connect(fake.connect(), endpoint)
            repeat(11) { tv.checkIdentity() }; fake.finish()
        }
    }

    @Test fun identityChangeDuringConfirmationReturnsNoSnapshotAndClosesSession() {
        FakeAdb {
            serve(listOf(Identity.PROBE) + commands) { index, command ->
                if (index == commands.size) identityText(id.copy(serial = "DIFFERENT")) else response(command)
            }
            awaitDisconnect()
        }.use { fake ->
            val tv = SelectedTv.connect(fake.connect(), endpoint)
            assertThrows(DeviceIdentityException::class.java) { tv.readVerifiedState() }
            assertThrows(IllegalStateException::class.java) { tv.read("must not run") }; fake.finish()
        }
    }

    @Test fun firmwareChangeBeforeConfirmationClosesSession() {
        FakeAdb {
            serve(listOf(Identity.PROBE, Identity.PROBE)) { index, _ ->
                identityText(if (index == 0) id else id.copy(fingerprint = "changed/firmware"))
            }
            awaitDisconnect()
        }.use { fake ->
            val tv = SelectedTv.connect(fake.connect(), endpoint)
            assertThrows(DeviceIdentityException::class.java) { tv.readVerifiedState() }; fake.finish()
        }
    }

    @Test fun incompleteIdentityDuringConfirmationIsFatal() {
        FakeAdb {
            serve(listOf(Identity.PROBE, Identity.PROBE)) { index, _ -> if (index == 0) identityText(id) else "incomplete\n" }
            awaitDisconnect()
        }.use { fake ->
            val tv = SelectedTv.connect(fake.connect(), endpoint)
            assertThrows(DeviceIdentityException::class.java) { tv.readVerifiedState() }; fake.finish()
        }
    }

    @Test fun savedAddressWithDifferentIdentityCannotBeSelected() {
        FakeAdb { serve(listOf(Identity.PROBE)); awaitDisconnect() }.use { fake ->
            assertThrows(DeviceIdentityException::class.java) { SelectedTv.connect(fake.connect(), endpoint, "different-stable-id") }
            fake.finish()
        }
    }

    @Test fun invalidIdentityCannotBecomeCandidate() {
        for (invalid in listOf(id.copy(serial = "unknown", androidId = "null"), id.copy(tv = false), id.copy(sdk = 0))) {
            FakeAdb { serve(listOf(Identity.PROBE)) { _, _ -> identityText(invalid) }; awaitDisconnect() }.use { fake ->
                assertThrows(DeviceIdentityException::class.java) { SelectedTv.connect(fake.connect(), endpoint) }; fake.finish()
            }
        }
    }

    @Test fun optionalHomeReadCannotHideInvalidAdbPacket() {
        FakeAdb {
            serve(listOf(Identity.PROBE) + commands.take(5))
            val open = open(); assertEquals("shell:${provider("get")}\u0000", open.data.toString(Charsets.UTF_8))
            send(Packet(AdbClient.CLSE, 7, open.arg0 + 100)); awaitDisconnect()
        }.use { fake ->
            val tv = SelectedTv.connect(fake.connect(), endpoint)
            assertThrows(IOException::class.java) { tv.readVerifiedState() }; fake.finish()
        }
    }

    @Test fun disconnectAndReconnectReverifyTheDevice() {
        repeat(2) {
            FakeAdb { serve(listOf(Identity.PROBE) + commands); awaitDisconnect() }.use { fake ->
                val tv = SelectedTv.connect(fake.connect(), endpoint, id.stableId)
                assertNotNull(tv.readVerifiedState()); tv.shell.close()
                assertThrows(IllegalStateException::class.java) { tv.read("after disconnect") }; fake.finish()
            }
        }
    }
}
