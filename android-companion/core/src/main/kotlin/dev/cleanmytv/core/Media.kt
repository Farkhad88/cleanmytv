package dev.cleanmytv.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** Bounds and protocol checks match Home 1.7.1. MD5 is required by its provider, SHA-256 protects local snapshots. */
object Media {
    const val MAX_IMAGE = 4 * 1024 * 1024
    data class Asset(val name: String, val size: Int, val sha256: String)
    val fireplace = listOf(Asset("fireplace.mp4", 9683738, "35a99bfd61111e05ef5941a8dfcc0cdde08f4359ee0d2ac1b0a2fe254d28727c"),
        Asset("crackle.m4a", 1120535, "e941736970cf0074c2bcf22d323c01ab9fcbb2bcfcad5b164b25724ba8a630a8"))
    fun jpeg(bytes: ByteArray) { require(bytes.size in 4..MAX_IMAGE && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) { "Choose a valid JPEG smaller than 4 MB." } }
    fun verify(asset: Asset, bytes: ByteArray) { require(bytes.size == asset.size && sha256(bytes) == asset.sha256) { "The ${asset.name} download failed size or SHA-256 verification." } }
    fun pieces(bytes: ByteArray, maxData: Int): List<String> {
        val limit = minOf(48 * 1024, ((maxData - 800) * 3 / 4)).let { it - it % 3 }
        require(limit >= 3) { "The TV's ADB payload limit is too small for secure uploads." }
        return (bytes.indices step limit).map { b64(bytes.copyOfRange(it, minOf(it + limit, bytes.size))) }
    }
    fun readWallpaper(tv: SelectedTv): ByteArray? {
        var total = -1; val output = java.io.ByteArrayOutputStream()
        while(total < 0 || output.size() < total) {
            val reply = tv.read(provider("wallpaper_read", output.size().toString()))
            val size = Regex("(?:[,{ ]|^)size=(\\d+)").find(reply)?.groupValues?.get(1)?.toInt() ?: error("Home cannot back up its wallpaper.")
            require(size in 0..MAX_IMAGE && (total == -1 || size == total)) { "The wallpaper changed during backup." }
            total = size
            if(size == 0) return null
            val data = Regex("data=([A-Za-z0-9+/=]+)").find(reply)?.groupValues?.get(1) ?: error("Incomplete wallpaper backup.")
            val piece = Base64.getDecoder().decode(data)
            require(piece.isNotEmpty() && output.size() + piece.size <= total)
            output.write(piece)
        }
        return output.toByteArray().also(::jpeg)
    }
    fun wallpaperCommands(tv: SelectedTv, bytes: ByteArray?, from: String): List<String> {
        if(bytes == null) return listOf(provider("wallpaper_remove"))
        jpeg(bytes)
        return listOf(provider("wallpaper_begin")) + pieces(bytes, tv.shell.maxData).map { provider("wallpaper_chunk", it) } +
            provider("wallpaper_end", digest("MD5", bytes)) + provider("set64", b64(JSONObject().put("wallpaper_from", from).toString().toByteArray()))
    }
    fun wallpaper(tv: SelectedTv, bytes: ByteArray?, from: String = "", look: JSONObject? = null, name: String = "Set wallpaper"): Plan {
        Planner(tv).requireHome()
        require(from.isEmpty() || Regex("[a-z0-9-]{1,40}:[0-3]").matches(from))
        if(bytes != null) jpeg(bytes)
        val cfg = tv.homeConfig(); val old = readWallpaper(tv)
        require(cfg.optBoolean("has_wallpaper") == (old != null)) { "The old wallpaper could not be verified." }
        val currentHash = old?.let(::sha256) ?: "absent"
        val nextHash = bytes?.let(::sha256) ?: "absent"
        val step = Step(name, wallpaperCommands(tv, bytes, from), listOf(Check(provider("get"), currentHash, "wallpaper")),
            listOf(Check(provider("get"), nextHash, "wallpaper")), wallpaperCommands(tv, old, cfg.optString("wallpaper_from")),
            listOf(Check(provider("get"), currentHash, "wallpaper")), 60_000)
        val steps = mutableListOf(step)
        if(look != null && look.length() > 0) {
            val clean = HomeSchema.themeLook(look); HomeSchema.validate(clean)
            val changed = JSONObject().apply { clean.stringKeys().filter { canonical(cfg.opt(it)) != canonical(clean.get(it)) }.forEach { put(it, clean.get(it)) } }
            if(changed.length() > 0) steps.addAll(Planner(tv).homeConfig(changed, name).steps)
        }
        return Plan.make(tv, name, steps)
    }
    fun slides(tv: SelectedTv, images: List<ByteArray>): Plan {
        Planner(tv).requireHome(); require(images.size in 1..12) { "Select 1 to 12 slideshow pictures." }
        images.forEach(::jpeg)
        val cfg = tv.homeConfig()
        require(cfg.optInt("slides_on_tv") == 0) { "Home 1.7.1 cannot export existing slideshow pictures. Replacement is blocked because the originals cannot be verified for recovery." }
        val commands = mutableListOf(provider("slides_begin"))
        images.forEachIndexed { n, bytes ->
            commands += provider("slide_file_begin", n.toString())
            commands += pieces(bytes, tv.shell.maxData).map { provider("slide_file_chunk", it) }
            commands += provider("slide_file_end", digest("MD5", bytes))
        }
        commands += provider("slides_end")
        return Plan.make(tv, "Upload ${images.size} slideshow pictures", listOf(Step("Upload and verify slideshow", commands,
            listOf(Check(provider("get"), "{\"slides_on_tv\":0}", "json")), listOf(Check(provider("get"), "{\"slides_on_tv\":${images.size}}", "json")),
            listOf(provider("slides_clear"), provider("set64", b64(JSONObject().put("slide_minutes", cfg.optInt("slide_minutes")).toString().toByteArray()))),
            listOf(Check(provider("get"), "{\"slides_on_tv\":0}", "json")), 60_000)), "Home checks every uploaded file's checksum. Select a slideshow interval after upload.")
    }
    data class Theme(val id: String, val name: String, val look: JSONObject, val pictures: List<ByteArray>)
    fun themePack(tv: SelectedTv, themes: List<Theme>): Plan {
        Planner(tv).requireHome()
        require(tv.homeConfig().optInt("themes_on_tv") == 0) { "Themes are already on this TV. Home 1.7.1 cannot export the existing pack for safe replacement. Individual themes can still be applied." }
        require(themes.size == 28)
        val commands = mutableListOf(provider("pack_begin"))
        themes.forEach { theme ->
            require(Regex("[a-z0-9-]{1,40}").matches(theme.id) && theme.pictures.size == 3)
            commands += provider("pack_meta", b64(JSONObject().put("id", theme.id).put("name", theme.name).put("look", theme.look).toString().toByteArray()))
            theme.pictures.forEachIndexed { n, bytes ->
                jpeg(bytes); commands += provider("pack_file_begin", b64("${theme.id}:$n".toByteArray()))
                commands += pieces(bytes, tv.shell.maxData).map { provider("pack_file_chunk", it) }
                commands += provider("pack_file_end", digest("MD5", bytes))
            }
        }
        commands += provider("pack_end", b64(JSONArray(themes.map { it.id }).toString().toByteArray()))
        return Plan.make(tv, "Put all 28 themes on the TV", listOf(Step("Transfer 84 original theme pictures", commands,
            listOf(Check(provider("get"), "{\"themes_on_tv\":0}", "json")), listOf(Check(provider("get"), "{\"themes_on_tv\":28}", "json")),
            listOf(provider("pack_clear")), listOf(Check(provider("get"), "{\"themes_on_tv\":0}", "json")), 60_000)))
    }
    fun safePath(path: String): String {
        require(Regex("/[A-Za-z0-9_./-]+").matches(path) && !path.split('/').contains("..")) { "The TV returned an unsafe storage path." }
        return quote(path)
    }
    fun fileState(path: String) = "if [ -f ${safePath(path)} ]; then sha256sum ${safePath(path)}; else echo absent; fi"
    fun pushCommands(tv: SelectedTv, bytes: ByteArray, path: String): List<String> {
        val temp = safePath("$path.b64"); val target = safePath(path)
        return listOf(": > $temp") + pieces(bytes, tv.shell.maxData).map { "printf %s ${quote(it)} >> $temp" } + "base64 -d $temp > $target && rm -f $temp"
    }
    fun uploadStep(tv: SelectedTv, bytes: ByteArray, path: String, name: String): Step {
        require(tv.read(fileState(path)).trim() == "absent") { "The staging file already exists. Prepare a new operation." }
        return Step(name, pushCommands(tv, bytes, path), listOf(Check(fileState(path), "absent")), listOf(Check("sha256sum ${safePath(path)}", sha256(bytes), "sha256")),
            listOf("rm -f ${safePath(path)} ${safePath("$path.b64")}"), listOf(Check(fileState(path), "absent")), 120_000)
    }
    fun fireplace(tv: SelectedTv, files: Map<String, ByteArray>): Plan {
        Planner(tv).requireHome()
        fireplace.forEach { verify(it, files.getValue(it.name)) }
        val dir = Regex("dir=([^,}\\s]+)").find(tv.read(provider("fire_dir")))?.groupValues?.get(1) ?: error("Home has no shared-storage folder on this TV.")
        safePath(dir)
        require(dir.endsWith("/Android/data/$HOME/files") || dir.contains("/Android/data/$HOME/files/")) { "Home returned an unexpected Fireplace storage location." }
        val steps = mutableListOf<Step>()
        val token = UUID.randomUUID().toString()
        fireplace.forEach { asset ->
            val path = "$dir/${asset.name}"; val state = tv.read(fileState(path)).trim()
            if(state.substringBefore(' ') == asset.sha256) return@forEach
            require(state == "absent") { "An unrecognised Fireplace asset is already on this TV. It was left untouched." }
            val stage = "$dir/.cmtv-$token-${asset.name}"
            val upload = uploadStep(tv, files.getValue(asset.name), stage, "Transfer ${asset.name}")
            steps += upload.copy(commands = listOf("mkdir -p ${safePath(dir)}") + upload.commands)
            steps += Step("Activate ${asset.name}", listOf("mv ${safePath(stage)} ${safePath(path)}", provider("fire_ready")), listOf(Check(fileState(path), "absent")),
                listOf(Check("sha256sum ${safePath(path)}", asset.sha256, "sha256")), listOf("rm -f ${safePath(path)}", provider("fire_ready")), listOf(Check(fileState(path), "absent")))
        }
        require(steps.isNotEmpty()) { "Both official Fireplace assets are already present and verified." }
        steps[steps.lastIndex] = steps.last().let { it.copy(after = it.after + Check(provider("get"), "{\"fire_on_tv\":true}", "json")) }
        return Plan.make(tv, "Install official Fireplace assets", steps, "Video and audio were verified against the v0.7.0 sizes and SHA-256 hashes. Choose playback, wallpaper or screensaver after transfer.")
    }
}
