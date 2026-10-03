package de.leaddialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.leaddialer.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var db: LeadDb
    private val adapter = LeadAdapter { showLead(it) }
    private var pendingLeadId = -1L

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) askListName(uri)
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) exportCsv(uri)
    }
    private val callPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDialer(pendingLeadId) else toast("Ohne Anruf-Berechtigung kann die App nicht wählen.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = LeadDb(this)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        b.list.adapter = adapter
        b.startButton.setOnClickListener { chooseMode() }
        b.listButton.setOnClickListener { chooseList() }
        b.emptyText.text = "Noch keine Leads.\n\nOben rechts im Menü: \"CSV importieren\" oder \"Lead hinzufügen\".\n\n" +
            "Die CSV braucht eine Spalte \"Telefon\", optional \"Name\", \"Firma\", \"Notiz\"."
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** The list currently shown and dialed; null means all lists. Falls back to all if it was deleted. */
    private fun selectedList(): String? {
        val sel = Prefs.selectedList(this) ?: return null
        if (db.lists().none { it.first == sel }) {
            Prefs.setSelectedList(this, null)
            return null
        }
        return sel
    }

    private fun listLabel(list: String) = list.ifEmpty { "Ohne Liste" }

    private fun refresh() {
        val sel = selectedList()
        val leads = db.all(sel)
        adapter.items = leads
        val open = db.queue(Prefs.maxAttempts(this), sel).size
        val appointments = leads.count { it.status == Status.TERMIN }
        b.listButton.text = "Liste: " + (sel?.let { listLabel(it) } ?: "Alle Listen") + "  ▾"
        b.stats.text = "${leads.size} Leads · $open offen · $appointments Termine"
        b.startButton.isEnabled = open > 0
        b.startButton.text = if (open > 0) "Wählen starten ($open)" else "Keine offenen Leads"
        b.emptyText.isVisible = leads.isEmpty()
    }

    private fun chooseList() {
        val lists = db.lists()
        val total = lists.sumOf { it.second }
        val labels = listOf("Alle Listen ($total)") + lists.map { "${listLabel(it.first)} (${it.second})" }
        val sel = selectedList()
        val checked = if (sel == null) 0 else lists.indexOfFirst { it.first == sel } + 1
        MaterialAlertDialogBuilder(this)
            .setTitle("Welche Liste?")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                Prefs.setSelectedList(this, if (which == 0) null else lists[which - 1].first)
                refresh()
                d.dismiss()
            }
            .show()
    }

    /** Asked at every session start; the last choice is preselected. */
    private fun chooseMode() {
        val options = arrayOf(
            "Automatisch weiterwählen\nNach dem Auflegen sofort der nächste Anruf",
            "Nach jedem Anruf pausieren\nErgebnis antippen, nächsten Anruf selbst starten",
        )
        var auto = Prefs.autoMode(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("Wie willst du telefonieren?")
            .setSingleChoiceItems(options, if (auto) 0 else 1) { _, which -> auto = which == 0 }
            .setPositiveButton("Los geht's") { _, _ ->
                Prefs.setAutoMode(this, auto)
                startDialer(-1L)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun startDialer(leadId: Long) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingLeadId = leadId
            callPermission.launch(Manifest.permission.CALL_PHONE)
            return
        }
        val intent = Intent(this, DialerActivity::class.java)
            .putExtra(DialerActivity.EXTRA_LEAD_ID, leadId)
            .putExtra(DialerActivity.EXTRA_AUTO, Prefs.autoMode(this))
        if (leadId < 0) selectedList()?.let { intent.putExtra(DialerActivity.EXTRA_LIST, it) }
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_IMPORT, 0, "CSV importieren")
        menu.add(0, MENU_ADD, 1, "Lead hinzufügen")
        menu.add(0, MENU_EXPORT, 2, "CSV exportieren")
        menu.add(0, MENU_SETTINGS, 3, "Einstellungen")
        menu.add(0, MENU_DELETE_LIST, 4, "Diese Liste löschen")
        menu.add(0, MENU_CLEAR, 5, "Alle Leads löschen")
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_DELETE_LIST)?.isVisible = selectedList() != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_IMPORT -> importLauncher.launch(arrayOf("*/*"))
            MENU_ADD -> addLead()
            MENU_EXPORT -> {
                val prefix = selectedList()?.let { listLabel(it).replace(Regex("[^A-Za-z0-9ÄÖÜäöüß_-]+"), "_") } ?: "leads"
                exportLauncher.launch(prefix + "_" + SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date()) + ".csv")
            }
            MENU_SETTINGS -> showSettings()
            MENU_DELETE_LIST -> selectedList()?.let { list ->
                confirm("Liste \"${listLabel(list)}\" löschen?", "Alle Leads dieser Liste werden gelöscht. Das kann nicht rückgängig gemacht werden.") {
                    db.deleteList(list)
                    Prefs.setSelectedList(this, null)
                    refresh()
                }
            }
            MENU_CLEAR -> confirm("Wirklich alle Leads löschen?", "Alle Listen werden gelöscht. Das kann nicht rückgängig gemacht werden.") {
                db.deleteAll()
                Prefs.setSelectedList(this, null)
                refresh()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /** Every import becomes its own list; the file name is the suggested list name. */
    private fun askListName(uri: Uri) {
        val fileName = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val suggestion = fileName?.substringBeforeLast('.')?.ifBlank { null }
            ?: ("Import " + SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date()))
        val input = field("Name der Liste", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, suggestion)
        MaterialAlertDialogBuilder(this)
            .setTitle("Liste benennen")
            .setMessage("Gibt es die Liste schon, werden die Leads ergänzt und doppelte Nummern übersprungen.")
            .setView(form(input))
            .setPositiveButton("Importieren") { _, _ -> importCsv(uri, input.text.toString().trim().ifEmpty { suggestion }) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun importCsv(uri: Uri, list: String) {
        Thread {
            val result = runCatching {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val leads = CsvIO.parse(CsvIO.decode(bytes)).onEach { it.list = list }
                leads.size to db.insertAll(leads)
            }
            runOnUiThread {
                result.onSuccess { (found, added) ->
                    val msg = if (found == 0) {
                        "Keine Telefonnummern gefunden. Die CSV braucht eine Spalte wie \"Telefon\"."
                    } else {
                        "$found Leads gefunden, $added in \"$list\" hinzugefügt." +
                            if (found > added) "\n${found - added} Duplikate übersprungen." else ""
                    }
                    if (added > 0) Prefs.setSelectedList(this, list)
                    MaterialAlertDialogBuilder(this).setTitle("Import").setMessage(msg).setPositiveButton("OK", null).show()
                    refresh()
                }.onFailure { toast("Import fehlgeschlagen: ${it.message}") }
            }
        }.start()
    }

    private fun exportCsv(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)!!.use { it.write(CsvIO.export(db.all(selectedList())).toByteArray(Charsets.UTF_8)) }
        }.onSuccess { toast("Export gespeichert") }
            .onFailure { toast("Export fehlgeschlagen: ${it.message}") }
    }

    private fun showLead(l: Lead) {
        val msg = buildString {
            appendLine(l.phone)
            if (l.company.isNotBlank()) appendLine(l.company)
            appendLine()
            appendLine("Liste: ${listLabel(l.list)}")
            appendLine("Status: ${l.status.label}")
            appendLine("Versuche: ${l.attempts}")
            if (l.lastCall > 0) appendLine("Zuletzt: " + SimpleDateFormat("dd.MM.yy HH:mm", Locale.GERMANY).format(Date(l.lastCall)))
            if (l.note.isNotBlank()) {
                appendLine()
                append(l.note)
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(l.name.ifBlank { l.phone })
            .setMessage(msg)
            .setPositiveButton("Anrufen") { _, _ -> startDialer(l.id) }
            .setNeutralButton("Status ändern") { _, _ -> changeStatus(l) }
            .setNegativeButton("Löschen") { _, _ ->
                confirm("Lead löschen?", l.name.ifBlank { l.phone }) {
                    db.delete(l.id)
                    refresh()
                }
            }
            .show()
    }

    private fun changeStatus(l: Lead) {
        val statuses = Status.values()
        MaterialAlertDialogBuilder(this)
            .setTitle("Status ändern")
            .setSingleChoiceItems(statuses.map { it.label }.toTypedArray(), statuses.indexOf(l.status)) { d, which ->
                l.status = statuses[which]
                // Back to "Neu" puts the lead fully back into the call queue.
                if (l.status == Status.NEU) l.attempts = 0
                db.update(l)
                refresh()
                d.dismiss()
            }
            .show()
    }

    private fun addLead() {
        val name = field("Name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val phone = field("Telefonnummer", InputType.TYPE_CLASS_PHONE)
        val company = field("Firma (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        MaterialAlertDialogBuilder(this)
            .setTitle("Lead hinzufügen")
            .setView(form(name, phone, company))
            .setPositiveButton("Speichern") { _, _ ->
                val number = CsvIO.cleanPhone(phone.text.toString())
                if (number.count { it.isDigit() } < 5) {
                    toast("Ungültige Telefonnummer")
                } else {
                    val list = selectedList() ?: "Manuell"
                    val added = db.insertAll(listOf(Lead(name = name.text.toString().trim(), phone = number, company = company.text.toString().trim(), list = list)))
                    if (added == 0) toast("Diese Nummer gibt es in \"${listLabel(list)}\" schon")
                    refresh()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showSettings() {
        val countdown = field("", InputType.TYPE_CLASS_NUMBER, Prefs.countdown(this).toString())
        val maxAttempts = field("", InputType.TYPE_CLASS_NUMBER, Prefs.maxAttempts(this).toString())
        // Choices for the outcome saved when the after-call timer runs out untouched.
        val outcomeChoices = listOf<Pair<Status?, String>>(
            Status.MAILBOX to "Mailbox",
            Status.NICHT_ERREICHT to "Nicht erreicht",
            null to "Nichts eintragen",
        )
        val current = Prefs.defaultOutcome(this)
        val outcomeGroup = RadioGroup(this).apply {
            outcomeChoices.forEachIndexed { i, (status, text) ->
                addView(RadioButton(this@MainActivity).apply {
                    id = 1000 + i
                    this.text = text
                    isChecked = status == current
                })
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Einstellungen")
            .setView(
                form(
                    label("Sekunden vor dem ersten Anruf und nach Überspringen (0 = sofort)"), countdown,
                    label("Max. Versuche bei \"Nicht erreicht\" / \"Mailbox\""), maxAttempts,
                    label("Ergebnis, wenn du nach dem Auflegen nichts antippst"), outcomeGroup,
                )
            )
            .setPositiveButton("Speichern") { _, _ ->
                val picked = outcomeChoices.getOrNull(outcomeGroup.checkedRadioButtonId - 1000)
                Prefs.save(
                    this,
                    countdown.text.toString().toIntOrNull()?.coerceIn(0, 120) ?: 3,
                    maxAttempts.text.toString().toIntOrNull()?.coerceIn(1, 20) ?: 3,
                    if (picked != null) picked.first else current,
                )
                refresh()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Ja") { _, _ -> action() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun field(hint: String, type: Int, value: String = "") = EditText(this).apply {
        this.hint = hint
        inputType = type
        setText(value)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, dp(12), 0, 0)
    }

    private fun form(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), 0)
        views.forEach { addView(it) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val MENU_DELETE_LIST = 6
        const val MENU_IMPORT = 1
        const val MENU_ADD = 2
        const val MENU_EXPORT = 3
        const val MENU_SETTINGS = 4
        const val MENU_CLEAR = 5
    }
}
