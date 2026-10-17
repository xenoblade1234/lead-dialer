package de.leaddialer

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

/** Lists planned calls, checks the one-time phone settings and links the plan file. */
class PlannerActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private val dp by lazy { resources.displayMetrics.density }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { PlanFile.link(this, uri) }
            .onSuccess { syncNow() }
            .onFailure { toast("Datei konnte nicht verknüpft werden: ${it.message}") }
    }
    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "Geplante Anrufe"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        CallScheduler.ensureChannel(this)
    }

    override fun onResume() {
        super.onResume()
        render()
        if (Prefs.planUri(this) != null) syncNow(quiet = true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun syncNow(quiet: Boolean = false) {
        Thread {
            val result = runCatching { PlanFile.sync(this) }
            CallScheduler.reschedule(this)
            runOnUiThread {
                render()
                result.onSuccess { r ->
                    if (!quiet && r != null) {
                        val (count, errors) = r
                        val msg = "$count Anrufe aus der Datei geladen." +
                            if (errors.isNotEmpty()) "\n\nNicht übernommen:\n" + errors.joinToString("\n") else ""
                        MaterialAlertDialogBuilder(this).setTitle("Anrufplan").setMessage(msg).setPositiveButton("OK", null).show()
                    } else if (quiet && r != null && r.second.isNotEmpty()) {
                        toast("${r.second.size} Zeilen im Anrufplan nicht lesbar. Tippe auf \"Jetzt laden\" für Details.")
                    }
                }.onFailure { if (!quiet) toast("Datei konnte nicht gelesen werden: ${it.message}") }
            }
        }.start()
    }

    // ---------- UI ----------

    private fun render() {
        content.removeAllViews()
        renderChecks()
        renderFile()
        val db = LeadDb(this)
        val up = CallScheduler.upcoming(db, LocalDateTime.now())
        content.addView(heading("Nächster Anruf"))
        content.addView(body(up?.let { (t, list) -> ScheduleMath.whenLabel(t) + " · " + list.joinToString(", ") { it.name.ifBlank { it.phone } } } ?: "Nichts geplant"))

        content.addView(heading("Alle geplanten Anrufe"))
        content.addView(button("+ Anruf planen", filled = true) { edit(null) })
        val all = db.schedules()
        if (all.isEmpty()) content.addView(body("Noch keine. Plane einen Anruf oder verknüpfe deinen Anrufplan."))
        all.forEach { content.addView(row(it)) }
    }

    private data class Check(val ok: Boolean, val label: String, val fix: () -> Unit)

    private fun checks(): List<Check> {
        val list = ArrayList<Check>()
        list.add(Check(granted(Manifest.permission.CALL_PHONE), "Anrufe erlauben") { askPermission.launch(Manifest.permission.CALL_PHONE) })
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Check(granted(Manifest.permission.POST_NOTIFICATIONS), "Benachrichtigungen erlauben") {
                askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NotificationManager::class.java)
            list.add(Check(nm.canUseFullScreenIntent(), "Vollbild-Benachrichtigungen erlauben (damit der Anruf-Bildschirm über der Sperre erscheint)") {
                open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            })
        }
        list.add(Check(Settings.canDrawOverlays(this), "\"Über anderen Apps einblenden\" erlauben (damit auch bei entsperrtem Handy automatisch gewählt wird)") {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        val pm = getSystemService(PowerManager::class.java)
        list.add(Check(pm.isIgnoringBatteryOptimizations(packageName), "Akku-Optimierung ausschalten (sonst kann der Wecker ausfallen)") {
            @SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            open(i)
        })
        list.add(Check(CallScheduler.canScheduleExact(this), "Wecker und Erinnerungen erlauben") {
            if (Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        })
        return list
    }

    private fun renderChecks() {
        val missing = checks().filter { !it.ok }
        content.addView(heading("Einrichtung"))
        if (missing.isEmpty()) {
            content.addView(body("Alles bereit ✓"))
            return
        }
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
        val uri = Prefs.planUri(this)
        if (uri == null) {
            content.addView(body("Verknüpfe einmal eine CSV- oder Excel-Datei (z. B. Anrufplan.csv in Google Drive). Spalten: Name, Telefon, Tage, Uhrzeit, Datum."))
            content.addView(button("Datei verknüpfen", filled = false) { pickFile.launch(arrayOf("*/*")) })
            return
        }
        val loaded = Prefs.planLoadedAt(this)
        val name = runCatching { Uri.parse(uri).lastPathSegment?.substringAfterLast('/') }.getOrNull() ?: "Datei"
        content.addView(
            body(
                "Verknüpft: $name\n" + if (loaded > 0) "Zuletzt geladen: " + SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(loaded)) else "Noch nicht geladen"
            )
        )
        val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowView.addView(button("Jetzt laden", filled = false) { syncNow() }, LinearLayout.LayoutParams(0, -2, 1f))
        rowView.addView(button("Lösen", filled = false) {
            confirm("Verknüpfung lösen?", "Die Anrufe aus der Datei werden aus dem Plan entfernt.") {
                PlanFile.unlink(this)
                CallScheduler.reschedule(this)
                render()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (8 * dp).toInt() })
        content.addView(rowView)
    }

    private fun row(s: Schedule): View {
        val up = s.takeIf { it.enabled }?.let { ScheduleMath.next(it, LocalDateTime.now()) }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@PlannerActivity).apply {
                this.text = s.name.ifBlank { s.phone }
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@PlannerActivity).apply {
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
        val toggle = SwitchCompat(this).apply {
            isChecked = s.enabled
            setOnCheckedChangeListener { _, on ->
                s.enabled = on
                LeadDb(this@PlannerActivity).saveSchedule(s)
                CallScheduler.reschedule(this@PlannerActivity)
                render()
            }
        }
        return LinearLayout(this).apply {
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
        fun field(hint: String, type: Int, value: String) = EditText(this).apply {
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
        val date = field("Datum für einmalig, z. B. 24.10.2026", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE,
            existing?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.let { "%02d.%02d.%d".format(it.dayOfMonth, it.monthValue, it.year) }.orEmpty())
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * dp).toInt()
            setPadding(pad, (8 * dp).toInt(), pad, 0)
            listOf(name, phone, time, days, date).forEach { addView(it) }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Anruf planen" else "Geplanten Anruf ändern")
            .setView(form)
            .setPositiveButton("Speichern", null)
            .setNegativeButton("Abbrechen", null)
        if (existing != null) dialog.setNeutralButton("Löschen") { _, _ ->
            LeadDb(this).deleteSchedule(existing.id)
            CallScheduler.reschedule(this)
            render()
        }
        val shown = dialog.show()
        // Validate before closing, so a typo does not throw the input away.
        shown.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val number = CsvIO.cleanPhone(phone.text.toString())
            val minute = ScheduleMath.parseTime(time.text.toString())
            val mask = ScheduleMath.parseDays(days.text.toString())
            val day = if (mask == 0) ScheduleMath.parseDate(date.text.toString(), LocalDate.now()) else null
            val problem = when {
                number.count { it.isDigit() } < 5 -> "Bitte eine gültige Telefonnummer eingeben."
                minute == null -> "Uhrzeit nicht lesbar, z. B. 09:00."
                mask == null -> "Tage nicht lesbar, z. B. Mo, Mi oder täglich."
                mask == 0 && day == null -> "Für einen einmaligen Anruf ein Datum eingeben, z. B. 24.10.2026."
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
                toast("Dieser Zeitpunkt liegt in der Vergangenheit.")
                return@setOnClickListener
            }
            LeadDb(this).saveSchedule(s)
            CallScheduler.reschedule(this)
            toast("Geplant: " + ScheduleMath.whenLabel(upNext))
            shown.dismiss()
            render()
        }
    }

    // ---------- helpers ----------

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun open(i: Intent) {
        runCatching { startActivity(i) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
    }

    private fun heading(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (20 * dp).toInt(), 0, (6 * dp).toInt())
    }

    private fun body(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit): MaterialButton {
        val b = if (filled) MaterialButton(this) else MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Ja") { _, _ -> action() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
