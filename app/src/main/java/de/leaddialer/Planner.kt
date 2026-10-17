package de.leaddialer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Planned calls. One system alarm clock (AlarmManager.setAlarmClock, exempt from Doze) is kept
 * set for the earliest planned call. When it fires, a full-screen notification opens
 * CallAlarmActivity, which counts down and dials; Android only lets a call start from the
 * foreground, which that activity is.
 */
object CallScheduler {
    const val CHANNEL = "scheduled_calls"
    const val EXTRA_IDS = "ids"
    const val EXTRA_AT = "at"
    const val EXTRA_MISSED = "missed"
    const val ACTION_FIRE = "de.leaddialer.FIRE_PLANNED_CALL"
    const val NOTIFY_DUE = 4711
    const val NOTIFY_MISSED = 4712
    /** An alarm delivered later than this (phone was off) is offered as missed, not dialed. */
    private const val LATE_MS = 15 * 60_000L

    private fun zone() = ZoneId.systemDefault()
    fun toMillis(t: LocalDateTime) = t.atZone(zone()).toInstant().toEpochMilli()
    fun toLocal(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone())

    /** The earliest planned time after [after] and every schedule due at that same time. */
    fun upcoming(db: LeadDb, after: LocalDateTime): Pair<LocalDateTime, List<Schedule>>? {
        val next = db.schedules().filter { it.enabled }.mapNotNull { s -> ScheduleMath.next(s, after)?.let { it to s } }
        val first = next.minByOrNull { it.first }?.first ?: return null
        return first to next.filter { it.first == first }.map { it.second }
    }

    fun canScheduleExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Sets the one alarm for the next planned call, or clears it when nothing is planned. */
    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val up = upcoming(LeadDb(ctx), LocalDateTime.now())
        val intent = Intent(ctx, PlannedCallReceiver::class.java).setAction(ACTION_FIRE)
        if (up != null) {
            intent.putExtra(EXTRA_IDS, up.second.map { it.id }.toLongArray())
            intent.putExtra(EXTRA_AT, toMillis(up.first))
        }
        val fire = PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        Prefs.setPlanLastCheck(ctx, System.currentTimeMillis())
        if (up == null) {
            am.cancel(fire)
            return
        }
        val at = toMillis(up.first)
        if (canScheduleExact(ctx)) {
            val show = PendingIntent.getActivity(ctx, 1, Intent(ctx, PlannerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        }
    }

    /** Alarm went off: mark the due calls as handled and bring up the call screen. */
    fun onAlarm(ctx: Context, ids: LongArray, at: Long) {
        val db = LeadDb(ctx)
        val due = ids.toList().mapNotNull { db.schedule(it) }.filter { it.enabled && it.lastFired < at }
        due.forEach { s ->
            s.lastFired = at
            if (s.days == 0) s.enabled = false
            db.saveSchedule(s)
        }
        if (due.isNotEmpty()) present(ctx, due.map { it.id }, missed = System.currentTimeMillis() - at > LATE_MS)
        reschedule(ctx)
    }

    /** After a reboot or clock change: calls whose time passed while the phone was off are offered as missed. */
    fun catchUp(ctx: Context) {
        val db = LeadDb(ctx)
        val since = Prefs.planLastCheck(ctx).takeIf { it > 0 } ?: return
        val now = System.currentTimeMillis()
        val missed = db.schedules().filter { it.enabled }.mapNotNull { s ->
            val occ = ScheduleMath.next(s, toLocal(since))?.let { toMillis(it) } ?: return@mapNotNull null
            if (occ > now || s.lastFired >= occ) return@mapNotNull null
            s.lastFired = occ
            if (s.days == 0) s.enabled = false
            db.saveSchedule(s)
            s.id
        }
        if (missed.isNotEmpty()) present(ctx, missed, missed = true)
    }

    /**
     * Shows the call screen. With "display over other apps" allowed the screen opens directly,
     * also while the phone is in use; otherwise the full-screen notification opens it when the
     * phone is locked, and shows as a banner to tap when it is unlocked.
     */
    private fun present(ctx: Context, ids: List<Long>, missed: Boolean) {
        ensureChannel(ctx)
        val open = Intent(ctx, CallAlarmActivity::class.java)
            .putExtra(EXTRA_IDS, ids.toLongArray())
            .putExtra(EXTRA_MISSED, missed)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, if (missed) 3 else 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val db = LeadDb(ctx)
        val names = ids.mapNotNull { db.schedule(it) }.joinToString(", ") { it.name.ifBlank { it.phone } }
        val builder = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (missed) "Verpasster geplanter Anruf" else "Geplanter Anruf")
            .setContentText(if (missed) "$names · tippen zum Nachholen" else "$names · wählt in ${CallAlarmActivity.COUNTDOWN_S} s")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (missed) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pi)
            .setAutoCancel(true)
        if (!missed) builder.setFullScreenIntent(pi, true).setTimeoutAfter(3 * 60_000L)
        try {
            NotificationManagerCompat.from(ctx).notify(if (missed) NOTIFY_MISSED else NOTIFY_DUE, builder.build())
        } catch (e: SecurityException) {
            // Notifications not allowed; the overlay path below may still work.
        }
        if (!missed && Settings.canDrawOverlays(ctx)) {
            runCatching { ctx.startActivity(open) }
        }
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Geplante Anrufe", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Kündigt geplante Anrufe an und startet sie"
        ch.setBypassDnd(true)
        nm.createNotificationChannel(ch)
    }
}

/** The plan file the user linked once (CSV or Excel in Google Drive or on the phone). */
object PlanFile {
    fun link(ctx: Context, uri: Uri) {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Prefs.setPlanUri(ctx, uri.toString())
    }

    fun unlink(ctx: Context) {
        Prefs.planUri(ctx)?.let { u ->
            runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        Prefs.setPlanUri(ctx, null)
        Prefs.setPlanLoadedAt(ctx, 0)
        LeadDb(ctx).replaceFileSchedules(emptyList())
    }

    /** Re-reads the linked file. Returns (count, row problems), or null when no file is linked. Throws on read errors. */
    fun sync(ctx: Context): Pair<Int, List<String>>? {
        val u = Prefs.planUri(ctx) ?: return null
        val bytes = ctx.contentResolver.openInputStream(Uri.parse(u))!!.use { it.readBytes() }
        val rows = if (XlsxReader.isXlsx(bytes)) XlsxReader.sheets(bytes).firstOrNull().orEmpty() else CsvIO.rowsOf(CsvIO.decode(bytes))
        val (plan, errors) = ScheduleMath.parsePlan(rows, LocalDate.now())
        LeadDb(ctx).replaceFileSchedules(plan)
        Prefs.setPlanLoadedAt(ctx, System.currentTimeMillis())
        return plan.size to errors
    }
}

class PlannedCallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != CallScheduler.ACTION_FIRE) return
        val ids = intent.getLongArrayExtra(CallScheduler.EXTRA_IDS) ?: return
        CallScheduler.onAlarm(ctx, ids, intent.getLongExtra(CallScheduler.EXTRA_AT, System.currentTimeMillis()))
        // Pick up changes to the plan file without delaying the call screen.
        val pending = goAsync()
        Thread {
            try {
                runCatching { PlanFile.sync(ctx) }
                CallScheduler.reschedule(ctx)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Alarms are lost on reboot and shift with clock changes, so they are set again here. */
class PlannerBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        CallScheduler.catchUp(ctx)
        CallScheduler.reschedule(ctx)
    }
}
