package dev.cleanmytv.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.cleanmytv.core.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Artwork {
    /** Original upstream Canvas algorithms, isolated from all network and file access. UI remains native Compose. */
    @Suppress("SetJavaScriptEnabled")
    suspend fun render(context: Context, paint: JSONObject, picture: Int = 0): ByteArray = withContext(Dispatchers.Main) {
        val copy = JSONObject(paint.toString())
        copy.optJSONObject("art")?.let { it.put("seed", it.getInt("seed") + picture * 7919) }
        val js = context.assets.open("artwork.js").bufferedReader().use { it.readText() }
        suspendCancellableCoroutine { continuation ->
            val web = WebView(context)
            web.settings.javaScriptEnabled = true
            web.settings.allowFileAccess = false; web.settings.allowContentAccess = false; web.settings.blockNetworkLoads = true
            web.settings.domStorageEnabled = false
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    val argument = JSONObject.quote(copy.toString())
                    view.evaluateJavascript("window.cmtvDraw(JSON.parse($argument))") { reply ->
                        if(continuation.isActive) {
                            try {
                                val data = org.json.JSONArray("[$reply]").getString(0).substringAfter(',')
                                val bytes = Base64.getDecoder().decode(data); Media.jpeg(bytes)
                                continuation.resume(bytes)
                            } catch(e: Exception) { continuation.resumeWithException(IllegalStateException("The wallpaper renderer did not return a picture.", e)) }
                        }
                        view.destroy()
                    }
                }
            }
            continuation.invokeOnCancellation { web.post { web.destroy() } }
            web.loadDataWithBaseURL("https://cleanmytv.invalid/", "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width\"></head><body><script>$js</script></body></html>", "text/html", "UTF-8", null)
        }
    }

    suspend fun image(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(65536)
            while(true) { val n = input.read(buffer); if(n < 0) break; require(out.size() + n <= 25 * 1024 * 1024) { "Choose a picture smaller than 25 MB." }; out.write(buffer, 0, n) }
            out.toByteArray()
        } ?: error("Android could not open this picture.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        require(bounds.outWidth in 1..40000 && bounds.outHeight in 1..40000) { "Choose a supported picture file." }
        val sample = maxOf(1, minOf(bounds.outWidth / 1920, bounds.outHeight / 1080))
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("The picture could not be decoded.")
        val target = Bitmap.createBitmap(1920, 1080, Bitmap.Config.RGB_565)
        val scale = maxOf(1920f / bitmap.width, 1080f / bitmap.height)
        val w = bitmap.width * scale; val h = bitmap.height * scale
        Canvas(target).drawBitmap(bitmap, null, RectF((1920-w)/2, (1080-h)/2, (1920+w)/2, (1080+h)/2), Paint(Paint.FILTER_BITMAP_FLAG))
        bitmap.recycle()
        var quality = 88; var data: ByteArray
        do { val out = ByteArrayOutputStream(); target.compress(Bitmap.CompressFormat.JPEG, quality, out); data = out.toByteArray(); quality -= 6 } while(data.size > 300 * 1024 && quality >= 28)
        target.recycle(); Media.jpeg(data); data
    }
}
