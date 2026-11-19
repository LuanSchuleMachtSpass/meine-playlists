package de.meineplaylists

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast

/**
 * Fernbedienung für Spotify Connect. Die Musik spielt die offizielle Spotify-App (oder ein anderes Gerät);
 * diese App steuert nur per Web API.
 */
class Remote(private val activity: Activity, private val afterAction: () -> Unit, private val onLoggedOut: () -> Unit) {

    /**
     * Führt eine Player-Aktion aus. Gibt es kein aktives Gerät (404), wird eins ausgewählt
     * und die Aktion gezielt auf diesem Gerät wiederholt.
     */
    fun withDevice(action: (deviceId: String?) -> Unit) {
        bg {
            try {
                action(null)
                ui { afterAction() }
            } catch (e: ApiException) {
                if (e.status != 404) { ui { showError(e) }; return@bg }
                var devices = try { Spotify.devices(activity) } catch (x: Exception) { ui { showError(x) }; return@bg }
                if (devices.isEmpty() && wakeSpotify()) {
                    // Spotify unsichtbar aufwecken und kurz warten, bis es sich als Gerät meldet
                    ui { Toast.makeText(activity, "Spotify wird im Hintergrund gestartet …", Toast.LENGTH_SHORT).show() }
                    for (i in 0 until 6) {
                        Thread.sleep(1500)
                        devices = try { Spotify.devices(activity) } catch (_: Exception) { emptyList() }
                        if (devices.isNotEmpty()) break
                    }
                }
                ui {
                    pickDevice(devices) { id ->
                        bg {
                            try { action(id); ui { afterAction() } } catch (x: Exception) { ui { showError(x) } }
                        }
                    }
                }
            } catch (e: Exception) {
                ui { showError(e) }
            }
        }
    }

    private fun alive() = !activity.isFinishing && !activity.isDestroyed

    /**
     * Schickt Spotify einen „Play“-Medientasten-Befehl, ohne die App zu öffnen. Dadurch startet
     * Spotify seinen Wiedergabedienst im Hintergrund und meldet das Handy als Connect-Gerät an.
     * Klappt nicht auf jedem Handy (z. B. wenn Spotify per „Beenden erzwingen“ gestoppt wurde).
     */
    private fun wakeSpotify(): Boolean {
        val pkg = "com.spotify.music"
        try {
            activity.packageManager.getPackageInfo(pkg, 0)
        } catch (_: Exception) {
            return false
        }
        return try {
            val now = android.os.SystemClock.uptimeMillis()
            for (action in intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                val event = android.view.KeyEvent(now, now, action, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0)
                val intent = android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON)
                    .setPackage(pkg)
                    .putExtra(android.content.Intent.EXTRA_KEY_EVENT, event)
                activity.sendBroadcast(intent)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun pickDevice(devices: List<Device>, onPick: (String) -> Unit) {
        if (!alive()) return
        when (devices.size) {
            0 -> AlertDialog.Builder(activity)
                .setTitle("Kein Gerät gefunden")
                .setMessage(
                    "Spotify konnte nicht im Hintergrund gestartet werden. Öffne die Spotify-App kurz auf diesem Handy " +
                        "(oder an einem anderen Gerät), komm zurück und tippe erneut.\n\n" +
                        "Tipp: Stell bei Spotify unter Android-Einstellungen → Apps → Spotify → Akku " +
                        "„Nicht eingeschränkt“ ein, dann passiert das seltener."
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

    fun showError(e: Exception) {
        if (!alive()) return
        if (e is NotLoggedInException) { onLoggedOut(); return }
        Toast.makeText(activity, errorText(e), Toast.LENGTH_LONG).show()
    }
}
