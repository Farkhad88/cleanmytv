package dev.cleanmytv.companion

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cleanmytv.core.*
import dev.cleanmytv.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.net.InetSocketAddress
import java.util.Base64

data class SavedTv(val name: String, val host: String, val port: Int, val stableId: String)
data class AppState(
    val stage: Int = 1, val busy: Boolean = false, val progress: String = "", val message: String = "",
    val selected: Identity? = null, val candidate: Identity? = null, val endpoint: String = "", val lost: Boolean = false,
    val discovered: List<DiscoveredTv> = emptyList(), val discovering: Boolean = false, val saved: List<SavedTv> = emptyList(),
    val packages: Map<String, Boolean> = emptyMap(), val launchers: List<String> = emptyList(), val home: String? = null,
    val homeVersion: String? = null, val cfg: JSONObject? = null, val apps: List<Pair<String,String>> = emptyList(),
    val plan: Plan? = null, val records: List<Journal> = emptyList(), val diagnostics: String = "", val log: List<String> = emptyList(),
    val preview: ByteArray? = null, val places: List<JSONObject> = emptyList(), val keys: String = "", val fingerprint: String = "")

class CompanionViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val _state = MutableStateFlow(AppState())
    val state = _state.asStateFlow()
    val profiles: List<Profile> = Profile.parse(asset("profiles.json"))
    val themes: List<JSONObject> = JSONArray(asset("themes.json")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    val credentials = Credentials(app)
    private val network = NetworkAdb(credentials)
    private val usbManager = app.getSystemService(UsbManager::class.java)
    private val usb = UsbAdb(usbManager, credentials)
    private val store = RecoveryStore(File(app.noBackupFilesDir, "recovery"), credentials::seal, credentials::open)
    private var selected: SelectedTv? = null
    private var candidate: SelectedTv? = null
    private var discovery: Discovery? = null
    private var pendingHost = ""; private var pendingPort = 5555
    private var lastUsb: UsbDevice? = null
    private var isUsb = false
    private var restartPlan = false
    private val savedFile = File(app.noBackupFilesDir, "known-tvs.enc")

    init { task("Loading local records") { _state.update { it.copy(saved = savedTvs(), records = store.all(), fingerprint = credentials.fingerprint()) } } }
    private fun asset(name: String) = app.assets.open(name).bufferedReader().use { it.readText() }
    fun stage(n: Int) { _state.update { it.copy(stage = n) } }
    fun dismissMessage() { _state.update { it.copy(message = "") } }
    fun dismissPlan() { _state.update { it.copy(plan = null) }; restartPlan = false }
    fun dismissPreview() { _state.update { it.copy(preview = null) } }
    fun previewTheme(theme: JSONObject, picture: Int) = task("Drawing the original theme") {
        val data = withTimeout(30_000) { Artwork.render(app, theme.getJSONObject("paint"), picture) }
        _state.update { it.copy(preview = data) }
    }
    fun connect(host: String, port: Int, expected: String? = null) = task("Connecting · approve the RSA key on your TV") {
        closeConnection(); pendingHost = host.trim(); pendingPort = port; isUsb = false
        val shell = network.connect(pendingHost, port) { progress("Waiting for Allow on the TV · compare the RSA fingerprint in Setup") }
        val identity = try { Identity.read(shell) } catch(e: Exception) { shell.close(); throw e }
        if(expected != null && identity.stableId != expected) { shell.close(); error("A different TV answered at this saved address. Select its address manually and check the identity.") }
        candidate = SelectedTv(shell, identity, "$pendingHost:$port")
        _state.update { it.copy(candidate = identity, endpoint = "$pendingHost:$port", selected = null, lost = false) }
    }
    fun pair(host: String, port: Int, code: String) = task("Pairing securely with the TV") {
        network.pair(host.trim(), port, code)
        message("Paired. Close the pairing window on the TV and enter the connection port from the main Wireless debugging screen.")
    }
    fun usbDevices() = usb.devices()
    fun usbPermission(device: UsbDevice) = usbManager.hasPermission(device)
    fun usbConnect(device: UsbDevice) = task("Connecting over USB OTG") {
        closeConnection(); isUsb = true; lastUsb = device
        val shell = usb.connect(device) { progress("Choose Allow on the TV") }
        val identity = try { Identity.read(shell) } catch(e: Exception) { shell.close(); throw e }
        candidate = SelectedTv(shell, identity, "usb:${device.deviceName}")
        _state.update { it.copy(candidate = identity, endpoint = "USB OTG", selected = null, lost = false) }
    }
    fun confirmTv() = task("Verifying the selected TV") {
        val tv = candidate ?: error("Connect to a TV first.")
        tv.checkIdentity(); selected = tv; candidate = null
        _state.update { it.copy(candidate = null, selected = tv.identity, lost = false, message = if(tv.identity.protected) "MiTV-MZTU1 is protected. All controls that change this TV are blocked." else "") }
        if(!isUsb) {
            val saved = (savedTvs().filter { it.stableId != tv.identity.stableId } + SavedTv("${tv.identity.manufacturer} ${tv.identity.model}", pendingHost, pendingPort, tv.identity.stableId)).takeLast(20)
            saveTvs(saved); _state.update { it.copy(saved = saved) }
        }
        refresh()
    }
    fun cancelCandidate() { candidate?.shell?.close(); candidate = null; _state.update { it.copy(candidate = null) } }
    fun disconnect() { if(_state.value.busy) return; closeConnection(); _state.update { it.copy(selected = null, candidate = null, cfg = null, packages = emptyMap(), home = null, homeVersion = null, plan = null, endpoint = "", lost = false) } }
    private fun closeConnection() { selected?.shell?.close(); candidate?.shell?.close(); selected = null; candidate = null }
    fun discover() {
        discovery?.close(); _state.update { it.copy(discovered = emptyList(), discovering = true) }
        discovery = Discovery(app, { tv -> _state.update { s -> s.copy(discovered = (s.discovered.filterNot { it.host == tv.host && it.port == tv.port } + tv).take(64)) } }, ::message)
        runCatching { discovery?.start() }.onFailure { message("Wi-Fi discovery is unavailable. Enter the TV's IP address manually.") }
        viewModelScope.launch { delay(25_000); stopDiscovery() }
    }
    fun stopDiscovery() { discovery?.close(); discovery = null; _state.update { it.copy(discovering = false) } }
    fun refreshTv() = task("Reading TV state") { refresh() }
    private fun tv() = selected ?: error("Choose and confirm a TV in Setup first.")
    private fun engine() = OperationEngine(tv(), store, ::progress)
    private fun prepare(plan: Plan) { restartPlan = false; val checked = engine().prepare(plan); _state.update { it.copy(plan = checked, records = store.all()) } }

    fun packageChanges(disable: Set<String>, enable: Set<String>, profile: Profile?) = task("Saving a verified package snapshot") { prepare(Planner(tv()).packageChanges(disable, enable, profile)) }
    fun configChange(change: JSONObject, title: String = "Update Home settings") = task("Reading original Home settings") { prepare(Planner(tv()).homeConfig(change, title)) }
    fun permission(permission: String) = task("Checking Home permission state") { prepare(Planner(tv()).permission(permission)) }
    fun setPin(pin: String) = task("Preparing a private App lock PIN") { prepare(Planner(tv()).pin(pin)) }
    fun animations(value: String) = task("Reading animation settings") { prepare(Planner(tv()).animation(value)) }
    fun dns(host: String) = task("Reading DNS settings") { prepare(Planner(tv()).dns(host)) }
    fun screensaver() = task("Finding Google's screensaver") { prepare(Planner(tv()).googleScreensaver()) }
    fun installHome(stock: String) = task("Verifying the Home APK and recovery launcher") {
        val bytes = app.assets.open("home.apk").use { it.readBytes() }
        val info = JSONObject(asset("home-info.json"))
        require(sha256(bytes) == info.getString("sha256")) { "The bundled Home APK checksum is incorrect." }
        val file = File(app.cacheDir, "verified-home.apk"); file.writeBytes(bytes)
        @Suppress("DEPRECATION") val archive = app.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        require(archive?.packageName == HOME && archive.versionName == "1.7.1") { "The bundled APK is not Home 1.7.1." }
        @Suppress("DEPRECATION") val signatures = archive.signingInfo?.apkContentsSigners
        require(signatures?.size == 1 && sha256(signatures[0].toByteArray()) == info.getString("signingCertificateSha256")) { "The Home signing identity does not match the upstream key." }
        val plan = Installer(tv()).install(bytes, info.getString("sha256"), stock)
        prepare(plan)
    }
    fun stockLauncher(): String? {
        val identity = _state.value.selected ?: return null
        val latest = _state.value.records.firstOrNull { it.plan.identity.stableId == identity.stableId && it.plan.title.startsWith("Install and launch") }
        val original = latest?.plan?.steps?.lastOrNull()?.before?.firstOrNull { it.command == HOME_QUERY }?.expected
        return original?.takeIf { COMPONENT.matches(it) && !it.startsWith("$HOME/") } ?: _state.value.launchers.firstOrNull { !it.startsWith("$HOME/") && !it.contains("setupwraith") && !it.startsWith("com.android.settings/") && !it.startsWith("com.android.tv.settings/") }
    }
    fun restoreStock(stock: String) = task("Preparing stock launcher restoration") { prepare(Installer(tv()).restoreStock(stock)) }
    fun restore(journal: Journal) = task("Checking the recovery record") { _state.update { it.copy(plan = engine().prepareRestore(journal)) }; restartPlan = false }
    fun confirmPlan() {
        val plan = _state.value.plan ?: return
        val restart = restartPlan
        _state.update { it.copy(plan = null) }
        task("Starting the approved operation") {
            app.startForegroundService(Intent(app, OperationService::class.java))
            try {
                val result = if(restart) {
                    val checker = RestartCheck(tv(), store, ::progress)
                    val (result, reconnected) = checker.execute(plan, plan.digest) {
                        val shell = if(isUsb) usb.connect(requireNotNull(lastUsb)) else network.connect(pendingHost, pendingPort)
                        SelectedTv(shell, Identity.read(shell), plan.endpoint)
                    }
                    selected = reconnected
                    _state.update { it.copy(selected = reconnected?.identity, lost = reconnected == null) }
                    result
                } else engine().execute(plan.id, plan.digest)
                message(result.message)
                _state.update { it.copy(records = store.all()) }
                if(selected != null) runCatching { refresh() }.onFailure { _state.update { s -> s.copy(lost = true) } }
            } finally { app.stopService(Intent(app, OperationService::class.java)) }
        }
    }
    fun restart() = task("Preparing the restart and recovery plan") {
        val stock = stockLauncher() ?: error("Select a recovery launcher in Home screen before the restart check.")
        val plan = RestartCheck(tv(), store, ::progress).prepare(stock); restartPlan = true
        _state.update { it.copy(plan = plan) }
    }
    fun remote(code: Int) = task("Sending the selected remote button") { RemoteControl.send(tv(), code); progress("Remote input sent. Check the TV for its visible result.") }
    fun trimCaches() = task("Reading storage before cache cleanup") {
        val before = tv().read("df -k /data")
        prepare(Plan.make(tv(), "Request cache cleanup and refresh storage", listOf(Step("Ask Android to trim app caches", listOf("pm trim-caches 4096G"),
            listOf(Check("df -k /data", "", "nonempty")), listOf(Check("df -k /data", "", "nonempty")), emptyList(), emptyList(), 120_000)),
            "Cached files cannot be restored. Apps rebuild them as needed. App data and logins are not cleared. Storage before:\n$before", false))
    }
    fun fireplaceInstall() = task("Checking the official Fireplace assets") {
        val files = Media.fireplace.associate { asset -> asset.name to app.assets.open("fireplace/${asset.name}").use { it.readBytes() } }
        prepare(Media.fireplace(tv(), files))
    }
    fun fireplaceShow() = task("Preparing Fireplace playback") {
        Planner(tv()).requireHome(); require(tv().homeConfig().optBoolean("fire_on_tv")) { "Install the Fireplace assets first." }
        val old = tv().home() ?: error("The current Home could not be identified.")
        prepare(Plan.make(tv(), "Show Fireplace fullscreen", listOf(Step("Open Fireplace", listOf("am start -W -n $MAIN --es open fireplace"), emptyList(),
            listOf(Check("dumpsys activity activities", "$HOME.FireplaceActivity", "contains")), listOf("am start -W -n ${quote(old)}"), listOf(Check(HOME_QUERY, old)))),
            "Press Back on the TV remote to close playback. Audio follows the Fireplace sound and volume settings."))
    }
    fun fireplaceSaver() = task("Reading screensaver settings") {
        Planner(tv()).requireHome(); require(tv().homeConfig().optBoolean("fire_on_tv")) { "Install the Fireplace assets first." }
        prepare(Planner(tv()).settings("Use Fireplace as screensaver", mapOf(("secure" to "screensaver_components") to "$HOME/.FireplaceDream", ("secure" to "screensaver_enabled") to "1")))
    }
    fun wallpaper(uri: Uri) = task("Preparing a wallpaper and its recovery copy") { prepare(Media.wallpaper(tv(), Artwork.image(app, uri))) }
    fun removeWallpaper() = task("Backing up the current wallpaper") { prepare(Media.wallpaper(tv(), null, name = "Remove wallpaper")) }
    fun slides(uris: List<Uri>) = task("Preparing slideshow pictures") { require(uris.size in 1..12) { "Select up to 12 pictures." }; prepare(Media.slides(tv(), uris.map { Artwork.image(app, it) })) }
    fun applyTheme(theme: JSONObject, picture: Int) = task("Drawing and backing up the theme") {
        val image = withTimeout(30_000) { Artwork.render(app, theme.getJSONObject("paint"), picture) }
        prepare(Media.wallpaper(tv(), image, theme.optString("id").takeIf { it.isNotBlank() }?.let { "$it:$picture" } ?: "", theme.getJSONObject("look"), "Apply ${theme.getString("name")}"))
    }
    fun putThemes() = task("Preparing the original 28-theme pack") {
        val pack = themes.mapIndexed { i, theme ->
            progress("Drawing ${theme.getString("name")} · ${i + 1}/28")
            val pictures = (0..2).map { withTimeout(30_000) { Artwork.render(app, theme.getJSONObject("paint"), it) } }
            Media.Theme(theme.getString("id"), theme.getString("name"), theme.getJSONObject("look"), pictures)
        }
        prepare(Media.themePack(tv(), pack))
    }
    fun importTheme(uri: Uri) = task("Reading the theme file") {
        val text = app.contentResolver.openInputStream(uri)?.use { it.readLimited(6 * 1024 * 1024 + 1).toString(Charsets.UTF_8) } ?: error("The theme file could not be opened.")
        val theme = HomeSchema.parseTheme(text)
        val image = if(theme.isNull("wallpaper")) null else if(theme.has("wallpaper")) Base64.getDecoder().decode(theme.getString("wallpaper")) else Media.readWallpaper(tv())
        prepare(Media.wallpaper(tv(), image, look = theme.getJSONObject("look"), name = "Import ${theme.optString("name", "My theme")}"))
    }
    fun exportTheme(uri: Uri, name: String) = task("Saving the current theme") {
        val cfg = tv().homeConfig(); val image = Media.readWallpaper(tv())
        val json = JSONObject().put("format", "cleanmytv-theme").put("version", 1).put("name", name.take(40).ifBlank { "My theme" })
            .put("look", HomeSchema.themeLook(cfg)).put("wallpaper", image?.let(::b64) ?: JSONObject.NULL)
        app.contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray()) } ?: error("The theme could not be saved.")
        message("Theme exported with its wallpaper. It is compatible with CleanMyTV v0.7.0.")
    }
    fun keyLog() = task("Reading remote-button diagnostics") { _state.update { it.copy(keys = tv().read(provider("keys"))) } }
    fun weather(query: String) = task("Searching weather locations") {
        require(query.trim().length >= 2) { "Enter at least two letters." }
        val url = java.net.URL("https://geocoding-api.open-meteo.com/v1/search?name=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}&count=6&language=en&format=json")
        val conn = url.openConnection() as javax.net.ssl.HttpsURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 15000; conn.instanceFollowRedirects = false
        try {
            require(conn.responseCode == 200) { "The weather location service is unavailable. Enter coordinates manually." }
            val json = JSONObject(conn.inputStream.use { it.readLimited(256 * 1024).toString(Charsets.UTF_8) })
            val list = json.optJSONArray("results") ?: JSONArray()
            _state.update { it.copy(places = (0 until list.length()).map(list::getJSONObject)) }
        } finally { conn.disconnect() }
    }
    fun health() = task("Checking the TV without making changes") {
        tv().checkIdentity()
        val h = tv().home(); val boot = tv().read("getprop sys.boot_completed").trim()
        val running = h?.let { tv().read("pidof ${it.substringBefore('/')}").trim().isNotEmpty() } ?: false
        val text = "Identity: matches the selected TV\nAndroid SDK: ${tv().identity.sdk}\nBoot completed: ${boot == "1"}\nHome: ${h ?: "not resolved"}\nLauncher running: $running\nADB authentication required: ${tv().identity.secure == "1"}\n\nStorage:\n${tv().read("df -k /data").trim()}"
        _state.update { it.copy(diagnostics = text) }
        refresh()
    }
    fun checkPort() = task("Checking the selected debugging port") {
        require(!isUsb && pendingHost.isNotBlank()) { "Choose a network TV before checking its port." }
        val result = try { Socket().use { it.connect(InetSocketAddress(pendingHost, pendingPort), 3000) }; "Port $pendingPort is open. Turn debugging off on the TV, then check again." }
        catch(_: java.net.ConnectException) { "Port $pendingPort refused the connection. Keep the TV awake and confirm that debugging is off in its settings." }
        catch(_: Exception) { "The port did not respond. This is inconclusive: the TV may be asleep, offline, or at a different address." }
        message(result)
    }
    fun exportLog(uri: Uri) = task("Saving diagnostics") {
        val text = "CleanMyTV unofficial Android port\nBaseline: v0.7.0 / b73b330e264107ca51a34bf84dd07095b13e517d\n\n" + _state.value.log.joinToString("\n")
        app.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("Diagnostics could not be saved.")
        message("Diagnostics saved. Pairing codes, PINs, addresses and Home settings are excluded.")
    }
    private fun refresh() {
        val target = tv(); target.checkIdentity()
        val packages = target.packages(); val home = target.home()
        val version = Regex("versionName=(\\S+)").find(target.read("dumpsys package $HOME"))?.groupValues?.get(1)
        val cfg = if(version == "1.7.1") runCatching { target.homeConfig() }.getOrNull() else null
        val launchers = target.read(HOME_CLAIMS).lines().map { it.trim() }.filter { COMPONENT.matches(it) }
        val apps = if(cfg != null) runCatching {
            val output = target.read(provider("apps")); val start = output.indexOf("apps=["); require(start >= 0)
            val array = JSONArray(output.substring(start + 5).substringBeforeLast("}]"))
            (0 until array.length()).map { val a = array.getJSONObject(it); a.getString("pkg") to a.optString("label", a.getString("pkg")) }.filter { PACKAGE.matches(it.first) }
        }.getOrDefault(emptyList()) else emptyList()
        _state.update { it.copy(packages = packages, home = home, homeVersion = version, cfg = cfg, launchers = launchers, apps = apps, records = store.all()) }
    }
    private fun savedTvs(): List<SavedTv> = if(!savedFile.exists()) emptyList() else JSONArray(credentials.open(savedFile.readBytes()).toString(Charsets.UTF_8)).let { a ->
        (0 until a.length()).map { val o = a.getJSONObject(it); SavedTv(o.getString("name"), o.getString("host"), o.getInt("port"), o.getString("id")) }
    }
    private fun saveTvs(list: List<SavedTv>) {
        val bytes = JSONArray(list.map { JSONObject().put("name", it.name).put("host", it.host).put("port", it.port).put("id", it.stableId) }).toString().toByteArray()
        val temp = File(savedFile.parentFile, "known-tvs.tmp"); temp.writeBytes(credentials.seal(bytes)); check(temp.renameTo(savedFile))
    }
    private fun progress(text: String) { _state.update { it.copy(progress = text) } }
    private fun message(text: String) { _state.update { it.copy(message = text) } }
    private fun log(text: String) { _state.update { it.copy(log = (it.log + "${java.time.Instant.now()} · $text").takeLast(250)) } }
    private fun task(label: String, block: suspend () -> Unit) {
        if(_state.value.busy) return
        _state.update { it.copy(busy = true, progress = label, message = "") }; log(label)
        viewModelScope.launch(Dispatchers.IO) {
            try { block(); log("Completed: $label") }
            catch(e: Exception) {
                if(e is CancellationException) throw e
                val text = when(e) {
                    is java.net.SocketTimeoutException -> "The TV did not answer in time. Check Wi-Fi, wake the TV, and reconnect."
                    is java.net.ConnectException -> "The TV refused the connection. Enable debugging and check the connection port."
                    is javax.net.ssl.SSLException -> "The secure connection failed. Verify the TV and pair with a new code."
                    is java.io.IOException -> "The connection or file operation failed. Reconnect to the same TV and check Recovery before retrying."
                    else -> e.message?.take(1000) ?: "The operation could not be completed."
                }
                message(text); log("Stopped: $label")
                if(e is java.io.IOException && selected != null) _state.update { it.copy(lost = true) }
            } finally { _state.update { it.copy(busy = false, progress = "") } }
        }
    }
    override fun onCleared() { discovery?.close(); closeConnection(); super.onCleared() }
}

private fun java.io.InputStream.readLimited(limit: Int): ByteArray {
 val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
 while(out.size()<limit){val n=read(buffer,0,minOf(buffer.size,limit-out.size()));if(n<0)break;out.write(buffer,0,n)}
 return out.toByteArray()
}
