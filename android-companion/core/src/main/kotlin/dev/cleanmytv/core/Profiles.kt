package dev.cleanmytv.core

import org.json.JSONArray
import org.json.JSONObject

data class PackageEntry(val pkg: String, val note: String, val suggested: Boolean, val section: String = "")
data class Profile(val id: String, val title: String, val brand: String, val models: List<String>, val sdk: Int, val entries: List<PackageEntry>) {
    fun matches(identity: Identity): Boolean = models.any { it.equals(identity.model.trim(), true) } &&
        brand.substringBefore(' ').equals(identity.manufacturer.substringBefore(' '), true) && (sdk == 0 || sdk == identity.sdk)
    companion object {
        fun parse(text: String): List<Profile> = JSONArray(text).let { a -> (0 until a.length()).map { i ->
            val p = a.getJSONObject(i)
            Profile(p.getString("id"), p.getString("title"), p.getString("brand"), p.getJSONArray("models").strings(), p.optInt("sdk"),
                p.getJSONArray("entries").let { e -> (0 until e.length()).map { n ->
                    val o = e.getJSONObject(n); PackageEntry(o.getString("pkg"), o.getString("note"), o.getBoolean("suggested"), o.optString("section"))
                } })
        } }
    }
}

enum class Risk(val label: String) { PROTECTED("Protected"), FEATURE("Personal choice"), REVIEW("Review carefully"), PROFILE("Profile recommendation") }
object PackageSafety {
    private val core = Regex("^(android|android\\.ext\\.(services|shared)|com\\.android\\.(systemui|shell|phone|settings|tv\\.settings)|com\\.(google\\.)?android\\.(permissioncontroller|packageinstaller|ext\\.services|ext\\.shared|modulemetadata)|com\\.google\\.android\\.overlay\\.modules.*|com\\.android\\.providers\\..*|com\\.cltv\\.mal|com\\.mediatek\\.(tis|tv\\.service|tv\\.agent))$")
    private val critical = Regex("(?i)(networkstack|connectivity|wifi|wlan|bluetooth|adbd|inputmethod|tungsten\\.setupwraith|provision|packageinstaller|permissioncontroller)")
    private val features = setOf("com.google.android.tv.remote.service", "com.google.android.apps.mediashell", "com.google.android.katniss",
        "com.google.android.speech.pumpkin", "com.google.android.apps.tv.dreamx", "com.google.android.backdrop")
    fun protected(pkg: String, launchers: Set<String>) = pkg == HOME || core.matches(pkg) || critical.containsMatchIn(pkg) || pkg in launchers
    fun risk(entry: PackageEntry?, pkg: String, launchers: Set<String>): Risk = when {
        protected(pkg, launchers) -> Risk.PROTECTED
        pkg in features || entry?.section?.contains(Regex("(?i)personal choice|optional")) == true -> Risk.FEATURE
        entry?.suggested == true -> Risk.PROFILE
        else -> Risk.REVIEW
    }
}

const val PACKAGE_QUERY = "pm list packages --user 0; echo __DISABLED__; pm list packages -d --user 0"

/** Compiles high-level changes into an immutable, inspectable and reversible command plan. */
class Planner(private val tv: SelectedTv) {
    fun packageChanges(disable: Set<String>, enable: Set<String>, profile: Profile? = null): Plan {
        require(disable.intersect(enable).isEmpty())
        require(profile == null || profile.matches(tv.identity)) { "The selected profile does not match this exact model and Android version." }
        val states = tv.packages()
        val launchers = tv.read(HOME_CLAIMS).lines().map { it.trim().substringBefore('/') }.filter { PACKAGE.matches(it) }.toSet() +
            listOfNotNull(tv.home()?.substringBefore('/')) + setOf("com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx", "com.google.android.leanbacklauncher", "com.mitv.tvhome")
        val changes = (disable.map { it to false } + enable.map { it to true })
        val steps = changes.mapNotNull { (pkg, on) ->
            require(PACKAGE.matches(pkg) && states.containsKey(pkg)) { "This package is not installed on the selected TV." }
            require(on || !PackageSafety.protected(pkg, launchers)) { "$pkg is protected. Use the verified Home handover for launcher changes." }
            if (states[pkg] == on) null else packageStep(pkg, states.getValue(pkg), on)
        }
        return Plan.make(tv, "Apply package changes", steps, "Apps are disabled for user 0, not uninstalled. Restoring returns each selected app to its saved state.")
    }

