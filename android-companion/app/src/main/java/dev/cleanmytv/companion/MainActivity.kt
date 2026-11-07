package dev.cleanmytv.companion

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import dev.cleanmytv.core.*
import org.json.JSONObject
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    private val vm: CompanionViewModel by viewModels()
    private var pendingUsb: UsbDevice? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { pendingUsb?.let { if(vm.usbPermission(it)) vm.usbConnect(it) }; pendingUsb = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter("dev.cleanmytv.USB_PERMISSION")
        androidx.core.content.ContextCompat.registerReceiver(this, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent { CleanMyTvApp(vm) { device ->
            if(vm.usbPermission(device)) vm.usbConnect(device) else {
                pendingUsb = device
                getSystemService(UsbManager::class.java).requestPermission(device, PendingIntent.getBroadcast(this, 3,
                    Intent("dev.cleanmytv.USB_PERMISSION").setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        } }
    }
    override fun onDestroy() { unregisterReceiver(receiver); super.onDestroy() }
}

val stages = listOf("Clean & Launch", "Setup", "Clean up", "Home screen", "TV extras", "Finish")
private val green = Color(0xFF4CAF50)
private val bg = Color(0xFF1C2A1F)
private val card = Color(0xFF2D3A2E)
private val ink = Color(0xFFF0EBE5)
private val muted = Color(0xFFD7CFC4)

@Composable fun CleanMyTvApp(vm: CompanionViewModel, usb: (UsbDevice) -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    val font = FontFamily(Font(R.font.jetbrains_mono_regular))
    MaterialTheme(colorScheme = darkColorScheme(primary = green, onPrimary = Color(0xFF0A1F0C), background = bg, surface = card,
        onBackground = ink, onSurface = ink, secondary = muted, outline = Color(0xFF3E4A3D)), typography = Typography().let { t ->
            t.copy(bodyLarge = t.bodyLarge.copy(fontFamily = font, fontSize = 14.sp), bodyMedium = t.bodyMedium.copy(fontFamily = font),
                titleLarge = t.titleLarge.copy(fontFamily = font), titleMedium = t.titleMedium.copy(fontFamily = font), labelLarge = t.labelLarge.copy(fontFamily = font))
        }) {
        Surface(Modifier.fillMaxSize(), color = bg) {
            Column(Modifier.safeDrawingPadding()) {
                Column(Modifier.padding(18.dp, 12.dp)) {
                    Text("▣  CleanMyTV", style = MaterialTheme.typography.titleLarge)
                    Text("Unofficial Android port · v0.7.0", color = muted, fontSize = 11.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(if(s.selected == null) "○  No TV selected" else if(s.lost) "○  Connection lost · reconnect in Setup" else "●  ${s.selected!!.manufacturer} ${s.selected!!.model}", color = if(s.selected != null && !s.lost) green else muted)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    stages.forEachIndexed { i, title -> FilterChip(s.stage == i, { vm.stage(i) }, label = { Text("${i+1}  $title") }) }
                }
                if(s.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(s.progress, Modifier.padding(12.dp), color = green) }
                if(s.message.isNotBlank()) Panel { Text(s.message); Act("Dismiss") { vm.dismissMessage() } }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stages[s.stage], color = green, style = MaterialTheme.typography.headlineSmall)
                    if(s.selected?.protected == true) Text("Protected MiTV-MZTU1 · read-only", color = Color(0xFFE0A100))
                    when(s.stage) {
                        1 -> SetupScreen(vm, s, usb)
                        0 -> GuidedScreen(vm, s)
                        2 -> CleanupScreen(vm, s)
                        3 -> HomeScreen(vm, s)
                        4 -> ExtrasScreen(vm, s)
                        5 -> FinishScreen(vm, s)
                    }
                    Text("Based on wingedpanther/CleanMyTV · MIT\nDirect phone-to-TV ADB. No PC required.", color = muted, fontSize = 11.sp)
                }
            }
        }
        s.candidate?.let { identity -> AlertDialog(onDismissRequest = vm::cancelCandidate, title = { Text("Confirm this TV") }, text = {
            Column { Text("${identity.manufacturer} ${identity.model}\nDevice: ${identity.device}\nAndroid SDK: ${identity.sdk}\nEndpoint: ${s.endpoint}\nIdentity: ${identity.stableId.take(16)}")
                Text("Compare this information with your TV before selecting it. ${if(identity.protected) "This model is read-only." else ""}") }
        }, confirmButton = { TextButton(onClick = vm::confirmTv) { Text("Use this TV") } }, dismissButton = { TextButton(onClick = vm::cancelCandidate) { Text("Cancel") } }) }
        s.plan?.let { plan -> PlanDialog(plan, vm::dismissPlan, vm::confirmPlan) }
        s.preview?.let { bytes -> AlertDialog(onDismissRequest = vm::dismissPreview, title = { Text("Original theme artwork") }, text = {
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.let { Image(it.asImageBitmap(), "Theme wallpaper", Modifier.fillMaxWidth().aspectRatio(16f/9f)) }
        }, confirmButton = { TextButton(onClick = vm::dismissPreview) { Text("Close") } }) }
    }
}

@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable private fun Act(text: String, enabled: Boolean = true, click: () -> Unit) { Button(click, enabled = enabled, shape = RoundedCornerShape(6.dp)) { Text(text) } }
@Composable private fun Field(label: String, value: String, change: (String) -> Unit, secret: Boolean = false) {
    if(secret) OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
    else OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}
private fun writable(s: AppState) = s.selected != null && !s.selected.protected && s.selected.verified && !s.busy && !s.lost
@Composable private fun NeedTv(s: AppState, vm: CompanionViewModel): Boolean {
    if(s.selected != null && !s.lost) return false
    Panel { Text("Select and confirm your TV in Setup to use this stage."); Act("Open Setup") { vm.stage(1) } }; return true
}
@Composable private fun SetupScreen(vm: CompanionViewModel, s: AppState, usb: (UsbDevice) -> Unit) {
    var host by remember { mutableStateOf("") }; var port by remember { mutableStateOf("5555") }
    var pairPort by remember { mutableStateOf("") }; var code by remember { mutableStateOf("") }
    Panel {
        Text("Check and connect", style = MaterialTheme.typography.titleMedium)
        Text("Connect your phone and TV to the same Wi-Fi. On the TV, enable Developer options and USB or Network debugging. Accept the Allow prompt on the TV.")
        Field("TV IP address", host, { host = it }); Field("Connection port", port, { port = it.filter(Char::isDigit) })
        Act("Connect", !s.busy && host.isNotBlank() && port.toIntOrNull() in 1..65535) { vm.connect(host, port.toInt()) }
        if(s.selected != null) Act("Disconnect", !s.busy, vm::disconnect)
    }
    Panel {
        Text("Find TVs on Wi-Fi", style = MaterialTheme.typography.titleMedium)
        Act(if(s.discovering) "Stop discovery" else "Discover TVs", !s.busy) { if(s.discovering) vm.stopDiscovery() else vm.discover() }
        s.discovered.forEach { tv -> TextButton(onClick = { host = tv.host; if(tv.pairing) pairPort = tv.port.toString() else port = tv.port.toString() }) {
            Text("${tv.name}\n${tv.host}:${tv.port} · ${if(tv.pairing) "Pairing" else "Select address"}") } }
        s.saved.forEach { tv -> Act("Connect to ${tv.name}", !s.busy) { vm.connect(tv.host,tv.port,tv.stableId) } }
    }
    Panel {
        Text("Pair with a code", style = MaterialTheme.typography.titleMedium)
        Text("For Wireless debugging, open Pair device with pairing code on the TV. Pairing and connection use different ports.")
        Field("Pairing port", pairPort, { pairPort = it.filter(Char::isDigit) }); Field("Six-digit pairing code", code, { code = it.filter(Char::isDigit).take(6) }, true)
        Act("Pair securely", !s.busy && host.isNotBlank() && pairPort.toIntOrNull() in 1..65535 && code.length == 6) { vm.pair(host,pairPort.toInt(),code); code = "" }
    }
    Panel { Text("USB OTG", style = MaterialTheme.typography.titleMedium); Text("Use a data cable and a USB host adapter. The TV must expose a USB ADB device interface; many TV USB ports only support host mode.")
        val devices = vm.usbDevices(); if(devices.isEmpty()) Text("No USB ADB device detected.")
        devices.forEach { device -> Act("Connect USB ${device.productName ?: device.deviceId}", !s.busy) { usb(device) } }
    }
    Panel { Text("This phone's ADB RSA fingerprint"); Text(s.fingerprint, fontSize = 11.sp); Text("If the TV reports unauthenticated ADB, turn debugging off when finished. Check guest Wi-Fi isolation, VPN local-network access and the TV's current IP if connection fails.") }
}
@Composable private fun GuidedScreen(vm: CompanionViewModel, s: AppState) {
    if(NeedTv(s, vm)) return
    val matches = vm.profiles.filter { it.matches(s.selected!!) }
    Panel { Text("One path, every step"); Text("1. Review an exact-model cleanup profile.\n2. Install and launch Home 1.7.1.\n3. Arrange your Home screen.\n4. Restart and check recovery. Each change has its own saved preview and confirmation.")
        Text(if(matches.isEmpty()) "No exact-model profile matches this TV. Choose individual packages in Clean up." else "${matches.size} exact-model profile(s) available.")
        Act("Review Clean up", !s.busy) { vm.stage(2) }; Act("Install Home", !s.busy) { vm.stage(3) }; Act("Restart check", writable(s), vm::restart)
    }
}
@Composable private fun CleanupScreen(vm: CompanionViewModel, s: AppState) {
    if(NeedTv(s, vm)) return
    var profileId by remember { mutableStateOf("") }; var query by remember { mutableStateOf("") }; var selections by remember { mutableStateOf(mapOf<String,Boolean>()) }
    val matches = vm.profiles.filter { it.matches(s.selected!!) }; val profile = matches.find { it.id == profileId }
    Panel { Text("Turn off the bloat"); Text("Choose packages explicitly. Core, networking, ADB, setup wizard and launcher packages are protected. Only exact model and Android-version profiles are offered.")
        matches.forEach { p -> FilterChip(profileId == p.id, { profileId = p.id; selections = emptyMap() }, label = { Text(p.id) }) }
        Act("All installed packages", !s.busy) { profileId = ""; selections = emptyMap() }
        Field("Search package names",query,{ query = it }); Act("Refresh states",!s.busy,vm::refreshTv)
        Act("Preview ${selections.size} changes", writable(s) && selections.isNotEmpty()) { vm.packageChanges(selections.filterValues { !it }.keys,selections.filterValues { it }.keys,profile) }
    }
    val launcherPkgs = s.launchers.map { it.substringBefore('/') }.toSet()
    val entries = profile?.entries?.associateBy { it.pkg }.orEmpty()
    val packages = s.packages.keys.filter { (profile == null || it in entries) && it.contains(query,true) }.sorted()
    packages.take(200).forEach { pkg ->
        val on = s.packages.getValue(pkg); val risk = PackageSafety.risk(entries[pkg],pkg,launcherPkgs)
        Panel { Text(pkg, fontSize = 12.sp); Text("${if(on) "Enabled" else "Disabled"} · ${risk.label}", color = if(risk == Risk.PROTECTED) muted else green)
            Row { Checkbox(selections.containsKey(pkg), { checked -> selections = if(checked) selections + (pkg to !on) else selections - pkg }, enabled = writable(s) && (on && risk != Risk.PROTECTED || !on)); Text(if(on) "Disable this package" else "Restore this package") }
        }
    }
    if(packages.size > 200) Text("Refine the search to see the remaining packages.")
}

private val homeTabs = listOf("Overview", "Layout", "Apps, rows & folders", "Look & wallpaper", "Widgets", "Music bars", "Remote buttons", "App lock")
private val tabKeys = listOf(emptyList(), listOf("layout","sections","top_space","quick","dock_scale","tile_scale","dock_size","columns","clock","greeting","idle_minutes"),
    listOf("order","hidden","rows","folders"), listOf("accent","background","focus","wallpaper_dim","show_names","name_focus","follow_colour","pin_focus","tilt","icon_style","tile_shape","intro","slide_minutes","slide_from"),
    listOf("weather_place","weather_lat","weather_lon","weather_unit","clock"), listOf("visualiser","viz_style","viz_bars","viz_height","viz_opacity","viz_colour","viz_peaks","viz_sensitivity"),
    listOf("buttons_on","buttons"), listOf("lock_on","lock_apps","lock_system","lock_for","lock_show"))
@Composable private fun HomeScreen(vm: CompanionViewModel, s: AppState) {
    if(NeedTv(s,vm)) return
    var tab by remember { mutableIntStateOf(0) }
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(8.dp)) { homeTabs.forEachIndexed { i,t -> FilterChip(tab == i,{tab=i},label={Text(t)}) } }
    if(tab == 0) {
        var stock by remember(s.launchers) { mutableStateOf(vm.stockLauncher().orEmpty()) }
        Panel { Text("CleanMyTV Home 1.7.1"); Text("Installed: ${s.homeVersion ?: "No"}\nHome button: ${s.home ?: "Not resolved"}")
            Field("Recovery launcher component",stock,{stock=it}); Text("Choose the original launcher. The app verifies that it can handle Home before handover.")
            s.launchers.filterNot { it.startsWith("$HOME/") || it.contains("setupwraith") }.forEach { c -> TextButton(onClick={stock=c}){Text(c,fontSize=11.sp)} }
            Act("Preview installation / handover",writable(s) && stock.isNotBlank()){vm.installHome(stock)}
            Act("Preview stock restoration",writable(s) && stock.isNotBlank()){vm.restoreStock(stock)}
            Act("Refresh Home status",!s.busy,vm::refreshTv)
        }
        return
    }
    if(tab == 5) Panel { Text("Music bars need access to TV audio output."); Act("Review audio permission",writable(s)){vm.permission("android.permission.RECORD_AUDIO")} }
    val cfg = s.cfg ?: run { Panel { Text("Install or launch Home 1.7.1 from Overview to configure it.") }; return }
    if(tab == 2) Panel { Text("Available TV apps"); Text(s.apps.joinToString("\n") { "${it.second}: ${it.first}" },fontSize=11.sp); Text("Order and hidden are JSON arrays of package names. Rows and folders contain objects with name and apps fields.") }
    if(tab == 3) ThemeControls(vm,s)
    Panel {
        Text(homeTabs[tab],style=MaterialTheme.typography.titleMedium)
        Text("Edit settings below, then review the saved change before applying it.")
        val draft = remember(tab,cfg.toString()) { mutableStateMapOf<String,String>() }
        tabKeys[tab].filter { cfg.has(it) }.forEach { key ->
            val value = draft[key] ?: cfg.get(key).toString()
            val label = key.replace('_',' ').replaceFirstChar { it.uppercase() }
            when {
                key in HomeSchema.bools -> Row { Checkbox(value == "true",{draft[key]=it.toString()},enabled=!s.busy); Text(label) }
                key in HomeSchema.choices -> { Text(label); Row(Modifier.horizontalScroll(rememberScrollState())) { HomeSchema.choices.getValue(key).forEach { choice -> FilterChip(value==choice,{draft[key]=choice},label={Text(choice.replace('_',' '))}) } } }
                else -> { Field(label,value,{draft[key]=it}); HomeSchema.ranges[key]?.let { Text("Range: ${it.first}–${it.last}",fontSize=11.sp,color=muted) } }
            }
        }
        var error by remember { mutableStateOf("") }; if(error.isNotBlank()) Text(error,color=Color(0xFFE0A100))
        Act("Review ${homeTabs[tab]} changes",writable(s) && draft.isNotEmpty()) {
            try { val change=JSONObject(); draft.forEach { (key,value) -> change.put(key, when { key in HomeSchema.bools -> value.toBooleanStrict(); key in HomeSchema.ranges -> value.toInt(); key in setOf("weather_lat","weather_lon") -> value.toDouble(); cfg.get(key) is JSONArray -> JSONArray(value); else -> value }) }; HomeSchema.validate(change); vm.configChange(change) } catch(_:Exception){error="Check numeric ranges and JSON array syntax before continuing."}
        }
    }
    if(tab == 6) Panel { Text("Map remote buttons using key codes and supported actions: ${HomeSchema.actions.joinToString()}. Example: [{\"code\":82,\"action\":\"settings\"}]"); Act("Read recent remote buttons",!s.busy,vm::keyLog); Text(s.keys,fontSize=11.sp) }
    if(tab == 7) { var pin by remember { mutableStateOf("") }; Panel { Text("PIN recovery"); Text("Disable App lock to regain access. An existing PIN cannot be exported by Home 1.7.1; replacing it here is blocked."); Field("New PIN (4–6 digits)",pin,{pin=it.filter(Char::isDigit).take(6)},true); Act("Review new PIN",writable(s)&&pin.length in 4..6){vm.setPin(pin);pin=""} } }
    if(tab == 4) { var place by remember { mutableStateOf("") }; Panel { Field("Search location",place,{place=it}); Act("Search weather locations",!s.busy && place.length>=2){vm.weather(place)}; s.places.forEach { p -> Act(p.optString("name"),writable(s)){vm.configChange(JSONObject().put("weather_place",p.optString("name").take(40)).put("weather_lat",p.getDouble("latitude")).put("weather_lon",p.getDouble("longitude")))} } }
    }
}
@Composable private fun ThemeControls(vm: CompanionViewModel,s: AppState) {
    val image = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(vm::wallpaper)}
    val slides = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty())vm.slides(it)}
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(vm::importTheme)}
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){it?.let { uri -> vm.exportTheme(uri,"My theme") }}
    Panel { Text("Themes & wallpaper"); Act("Choose wallpaper",writable(s)){image.launch(arrayOf("image/*"))}; Act("Remove wallpaper",writable(s),vm::removeWallpaper)
        Act("Choose slideshow pictures",writable(s)){slides.launch(arrayOf("image/*"))}; Act("Import theme",writable(s)){load.launch(arrayOf("*/*"))}; Act("Export current theme",!s.busy){save.launch("My theme.cmtvtheme")}
        Act("Put all 28 themes on the TV",writable(s),vm::putThemes)
    }
    var picture by remember { mutableIntStateOf(0) }
    Row { (0..2).forEach { n -> FilterChip(picture==n,{picture=n},label={Text("Picture ${n+1}")}) } }
    vm.themes.chunked(2).forEach { pair -> Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { pair.forEach { t ->
        Column(Modifier.weight(1f).background(card,RoundedCornerShape(10.dp)).padding(12.dp)) { Text(t.getString("name")); TextButton(onClick={vm.previewTheme(t,picture)},enabled=!s.busy){Text("Preview")}; TextButton(onClick={vm.applyTheme(t,picture)},enabled=writable(s)){Text("Apply")} }
    } }
    }
    var name by remember { mutableStateOf("My theme") }; var accent by remember { mutableStateOf("#4CAF50") }; var background by remember { mutableStateOf("#1C2A1F") }; var art by remember { mutableStateOf("ridges") }
    Panel { Text("Create a custom theme"); Field("Theme name",name,{name=it.take(40)}); Field("Accent colour",accent,{accent=it}); Field("Background colour",background,{background=it})
        Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("aurora","ridges","dunes","flow","stars","contours","bokeh","horizon","rings").forEach { a -> FilterChip(art==a,{art=a},label={Text(a)}) } }
        Act("Review custom theme",writable(s)&&Regex("#[0-9A-Fa-f]{6}").matches(accent)&&Regex("#[0-9A-Fa-f]{6}").matches(background)) {
            vm.applyTheme(JSONObject().put("name",name).put("look",JSONObject().put("accent",accent).put("background",background).put("wallpaper_dim",0))
                .put("paint",JSONObject().put("base",JSONArray(listOf(background,background))).put("glows",JSONArray()).put("art",JSONObject().put("kind",art).put("colours",JSONArray(listOf(accent,"#8BC34A"))).put("seed",42))),0)
        }
    }
}
@Composable private fun ExtrasScreen(vm: CompanionViewModel,s: AppState) {
    if(NeedTv(s,vm)) return
    var dns by remember { mutableStateOf("dns.adguard-dns.com") }
    Panel { Text("Private DNS"); Field("DNS hostname (empty for automatic)",dns,{dns=it}); Act("Review DNS change",writable(s)){vm.dns(dns.trim())} }
    Panel { Text("Animation speed"); Row { listOf("0","0.5","1").forEach { scale -> TextButton(onClick={vm.animations(scale)},enabled=writable(s)){Text("${scale}x")} } }
    }
    Panel { Text("Remote control"); Text("Each tap sends the named button immediately to the confirmed TV. Input cannot be undone.")
        RemoteControl.keys.entries.chunked(3).forEach { row -> Row { row.forEach { (name,code) -> TextButton(onClick={vm.remote(code)},enabled=writable(s)){Text(name)} } } }
    }
    Panel { Text("Fireplace"); Act("Install verified video & audio",writable(s),vm::fireplaceInstall); Act("Show fullscreen",writable(s),vm::fireplaceShow); Act("Use as screensaver",writable(s),vm::fireplaceSaver)
        s.cfg?.let { cfg -> listOf("fire_wall","fire_sound","fire_wall_sound").forEach { key -> Row { Checkbox(cfg.optBoolean(key),{vm.configChange(JSONObject().put(key,it))},enabled=writable(s));Text(key.removePrefix("fire_").replace('_',' ')) } }
            var volume by remember { mutableStateOf(cfg.optInt("fire_volume",40).toString()) }; var timer by remember { mutableStateOf(cfg.optInt("fire_timer").toString()) }
            Field("Fireplace volume (0–100)",volume,{volume=it});Field("Auto-off timer, minutes (0–240)",timer,{timer=it})
            Act("Review playback settings",writable(s)&&volume.toIntOrNull() in 0..100&&timer.toIntOrNull() in 0..240){vm.configChange(JSONObject().put("fire_volume",volume.toInt()).put("fire_timer",timer.toInt()))}
        }
    }
    Panel { Act("Restore Google's screensaver",writable(s),vm::screensaver);Act("Review cache cleanup",writable(s),vm::trimCaches);Act("Review TV restart",writable(s),vm::restart) }
}
@Composable private fun FinishScreen(vm: CompanionViewModel,s: AppState) {
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){it?.let(vm::exportLog)}
    Panel { Text("Check before closing debugging"); Text("Run the restart check while debugging is enabled. Then turn USB / Network / Wireless debugging off in the TV's Developer options and revoke this phone's authorization if desired.")
        Act("Read-only health check",s.selected!=null&&!s.busy,vm::health);Act("Review restart check",writable(s),vm::restart);Act("Check debugging port",!s.busy,vm::checkPort);Act("Export diagnostics",!s.busy){export.launch("CleanMyTV-diagnostics.txt")}
        if(s.diagnostics.isNotBlank())Text(s.diagnostics,fontSize=11.sp)
    }
    Text("Recovery records",style=MaterialTheme.typography.titleMedium)
    s.records.forEach { r -> Panel { Text(r.plan.title);Text("${r.plan.identity.model} · ${r.phase}\n${r.plan.id.take(12)}",fontSize=11.sp)
        if(r.plan.reversible&&r.attempted>=0&&r.phase in setOf("verified","running","recovery-needed")) Act("Review restoration",writable(s)){vm.restore(r)}
        if(r.message.isNotBlank())Text(r.message)
    } }
    if(s.records.isEmpty())Text("No operations have been recorded on this phone.")
}
@Composable private fun PlanDialog(plan: Plan,cancel:()->Unit,confirm:()->Unit) {
    var checked by remember(plan.id){mutableStateOf(false)}
    AlertDialog(onDismissRequest=cancel,title={Text("Review & confirm")},text={ Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(plan.title,color=green);Text("TV: ${plan.identity.manufacturer} ${plan.identity.model}\nIdentity: ${plan.identity.stableId.take(16)}\nSnapshot: ${plan.id.take(12)}")
        Text("The original state has been saved and checked on this phone.")
        plan.steps.forEach { step -> Text("• ${step.label}") }
        if(plan.warning.isNotBlank())Text(plan.warning)
        Text(if(plan.reversible)"If verification fails, the approved recovery commands will run. Recovery is reported successful only when checked." else "This operation cannot be automatically undone. Its result may require manual recovery.")
        Row { Checkbox(checked,{checked=it});Text("I confirm this TV and these exact changes.") }
    }},confirmButton={TextButton(onClick=confirm,enabled=checked){Text("Apply approved changes")}},dismissButton={TextButton(onClick=cancel){Text("Cancel")}})
}
