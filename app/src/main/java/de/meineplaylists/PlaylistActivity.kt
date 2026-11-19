package de.meineplaylists

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class PlaylistActivity : Activity() {
    private lateinit var playerBar: PlayerBar
    private lateinit var progress: ProgressBar
    private lateinit var countView: TextView
    private val adapter = TrackAdapter()
    private lateinit var playlistId: String
    private lateinit var playlistUri: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)
        playlistId = intent.getStringExtra("id") ?: return finish()
        playlistUri = intent.getStringExtra("uri") ?: return finish()
        val name = intent.getStringExtra("name") ?: ""

        findViewById<TextView>(R.id.bar_title).text = name
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        progress = findViewById(R.id.progress)

        val list = findViewById<ListView>(R.id.list)
        val header = LayoutInflater.from(this).inflate(R.layout.header_playlist, list, false)
        header.findViewById<TextView>(R.id.h_name).text = name
        countView = header.findViewById(R.id.h_count)
        countView.text = "${intent.getIntExtra("total", 0)} Titel"
        Images.load(header.findViewById(R.id.h_cover), intent.getStringExtra("image"))
        list.addHeaderView(header, null, false)
        list.adapter = adapter

        playerBar = PlayerBar(this) { np -> adapter.currentUri = np?.trackUri }

        header.findViewById<View>(R.id.h_play).setOnClickListener {
            playerBar.withDevice { d ->
                try { Spotify.shuffle(this, false, d) } catch (e: ApiException) { if (e.status == 404) throw e }
                Spotify.play(this, d, playlistUri, 0)
            }
        }
        header.findViewById<View>(R.id.h_shuffle).setOnClickListener {
            playerBar.withDevice { d ->
                Spotify.shuffle(this, true, d)
                Spotify.play(this, d, playlistUri, null)
            }
        }
        list.setOnItemClickListener { parent, _, pos, _ ->
            val t = parent.getItemAtPosition(pos) as? Track ?: return@setOnItemClickListener
            if (!t.playable) {
                Toast.makeText(this, "Dieser Titel kann nicht abgespielt werden (lokal/nicht verfügbar).", Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            }
            playerBar.withDevice { d -> Spotify.play(this, d, playlistUri, t.position) }
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (::playerBar.isInitialized) playerBar.start()
    }

    override fun onPause() {
        super.onPause()
        if (::playerBar.isInitialized) playerBar.stop()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        bg {
            try {
                val tracks = Spotify.tracks(this, playlistId)
                ui {
                    progress.visibility = View.GONE
                    adapter.items = tracks
                    countView.text = if (tracks.size == 1) "1 Titel" else "${tracks.size} Titel"
                }
            } catch (e: Exception) {
                ui {
                    progress.visibility = View.GONE
                    Toast.makeText(this, errorText(e), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private inner class TrackAdapter : BaseAdapter() {
        var items: List<Track> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }
        var currentUri: String? = null
            set(v) { if (field != v) { field = v; notifyDataSetChanged() } }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
            val t = items[position]
            val nameView = v.findViewById<TextView>(R.id.name)
            nameView.text = t.name
            nameView.setTextColor(
                getColor(
                    when {
                        t.uri != null && t.uri == currentUri -> R.color.accent
                        t.playable -> R.color.text
                        else -> R.color.text2
                    }
                )
            )
            v.findViewById<TextView>(R.id.subtitle).text = t.subtitle
            v.findViewById<TextView>(R.id.duration).text = formatDuration(t.durationMs)
            Images.load(v.findViewById<ImageView>(R.id.cover), t.imageUrl)
            v.alpha = if (t.playable) 1f else 0.5f
            return v
        }
    }
}
