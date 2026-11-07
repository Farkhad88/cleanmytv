package dev.cleanmytv.core

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.io.IOException
import org.json.JSONException

const val HOME = "io.github.wingedpanther.cleanmytv.home"
const val MAIN = "$HOME/.MainActivity"
const val URI = "content://$HOME.config"
const val HOME_QUERY = "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
const val HOME_CLAIMS = "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
val PACKAGE = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
val COMPONENT = Regex("^[A-Za-z_][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+$")
fun sha256(bytes: ByteArray) = digest("SHA-256", bytes)
fun digest(type: String, bytes: ByteArray) = MessageDigest.getInstance(type).digest(bytes).joinToString("") { "%02x".format(it) }
fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
fun provider(method: String, arg: String? = null): String {
    require(Regex("[a-z_0-9]+").matches(method))
    require(arg == null || Regex("[A-Za-z0-9+/=]*").matches(arg))
    return "content call --uri $URI --method $method" + (arg?.let { " --arg ${quote(it)}" } ?: "")
}
fun config(out: String): JSONObject {
    val start = out.indexOf("config={")
    require(start >= 0 && !out.contains("Exception")) { "CleanMyTV Home did not return settings. Install or launch Home 1.7.1 first." }
    val text = out.substring(start + 7)
    // Parse through the matching brace; braces inside quoted greetings are not structural.
    var depth = 0; var quoted = false; var escaped = false
    text.forEachIndexed { i, c ->
        if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
        else when(c) { '"' -> quoted = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return JSONObject(text.substring(0, i + 1)) } }
    }
    error("Incomplete Home configuration response.")
}

data class Identity(val model: String, val device: String, val manufacturer: String, val serial: String,
    val androidId: String, val fingerprint: String, val sdk: Int, val secure: String, val tv: Boolean) {
    val protected: Boolean get() = listOf(model, device).any { it.equals("MiTV-MZTU1", true) || it.equals("MZTU1", true) }
    val stableId: String get() = sha256("$serial|$androidId|$model|$device|$manufacturer".toByteArray())
    val verified: Boolean get() = tv && sdk >= 21 && model.isNotBlank() && device.isNotBlank() && manufacturer.isNotBlank() && fingerprint.isNotBlank() &&
        (serial.isNotBlank() && serial.lowercase() !in setOf("unknown", "null", "0123456789abcdef") || Regex("[0-9a-fA-F]{16}").matches(androidId))
    fun json() = JSONObject().put("model", model).put("device", device).put("manufacturer", manufacturer).put("serial", serial)
        .put("androidId", androidId).put("fingerprint", fingerprint).put("sdk", sdk).put("secure", secure).put("tv", tv)
    companion object {
        val PROBE = listOf("getprop ro.product.model", "getprop ro.product.device", "getprop ro.product.manufacturer",
            "getprop ro.serialno", "settings get secure android_id", "getprop ro.build.fingerprint", "getprop ro.build.version.sdk",
            "getprop ro.adb.secure", "pm list features").joinToString("; ")
        fun read(shell: Shell): Identity {
            val lines = shell.shell(PROBE).trimEnd().split('\n').map { it.trim() }
            require(lines.size >= 9) { "The TV returned incomplete identity information." }
            return Identity(lines[0], lines[1], lines[2], lines[3], lines[4], lines[5], lines[6].toIntOrNull() ?: 0, lines[7],
                lines.drop(8).any { it.substringBefore('=') in setOf("feature:android.software.leanback", "feature:android.hardware.type.television") })
        }
        fun from(o: JSONObject) = Identity(o.getString("model"), o.getString("device"), o.getString("manufacturer"), o.getString("serial"),
            o.getString("androidId"), o.getString("fingerprint"), o.getInt("sdk"), o.getString("secure"), o.getBoolean("tv"))
    }
}