    fun settings(title: String, values: Map<Pair<String, String>, String>): Plan = Plan.make(tv, title,
        values.mapNotNull { (key, value) -> settingStep(key.first, key.second, value) })

    fun animation(scale: String): Plan {
        require(scale in setOf("0", "0.5", "1"))
        return settings("Set animation speed to ${scale}x", listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
            .associate { ("global" to it) to scale })
    }
    fun dns(host: String): Plan {
        require(tv.identity.sdk >= 28) { "Private DNS needs Android 9 or newer." }
        require(host.isEmpty() || Regex("(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}").matches(host)) { "Enter a DNS host name, such as dns.adguard-dns.com." }
        val values = linkedMapOf<Pair<String,String>,String>()
        if (host.isNotEmpty()) values["global" to "private_dns_specifier"] = host
        values["global" to "private_dns_mode"] = if(host.isEmpty()) "opportunistic" else "hostname"
        return settings("Set Private DNS", values).copy(warning = "An unreachable DNS server can interrupt streaming. The saved DNS settings remain available in Recovery.")
    }
    fun settingStep(namespace: String, key: String, value: String): Step? {
        require(namespace in setOf("global", "secure", "system") && Regex("[a-z_]+").matches(key))
        require(!value.contains('\u0000') && value.length < 4096)
        val query = "settings get $namespace $key"
        val before = tv.read(query).trim()
        if(before == value) return null
        fun write(v: String) = if(v == "null") "settings delete $namespace $key" else "settings put $namespace $key ${quote(v)}"
        return Step("$key: $before → $value", listOf(write(value)), listOf(Check(query, before)), listOf(Check(query, value)), listOf(write(before)), listOf(Check(query, before)))
    }

    fun homeConfig(change: JSONObject, title: String = "Update Home settings"): Plan {
        requireHome()
        HomeSchema.validate(change)
        val old = tv.homeConfig()
        val before = JSONObject(); val after = JSONObject()
        change.stringKeys().forEach { key ->
            require(old.has(key)) { "Home 1.7.1 did not report $key. This setting cannot be backed up." }
            if(canonical(old.get(key)) != canonical(change.get(key))) { before.put(key, old.get(key)); after.put(key, change.get(key)) }
        }
        require(after.length() > 0) { "No Home settings changed." }
        if (after.optBoolean("lock_on")) require(old.optBoolean("lock_pin_set")) { "Set a PIN before enabling App lock." }
        val steps = mutableListOf<Step>()
        if (after.has("buttons_on") || after.has("lock_on")) {
            val enabled = after.optBoolean("buttons_on", old.optBoolean("buttons_on")) || after.optBoolean("lock_on", old.optBoolean("lock_on"))
            val key = "$HOME/$HOME.KeyService"
            val current = tv.read("settings get secure enabled_accessibility_services").trim()
            val list = current.split(':').filter { it.isNotBlank() && it != "null" && it != key }.toMutableList()
            if (enabled) list.add(key)
            settingStep("secure", "enabled_accessibility_services", list.joinToString(":"))?.let(steps::add)
            if(enabled) settingStep("secure", "accessibility_enabled", "1")?.let(steps::add)
        }
        if (after.has("sections")) {
            val listener = "$HOME/$HOME.NowPlayingListener"
            val on = after.getJSONArray("sections").strings().contains("nowplaying")
            val current = tv.read("settings get secure enabled_notification_listeners").trim()
            val entries = current.split(':').filter { it != "null" && it.isNotBlank() && it != listener }.toMutableList()
            if(on) entries.add(listener)
            settingStep("secure", "enabled_notification_listeners", entries.joinToString(":"))?.let(steps::add)
        }
        steps += Step("Save ${after.stringKeys().joinToString()}", listOf(provider("set64", b64(after.toString().toByteArray()))),
            listOf(Check(provider("get"), before.toString(), "json")), listOf(Check(provider("get"), after.toString(), "json")),
            listOf(provider("set64", b64(before.toString().toByteArray()))), listOf(Check(provider("get"), before.toString(), "json")))
        return Plan.make(tv, title, steps)
    }

