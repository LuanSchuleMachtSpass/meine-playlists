package de.meineplaylists

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
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
    private class ArtistListPage(title: String, val key: String, val loader: (Context) -> List<Artist>) : Page(title)
    private class AlbumsPage(title: String, val range: String) : Page(title)
    private class ArtistPage(val artist: Artist) : Page(artist.name)
    private class DiscoverPage : Page("Empfehlungen")
    private class StatsPage : Page("Deine Hördaten")
    private class QueuePage : Page("Warteschlange")
    private class Mp3Page : Page("MP3s")
    private class SettingsPage : Page("Einstellungen")

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
        val menu: (() -> Unit)? = null,
        val action: (() -> Unit)? = null,
    )

    private class SearchResult(val artists: List<Artist>, val tracks: List<Track>, val local: List<LocalSong>, val error: String?)

    private val stack = ArrayList<Page>()
    private var rows: List<Row> = emptyList()

    // ---------- Daten ----------
    private var playlists: List<Playlist>? = null
    private var loadingPlaylists = false
    private val lists = HashMap<String, List<Track>>()
    private val artistLists = HashMap<String, List<Artist>>()
    private val loading = HashSet<String>()
    private var discover: Discover? = null
    private var localSongs: List<LocalSong>? = null
    private var spotifyQueue: List<Track>? = null

    private var searchQuery = ""
    private var searchResult: SearchResult? = null
    private var searchSeq = 0

    private var np: NowPlaying? = null
    private var npAt = 0L
    private var userDraggingVolume = false
    private var userSeeking = false
    private var nowOpen = false
    private var currentMood = 0xFF1B1B1E.toInt()
    private var lastCoverKey: String? = null

    // ---------- Views ----------
    private lateinit var back: View
    private lateinit var headerTitle: TextView
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var mini: View
    private lateinit var miniCover: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageButton
    private lateinit var miniProgress: ProgressBar
    private lateinit var nowView: View
    private lateinit var nowBg: View
    private lateinit var nowSource: TextView
    private lateinit var npCover: ImageView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npDevice: TextView
    private lateinit var npSeek: SeekBar
    private lateinit var npLeft: TextView
    private lateinit var npRight: TextView
    private lateinit var npPlay: ImageButton
    private lateinit var npShuffle: ImageButton
    private lateinit var npRepeat: ImageButton
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
            animateProgress()
            if (nowOpen) renderInfoLine()
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
        mini = findViewById(R.id.mini)
        miniCover = findViewById(R.id.mini_cover)
        miniTitle = findViewById(R.id.mini_title)
        miniSub = findViewById(R.id.mini_sub)
        miniPlay = findViewById(R.id.mini_play)
        miniProgress = findViewById(R.id.mini_progress)
        nowView = findViewById(R.id.now)
        nowBg = findViewById(R.id.now_bg)
        nowSource = findViewById(R.id.now_source)
        npCover = findViewById(R.id.np_cover)
        npTitle = findViewById(R.id.np_title)
        npArtist = findViewById(R.id.np_artist)
        npDevice = findViewById(R.id.np_device)
        npSeek = findViewById(R.id.np_seek)
        npLeft = findViewById(R.id.np_left)
        npRight = findViewById(R.id.np_right)
        npPlay = findViewById(R.id.np_play)
        npShuffle = findViewById(R.id.np_shuffle)
        npRepeat = findViewById(R.id.np_repeat)
        npVolume = findViewById(R.id.np_volume)

        miniCover.roundCorners(10f)
        npCover.roundCorners(18f)

        remote = Remote(this, afterAction = { handler.postDelayed({ fetchNowPlaying() }, 600) }, onLoggedOut = {
            Auth.logout(this); resetToRoot()
        })

        back.setOnClickListener { goBack() }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> rows.getOrNull(pos)?.action?.invoke() }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val m = rows.getOrNull(pos)?.menu ?: return@setOnItemLongClickListener false
            m(); true
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { onSearchChanged(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        search.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); runSearch(searchQuery); true } else false
        }

        mini.setOnClickListener { openNow() }
        miniPlay.setOnClickListener { bounce(it); playPause() }
        findViewById<View>(R.id.mini_next).setOnClickListener { bounce(it); next() }
        findViewById<View>(R.id.now_close).setOnClickListener { closeNow() }
        findViewById<View>(R.id.now_queue).setOnClickListener { closeNow(); push(QueuePage()); loadQueue() }
        findViewById<View>(R.id.np_prev).setOnClickListener { bounce(it); prev() }
        findViewById<View>(R.id.np_next).setOnClickListener { bounce(it); next() }
        npPlay.setOnClickListener { bounce(it); playPause() }
        npShuffle.setOnClickListener { bounce(it); toggleShuffle() }
        npRepeat.setOnClickListener { bounce(it); cycleRepeat() }

        npSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    val d = durationNow()
                    if (d > 0) npLeft.text = formatDuration(d * value / 1000)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true; seekAnim?.cancel() }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                val ms = durationNow() * sb.progress / 1000
                seekTo(ms)
            }
        })
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
        if (isOnline()) {
            if (Auth.clientId(this) == null) {
                if (!autoPromptDone) { autoPromptDone = true; askClientId() }
            } else if (!Auth.isLoggedIn(this)) {
                if (!autoPromptDone) { autoPromptDone = true; openLogin() }
            } else if (playlists == null) {
                loadPlaylists()
            }
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
            nowOpen -> closeNow()
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

    // ---------- Animationen ----------
    private fun bounce(v: View) {
        v.animate().cancel()
        v.scaleX = 0.86f; v.scaleY = 0.86f
        v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun slideList(forward: Boolean) {
        val w = list.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        list.animate().cancel()
        list.translationX = if (forward) w * 0.18f else -w * 0.18f
        list.alpha = 0f
        list.animate().translationX(0f).alpha(1f).setDuration(240).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    private fun openNow() {
        if (nowOpen) return
        nowOpen = true
        hideKeyboard()
        renderNow()
        val h = (nowView.parent as View).height.toFloat().takeIf { it > 0 } ?: 2000f
        nowView.visibility = View.VISIBLE
        nowView.translationY = h
        nowView.animate().translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun closeNow() {
        if (!nowOpen) return
        nowOpen = false
        val h = (nowView.parent as View).height.toFloat()
        nowView.animate().translationY(h).setDuration(260).setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!nowOpen) nowView.visibility = View.GONE }.start()
    }

    private var seekAnim: ObjectAnimator? = null
    private var miniAnim: ObjectAnimator? = null

    /** Fortschritt weich über die nächste Sekunde animieren statt sprunghaft zu setzen. */
    private fun animateProgress() {
        val d = durationNow()
        if (d <= 0) {
            miniProgress.progress = 0
            if (!userSeeking) { npSeek.progress = 0; npLeft.text = ""; npRight.text = "" }
            return
        }
        val p = positionNow()
        val playing = isPlayingNow()
        val now = (p * 1000 / d).toInt().coerceIn(0, 1000)
        val target = if (playing) ((p + 1000) * 1000 / d).toInt().coerceIn(0, 1000) else now
        miniAnim?.cancel()
        miniProgress.progress = now
        miniAnim = ObjectAnimator.ofInt(miniProgress, "progress", now, target).apply {
            duration = 1000; interpolator = LinearInterpolator(); start()
        }
        if (nowOpen && !userSeeking) {
            seekAnim?.cancel()
            npSeek.progress = now
            seekAnim = ObjectAnimator.ofInt(npSeek, "progress", now, target).apply {
                duration = 1000; interpolator = LinearInterpolator(); start()
            }
            npLeft.text = formatDuration(p)
            npRight.text = "-" + formatDuration((d - p).coerceAtLeast(0))
        }
    }

    private fun setMood(color: Int) {
        if (color == currentMood) return
        val from = currentMood
        currentMood = color
        ValueAnimator.ofObject(ArgbEvaluator(), from, color).apply {
            duration = 500
            addUpdateListener { nowBg.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    // ---------- Navigation ----------
    private fun current() = stack.last()

    private fun push(p: Page) {
        current().scroll = list.firstVisiblePosition
        hideKeyboard()
        stack.add(p)
        render()
        list.setSelection(0)
        slideList(true)
    }

    private fun goBack() {
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1)
        render()
        list.setSelection(current().scroll)
        slideList(false)
    }

    private fun resetToRoot() {
        stack.clear()
        stack.add(RootPage())
        playlists = null
        lists.clear()
        artistLists.clear()
        discover = null
        Spotify.clearCache()
        render()
    }

    private fun render() {
        val page = current()
        headerTitle.text = page.title
        back.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        search.visibility = if (page is RootPage) View.VISIBLE else View.GONE
        mini.visibility = if (Auth.isLoggedIn(this) || local) View.VISIBLE else View.GONE
        rows = rowsFor(page)
        adapter.notifyDataSetChanged()
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
    private fun countText(n: Int) = if (n == 1) "1 Titel" else "$n Titel"

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
        is ArtistListPage -> artistLists[page.key]?.let { list ->
            if (list.isEmpty()) listOf(Row("Noch keine Künstler", "Hör ein bisschen mehr Musik", chevron = false, dim = true))
            else list.mapIndexed { i, a ->
                Row(if (page.key.startsWith("top")) "${i + 1}. ${a.name}" else a.name, null, image = a.imageUrl, showImage = true) { push(ArtistPage(a)) }
            }
        } ?: run { loadArtistList(page.key, page.loader); listOf(loadingRow()) }
        is AlbumsPage -> albumRows(page.range)
        is ArtistPage -> artistRows(page.artist)
        is DiscoverPage -> discoverRows()
        is StatsPage -> statsRows()
        is QueuePage -> queueRows()
        is Mp3Page -> mp3Rows()
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
            out += Row("Lieblingskünstler", "Meistgehört und gefolgt", R.drawable.ic_mic, orange) {
                push(ArtistListPage("Lieblingskünstler", "fav_artists") { Spotify.favoriteArtists(it) })
            }
            out += Row("Meine Playlists", pls?.count { it.owned }?.let { "$it Playlists" }, R.drawable.ic_queue, green) {
                push(OwnedPage()); if (playlists == null) loadPlaylists()
            }
            out += Row("Gefolgte Playlists", pls?.count { !it.owned }?.let { "$it Playlists" }, R.drawable.ic_people, blue) {
                push(FollowedPage()); if (playlists == null) loadPlaylists()
            }
            out += Row("Empfehlungen", "Neue Songs und Künstler für dich", R.drawable.ic_sparkle, violet) { push(DiscoverPage()) }
            out += Row("Deine Hördaten", "Top 50 Songs, Künstler und Alben", R.drawable.ic_stats, orange) { push(StatsPage()) }
        }
        out += Row("MP3s", "Musikdateien auf diesem Handy – auch offline", R.drawable.ic_library, teal) { openMp3s() }
        out += Row("Offline-Musik von Spotify", "Deine Downloads ohne Internet", R.drawable.ic_cloud_off, grey) { showOfflineInfo() }
        out += Row("Einstellungen", null, R.drawable.ic_settings, grey) { push(SettingsPage()) }
        return out
    }

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
        return head + ts.map { t ->
            trackRow(t) { stopLocal(); remote.withDevice { d -> Spotify.play(this, d, pl.uri, t.position) }; openNow() }
        }
    }

    private fun trackListRows(page: ListPage): List<Row> {
        val ts = lists[page.key] ?: run { loadList(page.key, page.loader); return listOf(loadingRow()) }
        if (ts.isEmpty()) return listOf(Row("Keine Titel gefunden", chevron = false, dim = true))
        val numbered = page.key.startsWith("top")
        return playRows({ playTracks(ts, null, false) }, { playTracks(ts, null, true) }) +
            ts.mapIndexed { i, t -> trackRow(t, if (numbered) i + 1 else null) { playTracks(ts, t, false) } }
    }

    private fun artistRows(a: Artist): List<Row> {
        val out = ArrayList<Row>()
        out += playRows({ playContext(a.uri, false, null) }, { playContext(a.uri, true, null) })
        val key = "artist_${a.id}"
        val popular = lists[key] ?: run { loadList(key) { Spotify.artistSongs(it, a) }; null }
        out += header("Beliebte Songs")
        if (popular == null) out += loadingRow()
        else if (popular.isEmpty()) out += Row("Keine Songs gefunden", chevron = false, dim = true)
        else out += popular.map { t -> trackRow(t) { playTracks(popular, t, false) } }

        val seen = HashSet<String>()
        popular?.forEach { t -> t.uri?.let { seen += it } }
        val mine = lists.filterKeys { !it.startsWith("artist_") }.values.flatten()
            .filter { a.id in it.artistIds && it.uri != null && seen.add(it.uri) }
        if (mine.isNotEmpty()) {
            out += header("Deine Songs von ${a.name}")
            out += mine.map { t -> trackRow(t) { playTracks(mine, t, false) } }
        }
        return out
    }

    private fun albumRows(range: String): List<Row> {
        val ts = lists["top_$range"] ?: run { loadList("top_$range") { Spotify.topTracks(it, range) }; return listOf(loadingRow()) }
        val albums = ts.filter { it.albumId != null && it.albumUri != null }.groupBy { it.albumId!! }
            .values.sortedByDescending { it.size }
        if (albums.isEmpty()) return listOf(Row("Noch keine Alben", chevron = false, dim = true))
        return albums.mapIndexed { i, group ->
            val t = group.first()
            Row(
                "${i + 1}. ${t.albumName ?: "Album"}",
                listOfNotNull(t.artists.firstOrNull()?.name, if (group.size == 1) "1 Top-Song" else "${group.size} Top-Songs").joinToString(" · "),
                image = t.albumImage ?: t.imageUrl, showImage = true, chevron = false,
            ) { playContext(t.albumUri!!, false, null) }
        }
    }

    private fun statsRows(): List<Row> {
        val ranges = listOf("short_term" to "4 Wochen", "medium_term" to "6 Monate", "long_term" to "1 Jahr")
        val out = ArrayList<Row>()
        out += header("Top-Songs")
        ranges.forEach { (r, label) ->
            out += Row("Top 50 Songs", label, R.drawable.ic_trend, orange) {
                push(ListPage("Top 50 · $label", "top_$r") { Spotify.topTracks(it, r) })
            }
        }
        out += header("Top-Künstler")
        ranges.forEach { (r, label) ->
            out += Row("Top-Künstler", label, R.drawable.ic_mic, pink) {
                push(ArtistListPage("Top-Künstler · $label", "top_artists_$r") { Spotify.topArtists(it, r) })
            }
        }
        out += header("Top-Alben")
        ranges.forEach { (r, label) ->
            out += Row("Top-Alben", label, R.drawable.ic_album, violet) { push(AlbumsPage("Top-Alben · $label", r)) }
        }
        out += header("Verlauf")
        out += Row("Zuletzt gehört", "Deine letzten 50 Titel", R.drawable.ic_history, blue) {
            push(ListPage("Zuletzt gehört", "recent") { Spotify.recentTracks(it) })
        }
        return out
    }

    private fun discoverRows(): List<Row> {
        val d = discover ?: run { loadDiscover(); return listOf(Row("Suche neue Musik für dich …", chevron = false, dim = true)) }
        val out = ArrayList<Row>()
        if (d.unknownSongs.isNotEmpty()) {
            out += playRows({ playTracks(d.unknownSongs, null, false) }, { playTracks(d.unknownSongs, null, true) })
            out += header("Neue Songs von Künstlern, die du hörst")
            out += d.unknownSongs.map { t -> trackRow(t) { playTracks(d.unknownSongs, t, false) } }
        }
        if (d.newArtists.isNotEmpty()) {
            out += header("Neue Künstler für dich")
            out += d.newArtists.map { a -> Row(a.name, "Arbeitet mit Künstlern, die du magst", R.drawable.ic_mic, violet) { push(ArtistPage(a)) } }
        }
        out += header("Mehr")
        out += Row("Wiederentdecken", "Früher oft gehört, zuletzt kaum", R.drawable.ic_history, blue) {
            push(ListPage("Wiederentdecken", "rediscover") { Spotify.rediscover(it) })
        }
        out += Row("Neu laden", "Andere Vorschläge", R.drawable.ic_sparkle, grey, chevron = false) { discover = null; render() }
        return out
    }

    private fun queueRows(): List<Row> {
        val song = LocalPlayerService.current()
        if (song != null) {
            val q = LocalPlayerService.queue
            val idx = LocalPlayerService.index
            val out = ArrayList<Row>()
            out += header("Läuft gerade")
            out += Row(song.title, song.artist, R.drawable.ic_note_white, teal, chevron = false, uri = "local:${song.id}")
            val upcoming = q.drop(idx + 1)
            out += header(if (upcoming.isEmpty()) "Nichts mehr in der Warteschlange" else "Als Nächstes")
            out += upcoming.mapIndexed { i, s ->
                Row(s.title, s.artist, R.drawable.ic_note_white, teal, chevron = false) {
                    LocalPlayerService.play(this, q, idx + 1 + i); handler.postDelayed({ render(); renderPlayer() }, 300)
                }
            }
            return out
        }
        val out = ArrayList<Row>()
        np?.let { out += header("Läuft gerade"); out += Row(it.title, it.subtitle, image = it.imageUrl, showImage = true, chevron = false, uri = it.trackUri) }
        val q = spotifyQueue ?: return out + loadingRow()
        out += header(if (q.isEmpty()) "Nichts in der Warteschlange" else "Als Nächstes")
        out += q.map { t -> Row(t.name, t.subtitle, image = t.imageUrl, showImage = true, chevron = false) }
        out += Row("Tipp", "Halte einen Song gedrückt, um ihn zur Warteschlange hinzuzufügen", chevron = false, dim = true)
        return out
    }

    private fun trackRow(t: Track, number: Int? = null, play: () -> Unit) = Row(
        if (number != null) "$number. ${t.name}" else t.name, t.subtitle,
        image = t.imageUrl, showImage = true, chevron = false, uri = t.uri, dim = !t.playable,
        menu = { trackMenu(t) },
        action = if (t.playable) play else ({
            Toast.makeText(this, "Dieser Titel kann nicht abgespielt werden.", Toast.LENGTH_SHORT).show()
        }),
    )

    private fun localRow(songs: List<LocalSong>, s: LocalSong) = Row(
        s.title, s.artist + " · MP3", R.drawable.ic_note_white, teal, chevron = false, uri = "local:${s.id}",
        menu = { localMenu(s) },
    ) { playLocal(songs, songs.indexOf(s)) }

    // ---------- Kontextmenüs & Warteschlange ----------
    private fun trackMenu(t: Track) {
        val uri = t.uri ?: return
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "Zur Warteschlange hinzufügen" to {
            if (local) Toast.makeText(this, "Gerade läuft eine MP3 – Warteschlange gilt nur für Spotify.", Toast.LENGTH_LONG).show()
            else remote.withDevice { d ->
                Spotify.addToQueue(this, d, uri)
                ui { Toast.makeText(this, "„${t.name}“ kommt als Nächstes", Toast.LENGTH_SHORT).show(); spotifyQueue = null }
            }
        }
        t.artists.firstOrNull()?.let { a -> items += "Zum Künstler: ${a.name}" to { push(ArtistPage(a)) } }
        t.albumUri?.let { au -> items += "Album abspielen" + (t.albumName?.let { ": $it" } ?: "") to { playContext(au, false, null) } }
        AlertDialog.Builder(this).setTitle(t.name).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun localMenu(s: LocalSong) {
        if (!local) {
            Toast.makeText(this, "Starte zuerst eine MP3, dann kannst du weitere anreihen.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this).setTitle(s.title)
            .setItems(arrayOf("Als Nächstes spielen", "Zur Warteschlange hinzufügen")) { _, i ->
                if (i == 0) LocalPlayerService.addNext(s) else LocalPlayerService.addEnd(s)
                Toast.makeText(this, if (i == 0) "Kommt als Nächstes" else "Zur Warteschlange hinzugefügt", Toast.LENGTH_SHORT).show()
                if (current() is QueuePage) render()
            }.show()
    }

    private fun loadQueue() {
        if (local || !Auth.isLoggedIn(this)) return
        spotifyQueue = null
        bg {
            val q = try { Spotify.queue(this) } catch (_: Exception) { emptyList() }
            ui { spotifyQueue = q; if (current() is QueuePage) render() }
        }
    }

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

    private fun loadList(key: String, loader: (Context) -> List<Track>) {
        if (!loading.add(key)) return
        bg {
            try {
                val ts = loader(this)
                ui { loading.remove(key); lists[key] = ts; render() }
            } catch (e: Exception) {
                ui { loading.remove(key); lists[key] = emptyList(); render(); scopeOrError(e) }
            }
        }
    }

    private fun loadArtistList(key: String, loader: (Context) -> List<Artist>) {
        if (!loading.add("a:$key")) return
        bg {
            try {
                val a = loader(this)
                ui { loading.remove("a:$key"); artistLists[key] = a; render() }
            } catch (e: Exception) {
                ui { loading.remove("a:$key"); artistLists[key] = emptyList(); render(); scopeOrError(e) }
            }
        }
    }

    private fun loadDiscover() {
        if (!loading.add("discover")) return
        bg {
            try {
                val d = Spotify.discover(this)
                ui { loading.remove("discover"); discover = d; render() }
            } catch (e: Exception) {
                ui { loading.remove("discover"); discover = Discover(emptyList(), emptyList()); render(); scopeOrError(e) }
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
        if (!LocalMusic.hasPermission(this) || !loading.add("local")) return
        bg {
            val songs = try { LocalMusic.query(this) } catch (_: Exception) { emptyList() }
            ui { loading.remove("local"); localSongs = songs; render() }
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
        if (Auth.isLoggedIn(this) && np?.isPlaying == true && isOnline()) {
            bg { try { Spotify.pause(this, null) } catch (_: Exception) {} }
            np = np?.copy(isPlaying = false)
        }
        LocalPlayerService.play(this, songs, index.coerceAtLeast(0))
        handler.postDelayed({ renderPlayer(); render() }, 300)
        openNow()
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
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:collection")).setPackage(pkg))
        } catch (_: Exception) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) startActivity(launch)
            else Toast.makeText(this, "Spotify-App ist nicht installiert.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Abspielen ----------
    private fun playContext(uri: String, shuffle: Boolean, position: Int?) {
        stopLocal()
        remote.withDevice { d ->
            try { Spotify.shuffle(this, shuffle, d) } catch (e: ApiException) { if (e.status == 404) throw e }
            Spotify.play(this, d, uri, position)
        }
        openNow()
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
        openNow()
    }

    private fun prev() {
        if (local) { LocalPlayerService.command(this, LocalPlayerService.ACTION_PREV); handler.postDelayed({ renderPlayer() }, 250); return }
        if (Auth.isLoggedIn(this)) remote.withDevice { Spotify.previous(this, it) }
    }

    private fun next() {
        if (local) { LocalPlayerService.command(this, LocalPlayerService.ACTION_NEXT); handler.postDelayed({ renderPlayer() }, 250); return }
        if (Auth.isLoggedIn(this)) remote.withDevice { Spotify.next(this, it) }
    }

    private fun playPause() {
        if (local) { LocalPlayerService.command(this, LocalPlayerService.ACTION_TOGGLE); handler.postDelayed({ renderPlayer(); animateProgress() }, 150); return }
        if (!Auth.isLoggedIn(this)) return
        val playing = np?.isPlaying == true
        np = np?.copy(isPlaying = !playing, progressMs = currentProgress())
        npAt = System.currentTimeMillis()
        renderPlayer()
        animateProgress()
        remote.withDevice { d -> if (playing) Spotify.pause(this, d) else Spotify.resume(this, d) }
    }

    private fun seekTo(ms: Long) {
        if (local) { LocalPlayerService.seekTo(ms); animateProgress(); return }
        np = np?.copy(progressMs = ms)
        npAt = System.currentTimeMillis()
        animateProgress()
        bg { try { Spotify.seek(this, ms) } catch (e: Exception) { ui { remote.showError(e) } } }
    }

    private fun toggleShuffle() {
        if (local) {
            val q = LocalPlayerService.queue
            val idx = LocalPlayerService.index
            if (q.size > idx + 1) {
                LocalPlayerService.queue = q.take(idx + 1) + q.drop(idx + 1).shuffled()
                Toast.makeText(this, "Rest der Warteschlange gemischt", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val on = !(np?.shuffle ?: false)
        np = np?.copy(shuffle = on)
        renderControls()
        remote.withDevice { d -> Spotify.shuffle(this, on, d) }
    }

    private fun cycleRepeat() {
        if (local) { Toast.makeText(this, "Wiederholen gibt es nur für Spotify", Toast.LENGTH_SHORT).show(); return }
        val next = when (np?.repeat) { "off", null -> "context"; "context" -> "track"; else -> "off" }
        np = np?.copy(repeat = next)
        renderControls()
        remote.withDevice { d -> Spotify.repeat(this, next, d) }
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
                animateProgress()
                if (trackChanged) {
                    adapter.notifyDataSetChanged()
                    if (current() is QueuePage) loadQueue()
                }
            }
        }
    }

    private fun currentProgress(): Long {
        val n = np ?: return 0
        val p = if (n.isPlaying) n.progressMs + (System.currentTimeMillis() - npAt) else n.progressMs
        return if (n.durationMs > 0) p.coerceAtMost(n.durationMs) else p
    }

    private fun positionNow(): Long = if (local) LocalPlayerService.positionMs() else currentProgress()
    private fun durationNow(): Long = if (local) LocalPlayerService.durationMs() else (np?.durationMs ?: 0L)
    private fun isPlayingNow(): Boolean = if (local) LocalPlayerService.isPlaying else np?.isPlaying == true

    private fun renderPlayer() {
        val song = LocalPlayerService.current()
        val playing = isPlayingNow()
        miniPlay.setImageResource(if (playing) R.drawable.ic_pause_white else R.drawable.ic_play_white)
        npPlay.setImageResource(if (playing) R.drawable.ic_pause_dark else R.drawable.ic_play_dark)
        when {
            song != null -> { miniTitle.text = song.title; miniSub.text = song.artist; Images.load(miniCover, null) }
            np == null -> { miniTitle.text = "Nichts wird abgespielt"; miniSub.text = "Wähle eine Playlist oder einen Song"; Images.load(miniCover, null) }
            else -> { miniTitle.text = np!!.title; miniSub.text = np!!.subtitle; Images.load(miniCover, np!!.imageUrl) }
        }
        if (local && current() !is RootPage) mini.visibility = View.VISIBLE
        if (nowOpen) renderNow()
    }

    private fun renderNow() {
        val song = LocalPlayerService.current()
        val n = np
        val coverKey: String?
        when {
            song != null -> {
                npTitle.text = song.title
                npArtist.text = song.artist
                nowSource.text = "MP3 auf diesem Handy"
                coverKey = "local"
                if (lastCoverKey != coverKey) { Images.load(npCover, null); setMood(0xFF123A3A.toInt()) }
                npVolume.isEnabled = false
            }
            n == null -> {
                npTitle.text = "Nichts wird abgespielt"
                npArtist.text = "Wähle eine Playlist oder einen Song"
                nowSource.text = "Jetzt läuft"
                coverKey = null
                Images.load(npCover, null)
                setMood(0xFF1B1B1E.toInt())
                npVolume.isEnabled = false
            }
            else -> {
                npTitle.text = n.title
                npArtist.text = n.subtitle
                nowSource.text = n.deviceName?.let { "Spielt auf $it" } ?: "Jetzt läuft"
                coverKey = n.bigImageUrl ?: n.imageUrl
                if (lastCoverKey != coverKey) Images.load(npCover, coverKey) { bmp -> setMood(Images.moodColor(bmp)) }
                if (!userDraggingVolume) n.volume?.let { npVolume.progress = it }
                npVolume.isEnabled = n.volume != null
            }
        }
        lastCoverKey = coverKey
        renderControls()
        renderInfoLine()
    }

    private fun renderControls() {
        val accent = getColor(R.color.accent)
        val n = np
        val shuffleOn = !local && n?.shuffle == true
        npShuffle.imageTintList = ColorStateList.valueOf(if (shuffleOn) accent else 0xB3FFFFFF.toInt())
        val repeat = if (local) "off" else n?.repeat ?: "off"
        npRepeat.setImageResource(if (repeat == "track") R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        npRepeat.imageTintList = ColorStateList.valueOf(if (repeat != "off") accent else 0xB3FFFFFF.toInt())
    }

    /** Zeile unten im Player: Zeitlimit, Ohrschutz. */
    private fun renderInfoLine() {
        val parts = ArrayList<String>()
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
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false).also {
                it.findViewById<View>(R.id.icon).roundCorners(10f)
            }
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
                v.minimumHeight = (40 * dp).toInt()
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
                    icon.alpha = 1f
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
