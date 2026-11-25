package de.meineplaylists

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore

data class LocalSong(val id: Long, val title: String, val artist: String, val durationMs: Long, val uri: Uri)

/** Liest Musikdateien (MP3 usw.) vom Handy. */
object LocalMusic {
    val permission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun query(ctx: Context): List<LocalSong> {
        val out = ArrayList<LocalSong>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION,
        )
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val artist = c.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unbekannter Künstler"
                out += LocalSong(
                    id, c.getString(titleCol) ?: "Unbekannt", artist, c.getLong(durCol),
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                )
            }
        }
        return out
    }
}

/**
 * Spielt MP3s selbst ab – läuft als Vordergrund-Dienst weiter, wenn die App im Hintergrund ist.
 * Steuerung über die Benachrichtigung oder die App.
 */
class LocalPlayerService : Service() {

    companion object {
        private const val CHANNEL = "player"
        private const val NOTIF_ID = 7
        const val ACTION_PLAY = "play_index"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_NEXT = "next"
        const val ACTION_PREV = "prev"
        const val ACTION_PAUSE = "pause"
        const val ACTION_STOP = "stop"

        @Volatile var queue: List<LocalSong> = emptyList()
        @Volatile var index = 0
        private var player: MediaPlayer? = null

        val isActive get() = player != null
        val isPlaying get() = try { player?.isPlaying == true } catch (_: Exception) { false }
        fun current(): LocalSong? = if (isActive) queue.getOrNull(index) else null
        fun positionMs(): Long = try { player?.currentPosition?.toLong() ?: 0 } catch (_: Exception) { 0 }
        fun durationMs(): Long = try { player?.duration?.toLong() ?: 0 } catch (_: Exception) { 0 }

        fun play(ctx: Context, songs: List<LocalSong>, start: Int) {
            queue = songs
            index = start.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
            val i = Intent(ctx, LocalPlayerService::class.java).setAction(ACTION_PLAY)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun command(ctx: Context, action: String) {
            if (!isActive) return
            try { ctx.startService(Intent(ctx, LocalPlayerService::class.java).setAction(action)) } catch (_: Exception) {}
        }
    }

    private lateinit var audio: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Wiedergabe", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> playIndex(index)
            ACTION_TOGGLE -> if (isPlaying) pause() else resume()
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> if (index < queue.size - 1) playIndex(index + 1) else pause()
            ACTION_PREV -> if (positionMs() > 3000 || index == 0) seekStart() else playIndex(index - 1)
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }

    private fun playIndex(i: Int) {
        val song = queue.getOrNull(i) ?: run { stopAll(); return }
        index = i
        player?.release()
        player = null
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                setDataSource(this@LocalPlayerService, song.uri)
                setOnCompletionListener {
                    if (index < queue.size - 1) playIndex(index + 1) else { pause(); seekStart() }
                }
                prepare()
            }
            requestFocus()
            player?.start()
        } catch (_: Exception) {
            // Datei kaputt/nicht lesbar → nächster Titel
            if (i < queue.size - 1) { playIndex(i + 1); return } else { stopAll(); return }
        }
        Safety.enforceLocal(this)
        showNotification()
    }

    private fun pause() {
        try { player?.pause() } catch (_: Exception) {}
        showNotification()
    }

    private fun resume() {
        requestFocus()
        Safety.enforceLocal(this)
        try { player?.start() } catch (_: Exception) {}
        showNotification()
    }

    private fun seekStart() {
        try { player?.seekTo(0) } catch (_: Exception) {}
        showNotification()
    }

    private fun stopAll() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
        }
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build().also { focusRequest = it }
            audio.requestAudioFocus(req)
        }
    }

    private fun pending(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, LocalPlayerService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun showNotification() {
        val song = current() ?: return
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = builder
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setContentIntent(open)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_prev), "Zurück", pending(ACTION_PREV, 1)).build())
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, if (isPlaying) R.drawable.ic_pause_dark else R.drawable.ic_play_dark),
                    if (isPlaying) "Pause" else "Play", pending(ACTION_TOGGLE, 2),
                ).build()
            )
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_next), "Weiter", pending(ACTION_NEXT, 3)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_back), "Beenden", pending(ACTION_STOP, 4)).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        super.onDestroy()
    }
}
