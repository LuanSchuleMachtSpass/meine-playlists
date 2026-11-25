package de.meineplaylists

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    // ---------- Seiten ----------
    private abstract class Page(val title: String) { var scroll = 0 }
    private class RootPage : Page("Meine Musik")
    private class OwnedPage : Page("Meine Playlists")
    private class FollowedPage : Page("Gefolgte Playlists")
    private class PlaylistPage(val pl: Playlist) : Page(pl.name)
    private class ListPage(title: String, val key: String, val loader: (Context) -> List<Track>) : Page(title)
    private class RecoPage : Page("Empfehlungen")
    private class ArtistsPage : Page("Lieblingskünstler")
    private class ArtistPage(val artist: Artist) : Page(artist.name)
    private class Mp3Page : Page("MP3s")
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
        val header: Boolean = false,
        val action: (() -> Unit)? = null,
    )

    private class SearchResult(val artists: List<Artist>, val tracks: List<Track>, val local: List<LocalSong>, val error: String?)

    private val stack = ArrayList<Page>()
    private var rows: List<Row> = emptyList()

    // ---------- Daten ----------
    private var playlists: List<Playlist>? = null
    private var loadingPlaylists = false
    private val lists = HashMap<String, List<Track>>()
    private val loading = HashSet<String>()
    private var artists: List<Artist>? = null
    private var loadingArtists = false
    private var localSongs: List<LocalSong>? = null

    private var searchQuery = ""
    private var searchResult: SearchResult? = null
    private var searchSeq = 0

    private var np: NowPlaying? = null
    private var npAt = 0L
    private var userDraggingVolume = false

    // ---------- Views ----------
    private lateinit var back: View
    private lateinit var headerTitle: TextView
    private lateinit var search: EditText
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
    private var pendingAfterPermission: (() -> Unit)? = null

    private val local get() = LocalPlayerService.isActive

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            if (tickCount++ % 4 == 0 && !local) fetchNowPlaying()
            if (Safety.enforceLocal(this@MainActivity)) capNotice()
            if (local) renderPlayer()
            if (current() is NowPage) { renderNowProgress(); renderInfoLine() }
            if (current() is SettingsPage && tickCount % 15 == 0) render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        back = findViewById(R.id.back)
        headerTitle = findViewById(R.id.header_title)
        search = findViewById(R.id.search)
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

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { onSearchChanged(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        search.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); runSearch(searchQuery); true } else false
        }

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
        renderPlayer()
        if (!isOnline()) {
            startTicker()
            return
        }
        if (Auth.clientId(this) == null) {
            if (!autoPromptDone) { autoPromptDone = true; askClientId() }
        } else if (!Auth.isLoggedIn(this)) {
            if (!autoPromptDone) { autoPromptDone = true; openLogin() }
        } else {
            if (playlists == null) loadPlaylists()
        }
        startTicker()
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
        when {
            stack.size > 1 -> goBack()
            searchQuery.isNotEmpty() -> search.setText("")
            else -> super.onBackPressed()
        }
    }

    private fun startTicker() {
        if (running) return
        running = true
        tickCount = 0
        handler.post(ticker)
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    // ---------- Navigation ----------
    private fun current() = stack.last()

    private fun push(p: Page) {
        current().scroll = list.firstVisiblePosition
        hideKeyboard()
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
        artists = null
        lists.clear()
        Spotify.clearCache()
        render()
    }

    private fun render() {
        val page = current()
        headerTitle.text = page.title
        back.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        search.visibility = if (page is RootPage) View.VISIBLE else View.GONE
        if (page is NowPage) {
            list.visibility = View.GONE
            nowView.visibility = View.VISIBLE
            mini.visibility = View.GONE
            renderNow()
        } else {
            nowView.visibility = View.GONE
            list.visibility = View.VISIBLE
            mini.visibility = if (Auth.isLoggedIn(this) || local) View.VISIBLE else View.GONE
            rows = rowsFor(page)
            adapter.notifyDataSetChanged()
        }
    }

    // Kachelfarben
    private val pink = 0xFFE0527A.toInt()
    private val green = 0xFF2FBF7A.toInt()
    private val blue = 0xFF3D7BE0.toInt()
    private val orange = 0xFFE8873A.toInt()
    private val violet = 0xFF8B5CF6.toInt()
    private val teal = 0xFF14A3A3.toInt()
    private val grey = 0xFF4A4B52.toInt()

    private fun loadingRow() = Row("Lädt …", chevron = false, dim = true)
    private fun header(text: String) = Row(text, chevron = false, header = true)

    private fun rowsFor(page: Page): List<Row> = when (page) {
        is RootPage -> if (searchQuery.isNotBlank()) searchRows() else rootRows()
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
        is ArtistsPage -> artists?.let { list ->
            if (list.isEmpty()) listOf(Row("Noch keine Lieblingskünstler", "Hör ein bisschen mehr Musik", chevron = false, dim = true))
            else list.map { a -> Row(a.name, null, image = a.imageUrl, showImage = true) { push(ArtistPage(a)) } }
        } ?: run { loadArtists(); listOf(loadingRow()) }
        is ArtistPage -> artistRows(page.artist)
        is Mp3Page -> mp3Rows()
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
        val out = ArrayList<Row>()
        val online = isOnline()
        if (!online) {
            out += Row("Kein Internet", "Spiel deine MP3s oder Spotify-Downloads", R.drawable.ic_cloud_off, grey, chevron = false, dim = true)
        }
        if (online && Auth.clientId(this) == null) {
            out += Row("Einrichten", "Client-ID deiner Spotify-Developer-App eintragen", R.drawable.ic_settings, blue) { askClientId() }
        } else if (online && !Auth.isLoggedIn(this)) {
            out += Row("Mit Spotify anmelden", null, R.drawable.ic_login, green) { openLogin() }
        } else if (online && !Auth.hasAllScopes(this)) {
            out += Row("Einmal neu anmelden", "Nötig für die neuen Funktionen", R.drawable.ic_login, green) { openLogin() }
        }
        val pls = playlists
        if (online && Auth.isLoggedIn(this)) {
            out += Row("Lieblingssongs", "Alle Titel mit „Gefällt mir“", R.drawable.ic_heart, pink) {
                push(ListPage("Lieblingssongs", "liked") { Spotify.likedTracks(it) })
            }
            out += Row("Lieblingskünstler", "Deine meistgehörten und gefolgten Künstler", R.drawable.ic_mic, orange) {
                push(ArtistsPage())
            }
            out += Row("Meine Playlists", pls?.count { it.owned }?.let { "$it Playlists" }, R.drawable.ic_queue, green) {
                push(OwnedPage()); if (playlists == null) loadPlaylists()
            }
            out += Row("Gefolgte Playlists", pls?.count { !it.owned }?.let { "$it Playlists" }, R.drawable.ic_people, blue) {
                push(FollowedPage()); if (playlists == null) loadPlaylists()
            }
            out += Row("Empfehlungen", "Aus deinen eigenen Hördaten", R.drawable.ic_sparkle, violet) { push(RecoPage()) }
        }
        out += Row("MP3s", "Musikdateien auf diesem Handy – auch offline", R.drawable.ic_library, teal) { openMp3s() }
        out += Row("Offline-Musik von Spotify", "Deine Downloads ohne Internet", R.drawable.ic_cloud_off, grey) { showOfflineInfo() }
        out += Row("Einstellungen", null, R.drawable.ic_settings, grey) { push(SettingsPage()) }
        return out
    }

    private fun countText(n: Int) = if (n == 1) "1 Titel" else "$n Titel"

    private fun playRows(onPlay: () -> Unit, onShuffle: () -> Unit) = listOf(
        Row("Abspielen", null, R.drawable.ic_play_white, green, chevron = false) { onPlay() },
        Row("Zufällig", null, R.drawable.ic_shuffle, grey, chevron = false) { onShuffle() },
    )

    private fun playlistRows(pl: Playlist): List<Row> {
        val head = playRows({ playContext(pl.uri, false, 0) }, { playContext(pl.uri, true, null) })
        if (!pl.owned) {
            return head + Row(
                "Titelliste nicht verfügbar",
                "Spotify zeigt die Titel fremder Playlists nicht mehr an – Abspielen klappt trotzdem.",
                chevron = false, dim = true,
            )
        }
        val ts = lists[pl.id] ?: run { loadList(pl.id) { Spotify.tracks(it, pl.id) }; return head + loadingRow() }
        return head + ts.map { t -> trackRow(t) { stopLocal(); remote.withDevice { d -> Spotify.play(this, d, pl.uri, t.position) }; push(NowPage()) } }
    }

    private fun trackListRows(page: ListPage): List<Row> {
        val ts = lists[page.key] ?: run { loadList(page.key, page.loader); return listOf(loadingRow()) }
        if (ts.isEmpty()) return listOf(Row("Keine Titel gefunden", chevron = false, dim = true))
        return playRows({ playTracks(ts, null, false) }, { playTracks(ts, null, true) }) +
            ts.map { t -> trackRow(t) { playTracks(ts, t, false) } }
    }

    private fun artistRows(a: Artist): List<Row> {
        val out = ArrayList<Row>()
        out += playRows({ playContext(a.uri, false, null) }, { playContext(a.uri, true, null) })
        // Deine eigenen Songs von diesem Künstler aus Lieblingssongs, Top-Listen und Verlauf
        if (lists["liked"] == null) loadList("liked") { Spotify.likedTracks(it) }
        val seen = HashSet<String>()
        val mine = lists.values.flatten().filter { a.id in it.artistIds && it.uri != null && seen.add(it.uri) }
        if (mine.isNotEmpty()) {
            out += header("Deine Songs von ${a.name}")
            out += mine.map { t -> trackRow(t) { playTracks(mine, t, false) } }
        } else if (lists["liked"] == null) {
            out += loadingRow()
        }
        return out
    }

    private fun trackRow(t: Track, play: () -> Unit) = Row(
        t.name, t.subtitle, image = t.imageUrl, showImage = true, chevron = false, uri = t.uri, dim = !t.playable,
        action = if (t.playable) play else ({
            Toast.makeText(this, "Dieser Titel kann nicht abgespielt werden.", Toast.LENGTH_SHORT).show()
        }),
    )

    private fun localRow(songs: List<LocalSong>, s: LocalSong) = Row(
        s.title, s.artist + " · MP3", R.drawable.ic_note_white, teal, chevron = false, uri = "local:${s.id}",
    ) { playLocal(songs, songs.indexOf(s)) }

    // ---------- Suche ----------
    private val searchRunnable = Runnable { runSearch(searchQuery) }

    private fun onSearchChanged(text: String) {
        searchQuery = text.trim()
        handler.removeCallbacks(searchRunnable)
        if (searchQuery.isEmpty()) {
            searchResult = null
            if (current() is RootPage) render()
            return
        }
        if (searchQuery.length >= 2) handler.postDelayed(searchRunnable, 450)
        if (current() is RootPage) render()
    }

    private fun runSearch(q: String) {
        if (q.isBlank()) return
        val seq = ++searchSeq
        val localMatches = (localSongs ?: emptyList()).filter {
            it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
        }.take(20)
        if (!isOnline() || !Auth.isLoggedIn(this)) {
            searchResult = SearchResult(emptyList(), emptyList(), localMatches, if (isOnline()) null else "Kein Internet – nur MP3s durchsucht")
            render()
            return
        }
        bg {
            val r = try {
                val (a, t) = Spotify.search(this, q)
                SearchResult(a, t, localMatches, null)
            } catch (e: Exception) {
                SearchResult(emptyList(), emptyList(), localMatches, errorText(e))
            }
            ui { if (seq == searchSeq && current() is RootPage) { searchResult = r; render() } }
        }
    }

    private fun searchRows(): List<Row> {
        val r = searchResult ?: return listOf(Row("Suche …", chevron = false, dim = true))
        val out = ArrayList<Row>()
        r.error?.let { out += Row(it, chevron = false, dim = true) }
        if (r.artists.isNotEmpty()) {
            out += header("Künstler")
            out += r.artists.take(5).map { a -> Row(a.name, null, image = a.imageUrl, showImage = true) { push(ArtistPage(a)) } }
        }
        if (r.tracks.isNotEmpty()) {
            out += header("Songs")
            out += r.tracks.map { t -> trackRow(t) { playTracks(r.tracks, t, false) } }
        }
        if (r.local.isNotEmpty()) {
            out += header("MP3s")
            out += r.local.map { s -> localRow(r.local, s) }
        }
        if (out.isEmpty()) out += Row("Nichts gefunden", "Versuch einen anderen Begriff", chevron = false, dim = true)
        return out
    }

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

    private fun loadArtists() {
        if (loadingArtists) return
        loadingArtists = true
        bg {
            try {
                val a = Spotify.favoriteArtists(this)
                ui { loadingArtists = false; artists = a; render() }
            } catch (e: Exception) {
                ui {
                    loadingArtists = false
                    artists = emptyList()
                    render()
                    scopeOrError(e)
                }
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
                    scopeOrError(e)
                }
            }
        }
    }

    private fun scopeOrError(e: Exception) {
        if (e is ApiException && e.status == 403 && !Auth.hasAllScopes(this)) {
            Toast.makeText(this, "Bitte im Hauptmenü einmal neu anmelden.", Toast.LENGTH_LONG).show()
        } else remote.showError(e)
    }

    // ---------- MP3s ----------
    private fun mp3Rows(): List<Row> {
        if (!LocalMusic.hasPermission(this)) {
            return listOf(Row("Zugriff auf Musik erlauben", "Damit Lite Player deine MP3s findet", R.drawable.ic_library, teal) {
                requestMusicPermission { render() }
            })
        }
        val songs = localSongs ?: run { loadLocal(); return listOf(loadingRow()) }
        if (songs.isEmpty()) {
            return listOf(Row("Keine Musikdateien gefunden", "Kopier MP3s z. B. in den Ordner „Music“ auf dem Handy", chevron = false, dim = true))
        }
        return playRows({ playLocal(songs, 0) }, { playLocal(songs.shuffled(), 0) }) + songs.map { s -> localRow(songs, s) }
    }

    private fun openMp3s() {
        if (LocalMusic.hasPermission(this)) push(Mp3Page())
        else requestMusicPermission { push(Mp3Page()) }
    }

    private fun loadLocal() {
        if (!LocalMusic.hasPermission(this)) return
        bg {
            val songs = try { LocalMusic.query(this) } catch (_: Exception) { emptyList() }
            ui { localSongs = songs; render() }
        }
    }

    private fun requestMusicPermission(then: () -> Unit) {
        pendingAfterPermission = then
        val perms = ArrayList<String>()
        perms += LocalMusic.permission
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), REQ_MUSIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MUSIC) return
        val idx = permissions.indexOf(LocalMusic.permission)
        if (idx >= 0 && grantResults.getOrNull(idx) == PackageManager.PERMISSION_GRANTED) {
            loadLocal()
            pendingAfterPermission?.invoke()
        } else {
            Toast.makeText(this, "Ohne Zugriff kann Lite Player keine MP3s finden.", Toast.LENGTH_LONG).show()
        }
        pendingAfterPermission = null
    }

    private fun playLocal(songs: List<LocalSong>, index: Int) {
        if (songs.isEmpty()) return
        // Spotify anhalten, damit nicht beides gleichzeitig läuft
        if (Auth.isLoggedIn(this) && np?.isPlaying == true && isOnline()) {
            bg { try { Spotify.pause(this, null) } catch (_: Exception) {} }
            np = np?.copy(isPlaying = false)
        }
        LocalPlayerService.play(this, songs, index.coerceAtLeast(0))
        handler.postDelayed({ renderPlayer() }, 300)
        push(NowPage())
    }

    private fun stopLocal() {
        if (local) LocalPlayerService.command(this, LocalPlayerService.ACTION_STOP)
    }

    // ---------- Offline ----------
    private fun showOfflineInfo() {
        AlertDialog.Builder(this)
            .setTitle("Offline-Musik von Spotify")
            .setMessage(
                "Ohne Internet kann Lite Player Spotify nicht steuern – und Spotify-Downloads sind verschlüsselt, " +
                    "darum spielt sie nur die Spotify-App selbst ab.\n\n" +
                    "So hörst du offline:\n" +
                    "• Spotify-Downloads: In Spotify unter Bibliothek → „Heruntergeladen“.\n" +
                    "• Oder deine eigenen MP3s direkt hier in Lite Player."
            )
            .setPositiveButton("Spotify-Downloads öffnen") { _, _ -> openSpotifyDownloads() }
            .setNeutralButton("MP3s") { _, _ -> openMp3s() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun openSpotifyDownloads() {
        val pkg = "com.spotify.music"
        val deep = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:collection")).setPackage(pkg)
        try {
            startActivity(deep)
        } catch (_: Exception) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) startActivity(launch)
            else Toast.makeText(this, "Spotify-App ist nicht installiert.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Abspielen (Spotify) ----------
    private fun playContext(uri: String, shuffle: Boolean, position: Int?) {
        stopLocal()
        remote.withDevice { d ->
            try { Spotify.shuffle(this, shuffle, d) } catch (e: ApiException) { if (e.status == 404) throw e }
            Spotify.play(this, d, uri, position)
        }
        push(NowPage())
    }

    private fun playTracks(all: List<Track>, start: Track?, shuffle: Boolean) {
        val uris = all.filter { it.playable }.mapNotNull { it.uri }
        if (uris.isEmpty()) return
        val order = when {
            shuffle -> uris.shuffled()
            start != null -> uris.drop(uris.indexOf(start.uri).coerceAtLeast(0))
            else -> uris
        }
        stopLocal()
        remote.withDevice { d ->
            try { Spotify.shuffle(this, false, d) } catch (e: ApiException) { if (e.status == 404) throw e }
            Spotify.playUris(this, d, order)
        }
        push(NowPage())
    }

    private fun prev() {
        if (local) { LocalPlayerService.command(this, LocalPlayerService.ACTION_PREV); handler.postDelayed({ renderPlayer() }, 300); return }
        if (Auth.isLoggedIn(this)) remote.withDevice { Spotify.previous(this, it) }
    }

    private fun next() {
        if (local) { LocalPlayerService.command(this, LocalPlayerService.ACTION_NEXT); handler.postDelayed({ renderPlayer() }, 300); return }
        if (Auth.isLoggedIn(this)) remote.withDevice { Spotify.next(this, it) }
    }

    private fun playPause() {
        if (local) { LocalPlayerService.command(this, LocalPlayerService.ACTION_TOGGLE); handler.postDelayed({ renderPlayer() }, 200); return }
        if (!Auth.isLoggedIn(this)) return
        val playing = np?.isPlaying == true
        np = np?.copy(isPlaying = !playing, progressMs = currentProgress())
        npAt = System.currentTimeMillis()
        renderPlayer()
        remote.withDevice { d -> if (playing) Spotify.pause(this, d) else Spotify.resume(this, d) }
    }

    // ---------- Jetzt läuft ----------
    private fun fetchNowPlaying() {
        if (!Auth.isLoggedIn(this) || !isOnline()) return
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
        val song = LocalPlayerService.current()
        val playing = if (song != null) LocalPlayerService.isPlaying else np?.isPlaying == true
        val playIcon = if (playing) R.drawable.ic_pause_dark else R.drawable.ic_play_dark
        miniPlay.setImageResource(playIcon)
        npPlay.setImageResource(playIcon)
        when {
            song != null -> {
                miniTitle.text = song.title
                miniSub.text = song.artist
                Images.load(miniCover, null)
            }
            np == null -> {
                miniTitle.text = "Nichts wird abgespielt"
                miniSub.text = "Wähle eine Playlist"
                Images.load(miniCover, null)
            }
            else -> {
                miniTitle.text = np!!.title
                miniSub.text = np!!.subtitle
                Images.load(miniCover, np!!.imageUrl)
            }
        }
        if (current() !is NowPage && local) mini.visibility = View.VISIBLE
        if (current() is NowPage) renderNow()
    }

    private fun renderNow() {
        val song = LocalPlayerService.current()
        val n = np
        when {
            song != null -> {
                npTitle.text = song.title
                npArtist.text = song.artist
                Images.load(npCover, null)
                npVolume.isEnabled = false
            }
            n == null -> {
                npTitle.text = "Nichts wird abgespielt"
                npArtist.text = "Wähle eine Playlist oder einen Titel"
                Images.load(npCover, null)
                npVolume.isEnabled = false
            }
            else -> {
                npTitle.text = n.title
                npArtist.text = n.subtitle
                Images.load(npCover, n.bigImageUrl ?: n.imageUrl)
                if (!userDraggingVolume) n.volume?.let { npVolume.progress = it }
                npVolume.isEnabled = n.volume != null
            }
        }
        renderNowProgress()
        renderInfoLine()
    }

    private fun renderNowProgress() {
        val (p, d) = if (local) LocalPlayerService.positionMs() to LocalPlayerService.durationMs()
        else currentProgress() to (np?.durationMs ?: 0L)
        if (d <= 0) { npProgress.progress = 0; npLeft.text = ""; npRight.text = ""; return }
        npProgress.progress = (p * 1000 / d).toInt()
        npLeft.text = formatDuration(p)
        npRight.text = "-" + formatDuration((d - p).coerceAtLeast(0))
    }

    /** Zeile unter dem Lautstärkeregler: Quelle, Zeitlimit, Ohrschutz. */
    private fun renderInfoLine() {
        val parts = ArrayList<String>()
        if (local) parts += "MP3 auf diesem Handy" else np?.deviceName?.let { parts += "auf $it" }
        if (Safety.sleepAt(this) != 0L) parts += "Zeitlimit: " + timerText().substringAfter("· ")
        Safety.earCap(this).takeIf { it > 0 }?.let { parts += "Ohrschutz $it %" }
        npDevice.text = parts.joinToString("  ·  ")
    }

    // ---------- Zeitlimit & Ohrschutz ----------
    private fun timerText(): String {
        val at = Safety.sleepAt(this)
        if (at == 0L) return "Aus"
        val left = ((at - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1
        val time = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(at))
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Lauter-Taste blockieren, sobald das Ohrschutz-Limit erreicht ist
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && Safety.earCap(this) > 0) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) >= Safety.capIndex(am, Safety.earCap(this))) {
                if (event.action == KeyEvent.ACTION_DOWN) capNotice()
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
        override fun isEnabled(position: Int) = rows.getOrNull(position)?.let { !it.header } ?: false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
            val row = rows[position]
            val playingNow = LocalPlayerService.current()?.let { "local:${it.id}" } ?: np?.trackUri
            val playing = row.uri != null && row.uri == playingNow
            val title = v.findViewById<TextView>(R.id.title)
            val sub = v.findViewById<TextView>(R.id.sub)
            val icon = v.findViewById<ImageView>(R.id.icon)
            val dp = resources.displayMetrics.density

            title.text = row.title
            if (row.header) {
                title.textSize = 13f
                title.setTextColor(getColor(R.color.text2))
                v.minimumHeight = (36 * dp).toInt()
            } else {
                title.textSize = 16f
                title.setTextColor(
                    when {
                        row.dim -> 0xFF6A6A72.toInt()
                        playing -> getColor(R.color.accent)
                        else -> 0xFFFFFFFF.toInt()
                    }
                )
                v.minimumHeight = (64 * dp).toInt()
            }
            if (row.sub.isNullOrBlank()) sub.visibility = View.GONE
            else { sub.visibility = View.VISIBLE; sub.text = row.sub }

            when {
                row.header -> { icon.visibility = View.GONE; icon.tag = null }
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
            v.findViewById<View>(R.id.chevron).visibility = if (row.chevron && !row.header) View.VISIBLE else View.GONE
            return v
        }
    }

    companion object {
        private const val REQ_MUSIC = 42
    }
}
