package de.meineplaylists

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    // ---------- Seiten ----------
    private abstract class Page(val title: String) { var scroll = 0 }
    private class RootPage : Page("Meine Musik")
    private class OwnedPage : Page("Meine Playlists")
    private class FollowedPage : Page("Gefolgte Playlists")
    private class PlaylistPage(val pl: Playlist) : Page(pl.name)
    private class ListPage(title: String, val key: String, val loader: (Context) -> List<Track>) : Page(title)
    private class RecoPage : Page("Empfehlungen")
    private class SettingsPage : Page("Einstellungen")
    private class NowPage : Page("Jetzt läuft")

    private class Row(
        val title: String,
        val sub: String? = null,
        val icon: Int = 0,
        val tint: Int = 0,
        val image: String? = null,
        val showImage: Boolean = false,
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
    private val lists = HashMap<String, List<Track>>()
    private val loading = HashSet<String>()

    private var np: NowPlaying? = null
    private var npAt = 0L
    private var userDraggingVolume = false

    // ---------- Views ----------
    private lateinit var back: View
    private lateinit var headerTitle: TextView
    private lateinit var list: ListView
    private lateinit var nowView: View
    private lateinit var mini: View
    private lateinit var miniCover: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageButton
    private lateinit var npCover: ImageView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npDevice: TextView
    private lateinit var npProgress: ProgressBar
    private lateinit var npLeft: TextView
    private lateinit var npRight: TextView
    private lateinit var npPlay: ImageButton
    private lateinit var npVolume: SeekBar

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
            if (Safety.enforceLocal(this@MainActivity)) capNotice()
            if (current() is NowPage) { renderNowProgress(); renderTimerLine() }
            if (current() is SettingsPage && tickCount % 15 == 0) render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        back = findViewById(R.id.back)
        headerTitle = findViewById(R.id.header_title)
        list = findViewById(R.id.list)
        nowView = findViewById(R.id.now)
        mini = findViewById(R.id.mini)
        miniCover = findViewById(R.id.mini_cover)
        miniTitle = findViewById(R.id.mini_title)
        miniSub = findViewById(R.id.mini_sub)
        miniPlay = findViewById(R.id.mini_play)
        npCover = findViewById(R.id.np_cover)
        npTitle = findViewById(R.id.np_title)
        npArtist = findViewById(R.id.np_artist)
        npDevice = findViewById(R.id.np_device)
        npProgress = findViewById(R.id.np_progress)
        npLeft = findViewById(R.id.np_left)
        npRight = findViewById(R.id.np_right)
        npPlay = findViewById(R.id.np_play)
        npVolume = findViewById(R.id.np_volume)

        remote = Remote(this, afterAction = { handler.postDelayed({ fetchNowPlaying() }, 600) }, onLoggedOut = {
            Auth.logout(this); resetToRoot()
        })

        back.setOnClickListener { goBack() }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> rows.getOrNull(pos)?.action?.invoke() }

        mini.setOnClickListener { if (current() !is NowPage) push(NowPage()) }
        findViewById<View>(R.id.mini_prev).setOnClickListener { prev() }
        findViewById<View>(R.id.mini_next).setOnClickListener { next() }
        miniPlay.setOnClickListener { playPause() }
        findViewById<View>(R.id.np_prev).setOnClickListener { prev() }
        findViewById<View>(R.id.np_next).setOnClickListener { next() }
        npPlay.setOnClickListener { playPause() }
        npVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) { userDraggingVolume = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userDraggingVolume = false
                val cap = Safety.earCap(this@MainActivity)
                if (cap > 0 && sb.progress > cap) { sb.progress = cap; capNotice() }
                val v = sb.progress
                remote.withDevice { Spotify.setVolume(this@MainActivity, v) }
            }
        })

        AuthServer.listener = loginListener
        stack.add(RootPage())
        render()
        renderPlayer()
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
        current().scroll = list.firstVisiblePosition
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

    @Deprecated("Zurück-Taste")
    override fun onBackPressed() {
        if (stack.size > 1) goBack() else super.onBackPressed()
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
        current().scroll = list.firstVisiblePosition
        stack.add(p)
        render()
        list.setSelection(0)
    }

    private fun goBack() {
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1)
        render()
        list.setSelection(current().scroll)
    }

    private fun resetToRoot() {
        stack.clear()
        stack.add(RootPage())
        playlists = null
        lists.clear()
        Spotify.clearCache()
        render()
    }

    private fun render() {
        val page = current()
        headerTitle.text = page.title
        back.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        if (page is NowPage) {
            list.visibility = View.GONE
            nowView.visibility = View.VISIBLE
            mini.visibility = View.GONE
            renderNow()
        } else {
            nowView.visibility = View.GONE
            list.visibility = View.VISIBLE
            mini.visibility = if (Auth.isLoggedIn(this)) View.VISIBLE else View.GONE
            rows = rowsFor(page)
            adapter.notifyDataSetChanged()
        }
    }

    // Kachelfarben fürs Hauptmenü
    private val pink = 0xFFE0527A.toInt()
    private val green = 0xFF2FBF7A.toInt()
    private val blue = 0xFF3D7BE0.toInt()
    private val orange = 0xFFE8873A.toInt()
    private val violet = 0xFF8B5CF6.toInt()
    private val grey = 0xFF4A4B52.toInt()

    private fun loadingRow() = Row("Lädt …", chevron = false, dim = true)

    private fun rowsFor(page: Page): List<Row> = when (page) {
        is RootPage -> rootRows()
        is OwnedPage -> playlists?.filter { it.owned }?.let { pls ->
            if (pls.isEmpty()) listOf(Row("Keine eigenen Playlists", chevron = false, dim = true))
            else pls.map { p -> Row(p.name, countText(p.total), image = p.imageUrl, showImage = true) { push(PlaylistPage(p)) } }
        } ?: listOf(loadingRow())
        is FollowedPage -> playlists?.filter { !it.owned }?.let { pls ->
            if (pls.isEmpty()) listOf(Row("Du folgst keinen Playlists", chevron = false, dim = true))
            else pls.map { p ->
                Row(p.name, listOfNotNull(p.ownerName?.let { "von $it" }, countText(p.total)).joinToString(" · "),
                    image = p.imageUrl, showImage = true) { push(PlaylistPage(p)) }
            }
        } ?: listOf(loadingRow())
        is PlaylistPage -> playlistRows(page.pl)
        is ListPage -> trackListRows(page)
        is RecoPage -> listOf(
            Row("Deine Top-Songs", "Letzte 4 Wochen", R.drawable.ic_trend, orange) {
                push(ListPage("Top · 4 Wochen", "top_short") { Spotify.topTracks(it, "short_term") })
            },
            Row("Deine Top-Songs", "Letzte 6 Monate", R.drawable.ic_trend, orange) {
                push(ListPage("Top · 6 Monate", "top_medium") { Spotify.topTracks(it, "medium_term") })
            },
            Row("Deine Top-Songs", "Letztes Jahr", R.drawable.ic_trend, orange) {
                push(ListPage("Top · 1 Jahr", "top_long") { Spotify.topTracks(it, "long_term") })
            },
            Row("Wiederentdecken", "Früher oft gehört, zuletzt kaum", R.drawable.ic_sparkle, violet) {
                push(ListPage("Wiederentdecken", "rediscover") { Spotify.rediscover(it) })
            },
            Row("Zuletzt gehört", "Deine letzten 50 Titel", R.drawable.ic_history, blue) {
                push(ListPage("Zuletzt gehört", "recent") { Spotify.recentTracks(it) })
            },
        )
        is SettingsPage -> listOf(
            Row("Zeitlimit", timerText(), R.drawable.ic_timer, orange) { askTimer() },
            Row("Ohrschutz", Safety.earCap(this).let { if (it > 0) "An · höchstens $it % Lautstärke" else "Aus" },
                R.drawable.ic_ear, pink) { askEarCap() },
            Row("Neu anmelden", "Bei Problemen oder neuen Funktionen", R.drawable.ic_login, blue) { openLogin() },
            Row("Client-ID ändern", Auth.clientId(this)?.let { it.take(8) + "…" }, R.drawable.ic_settings, grey) { askClientId() },
            Row("Abmelden", null, R.drawable.ic_back, grey, chevron = false) { confirmLogout() },
        )
        else -> emptyList()
    }

    private fun rootRows(): List<Row> {
        if (Auth.clientId(this) == null) {
            return listOf(Row("Einrichten", "Client-ID deiner Spotify-Developer-App eintragen", R.drawable.ic_settings, blue) { askClientId() })
        }
        if (!Auth.isLoggedIn(this)) {
            return listOf(
                Row("Mit Spotify anmelden", null, R.drawable.ic_login, green) { openLogin() },
                Row("Client-ID ändern", null, R.drawable.ic_settings, grey) { askClientId() },
            )
        }
        val out = ArrayList<Row>()
        if (!Auth.hasAllScopes(this)) {
            out += Row("Einmal neu anmelden", "Nötig für Lieblingssongs & Empfehlungen", R.drawable.ic_login, green) { openLogin() }
        }
        val pls = playlists
        out += Row("Lieblingssongs", "Alle Titel mit „Gefällt mir“", R.drawable.ic_heart, pink) {
            push(ListPage("Lieblingssongs", "liked") { Spotify.likedTracks(it) })
        }
        out += Row("Meine Playlists", pls?.count { it.owned }?.let { "$it Playlists" }, R.drawable.ic_queue, green) {
            push(OwnedPage()); if (playlists == null) loadPlaylists()
        }
        out += Row("Gefolgte Playlists", pls?.count { !it.owned }?.let { "$it Playlists" }, R.drawable.ic_people, blue) {
            push(FollowedPage()); if (playlists == null) loadPlaylists()
        }
        out += Row("Empfehlungen", "Aus deinen eigenen Hördaten", R.drawable.ic_sparkle, violet) { push(RecoPage()) }
        out += Row("Einstellungen", null, R.drawable.ic_settings, grey) { push(SettingsPage()) }
        return out
    }

    private fun countText(n: Int) = if (n == 1) "1 Titel" else "$n Titel"

    private fun playRows(onPlay: () -> Unit, onShuffle: () -> Unit) = listOf(
        Row("Abspielen", null, R.drawable.ic_play_white, green, chevron = false) { onPlay() },
        Row("Zufällig", null, R.drawable.ic_shuffle, grey, chevron = false) { onShuffle() },
    )

    private fun playlistRows(pl: Playlist): List<Row> {
        val head = playRows({ playPlaylist(pl, false) }, { playPlaylist(pl, true) })
        if (!pl.owned) {
            return head + Row(
                "Titelliste nicht verfügbar",
                "Spotify zeigt die Titel fremder Playlists nicht mehr an – Abspielen klappt trotzdem.",
                chevron = false, dim = true,
            )
        }
        val ts = lists[pl.id] ?: run { loadList(pl.id) { Spotify.tracks(it, pl.id) }; return head + loadingRow() }
        return head + ts.map { t ->
            trackRow(t) { remote.withDevice { d -> Spotify.play(this, d, pl.uri, t.position) }; push(NowPage()) }
        }
    }

    private fun trackListRows(page: ListPage): List<Row> {
        val ts = lists[page.key] ?: run { loadList(page.key, page.loader); return listOf(loadingRow()) }
        if (ts.isEmpty()) return listOf(Row("Keine Titel gefunden", chevron = false, dim = true))
        val uris = ts.filter { it.playable }.mapNotNull { it.uri }
        return playRows({ playUris(uris, shuffle = false) }, { playUris(uris, shuffle = true) }) + ts.map { t ->
            trackRow(t) {
                val start = uris.indexOf(t.uri).coerceAtLeast(0)
                playUris(uris.drop(start), shuffle = false)
            }
        }
    }

    private fun trackRow(t: Track, play: () -> Unit) = Row(
        t.name, t.subtitle, image = t.imageUrl, showImage = true, chevron = false, uri = t.uri, dim = !t.playable,
        action = if (t.playable) play else ({
            Toast.makeText(this, "Dieser Titel kann nicht abgespielt werden.", Toast.LENGTH_SHORT).show()
        }),
    )

    // ---------- Laden ----------
    private fun loadPlaylists() {
        if (loadingPlaylists) return
        loadingPlaylists = true
        bg {
            try {
                val pls = Spotify.allPlaylists(this)
                ui { loadingPlaylists = false; playlists = pls; render() }
            } catch (e: Exception) {
                ui { loadingPlaylists = false; remote.showError(e) }
            }
        }
    }

    private fun loadList(key: String, loader: (Context) -> List<Track>) {
        if (!loading.add(key)) return
        bg {
            try {
                val ts = loader(this)
                ui { loading.remove(key); lists[key] = ts; render() }
            } catch (e: Exception) {
                ui {
                    loading.remove(key)
                    lists[key] = emptyList()
                    render()
                    if (e is ApiException && e.status == 403 && !Auth.hasAllScopes(this)) {
                        Toast.makeText(this, "Bitte im Hauptmenü einmal neu anmelden.", Toast.LENGTH_LONG).show()
                    } else remote.showError(e)
                }
            }
        }
    }

    // ---------- Abspielen ----------
    private fun playPlaylist(p: Playlist, shuffle: Boolean) {
        remote.withDevice { d ->
            try { Spotify.shuffle(this, shuffle, d) } catch (e: ApiException) { if (e.status == 404) throw e }
            Spotify.play(this, d, p.uri, if (shuffle) null else 0)
        }
        push(NowPage())
    }

    private fun playUris(uris: List<String>, shuffle: Boolean) {
        if (uris.isEmpty()) return
        val order = if (shuffle) uris.shuffled() else uris
        remote.withDevice { d ->
            try { Spotify.shuffle(this, false, d) } catch (e: ApiException) { if (e.status == 404) throw e }
            Spotify.playUris(this, d, order)
        }
        push(NowPage())
    }

    private fun prev() { if (Auth.isLoggedIn(this)) remote.withDevice { Spotify.previous(this, it) } }
    private fun next() { if (Auth.isLoggedIn(this)) remote.withDevice { Spotify.next(this, it) } }

    private fun playPause() {
        if (!Auth.isLoggedIn(this)) return
        val playing = np?.isPlaying == true
        np = np?.copy(isPlaying = !playing, progressMs = currentProgress())
        npAt = System.currentTimeMillis()
        renderPlayer()
        remote.withDevice { d -> if (playing) Spotify.pause(this, d) else Spotify.resume(this, d) }
    }

    // ---------- Jetzt läuft ----------
    private fun fetchNowPlaying() {
        if (!Auth.isLoggedIn(this)) return
        bg {
            val r = try { Spotify.nowPlaying(this) } catch (_: Exception) { return@bg }
            ui {
                val trackChanged = r?.trackUri != np?.trackUri
                np = r
                capSpotifyVolume(r?.volume)
                npAt = System.currentTimeMillis()
                renderPlayer()
                if (trackChanged && current() !is NowPage) adapter.notifyDataSetChanged()
            }
        }
    }

    private fun currentProgress(): Long {
        val n = np ?: return 0
        val p = if (n.isPlaying) n.progressMs + (System.currentTimeMillis() - npAt) else n.progressMs
        return if (n.durationMs > 0) p.coerceAtMost(n.durationMs) else p
    }

    private fun renderPlayer() {
        val n = np
        val playIcon = if (n?.isPlaying == true) R.drawable.ic_pause_dark else R.drawable.ic_play_dark
        miniPlay.setImageResource(playIcon)
        npPlay.setImageResource(playIcon)
        if (n == null) {
            miniTitle.text = "Nichts wird abgespielt"
            miniSub.text = "Wähle eine Playlist"
            Images.load(miniCover, null)
        } else {
            miniTitle.text = n.title
            miniSub.text = n.subtitle
            Images.load(miniCover, n.imageUrl)
        }
        if (current() is NowPage) renderNow()
    }

    private fun renderNow() {
        val n = np
        if (n == null) {
            npTitle.text = "Nichts wird abgespielt"
            npArtist.text = "Wähle eine Playlist oder einen Titel"
            npDevice.text = ""
            Images.load(npCover, null)
        } else {
            npTitle.text = n.title
            npArtist.text = n.subtitle
            Images.load(npCover, n.bigImageUrl ?: n.imageUrl)
            if (!userDraggingVolume) n.volume?.let { npVolume.progress = it }
        }
        npVolume.isEnabled = n?.volume != null
        renderNowProgress()
        renderTimerLine()
    }

    /** Zeile unter dem Lautstärkeregler: Gerät, Zeitlimit, Ohrschutz. */
    private fun renderTimerLine() {
        val parts = ArrayList<String>()
        np?.deviceName?.let { parts += "auf $it" }
        if (Safety.sleepAt(this) != 0L) parts += "Zeitlimit: " + timerText().substringAfter("· ")
        Safety.earCap(this).takeIf { it > 0 }?.let { parts += "Ohrschutz $it %" }
        npDevice.text = parts.joinToString("  ·  ")
    }

    private fun renderNowProgress() {
        val n = np
        if (n == null || n.durationMs <= 0) {
            npProgress.progress = 0; npLeft.text = ""; npRight.text = ""; return
        }
        val p = currentProgress()
        npProgress.progress = (p * 1000 / n.durationMs).toInt()
        npLeft.text = formatDuration(p)
        npRight.text = "-" + formatDuration(n.durationMs - p)
    }

    // ---------- Zeitlimit & Ohrschutz ----------
    private fun timerText(): String {
        val at = Safety.sleepAt(this)
        if (at == 0L) return "Aus"
        val left = ((at - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).format(java.util.Date(at))
        return "Stoppt um $time · noch $left Min."
    }

    private fun askTimer() {
        val minutes = intArrayOf(0, 15, 30, 45, 60, 90, 120)
        val labels = minutes.map { if (it == 0) "Aus" else "$it Minuten" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Zeitlimit – Musik stoppt nach …")
            .setItems(labels) { _, i ->
                if (minutes[i] == 0) Safety.cancelTimer(this) else Safety.startTimer(this, minutes[i])
                Toast.makeText(this, if (minutes[i] == 0) "Zeitlimit aus" else "Musik stoppt in ${minutes[i]} Minuten", Toast.LENGTH_SHORT).show()
                render()
            }
            .show()
    }

    private fun askEarCap() {
        val caps = intArrayOf(0, 40, 50, 60, 70)
        val labels = caps.map { if (it == 0) "Aus" else "Höchstens $it % Lautstärke" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Ohrschutz")
            .setItems(labels) { _, i ->
                Safety.setEarCap(this, caps[i])
                if (caps[i] > 0) {
                    Safety.enforceLocal(this)
                    capSpotifyVolume(np?.volume)
                    Toast.makeText(this, "Ohrschutz an: maximal ${caps[i]} %", Toast.LENGTH_SHORT).show()
                }
                render()
            }
            .show()
    }

    /** Senkt die Lautstärke des Spotify-Geräts, falls sie über dem Ohrschutz-Limit liegt. */
    private fun capSpotifyVolume(current: Int?) {
        val cap = Safety.earCap(this)
        if (cap <= 0 || current == null || current <= cap) return
        bg { try { Spotify.setVolume(this, cap) } catch (_: Exception) {} }
        np = np?.copy(volume = cap)
    }

    private var lastCapToast = 0L
    private fun capNotice() {
        val now = System.currentTimeMillis()
        if (now - lastCapToast > 4000) {
            lastCapToast = now
            Toast.makeText(this, "Ohrschutz: Lautstärke ist begrenzt", Toast.LENGTH_SHORT).show()
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Lauter-Taste blockieren, sobald das Ohrschutz-Limit erreicht ist
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP && Safety.earCap(this) > 0) {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            if (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) >= Safety.capIndex(am, Safety.earCap(this))) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) capNotice()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
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
                renderPlayer()
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
            val playing = row.uri != null && row.uri == np?.trackUri
            val title = v.findViewById<TextView>(R.id.title)
            val sub = v.findViewById<TextView>(R.id.sub)
            val icon = v.findViewById<ImageView>(R.id.icon)
            val dp = resources.displayMetrics.density

            title.text = row.title
            title.setTextColor(
                when {
                    row.dim -> 0xFF6A6A72.toInt()
                    playing -> getColor(R.color.accent)
                    else -> 0xFFFFFFFF.toInt()
                }
            )
            if (row.sub.isNullOrBlank()) sub.visibility = View.GONE
            else { sub.visibility = View.VISIBLE; sub.text = row.sub }

            when {
                row.icon != 0 -> {
                    icon.visibility = View.VISIBLE
                    icon.tag = null
                    icon.setBackgroundResource(R.drawable.tile_bg)
                    icon.backgroundTintList = ColorStateList.valueOf(row.tint)
                    icon.scaleType = ImageView.ScaleType.FIT_CENTER
                    val pad = (13 * dp).toInt()
                    icon.setPadding(pad, pad, pad, pad)
                    icon.setImageResource(row.icon)
                }
                row.showImage -> {
                    icon.visibility = View.VISIBLE
                    icon.setBackgroundColor(getColor(R.color.surface))
                    icon.backgroundTintList = null
                    icon.scaleType = ImageView.ScaleType.CENTER_CROP
                    icon.setPadding(0, 0, 0, 0)
                    Images.load(icon, row.image)
                }
                else -> { icon.visibility = View.GONE; icon.tag = null }
            }
            v.findViewById<View>(R.id.chevron).visibility = if (row.chevron) View.VISIBLE else View.GONE
            return v
        }
    }
}