    fun pin(pin: String): Plan {
        requireHome(); require(Regex("[0-9]{4,6}").matches(pin)) { "The PIN must contain 4 to 6 digits." }
        val before = tv.homeConfig()
        require(!before.optBoolean("lock_pin_set")) { "Home 1.7.1 cannot back up an existing PIN. Clear it on the TV before setting a new PIN here." }
        return Plan.make(tv, "Set App lock PIN", listOf(Step("Set a private PIN (never logged)",
            listOf(provider("lock_pin", b64(JSONObject().put("pin", pin).toString().toByteArray()))),
            listOf(Check(provider("get"), "{\"lock_pin_set\":false}", "json")), listOf(Check(provider("get"), "{\"lock_pin_set\":true}", "json")),
            listOf(provider("lock_clear")), listOf(Check(provider("get"), "{\"lock_pin_set\":false,\"lock_on\":false}", "json")))),
            "The TV stores a hash. Recovery clears this newly created PIN. Keep this phone's ADB authorization until you have tested recovery.")
    }

    fun googleScreensaver(): Plan {
        val installed = tv.packages()
        val candidates = setOf("com.google.android.apps.tv.dreamx", "com.google.android.backdrop")
        val services = tv.read("cmd package query-services --brief -a android.service.dreams.DreamService").lines().map { it.trim() }
        val component = services.firstOrNull { COMPONENT.matches(it) && it.substringBefore('/') in candidates }
            ?: error("Google's screensaver service is not visible. Enable its apps in Clean up, then try again.")
        val steps = candidates.filter { installed[it] == false }.map { packageStep(it, false, true) }.toMutableList()
        settingStep("secure", "screensaver_components", component)?.let(steps::add)
        settingStep("secure", "screensaver_enabled", "1")?.let(steps::add)
        return Plan.make(tv, "Restore Google's screensaver", steps, "Choose your albums in the TV's Ambient mode settings.")
    }

    fun permission(permission: String): Plan {
        requireHome()
        require(permission in setOf("android.permission.RECORD_AUDIO", "android.permission.WRITE_SECURE_SETTINGS"))
        val query = "dumpsys package $HOME"
        val dump = tv.read(query)
        val was = Regex(Regex.escape(permission) + ": granted=(true|false)").find(dump)?.groupValues?.get(1)
            ?: error("This TV does not report the permission state. Grant access from the TV's settings instead.")
        require(was == "false") { "This permission is already granted." }
        return Plan.make(tv, "Grant Home permission: $permission", listOf(Step("Grant $permission", listOf("pm grant $HOME $permission"),
            listOf(Check(query, "$permission: granted=false", "contains")), listOf(Check(query, "$permission: granted=true", "contains")),
            listOf("pm revoke $HOME $permission"), listOf(Check(query, "$permission: granted=false", "contains")))))
    }

    fun requireHome() {
        require(tv.identity.sdk >= 28) { "CleanMyTV Home needs Android 9 or newer." }
        val dump = tv.read("dumpsys package $HOME")
        require(Regex("versionName=1\\.7\\.1(?:\\s|$)").containsMatchIn(dump)) { "This workflow requires CleanMyTV Home 1.7.1. No settings were changed." }
    }

    companion object {
        fun packageStep(pkg: String, old: Boolean, new: Boolean): Step {
            require(PACKAGE.matches(pkg))
            fun command(on: Boolean) = "pm ${if(on) "enable" else "disable-user"} --user 0 $pkg"
            fun check(on: Boolean) = Check(PACKAGE_QUERY, "$pkg=${if(on) "enabled" else "disabled"}", "package")
            return Step("${if(new) "Restore" else "Disable"} $pkg", listOf(command(new)), listOf(check(old)), listOf(check(new)), listOf(command(old)), listOf(check(old)))
        }
    }
}
