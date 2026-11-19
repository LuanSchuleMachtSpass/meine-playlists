package de.meineplaylists

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class Playlist(val id: String, val name: String, val uri: String, val imageUrl: String?, val total: Int)

data class Track(
    val uri: String?, val name: String, val subtitle: String, val durationMs: Long,
    val imageUrl: String?, val position: Int, val playable: Boolean,
)

data class NowPlaying(
    val isPlaying: Boolean, val title: String, val subtitle: String,
    val imageUrl: String?, val deviceName: String?, val trackUri: String?,
)

data class Device(val id: String, val name: String, val type: String)

/** Null-sicheres String-Lesen (org.json liefert sonst "null"). */
fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/** Nur die Endpunkte, die die App braucht – keine Empfehlungen, keine Browse-/Home-Daten. */
object Spotify {
    @Volatile private var meId: String? = null

    fun clearCache() { meId = null }

    private fun myId(ctx: Context): String =
        meId ?: (Api.get(ctx, "/me") ?: throw NotLoggedInException()).getString("id").also { meId = it }

    private enum class Size { L, M, S }

    // Spotify liefert Bilder absteigend nach Größe sortiert.
    private fun image(arr: JSONArray?, size: Size): String? {
        if (arr == null || arr.length() == 0) return null
        val idx = when (size) {
            Size.L -> 0
            Size.M -> minOf(1, arr.length() - 1)
            Size.S -> arr.length() - 1
        }
        return arr.optJSONObject(idx)?.str("url")
    }

    private fun artists(arr: JSONArray?): String {
        if (arr == null) return ""
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.str("name") }.joinToString(", ")
    }

    private fun subtitleOf(item: JSONObject): String =
        if (item.str("type") == "episode") item.optJSONObject("show")?.str("name") ?: "Podcast"
        else artists(item.optJSONArray("artists"))

    private fun smallImageOf(item: JSONObject): String? =
        if (item.str("type") == "episode") image(item.optJSONArray("images"), Size.S)
        else image(item.optJSONObject("album")?.optJSONArray("images"), Size.S)

    /** Nur Playlists, deren Besitzer der angemeldete Nutzer ist. */
    fun myPlaylists(ctx: Context): List<Playlist> {
        val me = myId(ctx)
        val out = ArrayList<Playlist>()
        var next: String? = "/me/playlists?limit=50"
        while (next != null) {
            val page = Api.get(ctx, next) ?: break
            val items = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val p = items.optJSONObject(i) ?: continue
                if (p.optJSONObject("owner")?.str("id") != me) continue
                val id = p.str("id") ?: continue
                val uri = p.str("uri") ?: continue
                // Feld heißt seit 2026 "items", früher "tracks"
                val count = (p.optJSONObject("items") ?: p.optJSONObject("tracks"))?.optInt("total") ?: 0
                out += Playlist(id, p.str("name") ?: "Ohne Namen", uri, image(p.optJSONArray("images"), Size.M), count)
            }
            next = page.str("next")
        }
        return out
    }

    fun tracks(ctx: Context, playlistId: String): List<Track> {
        val out = ArrayList<Track>()
        var next: String? = "/playlists/$playlistId/items?limit=50&additional_types=track,episode"
        var pos = 0
        while (next != null) {
            val page = Api.get(ctx, next) ?: break
            val items = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val position = pos++
                val entry = items.optJSONObject(i) ?: continue
                val t = entry.optJSONObject("item") ?: entry.optJSONObject("track") ?: continue
                val uri = t.str("uri")
                val local = entry.optBoolean("is_local") || t.optBoolean("is_local")
                out += Track(
                    uri = uri,
                    name = t.str("name") ?: "Unbekannt",
                    subtitle = subtitleOf(t),
                    durationMs = t.optLong("duration_ms"),
                    imageUrl = smallImageOf(t),
                    position = position,
                    playable = uri != null && !local && t.optBoolean("is_playable", true),
                )
            }
            next = page.str("next")
        }
        return out
    }

    fun nowPlaying(ctx: Context): NowPlaying? {
        val j = Api.get(ctx, "/me/player?additional_types=episode") ?: return null
        val device = j.optJSONObject("device")?.str("name")
        val item = j.optJSONObject("item")
            ?: return NowPlaying(j.optBoolean("is_playing"), "Unbekannter Titel", "", null, device, null)
        return NowPlaying(
            isPlaying = j.optBoolean("is_playing"),
            title = item.str("name") ?: "Unbekannt",
            subtitle = subtitleOf(item),
            imageUrl = smallImageOf(item),
            deviceName = device,
            trackUri = item.str("uri"),
        )
    }

    fun devices(ctx: Context): List<Device> {
        val arr = Api.get(ctx, "/me/player/devices")?.optJSONArray("devices") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val d = arr.optJSONObject(i) ?: return@mapNotNull null
            if (d.optBoolean("is_restricted")) return@mapNotNull null
            Device(d.str("id") ?: return@mapNotNull null, d.str("name") ?: "Gerät", d.str("type") ?: "")
        }
    }

    private fun dev(deviceId: String?, first: Boolean = true): String =
        if (deviceId == null) "" else (if (first) "?" else "&") + "device_id=" + URLEncoder.encode(deviceId, "UTF-8")

    fun play(ctx: Context, deviceId: String?, contextUri: String, position: Int?) {
        val body = JSONObject().put("context_uri", contextUri)
        if (position != null) body.put("offset", JSONObject().put("position", position))
        Api.call(ctx, "PUT", "/me/player/play" + dev(deviceId), body)
    }

    fun resume(ctx: Context, deviceId: String?) { Api.call(ctx, "PUT", "/me/player/play" + dev(deviceId)) }
    fun pause(ctx: Context, deviceId: String?) { Api.call(ctx, "PUT", "/me/player/pause" + dev(deviceId)) }
    fun next(ctx: Context, deviceId: String?) { Api.call(ctx, "POST", "/me/player/next" + dev(deviceId)) }
    fun previous(ctx: Context, deviceId: String?) { Api.call(ctx, "POST", "/me/player/previous" + dev(deviceId)) }
    fun shuffle(ctx: Context, on: Boolean, deviceId: String?) {
        Api.call(ctx, "PUT", "/me/player/shuffle?state=$on" + dev(deviceId, first = false))
    }
}
