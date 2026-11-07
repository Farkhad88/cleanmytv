package dev.cleanmytv.core

import org.json.JSONArray
import org.json.JSONObject

/** Home 1.7.1's Config.kt / src/main/home.ts contract, not a guessed provider API. */
object HomeSchema {
    val choices = linkedMapOf(
        "layout" to listOf("shelf", "split", "rail"), "focus" to listOf("lift", "glow", "outline", "none"),
        "clock" to listOf("time_date", "time", "off"), "viz_style" to listOf("bars", "mirror", "wave"),
        "viz_colour" to listOf("accent", "rainbow", "white"), "weather_unit" to listOf("c", "f"),
        "icon_style" to listOf("banner", "colour", "mono", "duo"), "tile_shape" to listOf("wide", "square", "squircle", "round"),
        "lock_for" to listOf("sleep", "15", "always"), "lock_show" to listOf("badge", "hide"), "slide_from" to listOf("mine", "theme"))
    val ranges = linkedMapOf("top_space" to 0..40, "dock_scale" to 60..150, "tile_scale" to 60..150, "wallpaper_dim" to 0..80,
        "dock_size" to 0..8, "columns" to 3..8, "idle_minutes" to 0..60, "viz_bars" to 8..64, "viz_height" to 4..40,
        "viz_opacity" to 20..100, "viz_sensitivity" to 25..300, "fire_volume" to 0..100, "fire_timer" to 0..240, "slide_minutes" to 0..240)
    val bools = setOf("show_names", "name_focus", "follow_colour", "pin_focus", "tilt", "intro", "lock_on", "lock_system",
        "fire_wall", "fire_sound", "fire_wall_sound", "visualiser", "viz_peaks", "buttons_on")
    val quick = listOf("settings", "wifi", "inputs", "bluetooth", "display", "apps", "sleep", "search", "music", "panel", "themes", "fireplace")
    val sections = listOf("nowplaying", "dock", "rows", "folders", "grid")
    val actions = quick + listOf("next_wallpaper", "home", "back", "none", "app")
    val themeKeys = setOf("accent", "focus", "background", "wallpaper_dim", "top_space", "dock_scale", "tile_scale", "show_names", "clock",
        "visualiser", "viz_style", "viz_bars", "viz_height", "viz_opacity", "viz_colour", "viz_peaks", "viz_sensitivity", "icon_style", "tile_shape")
    val keys = choices.keys + ranges.keys + bools + setOf("accent", "background", "order", "hidden", "lock_apps", "sections", "quick", "rows", "folders", "buttons",
        "greeting", "weather_place", "weather_lat", "weather_lon", "wallpaper_from")

    fun validate(o: JSONObject) {
        require(o.length() > 0) { "No settings were supplied." }
        for (key in o.stringKeys()) {
            require(key in keys) { "Unsupported Home 1.7.1 setting: $key." }
            val value = o.get(key)
            val valid = when {
                key in choices -> value is String && value in choices.getValue(key)
                key in ranges -> value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble() && value.toInt() in ranges.getValue(key)
                key in bools -> value is Boolean
                key in setOf("accent", "background") -> value is String && Regex("#[0-9A-Fa-f]{6}").matches(value)
                key in setOf("greeting", "weather_place") -> value is String && value.length <= 40 && value.none { it.code < 32 || it.code == 127 }
                key in setOf("weather_lat", "weather_lon") -> value is Number && value.toDouble().isFinite() && kotlin.math.abs(value.toDouble()) <= if(key == "weather_lat") 90 else 180
                key == "wallpaper_from" -> value is String && (value.isEmpty() || Regex("[a-z0-9-]{1,40}:[0-3]").matches(value))
                key in setOf("order", "hidden", "lock_apps") -> packageList(value, 500)
                key == "sections" -> stringList(value, 5) { it in sections }
                key == "quick" -> stringList(value, 18) { it in quick || it.startsWith("app:") && PACKAGE.matches(it.removePrefix("app:")) } &&
                    (value as JSONArray).strings().count { it.startsWith("app:") } <= 6
                key in setOf("rows", "folders") -> value is JSONArray && value.length() <= (if(key == "rows") 8 else 12) &&
                    (0 until value.length()).all { n -> value.optJSONObject(n)?.let { group ->
                        val name = group.optString("name"); name.isNotBlank() && name.length <= 30 && name.none { it.code < 32 } && packageList(group.opt("apps"), 60)
                    } == true }
                key == "buttons" -> value is JSONArray && value.length() <= 30 && (0 until value.length()).all { n -> value.optJSONObject(n)?.let { b ->
                    b.optInt("code") in 1..65535 && b.optString("action") in actions && (b.optString("action") != "app" || PACKAGE.matches(b.optString("app")))
                } == true } && (0 until value.length()).map { value.getJSONObject(it).getInt("code") }.distinct().size == value.length()
                else -> false
            }
            require(valid) { "Invalid value for $key. Check the range and format shown in the editor." }
        }
    }
    private fun packageList(v: Any?, max: Int) = stringList(v, max) { PACKAGE.matches(it) }
    private fun stringList(v: Any?, max: Int, predicate: (String) -> Boolean): Boolean = v is JSONArray && v.length() <= max &&
        (0 until v.length()).all { v.get(it) is String && predicate(v.getString(it)) } && v.strings().distinct().size == v.length()
    fun themeLook(o: JSONObject) = JSONObject().apply { o.stringKeys().filter { it in themeKeys }.forEach { put(it, o.get(it)) } }
    fun parseTheme(text: String): JSONObject {
        require(text.length <= 6 * 1024 * 1024) { "The theme file is too large." }
        val o = JSONObject(text)
        require(o.optString("format") == "cleanmytv-theme" && o.optInt("version") == 1) { "This is not a supported CleanMyTV theme file." }
        val look = themeLook(o.getJSONObject("look")); validate(look)
        if (o.has("wallpaper") && !o.isNull("wallpaper")) {
            val bytes = java.util.Base64.getDecoder().decode(o.getString("wallpaper")); Media.jpeg(bytes)
        }
        return o.put("look", look)
    }
}
