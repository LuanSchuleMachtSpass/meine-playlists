package de.meineplaylists

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class NotLoggedInException : Exception("Nicht angemeldet")
class ApiException(val status: Int, val reason: String?, message: String) : Exception(message)

/** Dünner Wrapper um die Spotify Web API. */
object Api {
    private const val BASE = "https://api.spotify.com/v1"

    fun get(ctx: Context, path: String): JSONObject? = call(ctx, "GET", path)

    fun call(ctx: Context, method: String, path: String, body: JSONObject? = null): JSONObject? {
        var token = Auth.accessToken(ctx) ?: throw NotLoggedInException()
        var res = raw(method, path, body, token)
        if (res.first == 401) {
            token = Auth.accessToken(ctx, forceRefresh = true) ?: throw NotLoggedInException()
            res = raw(method, path, body, token)
        }
        val (code, text) = res
        if (code in 200..299) {
            return if (text.isBlank()) null else try { JSONObject(text) } catch (_: Exception) { null }
        }
        if (code == 401) throw NotLoggedInException()
        var msg = "HTTP $code"
        var reason: String? = null
        try {
            JSONObject(text).optJSONObject("error")?.let { err ->
                msg = err.optString("message").ifBlank { msg }
                reason = err.optString("reason").ifBlank { null }
            }
        } catch (_: Exception) {
        }
        throw ApiException(code, reason, msg)
    }

    private fun raw(method: String, path: String, body: JSONObject?, token: String): Pair<Int, String> {
        val url = if (path.startsWith("http")) path else BASE + path
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (method != "GET") {
                val bytes = body?.toString()?.toByteArray() ?: ByteArray(0)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            // Antwort komplett lesen und Stream schließen – ohne disconnect(), damit die
            // TLS-Verbindung im Keep-Alive-Pool bleibt und die nächste Anfrage sofort startet.
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } catch (e: java.io.IOException) {
            c.disconnect()
            throw e
        }
    }
}
