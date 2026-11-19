package de.meineplaylists

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Eine einzige Ansicht wie bei einem klassischen MP3-Player:
 * oben der Bildschirm mit Menüs bzw. „Jetzt läuft“, unten das Klickrad.
 */
class MainActivity : Activity(), ClickWheelView.Listener {

    // ---------- Seiten ----------
    private abstract class Page(val title: String) { var sel = 0 }
    private class RootPage : Page("Meine Playlists")
    private class PlaylistsPage : Page("Playlists")
    private class TracksPage(val pl: Playlist) : Page(pl.name)
    private class NowPage : Page("Jetzt läuft")
    private class SettingsPage : Page("Einstellungen")

    private class Row(
        val title: String,
        val sub: String? = null,
        val chevron: Boolean = true,
        val uri: String? = null,
        val dim: Boolean = false,
        val action: (() -> Unit)? = null,
    )

    private val stack = ArrayList<Page>()
    private var rows: List<Row> = emptyList()

    // ---------- Daten ----------
    private var playlists: List<Playlist>? = null
    private var loadingPlaylists = false
    private val tracks = HashMap<String, List<Track>>()
    private val loadingTracks = HashSet<String>()

    private var np: NowPlaying? = null
    private var npAt = 0L
    private var volume: Int? = null
    private var volumeShownUntil = 0L
    private var volumeSendPending = false

    // ---------- Views ----------
    private lateinit var headerTitle: TextView
    private lateinit var headerState: ImageView
    private lateinit var list: ListView
    private lateinit var nowView: View
    private lateinit var npCover: ImageView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npDevice: TextView
    private lateinit var npProgress: ProgressBar
    private lateinit var npLeft: TextView
    private lateinit var npRight: TextView

    private lateinit var remote: Remote
    private val adapter = RowAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var tickCount = 0
    private var autoPromptDone = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            if (tickCount++ % 4 == 0) fetchNowPlaying()
            if (stack.lastOrNull() is NowPage) renderNow()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        headerTitle = findViewById(R.id.header_title)
        headerState = findViewById(R.id.header_state)
        list = findViewById(R.id.list)
        nowView = findViewById(R.id.now)
        npCover = findViewById(R.id.np_cover)
        npTitle = findViewById(R.id.np_title)
        npArtist = findViewById(R.id.np_artist)
        npDevice = findViewById(R.id.np_device)
        npProgress = findViewById(R.id.np_progress)
        npLeft = findViewById(R.id.np_left)
        npRight = findViewById(R.id.np_right)
        findViewById<ClickWheelView>(R.id.wheel).listener = this

