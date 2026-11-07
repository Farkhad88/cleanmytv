package dev.cleanmytv.core

import org.json.JSONObject
import java.util.UUID

/** Installs only the bundled, signature-checked Home. Existing app data is never uninstalled. */
class Installer(private val tv: SelectedTv) {
    fun install(apk: ByteArray, expectedSha256: String, stock: String): Plan {
        require(tv.identity.sdk >= 28) { "CleanMyTV Home needs Android 9 or newer." }
        require(sha256(apk) == expectedSha256) { "The bundled Home APK failed verification." }
        require(COMPONENT.matches(stock) && !stock.startsWith("$HOME/")) { "Select a verified stock launcher component first." }
        val claims = tv.read(HOME_CLAIMS).lines().map { it.trim() }
        require(stock in claims || tv.home() == stock) { "The selected stock launcher cannot currently handle Home." }
        val stockPkg = stock.substringBefore('/')
        val states = tv.packages()
        require(states[stockPkg] == true) { "The recovery launcher must be installed and enabled." }
        val oldHome = tv.home() ?: error("Choose a default Home on the TV before installing; the original launcher must be recoverable.")
        val dump = tv.read("dumpsys package $HOME")
        val oldVersion = Regex("versionName=(\\S+)").find(dump)?.groupValues?.get(1)
        val steps = mutableListOf<Step>()
        if(oldVersion != "1.7.1") {
            if(oldVersion != null) {
                require(compareVersion(oldVersion, "1.7.1") < 0) { "A newer Home is installed. Downgrading is blocked to preserve its data." }
                require(tv.identity.sdk >= 29 && tv.read("cmd rollback help").contains("rollback-app")) {
                    "This TV does not expose Android's app rollback service. An update cannot be safely reversed, so it is blocked. The existing Home was left untouched."
                }
            }
            val stage = "/data/local/tmp/cmtv-${UUID.randomUUID()}.apk"
            steps += Media.uploadStep(tv, apk, stage, "Transfer signed Home 1.7.1 APK")
            val beforeQuery = "pm list packages --user 0 $HOME"
            val beforeList = tv.read(beforeQuery).trim()
            val after = mutableListOf(Check("dumpsys package $HOME", "versionName=1.7.1", "contains"))
            if(oldVersion != null) after += Check("cmd rollback list", HOME, "contains")
            val undo = if(oldVersion == null) listOf("pm uninstall $HOME") else listOf("cmd rollback rollback-app $HOME")
            val verify = if(oldVersion == null) listOf(Check(beforeQuery, beforeList)) else listOf(Check("dumpsys package $HOME", "versionName=$oldVersion", "contains"))
            steps += Step(if(oldVersion == null) "Install Home 1.7.1" else "Update Home $oldVersion to 1.7.1 with Android rollback",
                listOf("pm install -r ${if(oldVersion != null) "--enable-rollback " else ""}${Media.safePath(stage)}"),
                listOf(Check(beforeQuery, beforeList)), after, undo, verify, 300_000)
            // The staged, checksummed APK is retained for an interrupted operation, then can be cleaned from Finish.
        }
        steps += Step("Start Home and verify it runs", listOf("am start -W -n $MAIN"), listOf(Check(HOME_QUERY, oldHome)),
            listOf(Check("pidof $HOME", "", "nonempty")), listOf("am start -W -n ${quote(oldHome)}"), listOf(Check(HOME_QUERY, oldHome)), 60_000)
        steps += handover(stock, oldHome, states)
        return Plan.make(tv, "Install and launch CleanMyTV Home 1.7.1", steps,
            "Recovery launcher: $stock. Existing Home data is preserved. The stock launcher is disabled only after Home starts. The setup wizard and Wi-Fi components remain enabled; if they intercept Home, handover rolls back.")
    }

    private fun handover(stock: String, oldHome: String, states: Map<String, Boolean>): Step {
        val pkg = stock.substringBefore('/')
        require(states[pkg] == true)
        return Step("Start Home, then switch the Home button", listOf(
            "pm disable-user --user 0 $pkg", "cmd package set-home-activity --user 0 $MAIN"),
            listOf(Check(HOME_QUERY, oldHome)), listOf(Check(HOME_QUERY, MAIN), Check("pidof $HOME", "", "nonempty")),
            listOf("pm enable --user 0 $pkg", "cmd package set-home-activity --user 0 ${quote(oldHome)}", "am start -W -n ${quote(oldHome)}"),
            listOf(Check(HOME_QUERY, oldHome), Check(PACKAGE_QUERY, "$pkg=enabled", "package")), 60_000)
    }
    fun restoreStock(component: String): Plan {
        require(COMPONENT.matches(component) && !component.startsWith("$HOME/"))
        val states = tv.packages(); val pkg = component.substringBefore('/')
        require(states.containsKey(pkg)) { "The stock launcher is no longer installed." }
        val old = tv.home() ?: error("The current Home could not be identified.")
        val steps = mutableListOf<Step>()
        if(states[pkg] == false) steps += Planner.packageStep(pkg, false, true)
        steps += Step("Return the Home button to $pkg", listOf("am start -W -n ${quote(component)}", "cmd package set-home-activity --user 0 ${quote(component)}"),
            listOf(Check(HOME_QUERY, old)), listOf(Check(HOME_QUERY, component), Check("pidof $pkg", "", "nonempty")),
            listOf("cmd package set-home-activity --user 0 ${quote(old)}", "am start -W -n ${quote(old)}"), listOf(Check(HOME_QUERY, old)))
        return Plan.make(tv, "Restore stock launcher", steps, "CleanMyTV Home and its settings remain installed.")
    }
    companion object {
        fun compareVersion(a: String, b: String): Int {
            val aa = a.split('.').map { it.toIntOrNull() ?: 0 }; val bb = b.split('.').map { it.toIntOrNull() ?: 0 }
            for(i in 0 until maxOf(aa.size, bb.size)) { val d = (aa.getOrElse(i) { 0 }).compareTo(bb.getOrElse(i) { 0 }); if(d != 0) return d }
            return 0
        }
    }
}
