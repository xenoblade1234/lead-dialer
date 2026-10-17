package de.leaddialer

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

/**
 * The "Geplante Anrufe" tab: one-time phone settings, the linked plan file and the list of
 * planned calls. Lives inside MainActivity, which owns the activity-result launchers.
 */
class PlannerPanel(
    private val act: AppCompatActivity,
    private val pickFile: () -> Unit,
    private val askPermission: (String) -> Unit,
) {
    private val dp = act.resources.displayMetrics.density
    private val content = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (16 * dp).toInt()
        setPadding(pad, pad, pad, pad)
    }
    val view: View = ScrollView(act).apply { addView(content) }

    init {
        CallScheduler.ensureChannel(act)
    }

    fun syncNow(quiet: Boolean = false) {
        Thread {
            val result = runCatching { PlanFile.sync(act) }
            CallScheduler.reschedule(act)
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                render()
                result.onSuccess { r ->
                    if (!quiet && r != null) {
                        val (count, errors) = r
                        val msg = "$count Anrufe aus der Datei geladen." +
                            if (errors.isNotEmpty()) "\n\nNicht übernommen:\n" + errors.joinToString("\n") else ""
                        MaterialAlertDialogBuilder(act).setTitle("Anrufplan").setMessage(msg).setPositiveButton("OK", null).show()
                    } else if (quiet && r != null && r.second.isNotEmpty()) {
                        toast("${r.second.size} Zeilen im Anrufplan nicht lesbar. Tippe auf \"Jetzt laden\" für Details.")
                    }
                }.onFailure { if (!quiet) toast("Datei konnte nicht gelesen werden: ${it.message}") }
            }
        }.start()
    }

    fun render() {
        content.removeAllViews()
        renderChecks()
        val db = LeadDb(act)
        val up = CallScheduler.upcoming(db, LocalDateTime.now())
        content.addView(heading("Nächster Anruf"))
        content.addView(body(up?.let { (t, list) -> ScheduleMath.whenLabel(t) + " · " + list.joinToString(", ") { it.name.ifBlank { it.phone } } } ?: "Nichts geplant"))

        content.addView(heading("Alle geplanten Anrufe"))
        content.addView(button("+ Anruf planen", filled = true) { edit(null) })
        val all = db.schedules()
        if (all.isEmpty()) content.addView(body("Noch keine. Plane einen Anruf oder verknüpfe deinen Anrufplan."))
        all.forEach { content.addView(row(it)) }
        renderFile()
    }

    private data class Check(val ok: Boolean, val label: String, val fix: () -> Unit)

    private fun checks(): List<Check> {
        val pkg = act.packageName
        val list = ArrayList<Check>()
        list.add(Check(granted(Manifest.permission.CALL_PHONE), "Anrufe erlauben") { askPermission(Manifest.permission.CALL_PHONE) })
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Check(granted(Manifest.permission.POST_NOTIFICATIONS), "Benachrichtigungen erlauben") {
                askPermission(Manifest.permission.POST_NOTIFICATIONS)
            })
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = act.getSystemService(NotificationManager::class.java)
            list.add(Check(nm.canUseFullScreenIntent(), "Vollbild-Benachrichtigungen erlauben (damit der Anruf-Bildschirm über der Sperre erscheint)") {
                open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg")))
            })
        }
        list.add(Check(Settings.canDrawOverlays(act), "\"Über anderen Apps einblenden\" erlauben (damit auch bei entsperrtem Handy automatisch gewählt wird)") {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg")))
        })
        val pm = act.getSystemService(PowerManager::class.java)
        list.add(Check(pm.isIgnoringBatteryOptimizations(pkg), "Akku-Optimierung ausschalten (sonst kann der Wecker ausfallen)") {
            @SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))
            open(i)
        })
        list.add(Check(CallScheduler.canScheduleExact(act), "Wecker und Erinnerungen erlauben") {
            if (Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg")))
        })
        return list
    }

    private fun renderChecks() {
        val missing = checks().filter { !it.ok }
        if (missing.isEmpty()) return
        content.addView(heading("Einrichtung"))
        content.addView(body("Einmalig erlauben, damit geplante Anrufe zuverlässig klappen:"))
        missing.forEach { c -> content.addView(button(c.label, filled = false) { c.fix() }) }
        content.addView(
            body(
                "Lässt sich \"Über anderen Apps einblenden\" nicht einschalten: App-Info des Lead Dialer öffnen, " +
                    "oben rechts ⋮ → \"Eingeschränkte Einstellungen zulassen\", dann nochmal versuchen."
            ).apply { alpha = 0.7f; textSize = 13f }
        )
    }

    private fun renderFile() {
        content.addView(heading("Anrufplan-Datei"))
        val uri = Prefs.planUri(act)
        if (uri == null) {
            content.addView(body("Verknüpfe einmal eine CSV- oder Excel-Datei (z. B. Anrufplan.csv in Google Drive). Spalten: Name, Telefon, Tage, Uhrzeit, Datum."))
            content.addView(button("Datei verknüpfen", filled = false) { pickFile() })
            return
        }
        val loaded = Prefs.planLoadedAt(act)
        val name = runCatching { Uri.parse(uri).lastPathSegment?.substringAfterLast('/') }.getOrNull() ?: "Datei"
        content.addView(
            body(
                "Verknüpft: $name\n" + if (loaded > 0) "Zuletzt geladen: " + SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(loaded)) else "Noch nicht geladen"
            )
        )
        val rowView = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        rowView.addView(button("Jetzt laden", filled = false) { syncNow() }, LinearLayout.LayoutParams(0, -2, 1f))
        rowView.addView(button("Lösen", filled = false) {
            confirm("Verknüpfung lösen?", "Die Anrufe aus der Datei werden aus dem Plan entfernt.") {
                PlanFile.unlink(act)
                CallScheduler.reschedule(act)
                render()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (8 * dp).toInt() })
        content.addView(rowView)
    }

    private fun row(s: Schedule): View {
        val up = s.takeIf { it.enabled }?.let { ScheduleMath.next(it, LocalDateTime.now()) }
        val text = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(act).apply {
                this.text = s.name.ifBlank { s.phone }
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(act).apply {
                this.text = listOfNotNull(
                    s.phone,
                    ScheduleMath.describe(s),
                    up?.let { "nächster: " + ScheduleMath.whenLabel(it) } ?: if (s.enabled) "kein weiterer Termin" else "aus",
                    if (s.fromFile) "aus Datei" else null,
                ).joinToString(" · ")
                textSize = 13f
                alpha = 0.75f
            })
        }
        val toggle = SwitchCompat(act).apply {
            isChecked = s.enabled
            setOnCheckedChangeListener { _, on ->
                s.enabled = on
                LeadDb(act).saveSchedule(s)
                CallScheduler.reschedule(act)
                render()
            }
        }
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
            addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            addView(toggle)
            setOnClickListener {
                if (s.fromFile) toast("Kommt aus der Datei. Ändern oder löschen in der Datei, an/aus geht hier.") else edit(s)
            }
        }
    }

    private fun edit(existing: Schedule?) {
        fun field(hint: String, type: Int, value: String) = EditText(act).apply {
            this.hint = hint
            inputType = type
            setText(value)
        }
        val name = field("Name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, existing?.name.orEmpty())
        val phone = field("Telefonnummer", InputType.TYPE_CLASS_PHONE, existing?.phone.orEmpty())
        val time = field("Uhrzeit, z. B. 09:00", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME,
            existing?.let { ScheduleMath.timeLabel(it.minuteOfDay) }.orEmpty())
        val days = field("Tage, z. B. Mo, Mi oder täglich (leer = einmalig)", InputType.TYPE_CLASS_TEXT,
            existing?.takeIf { it.days != 0 }?.let { ScheduleMath.daysLabel(it.days) }.orEmpty())
        val date = field("Datum für einmalig (leer = heute)", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE,
            existing?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.let { "%02d.%02d.%d".format(it.dayOfMonth, it.monthValue, it.year) }.orEmpty())
        val form = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * dp).toInt()
            setPadding(pad, (8 * dp).toInt(), pad, 0)
            listOf(name, phone, time, days, date).forEach { addView(it) }
        }
        val dialog = MaterialAlertDialogBuilder(act)
            .setTitle(if (existing == null) "Anruf planen" else "Geplanten Anruf ändern")
            .setView(form)
            .setPositiveButton("Speichern", null)
            .setNegativeButton("Abbrechen", null)
        if (existing != null) dialog.setNeutralButton("Löschen") { _, _ ->
            LeadDb(act).deleteSchedule(existing.id)
            CallScheduler.reschedule(act)
            render()
        }
        val shown = dialog.show()
        // Validate before closing, so a typo does not throw the input away.
        shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val number = CsvIO.cleanPhone(phone.text.toString())
            val minute = ScheduleMath.parseTime(time.text.toString())
            val mask = ScheduleMath.parseDays(days.text.toString())
            val dateText = date.text.toString().trim()
            // One-time call without a date means today.
            val day = if (mask == 0) {
                if (dateText.isEmpty()) LocalDate.now().toString() else ScheduleMath.parseDate(dateText, LocalDate.now())
            } else null
            val problem = when {
                number.count { it.isDigit() } < 5 -> "Bitte eine gültige Telefonnummer eingeben."
                minute == null -> "Uhrzeit nicht lesbar, z. B. 09:00."
                mask == null -> "Tage nicht lesbar, z. B. Mo, Mi oder täglich."
                mask == 0 && day == null -> "Datum nicht lesbar, z. B. 24.10.2026, oder leer lassen für heute."
                else -> null
            }
            if (problem != null) {
                toast(problem)
                return@setOnClickListener
            }
            val s = existing ?: Schedule(name = "", phone = "", minuteOfDay = 0)
            s.name = name.text.toString().trim()
            s.phone = number
            s.minuteOfDay = minute!!
            s.days = mask!!
            s.date = day
            s.enabled = true
            // A changed time starts fresh, so the new time is not skipped as already handled.
            s.lastFired = 0
            val upNext = ScheduleMath.next(s, LocalDateTime.now())
            if (upNext == null) {
                toast(if (mask == 0 && dateText.isEmpty()) "Diese Uhrzeit ist heute schon vorbei." else "Dieser Zeitpunkt liegt in der Vergangenheit.")
                return@setOnClickListener
            }
            LeadDb(act).saveSchedule(s)
            CallScheduler.reschedule(act)
            toast("Geplant: " + ScheduleMath.whenLabel(upNext))
            shown.dismiss()
            render()
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(act, p) == PackageManager.PERMISSION_GRANTED

    private fun open(i: Intent) {
        runCatching { act.startActivity(i) }.onFailure {
            runCatching { act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${act.packageName}"))) }
        }
    }

    private fun heading(t: String) = TextView(act).apply {
        text = t
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (20 * dp).toInt(), 0, (6 * dp).toInt())
    }

    private fun body(t: String) = TextView(act).apply {
        text = t
        textSize = 15f
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit): MaterialButton {
        val b = if (filled) MaterialButton(act) else MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(act)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Ja") { _, _ -> action() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
}
