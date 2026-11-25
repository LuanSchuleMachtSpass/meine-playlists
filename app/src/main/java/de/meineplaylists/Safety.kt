package de.meineplaylists

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build

/** Einstellungen für Zeitlimit und Ohrschutz. */
object Safety {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("safety", Context.MODE_PRIVATE)

    // ---------- Ohrschutz ----------
    /** Maximale Lautstärke in Prozent, 0 = aus. */
    fun earCap(ctx: Context): Int = prefs(ctx).getInt("ear_cap", 0)
    fun setEarCap(ctx: Context, percent: Int) = prefs(ctx).edit().putInt("ear_cap", percent).apply()

    /** Stufe der Handy-Medienlautstärke, die dem Ohrschutz-Limit entspricht. */
    fun capIndex(am: AudioManager, cap: Int): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return (max * cap / 100).coerceAtLeast(1)
    }

    /** Senkt die Handy-Medienlautstärke auf das Limit. true, wenn gesenkt wurde. */
    fun enforceLocal(ctx: Context): Boolean {
        val cap = earCap(ctx)
        if (cap <= 0) return false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val limit = capIndex(am, cap)
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > limit) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, limit, 0)
            return true
        }
        return false
    }

    // ---------- Zeitlimit ----------
    /** Zeitpunkt (ms), an dem die Musik stoppt; 0 = kein Zeitlimit. */
    fun sleepAt(ctx: Context): Long = prefs(ctx).getLong("sleep_at", 0).let {
        if (it != 0L && it < System.currentTimeMillis() - 60_000) 0L else it
    }

    fun startTimer(ctx: Context, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        prefs(ctx).edit().putLong("sleep_at", at).apply()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancelTimer(ctx: Context) {
        prefs(ctx).edit().putLong("sleep_at", 0).apply()
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx))
    }

    internal fun clearTimer(ctx: Context) = prefs(ctx).edit().putLong("sleep_at", 0).apply()

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx.applicationContext, 0, Intent(ctx.applicationContext, SleepReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Wird vom Zeitlimit ausgelöst und pausiert die Wiedergabe – auch wenn die App geschlossen ist. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val result = goAsync()
        LocalPlayerService.command(app, LocalPlayerService.ACTION_PAUSE)
        Thread {
            try {
                Spotify.pause(app, null)
            } catch (_: Exception) {
            } finally {
                Safety.clearTimer(app)
                result.finish()
            }
        }.start()
    }
}
