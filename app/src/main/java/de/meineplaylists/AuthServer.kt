package de.meineplaylists

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Winziger HTTP-Empfänger auf 127.0.0.1:8888 – nur während der Anmeldung aktiv.
 * Spotify leitet den Browser nach dem Login auf http://127.0.0.1:8888/callback?code=… weiter;
 * hier wird der Code abgeholt und gegen Tokens getauscht.
 */
object AuthServer {
    private const val PORT = 8888
    @Volatile private var server: ServerSocket? = null

    /** Wird im UI-Thread aufgerufen: null = Erfolg, sonst Fehlermeldung. */
    @Volatile var listener: ((String?) -> Unit)? = null

    fun start(ctx: Context): Boolean {
        stop()
        val app = ctx.applicationContext
        val s = try {
            ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1")).apply {
                reuseAddress = true
                soTimeout = 10 * 60 * 1000 // nach 10 Minuten ohne Login aufgeben
            }
        } catch (_: Exception) {
            return false
        }
        server = s
        Thread {
            try {
                while (!s.isClosed) {
                    val done = s.accept().use { handle(app, it) }
                    if (done) break
                }
            } catch (_: Exception) {
            } finally {
                try { s.close() } catch (_: Exception) {}
                if (server === s) server = null
            }
        }.start()
        return true
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(ctx: Context, sock: Socket): Boolean {
        sock.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return false
        // restliche Header lesen, damit der Browser die Antwort sauber bekommt
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val path = requestLine.split(" ").getOrNull(1) ?: "/"
        if (!path.startsWith("/callback")) {
            respond(sock, "404 Not Found", page("Nicht gefunden", ""))
            return false
        }
        val uri = Uri.parse("http://127.0.0.1:$PORT$path")
        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        val result: String? = when {
            error != null -> if (error == "access_denied") "Anmeldung abgebrochen." else "Fehler von Spotify: $error"
            code == null || uri.getQueryParameter("state") != Auth.expectedState(ctx) -> "Ungültige Antwort von Spotify."
            else -> try { Auth.exchangeCode(ctx, code); null } catch (e: Exception) { errorText(e) }
        }
        if (result == null) {
            respond(sock, "200 OK", page("Angemeldet ✓", "Du kannst jetzt zurück zur App."))
        } else {
            respond(sock, "200 OK", page("Anmeldung fehlgeschlagen", result))
        }
        ui { listener?.invoke(result) }
        return true
    }

    private fun respond(sock: Socket, status: String, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        val out = sock.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun page(title: String, text: String) = """<!doctype html><html lang="de"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Meine Playlists</title>
<style>body{margin:0;background:#0F0F10;color:#fff;font-family:sans-serif;display:flex;min-height:100vh;
align-items:center;justify-content:center;text-align:center;padding:24px;box-sizing:border-box}
h1{font-size:24px;margin:0 0 8px}p{color:#A0A0A8;margin:0 0 28px}
a{display:inline-block;background:#3DDC97;color:#0F0F10;font-weight:bold;text-decoration:none;
padding:14px 28px;border-radius:24px}</style></head><body><div>
<h1>${esc(title)}</h1><p>${esc(text)}</p><a href="meineplaylists://login">Zurück zur App</a>
</div><script>setTimeout(function(){location.href="meineplaylists://login"},400)</script></body></html>"""
}
