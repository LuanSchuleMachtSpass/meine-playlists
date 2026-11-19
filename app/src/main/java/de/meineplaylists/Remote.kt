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
                val devices = try { Spotify.devices(activity) } catch (x: Exception) { ui { showError(x) }; return@bg }
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

    private fun pickDevice(devices: List<Device>, onPick: (String) -> Unit) {
        if (!alive()) return
        when (devices.size) {
            0 -> AlertDialog.Builder(activity)
                .setTitle("Kein Gerät gefunden")
                .setMessage(
                    "Die Musik wird von Spotify selbst abgespielt. Öffne die Spotify-App kurz auf diesem Handy " +
                        "(oder an einem anderen Gerät), komm zurück und drück erneut."
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
