package de.meineplaylists

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var list: ListView
    private lateinit var progress: ProgressBar
    private lateinit var status: View
    private lateinit var statusText: TextView
    private lateinit var statusButton: Button
    private lateinit var search: EditText
    private lateinit var playerBar: PlayerBar

    private val adapter = PlaylistAdapter()
    private var all: List<Playlist> = emptyList()
    private var loaded = false
    private var loading = false
    private var autoPromptDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        statusText = findViewById(R.id.status_text)
        statusButton = findViewById(R.id.status_button)
        search = findViewById(R.id.search)
        playerBar = PlayerBar(this)

        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val p = adapter.getItem(pos)
            startActivity(
                Intent(this, PlaylistActivity::class.java)
                    .putExtra("id", p.id).putExtra("name", p.name).putExtra("uri", p.uri)
                    .putExtra("image", p.imageUrl).putExtra("total", p.total)
            )
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        findViewById<View>(R.id.btn_refresh).setOnClickListener { if (Auth.isLoggedIn(this)) load() else ensureReady() }
        findViewById<View>(R.id.btn_logout).setOnClickListener { confirmLogout() }
    }

    override fun onResume() {
        super.onResume()
        ensureReady()
    }

    override fun onPause() {
        super.onPause()
        playerBar.stop()
    }

    private fun ensureReady() {
        when {
            Auth.clientId(this) == null -> {
                showStatus("Einmalige Einrichtung: Client-ID deiner Spotify-Developer-App eintragen.", "Einrichten") { askClientId() }
                if (!autoPromptDone) { autoPromptDone = true; askClientId() }
            }
            !Auth.isLoggedIn(this) -> {
                loaded = false
                adapter.items = emptyList()
                showStatus("Nicht angemeldet.", "Mit Spotify anmelden") { openLogin() }
                if (!autoPromptDone) { autoPromptDone = true; openLogin() }
            }
            else -> {
                if (!loaded) load()
                playerBar.start()
            }
        }
    }

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
                    autoPromptDone = false
                }
                ensureReady()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun openLogin() {
        startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN && resultCode == RESULT_OK) {
            Spotify.clearCache()
            loaded = false
        }
    }

    private fun load() {
        if (loading) return
        loading = true
        hideStatus()
        if (all.isEmpty()) progress.visibility = View.VISIBLE
        bg {
            try {
                val pls = Spotify.myPlaylists(this)
                ui {
                    loading = false
                    loaded = true
                    progress.visibility = View.GONE
                    all = pls
                    applyFilter()
                    if (pls.isEmpty()) showStatus("Du hast noch keine eigenen Playlists.", null, null)
                }
            } catch (e: NotLoggedInException) {
                ui { loading = false; progress.visibility = View.GONE; autoPromptDone = false; ensureReady() }
            } catch (e: Exception) {
                ui {
                    loading = false
                    progress.visibility = View.GONE
                    if (all.isEmpty()) showStatus(errorText(e), "Erneut versuchen") { load() }
                    else android.widget.Toast.makeText(this, errorText(e), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim()
        adapter.items = if (q.isEmpty()) all else all.filter { it.name.contains(q, ignoreCase = true) }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Abmelden?")
            .setPositiveButton("Abmelden") { _, _ ->
                Auth.logout(this)
                Spotify.clearCache()
                CookieManager.getInstance().removeAllCookies(null)
                all = emptyList()
                adapter.items = emptyList()
                loaded = false
                autoPromptDone = true // nicht sofort wieder Login öffnen
                playerBar.stop()
                ensureReady()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showStatus(text: String, button: String?, onClick: (() -> Unit)?) {
        status.visibility = View.VISIBLE
        statusText.text = text
        if (button == null) statusButton.visibility = View.GONE
        else {
            statusButton.visibility = View.VISIBLE
            statusButton.text = button
            statusButton.setOnClickListener { onClick?.invoke() }
        }
    }

    private fun hideStatus() { status.visibility = View.GONE }

    private class PlaylistAdapter : BaseAdapter() {
        var items: List<Playlist> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
            val p = items[position]
            v.findViewById<TextView>(R.id.name).text = p.name
            v.findViewById<TextView>(R.id.count).text = if (p.total == 1) "1 Titel" else "${p.total} Titel"
            Images.load(v.findViewById<ImageView>(R.id.cover), p.imageUrl)
            return v
        }
    }

    companion object {
        private const val REQ_LOGIN = 1
    }
}
