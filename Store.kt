package com.reelbrain

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Local-only storage. Nothing here ever leaves the device. */
object Store {
    private const val FILE = "reelbrain"
    private const val IDLE_MS = 10 * 60 * 1000L // a new session starts after 10 idle minutes

    private val recent = mutableListOf<Long>()

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun dayKey(ms: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms))

    private fun total(s: SharedPreferences, dk: String): Int =
        s.getInt("${dk}_ig", 0) + s.getInt("${dk}_yt", 0)

    /** Records one reel. [app] is "ig" or "yt". Returns today's new total. */
    @Synchronized
    fun add(c: Context, app: String): Int {
        val s = sp(c)
        val now = System.currentTimeMillis()
        val last = s.getLong("last", 0L)
        var session = s.getInt("session", 0)
        var start = s.getLong("start", now)
        if (session == 0 || now - last > IDLE_MS) {
            session = 0
            start = now
        }
        session++
        val dk = dayKey(now)
        val key = "${dk}_$app"
        s.edit()
            .putInt(key, s.getInt(key, 0) + 1)
            .putInt("session", session)
            .putLong("start", start)
            .putLong("last", now)
            .apply()
        recent.add(now)
        return total(s, dk)
    }

    @Synchronized
    fun snapshot(c: Context, serviceOn: Boolean): String {
        val s = sp(c)
        val now = System.currentTimeMillis()
        val dk = dayKey(now)
        recent.removeAll { now - it > 60_000 }
        val idle = now - s.getLong("last", 0L) > IDLE_MS

        val o = JSONObject()
        o.put("ig", s.getInt("${dk}_ig", 0))
        o.put("yt", s.getInt("${dk}_yt", 0))
        o.put("total", total(s, dk))
        o.put("session", if (idle) 0 else s.getInt("session", 0))
        o.put("sessionMs", if (idle) 0L else now - s.getLong("start", now))
        o.put("rate", recent.size)
        o.put("limit", limit(c))
        o.put("compat", compat(c))
        o.put("haptic", haptic(c))
        o.put("overlay", overlay(c))
        o.put("service", serviceOn)

        val fmt = SimpleDateFormat("EEE", Locale.getDefault())
        val week = JSONArray()
        for (i in 6 downTo 0) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            week.put(
                JSONObject()
                    .put("d", fmt.format(cal.time))
                    .put("n", total(s, dayKey(cal.timeInMillis)))
            )
        }
        o.put("week", week)
        return o.toString()
    }

    @Synchronized
    fun resetToday(c: Context) {
        val dk = dayKey(System.currentTimeMillis())
        sp(c).edit().remove("${dk}_ig").remove("${dk}_yt").putInt("session", 0).apply()
        recent.clear()
    }

    fun limit(c: Context): Int = sp(c).getInt("limit", 100)
    fun setLimit(c: Context, n: Int) { sp(c).edit().putInt("limit", n.coerceIn(10, 1000)).apply() }

    fun compat(c: Context): Boolean = sp(c).getBoolean("compat", false)
    fun setCompat(c: Context, b: Boolean) { sp(c).edit().putBoolean("compat", b).apply() }

    /** Floating window: 0 = off, 1 = compact, 2 = full (with brain). */
    fun overlay(c: Context): Int = sp(c).getInt("overlayMode", 2)
    fun setOverlay(c: Context, n: Int) { sp(c).edit().putInt("overlayMode", n.coerceIn(0, 2)).apply() }

    fun haptic(c: Context): Boolean = sp(c).getBoolean("haptic", true)
    fun setHaptic(c: Context, b: Boolean) { sp(c).edit().putBoolean("haptic", b).apply() }
}
