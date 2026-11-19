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

    fun load(view: ImageView, url: String?) {
        view.tag = url
        if (url == null) { view.setImageResource(R.drawable.ic_note); return }
        cache.get(url)?.let { view.setImageBitmap(it); return }
        view.setImageResource(R.drawable.ic_note)
        imagePool.execute {
            try {
                val bmp = URL(url).openStream().use { BitmapFactory.decodeStream(it) } ?: return@execute
                cache.put(url, bmp)
                ui { if (view.tag == url) view.setImageBitmap(bmp) }
            } catch (_: Exception) {
            }
        }
    }
}
