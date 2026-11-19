package de.meineplaylists

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/**
 * Untere Steuerleiste. Die Musik selbst spielt Spotify (App auf dem Handy oder ein anderes Connect-Gerät);
 * diese App steuert nur per Web API.
 */
class PlayerBar(private val activity: Activity, private val onChange: ((NowPlaying?) -> Unit)? = null) {
    private val root: View = activity.findViewById(R.id.player_bar)
    private val cover: ImageView = root.findViewById(R.id.pb_cover)
    private val title: TextView = root.findViewById(R.id.pb_title)
    private val subtitle: TextView = root.findViewById(R.id.pb_subtitle)
    private val play: ImageButton = root.findViewById(R.id.pb_play)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    var state: NowPlaying? = null
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            handler.postDelayed(this, 4000)
        }
    }

    init {
        root.findViewById<View>(R.id.pb_prev).setOnClickListener { withDevice { Spotify.previous(activity, it) } }
        root.findViewById<View>(R.id.pb_next).setOnClickListener { withDevice { Spotify.next(activity, it) } }
        play.setOnClickListener {
            val playing = state?.isPlaying == true
            // sofortiges Feedback
            play.setImageResource(if (playing) R.drawable.ic_play else R.drawable.ic_pause)
            withDevice { d -> if (playing) Spotify.pause(activity, d) else Spotify.resume(activity, d) }
        }
        render(null)
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    fun refresh() {
        bg {
            val np = try { Spotify.nowPlaying(activity) } catch (_: Exception) { return@bg }
            ui { render(np) }
        }
    }

    private fun refreshSoon() { handler.postDelayed({ refresh() }, 700) }

    private fun render(np: NowPlaying?) {
        state = np
        if (np == null) {
            title.text = "Nichts wird abgespielt"
            subtitle.text = "Tippe auf einen Titel"
            Images.load(cover, null)
            play.setImageResource(R.drawable.ic_play)
        } else {
            title.text = np.title
            subtitle.text = listOfNotNull(np.subtitle.ifBlank { null }, np.deviceName?.let { "auf $it" })
                .joinToString(" · ")
            Images.load(cover, np.imageUrl)
            play.setImageResource(if (np.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }
        onChange?.invoke(np)
    }

    /**
     * Führt eine Player-Aktion aus. Gibt es kein aktives Gerät (404), wird eins ausgewählt
     * und die Aktion gezielt auf diesem Gerät wiederholt.
     */
    fun withDevice(action: (deviceId: String?) -> Unit) {
        bg {
            try {
                action(null)
                refreshSoon()
            } catch (e: ApiException) {
                if (e.status != 404) { ui { showError(e) }; return@bg }
                val devices = try { Spotify.devices(activity) } catch (x: Exception) { ui { showError(x) }; return@bg }
                ui {
                    pickDevice(devices) { id ->
                        bg {
                            try { action(id); refreshSoon() } catch (x: Exception) { ui { showError(x) } }
                        }
                    }
                }
            } catch (e: Exception) {
                ui { showError(e) }
            }
        }
    }

    private fun alive() = !activity.isFinishing && !activity.isDestroyed

    private fun pickDevice(devices: List<Device>, onPick: (String) -> Unit) {
        if (!alive()) return
        when (devices.size) {
            0 -> AlertDialog.Builder(activity)
                .setTitle("Kein Gerät gefunden")
                .setMessage(
                    "Die Musik wird von Spotify selbst abgespielt. Öffne die Spotify-App kurz auf diesem Handy " +
                        "(oder an einem anderen Gerät), komm zurück und tippe erneut."
                )
                .setPositiveButton("Spotify öffnen") { _, _ -> openSpotifyApp() }
                .setNegativeButton("Abbrechen", null)
                .show()
            1 -> onPick(devices[0].id)
            else -> AlertDialog.Builder(activity)
                .setTitle("Wo abspielen?")
                .setItems(devices.map { if (it.type.isBlank()) it.name else "${it.name}  ·  ${it.type}" }.toTypedArray()) { _, i ->
                    onPick(devices[i].id)
                }
                .show()
        }
    }

    private fun openSpotifyApp() {
        val i = activity.packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (i != null) activity.startActivity(i)
        else Toast.makeText(activity, "Spotify-App ist nicht installiert.", Toast.LENGTH_LONG).show()
    }

    private fun showError(e: Exception) {
        if (!alive()) return
        refresh()
        if (e is NotLoggedInException) {
            activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            return
        }
        Toast.makeText(activity, errorText(e), Toast.LENGTH_LONG).show()
    }
}
