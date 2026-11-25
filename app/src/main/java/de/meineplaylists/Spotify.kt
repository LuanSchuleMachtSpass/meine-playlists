package de.meineplaylists

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class Playlist(
    val id: String, val name: String, val uri: String, val imageUrl: String?, val total: Int,
    val owned: Boolean, val ownerName: String?,
)

data class Track(
    val uri: String?, val name: String, val subtitle: String, val durationMs: Long,
    val imageUrl: String?, val position: Int, val playable: Boolean,
    val artistIds: List<String> = emptyList(),
)

data class Artist(val id: String, val name: String, val uri: String, val imageUrl: String?)

data class NowPlaying(
    val isPlaying: Boolean, val title: String, val subtitle: String,
    val imageUrl: String?, val deviceName: String?, val trackUri: String?,
    val progressMs: Long = 0, val durationMs: Long = 0, val volume: Int? = null,
    val bigImageUrl: String? = null,
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

    /** Alle Playlists in der Bibliothek – eigene (owned) und gefolgte. */
    fun allPlaylists(ctx: Context): List<Playlist> {
        val me = myId(ctx)
        val out = ArrayList<Playlist>()
        var next: String? = "/me/playlists?limit=50"
        while (next != null) {
            val page = Api.get(ctx, next) ?: break
            val items = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val p = items.optJSONObject(i) ?: continue
                val owner = p.optJSONObject("owner")
                val id = p.str("id") ?: continue
                val uri = p.str("uri") ?: continue
                // Feld heißt seit 2026 "items", früher "tracks"
                val count = (p.optJSONObject("items") ?: p.optJSONObject("tracks"))?.optInt("total") ?: 0
                out += Playlist(
                    id, p.str("name") ?: "Ohne Namen", uri, image(p.optJSONArray("images"), Size.M), count,
                    owned = owner?.str("id") == me || p.optBoolean("collaborative"),
                    ownerName = owner?.str("display_name") ?: owner?.str("id"),
                )
            }
            next = page.str("next")
        }
        return out
    }

    private fun parseTrack(t: JSONObject, position: Int, local: Boolean = false): Track {
        val uri = t.str("uri")
        return Track(
            uri = uri,
            name = t.str("name") ?: "Unbekannt",
            subtitle = subtitleOf(t),
            durationMs = t.optLong("duration_ms"),
            imageUrl = smallImageOf(t),
            position = position,
            playable = uri != null && !local && !t.optBoolean("is_local") && t.optBoolean("is_playable", true),
            artistIds = t.optJSONArray("artists")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.str("id") }
            } ?: emptyList(),
        )
    }

    private fun parseArtist(a: JSONObject?): Artist? {
        if (a == null) return null
        return Artist(
            id = a.str("id") ?: return null,
            name = a.str("name") ?: "Unbekannt",
            uri = a.str("uri") ?: return null,
            imageUrl = image(a.optJSONArray("images"), Size.M),
        )
    }

    /** Meistgehörte Künstler (≈6 Monate) zuerst, danach gefolgte Künstler. */
    fun favoriteArtists(ctx: Context): List<Artist> {
        val out = LinkedHashMap<String, Artist>()
        Api.get(ctx, "/me/top/artists?limit=50&time_range=medium_term")?.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) parseArtist(arr.optJSONObject(i))?.let { out.putIfAbsent(it.id, it) }
        }
        try {
            var next: String? = "/me/following?type=artist&limit=50"
            var pages = 0
            while (next != null && pages++ < 4) {
                val artists = Api.get(ctx, next)?.optJSONObject("artists") ?: break
                val arr = artists.optJSONArray("items") ?: JSONArray()
                for (i in 0 until arr.length()) parseArtist(arr.optJSONObject(i))?.let { out.putIfAbsent(it.id, it) }
                next = artists.str("next")
            }
        } catch (e: ApiException) {
            // gefolgte Künstler sind optional – Top-Künstler reichen notfalls
            if (out.isEmpty()) throw e
        }
        return out.values.toList()
    }

    /** Suche nach Songs und Künstlern. */
    fun search(ctx: Context, query: String): Pair<List<Artist>, List<Track>> {
        val q = URLEncoder.encode(query, "UTF-8")
        val j = Api.get(ctx, "/search?q=$q&type=artist,track&limit=10") ?: return emptyList<Artist>() to emptyList()
        val artists = j.optJSONObject("artists")?.optJSONArray("items")?.let { arr ->
            (0 until arr.length()).mapNotNull { parseArtist(arr.optJSONObject(it)) }
        } ?: emptyList()
        val tracks = j.optJSONObject("tracks")?.optJSONArray("items")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseTrack(it, i) } }
        } ?: emptyList()
        return artists to tracks
    }

    /** Lieblingssongs („Gefällt mir“). Begrenzt, damit das Kontingent der Dev-App nicht leerläuft. */
    fun likedTracks(ctx: Context, max: Int = 500): List<Track> {
        val out = ArrayList<Track>()
        var next: String? = "/me/tracks?limit=50"
        while (next != null && out.size < max) {
            val page = Api.get(ctx, next) ?: break
            val items = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val t = items.optJSONObject(i)?.optJSONObject("track") ?: continue
                out += parseTrack(t, out.size)
            }
            next = page.str("next")
        }
        return out
    }

    /** range: short_term (≈4 Wochen), medium_term (≈6 Monate), long_term (≈1 Jahr) */
    fun topTracks(ctx: Context, range: String): List<Track> {
        val items = Api.get(ctx, "/me/top/tracks?limit=50&time_range=$range")?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let { parseTrack(it, i) } }
    }

    fun recentTracks(ctx: Context): List<Track> {
        val items = Api.get(ctx, "/me/player/recently-played?limit=50")?.optJSONArray("items") ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Track>()
        for (i in 0 until items.length()) {
            val t = items.optJSONObject(i)?.optJSONObject("track") ?: continue
            val uri = t.str("uri") ?: continue
            if (seen.add(uri)) out += parseTrack(t, out.size)
        }
        return out
    }

    /** Früher oft gehört (≈1 Jahr), in den letzten Monaten aber nicht mehr. */
    fun rediscover(ctx: Context): List<Track> {
        val recent = (topTracks(ctx, "short_term") + topTracks(ctx, "medium_term")).mapNotNull { it.uri }.toSet()
        return topTracks(ctx, "long_term").filter { it.uri !in recent }
    }

    /** Spielt eine Liste von Titeln (für Listen ohne Playlist-Kontext, z. B. Lieblingssongs). */
    fun playUris(ctx: Context, deviceId: String?, uris: List<String>) {
        val arr = JSONArray()
        uris.take(100).forEach { arr.put(it) }
        Api.call(ctx, "PUT", "/me/player/play" + dev(deviceId), JSONObject().put("uris", arr))
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
                out += parseTrack(t, position, entry.optBoolean("is_local"))
            }
            next = page.str("next")
        }
        return out
    }

    fun nowPlaying(ctx: Context): NowPlaying? {
        val j = Api.get(ctx, "/me/player?additional_types=episode") ?: return null
        val dev = j.optJSONObject("device")
        val device = dev?.str("name")
        val volume = if (dev != null && dev.has("volume_percent") && !dev.isNull("volume_percent")) dev.optInt("volume_percent") else null
        val item = j.optJSONObject("item")
            ?: return NowPlaying(j.optBoolean("is_playing"), "Unbekannter Titel", "", null, device, null, volume = volume)
        val big = if (item.str("type") == "episode") image(item.optJSONArray("images"), Size.M)
        else image(item.optJSONObject("album")?.optJSONArray("images"), Size.M)
        return NowPlaying(
            isPlaying = j.optBoolean("is_playing"),
            title = item.str("name") ?: "Unbekannt",
            subtitle = subtitleOf(item),
            imageUrl = smallImageOf(item),
            deviceName = device,
            trackUri = item.str("uri"),
            progressMs = j.optLong("progress_ms"),
            durationMs = item.optLong("duration_ms"),
            volume = volume,
            bigImageUrl = big,
        )
    }

    fun setVolume(ctx: Context, percent: Int) {
        Api.call(ctx, "PUT", "/me/player/volume?volume_percent=${percent.coerceIn(0, 100)}")
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