        remote = Remote(this, afterAction = { handler.postDelayed({ fetchNowPlaying() }, 600) }, onLoggedOut = {
            Auth.logout(this); resetToRoot()
        })

        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            current().sel = pos
            adapter.notifyDataSetChanged()
            rows.getOrNull(pos)?.action?.invoke()
        }

        AuthServer.listener = loginListener
        stack.add(RootPage())
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
        if (Auth.clientId(this) == null) {
            if (!autoPromptDone) { autoPromptDone = true; askClientId() }
        } else if (!Auth.isLoggedIn(this)) {
            if (!autoPromptDone) { autoPromptDone = true; openLogin() }
        } else {
            if (playlists == null) loadPlaylists()
            startTicker()
        }
    }

    override fun onPause() {
        super.onPause()
        running = false
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (AuthServer.listener === loginListener) AuthServer.listener = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Rückkehr aus dem Browser (meineplaylists://login)
    }

    @Deprecated("Zurück-Taste = MENU")
    override fun onBackPressed() {
        if (stack.size > 1) onMenu() else super.onBackPressed()
    }

    private fun startTicker() {
        if (running) return
        running = true
        tickCount = 0
        handler.post(ticker)
    }

    // ---------- Navigation ----------
    private fun current() = stack.last()

    private fun push(p: Page) {
        stack.add(p)
        render()
    }

    private fun resetToRoot() {
        stack.clear()
        stack.add(RootPage())
        playlists = null
        tracks.clear()
        Spotify.clearCache()
        render()
    }

    private fun render() {
        val page = current()
        headerTitle.text = page.title
        if (page is NowPage) {
            list.visibility = View.GONE
            nowView.visibility = View.VISIBLE
            renderNow()
        } else {
            nowView.visibility = View.GONE
            list.visibility = View.VISIBLE
            rows = rowsFor(page)
            page.sel = page.sel.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
            adapter.notifyDataSetChanged()
            ensureVisible(page.sel)
        }
        renderHeaderState()
    }

    private fun rowsFor(page: Page): List<Row> = when (page) {
        is RootPage -> when {
            Auth.clientId(this) == null -> listOf(Row("Einrichten: Client-ID", "Mitteltaste drücken") { askClientId() })
            !Auth.isLoggedIn(this) -> listOf(
                Row("Mit Spotify anmelden") { openLogin() },
                Row("Client-ID ändern") { askClientId() },
            )
            else -> listOf(
                Row("Playlists") { push(PlaylistsPage()); if (playlists == null) loadPlaylists() },
                Row("Jetzt läuft") { push(NowPage()) },
                Row("Einstellungen") { push(SettingsPage()) },
            )
        }
        is PlaylistsPage -> playlists?.let { pls ->
            if (pls.isEmpty()) listOf(Row("Keine eigenen Playlists", chevron = false, dim = true))
            else pls.map { p ->
                Row(p.name, if (p.total == 1) "1 Titel" else "${p.total} Titel") { openPlaylist(p) }
            }
        } ?: listOf(Row("Lädt …", chevron = false, dim = true))
        is TracksPage -> tracks[page.pl.id]?.let { ts ->
            listOf(
                Row("Alle abspielen", chevron = false) { playPlaylist(page.pl, shuffle = false) },
                Row("Zufällig", chevron = false) { playPlaylist(page.pl, shuffle = true) },
            ) + ts.map { t ->
                Row(t.name, t.subtitle, chevron = false, uri = t.uri, dim = !t.playable,
                    action = if (t.playable) ({ playTrack(page.pl, t) }) else ({
                        Toast.makeText(this, "Dieser Titel kann nicht abgespielt werden.", Toast.LENGTH_SHORT).show()
                    }))
            }
        } ?: listOf(Row("Lädt …", chevron = false, dim = true))
        is SettingsPage -> listOf(
            Row("Client-ID ändern") { askClientId() },
            Row("Abmelden", chevron = false) { confirmLogout() },
        )
        else -> emptyList()
    }

    private fun ensureVisible(pos: Int) {
        list.post {
            val first = list.firstVisiblePosition
            val last = list.lastVisiblePosition
            val rowH = (48 * resources.displayMetrics.density).toInt()
            when {
                last < 0 -> list.setSelection(pos)
                pos <= first -> list.setSelectionFromTop(pos, 0)
                pos >= last -> list.setSelectionFromTop(pos, list.height - list.paddingTop - list.paddingBottom - rowH)
            }
        }
    }

    // ---------- Klickrad ----------
    override fun onWheelScroll(steps: Int) {
        val page = current()
        if (page is NowPage) { changeVolume(steps * 2); return }
        if (rows.isEmpty()) return
        val next = (page.sel + steps).coerceIn(0, rows.size - 1)
        if (next != page.sel) {
            page.sel = next
            adapter.notifyDataSetChanged()
            ensureVisible(next)
        }
    }

    override fun onMenu() {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            render()
        }
    }

    override fun onSelect() {
        val page = current()
        if (page is NowPage) { onPlayPause(); return }
        rows.getOrNull(page.sel)?.action?.invoke()
    }

    override fun onPrev() { if (Auth.isLoggedIn(this)) remote.withDevice { Spotify.previous(this, it) } }
    override fun onNext() { if (Auth.isLoggedIn(this)) remote.withDevice { Spotify.next(this, it) } }

    override fun onPlayPause() {
        if (!Auth.isLoggedIn(this)) return
        val playing = np?.isPlaying == true
        np = np?.copy(isPlaying = !playing, progressMs = currentProgress())
        npAt = System.currentTimeMillis()
        renderHeaderState()
        if (current() is NowPage) renderNow()
        remote.withDevice { d -> if (playing) Spotify.pause(this, d) else Spotify.resume(this, d) }
    }

    // ---------- Aktionen ----------
    private fun openPlaylist(p: Playlist) {
        push(TracksPage(p))
        if (tracks[p.id] == null && loadingTracks.add(p.id)) {
            bg {
                try {
                    val ts = Spotify.tracks(this, p.id)
                    ui { loadingTracks.remove(p.id); tracks[p.id] = ts; if ((current() as? TracksPage)?.pl?.id == p.id) render() }
                } catch (e: Exception) {
                    ui { loadingTracks.remove(p.id); remote.showError(e) }
                }
            }
        }
    }

    private fun playTrack(p: Playlist, t: Track) {
        remote.withDevice { d -> Spotify.play(this, d, p.uri, t.position) }
        push(NowPage())
    }

    private fun playPlaylist(p: Playlist, shuffle: Boolean) {
        remote.withDevice { d ->
            try { Spotify.shuffle(this, shuffle, d) } catch (e: ApiException) { if (e.status == 404) throw e }
            Spotify.play(this, d, p.uri, if (shuffle) null else 0)
        }
        push(NowPage())
    }

    private fun loadPlaylists() {
        if (loadingPlaylists) return
        loadingPlaylists = true
        bg {
            try {
                val pls = Spotify.myPlaylists(this)
                ui { loadingPlaylists = false; playlists = pls; render() }
            } catch (e: Exception) {
                ui { loadingPlaylists = false; remote.showError(e) }
            }
        }
    }

    // ---------- Jetzt läuft ----------
    private fun fetchNowPlaying() {
        if (!Auth.isLoggedIn(this)) return
        bg {
            val r = try { Spotify.nowPlaying(this) } catch (_: Exception) { return@bg }
            ui {
                np = r
                npAt = System.currentTimeMillis()
                if (!volumeSendPending && System.currentTimeMillis() > volumeShownUntil) volume = r?.volume
                renderHeaderState()
                if (current() is NowPage) renderNow()
                if (current() is TracksPage) adapter.notifyDataSetChanged()
            }
        }
    }

    private fun currentProgress(): Long {
        val n = np ?: return 0
        val p = if (n.isPlaying) n.progressMs + (System.currentTimeMillis() - npAt) else n.progressMs
        return if (n.durationMs > 0) p.coerceAtMost(n.durationMs) else p
    }

    private fun renderHeaderState() {
        val n = np
        if (n == null) headerState.visibility = View.INVISIBLE
        else {
            headerState.visibility = View.VISIBLE
            headerState.setImageResource(if (n.isPlaying) R.drawable.ic_hplay else R.drawable.ic_hpause)
        }
    }

    private fun renderNow() {
        val n = np
        if (n == null) {
            npTitle.text = "Nichts wird abgespielt"
            npArtist.text = "Wähle eine Playlist aus"
            npDevice.text = ""
            Images.load(npCover, null)
            npProgress.progress = 0
            npLeft.text = ""
            npRight.text = ""
            return
        }
        npTitle.text = n.title
        npArtist.text = n.subtitle
        npDevice.text = n.deviceName?.let { "auf $it" } ?: ""
        Images.load(npCover, n.bigImageUrl ?: n.imageUrl)
        if (System.currentTimeMillis() < volumeShownUntil && volume != null) {
            npProgress.progress = volume!! * 10
            npLeft.text = "Lautstärke"
            npRight.text = "$volume %"
        } else {
            val p = currentProgress()
            npProgress.progress = if (n.durationMs > 0) (p * 1000 / n.durationMs).toInt() else 0
            npLeft.text = formatDuration(p)
            npRight.text = if (n.durationMs > 0) "-" + formatDuration(n.durationMs - p) else ""
        }
    }

    private fun changeVolume(delta: Int) {
        val v = volume ?: np?.volume
        if (v == null) {
            Toast.makeText(this, "Lautstärke für dieses Gerät nicht verfügbar.", Toast.LENGTH_SHORT).show()
            return
        }
        volume = (v + delta).coerceIn(0, 100)
        volumeShownUntil = System.currentTimeMillis() + 1500
        renderNow()
        if (!volumeSendPending) {
            volumeSendPending = true
            handler.postDelayed({
                val target = volume ?: return@postDelayed
                bg {
                    try { Spotify.setVolume(this, target) } catch (e: Exception) { ui { remote.showError(e) } }
                    ui { volumeSendPending = false }
                }
            }, 250)
        }
    }

    // ---------- Einrichtung & Login ----------
    private fun askClientId() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = "Client-ID"
            setSingleLine()
            setText(Auth.clientId(this@MainActivity) ?: "")
        }
        val box = LinearLayout(this).apply { setPadding(pad, 0, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Spotify Client-ID")
            .setMessage(
                "1. developer.spotify.com/dashboard → „Create app“\n" +
                    "2. Redirect URI: ${Auth.REDIRECT_URI}\n" +
                    "3. API: „Web API“ anhaken, speichern\n" +
                    "4. Client-ID kopieren und hier einfügen"
            )
            .setView(box)
            .setPositiveButton("Speichern") { _, _ ->
                val id = input.text.toString().trim()
                if (id.length >= 16) {
                    Auth.setClientId(this, id)
                    render()
                    if (!Auth.isLoggedIn(this)) openLogin()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Login im Standard-Browser; die App empfängt die Weiterleitung über AuthServer. */
    private fun openLogin() {
        if (Auth.clientId(this) == null) { askClientId(); return }
        if (!AuthServer.start(this)) {
            Toast.makeText(this, "Login-Empfänger konnte nicht gestartet werden (Port 8888 belegt?).", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Auth.buildAuthUrl(this))))
        } catch (e: Exception) {
            AuthServer.stop()
            Toast.makeText(this, "Kein Browser gefunden.", Toast.LENGTH_LONG).show()
        }
    }

    private val loginListener: (String?) -> Unit = { error ->
        if (!isFinishing && !isDestroyed) {
            if (error == null) {
                resetToRoot()
                loadPlaylists()
                startTicker()
            } else {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Abmelden?")
            .setPositiveButton("Abmelden") { _, _ ->
                Auth.logout(this)
                np = null
                autoPromptDone = true
                resetToRoot()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ---------- Liste ----------
    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
            val row = rows[position]
            val selected = position == current().sel
            val playing = row.uri != null && row.uri == np?.trackUri
            val title = v.findViewById<TextView>(R.id.title)
            val sub = v.findViewById<TextView>(R.id.sub)
            val chev = v.findViewById<ImageView>(R.id.chevron)

            v.setBackgroundResource(if (selected) R.drawable.row_selected else android.R.color.transparent)
            title.text = if (playing) "♪  ${row.title}" else row.title
            title.setTextColor(
                when {
                    selected -> 0xFFFFFFFF.toInt()
                    row.dim -> 0xFF6A6A72.toInt()
                    playing -> 0xFF7FBCFF.toInt()
                    else -> 0xFFE8E8EC.toInt()
                }
            )
            if (row.sub.isNullOrBlank()) sub.visibility = View.GONE
            else {
                sub.visibility = View.VISIBLE
                sub.text = row.sub
                sub.setTextColor(if (selected) 0xDDFFFFFF.toInt() else 0xFF8A8A92.toInt())
            }
            chev.visibility = if (row.chevron) View.VISIBLE else View.INVISIBLE
            chev.alpha = if (selected) 1f else 0.7f
            return v
        }
    }
}
