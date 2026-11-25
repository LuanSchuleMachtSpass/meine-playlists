package de.meineplaylists

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

class AuthException(val status: Int, body: String) : Exception("Anmeldung fehlgeschlagen ($status): $body")

/** OAuth Authorization Code + PKCE – kein Client-Secret nötig. */
object Auth {
    // Spotify erlaubt nur HTTPS oder Loopback-IPs. Die WebView fängt diese Adresse ab, es läuft kein Server.
    const val REDIRECT_URI = "http://127.0.0.1:8888/callback"
    private const val SCOPES = "playlist-read-private playlist-read-collaborative " +
        "user-read-playback-state user-modify-playback-state user-read-currently-playing " +
        "user-library-read user-top-read user-read-recently-played user-follow-read"

    /** true, wenn das gespeicherte Login alle aktuell benötigten Rechte hat (sonst neu anmelden). */
    fun hasAllScopes(ctx: Context): Boolean {
        val granted = prefs(ctx).getString("scope", null)?.split(" ")?.toSet() ?: return false
        return SCOPES.split(" ").all { it in granted }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("auth", Context.MODE_PRIVATE)

    fun clientId(ctx: Context): String? = prefs(ctx).getString("client_id", null)?.takeIf { it.isNotBlank() }
    fun setClientId(ctx: Context, id: String) = prefs(ctx).edit().putString("client_id", id.trim()).apply()
    fun isLoggedIn(ctx: Context) = prefs(ctx).getString("refresh_token", null) != null

    fun logout(ctx: Context) {
        prefs(ctx).edit().remove("access_token").remove("refresh_token").remove("expires_at").apply()
    }

    private fun b64(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun buildAuthUrl(ctx: Context): String {
        val rnd = SecureRandom()
        val verifier = b64(ByteArray(64).also { rnd.nextBytes(it) })
        val state = b64(ByteArray(16).also { rnd.nextBytes(it) })
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        prefs(ctx).edit().putString("verifier", verifier).putString("state", state).apply()
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId(ctx))
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPES)
            .build().toString()
    }

    fun expectedState(ctx: Context): String? = prefs(ctx).getString("state", null)

    fun exchangeCode(ctx: Context, code: String) {
        val json = postToken(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "client_id" to (clientId(ctx) ?: throw NotLoggedInException()),
                "code_verifier" to (prefs(ctx).getString("verifier", null) ?: throw NotLoggedInException()),
            )
        )
        save(ctx, json)
    }

    /** Liefert ein gültiges Access-Token (erneuert es bei Bedarf) oder null, wenn nicht angemeldet. */
    @Synchronized
    fun accessToken(ctx: Context, forceRefresh: Boolean = false): String? {
        val p = prefs(ctx)
        val token = p.getString("access_token", null)
        // 5 Minuten Puffer: erneuert wird vorab im Hintergrund, nicht erst beim Abspielen
        if (!forceRefresh && token != null && System.currentTimeMillis() < p.getLong("expires_at", 0) - 300_000) {
            return token
        }
        val refresh = p.getString("refresh_token", null) ?: return null
        val id = clientId(ctx) ?: return null
        val json = try {
            postToken(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to id))
        } catch (e: AuthException) {
            if (e.status == 400 || e.status == 401) { logout(ctx); return null }
            throw e
        }
        save(ctx, json)
        return json.getString("access_token")
    }

    private fun save(ctx: Context, json: JSONObject) {
        val e = prefs(ctx).edit()
            .putString("access_token", json.getString("access_token"))
            .putLong("expires_at", System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000)
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { e.putString("refresh_token", it) }
        json.optString("scope").takeIf { it.isNotBlank() }?.let { e.putString("scope", it) }
        e.apply()
    }

    private fun postToken(params: Map<String, String>): JSONObject {
        val body = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }.toByteArray()
        val c = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw AuthException(code, text)
            return JSONObject(text)
        } catch (e: java.io.IOException) {
            c.disconnect()
            throw e
        }
    }
}
