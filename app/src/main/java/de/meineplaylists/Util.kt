package de.meineplaylists

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors

private val pool = Executors.newCachedThreadPool()
private val imagePool = Executors.newFixedThreadPool(4)
private val main = Handler(Looper.getMainLooper())

fun bg(block: () -> Unit) = pool.execute(block)
fun ui(block: () -> Unit) { main.post(block) }

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

fun errorText(e: Throwable): String = when (e) {
    is NotLoggedInException -> "Bitte erneut anmelden."
    is ApiException -> when {
        e.reason == "PREMIUM_REQUIRED" -> "Zum Abspielen brauchst du Spotify Premium."
        e.status == 429 -> "Zu viele Anfragen – bitte kurz warten."
        e.status == 403 && (e.message ?: "").contains("estriction") -> "Das geht gerade nicht (${e.message})."
        e.status == 403 -> "Zugriff verweigert (${e.message}). Ist dein Spotify-Account in deiner Developer-App unter „User Management“ eingetragen?"
        else -> e.message ?: "Fehler ${e.status}"
    }
    is UnknownHostException -> "Keine Internetverbindung."
    else -> e.message ?: e.javaClass.simpleName
}

/** Minimaler Bild-Lader mit Speicher-Cache. */
object Images {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun load(view: ImageView, url: String?, onBitmap: ((Bitmap?) -> Unit)? = null) {
        if (url != null && view.tag == url && view.getTag(R.id.icon) == true) { onBitmap?.invoke(cache.get(url)); return }
        view.tag = url
        view.setTag(R.id.icon, false)
        if (url == null) { view.setImageResource(R.drawable.ic_note); onBitmap?.invoke(null); return }
        cache.get(url)?.let { view.setImageBitmap(it); view.setTag(R.id.icon, true); onBitmap?.invoke(it); return }
        view.setImageResource(R.drawable.ic_note)
        imagePool.execute {
            try {
                val bmp = URL(url).openStream().use { BitmapFactory.decodeStream(it) } ?: return@execute
                cache.put(url, bmp)
                ui {
                    if (view.tag == url) {
                        // weich einblenden statt hart umschalten
                        view.alpha = 0f
                        view.setImageBitmap(bmp)
                        view.setTag(R.id.icon, true)
                        view.animate().alpha(1f).setDuration(220).start()
                        onBitmap?.invoke(bmp)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Dunkle, gedeckte Hintergrundfarbe aus einem Cover. */
    fun moodColor(bmp: Bitmap?): Int {
        if (bmp == null) return 0xFF1B1B1E.toInt()
        val px = Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0)
        val r = ((px shr 16) and 0xFF) * 45 / 100
        val g = ((px shr 8) and 0xFF) * 45 / 100
        val b = (px and 0xFF) * 45 / 100
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/** Abgerundete Ecken für Cover-Bilder. */
fun android.view.View.roundCorners(radiusDp: Float) {
    val r = radiusDp * resources.displayMetrics.density
    outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, r)
        }
    }
    clipToOutline = true
}