/** All write paths, including rollback and remote keys, pass this boundary. */
class DeviceIdentityException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class SelectedTv(val shell: Shell, val identity: Identity, val endpoint: String) {
    fun checkIdentity(mutation: Boolean = false) {
        val current = try { Identity.read(shell) } catch (e: Exception) {
            shell.close()
            if (e is IOException) throw e
            throw DeviceIdentityException("The TV returned incomplete or invalid identity information.", e)
        }
        if (current != identity || !current.verified) {
            shell.close()
            throw DeviceIdentityException("Device identity or firmware changed or could not be verified. Disconnect and select the TV again.")
        }
        if (mutation) {
            require(!current.protected) { "MiTV-MZTU1 is protected. This device is read-only." }
            require(current.verified) { "The TV has no verified unique identity or TV capability. Changes are blocked." }
        }
    }
    internal fun write(command: String, timeoutMs: Int = 30_000): String {
        checkIdentity(true)
        return shell.shell(command, timeoutMs)
    }
    fun read(command: String, timeoutMs: Int = 30_000) = shell.shell(command, timeoutMs)
    fun packages(): Map<String, Boolean> {
        val all = parsePackages(read("pm list packages --user 0"))
        require(all.isNotEmpty()) { "The TV returned no installed packages." }
        val disabled = parsePackages(read("pm list packages -d --user 0"))
        return all.associateWith { it !in disabled }
    }
    fun home() = read(HOME_QUERY).trim().lines().lastOrNull().orEmpty().takeIf { COMPONENT.matches(it) }
    fun homeConfig() = config(read(provider("get")))

    /** The exact confirmation/refresh path used by the app, published only after final verification. */
    fun readVerifiedState(): TvSnapshot {
        try {
            checkIdentity()
            val packages = packages()
            val home = home()
            val version = Regex("versionName=(\\S+)").find(read("dumpsys package $HOME"))?.groupValues?.get(1)
            val cfg = if (version == "1.7.1") optionalHome { homeConfig() } else null
            val launchers = read(HOME_CLAIMS).lines().map { it.trim() }.filter { COMPONENT.matches(it) }
            val apps = if (cfg != null) optionalHome {
                val output = read(provider("apps")); val start = output.indexOf("apps=["); require(start >= 0)
                val array = org.json.JSONArray(output.substring(start + 5).substringBeforeLast("}]"))
                (0 until array.length()).map {
                    val app = array.getJSONObject(it)
                    app.getString("pkg") to app.optString("label", app.getString("pkg"))
                }.filter { PACKAGE.matches(it.first) }
            }.orEmpty() else emptyList()
            checkIdentity()
            return TvSnapshot(packages, home, version, cfg, launchers, apps)
        } catch (e: Exception) {
            shell.close()
            if (e is IOException || e is DeviceIdentityException) throw e
            throw AdbProtocolException("The TV state could not be verified. Reconnect to the TV.", e)
        }
    }

    private fun <T> optionalHome(read: () -> T): T? = try { read() }
        catch (e: IOException) { throw e } // Optional provider data must never conceal a broken session.
        catch (_: JSONException) { null }
        catch (_: IllegalArgumentException) { null }

    companion object {
        fun connect(shell: Shell, endpoint: String, expectedStableId: String? = null): SelectedTv {
            try {
                val identity = Identity.read(shell)
                if (!identity.verified) throw DeviceIdentityException("The TV did not return a verified unique identity and TV capability.")
                if (expectedStableId != null && identity.stableId != expectedStableId)
                    throw DeviceIdentityException("A different TV answered at this saved address. Select its address manually and check the identity.")
                return SelectedTv(shell, identity, endpoint)
            } catch (e: Exception) { shell.close(); throw e }
        }
        fun parsePackages(text: String) = text.lines().filter { it.startsWith("package:") }.map { it.substring(8).trim() }.filter { PACKAGE.matches(it) || it == "android" }.toSet()
    }
}

data class TvSnapshot(val packages: Map<String, Boolean>, val home: String?, val homeVersion: String?,
    val cfg: JSONObject?, val launchers: List<String>, val apps: List<Pair<String, String>>)
