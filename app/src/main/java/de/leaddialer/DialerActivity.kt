package de.leaddialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import de.leaddialer.databinding.ActivityDialerBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The auto-dial loop: show lead -> countdown -> call -> (user hangs up, app comes back)
 * -> short timer, outcome optional -> next call right away. The end of a call is detected
 * by the activity resuming after it left for the phone app, which needs no extra
 * phone-state permission. Android cannot tell an app that a voicemail picked up, so the
 * user hangs up themselves and the timer's default outcome covers the mailbox case.
 */
class DialerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LEAD_ID = "leadId"
        const val EXTRA_LIST = "list"
        /** true: dial the next lead by itself after each call; false: wait for an outcome and a tap. */
        const val EXTRA_AUTO = "auto"
    }

    private val auto by lazy { intent.getBooleanExtra(EXTRA_AUTO, true) }

    private enum class State { READY, CALLING, OUTCOME, DONE }

    private lateinit var b: ActivityDialerBinding
    private lateinit var db: LeadDb
    private var queue = LongArray(0)
    private var index = 0
    private var state = State.READY
    private var paused = false
    private var leftForCall = false
    private var single = false
    private var current: Lead? = null
    private var timer: CountDownTimer? = null
    /** Outcome that will be saved when the after-call timer ends; null keeps the old status. */
    private var chosen: Status? = null
    /** Timer stopped only because the app left the foreground; restarts on return. Never set by the user. */
    private var suspended = false

    private val outcomeButtons by lazy {
        mapOf(
            Status.NICHT_ERREICHT to b.btnNotReached,
            Status.MAILBOX to b.btnMailbox,
            Status.RUECKRUF to b.btnCallback,
            Status.TERMIN to b.btnAppointment,
            Status.KEIN_INTERESSE to b.btnNoInterest,
            Status.FALSCHE_NUMMER to b.btnWrongNumber,
        )
    }

    private val callPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            placeCall()
        } else {
            paused = true
            render()
            toast("Ohne Anruf-Berechtigung kann die App nicht wählen.")
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        b = ActivityDialerBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        db = LeadDb(this)

        val singleId = intent.getLongExtra(EXTRA_LEAD_ID, -1L)
        single = singleId >= 0

        b.pauseButton.setOnClickListener { togglePause() }
        b.outcomePause.setOnClickListener { togglePause() }
        b.skipButton.setOnClickListener {
            index++
            loadLead(callNow = false)
        }
        b.callButton.setOnClickListener { placeCall() }
        b.nextNow.setOnClickListener {
            paused = false
            commitOutcome()
        }
        b.endButton.setOnClickListener { finish() }
        b.btnRedial.setOnClickListener { placeCall() }
        outcomeButtons.forEach { (status, button) ->
            button.setOnClickListener {
                if (auto) {
                    // Auto mode only picks what the timer saves; tapping it again clears it.
                    chosen = if (chosen == status) null else status
                    render()
                } else {
                    chosen = status
                    commitOutcome()
                }
            }
        }

        if (saved == null) {
            queue = if (single) longArrayOf(singleId)
            else db.queue(Prefs.maxAttempts(this), intent.getStringExtra(EXTRA_LIST)).toLongArray()
            loadLead(callNow = false)
        } else {
            queue = saved.getLongArray("queue") ?: LongArray(0)
            index = saved.getInt("index")
            state = State.valueOf(saved.getString("state") ?: State.READY.name)
            leftForCall = saved.getBoolean("leftForCall")
            chosen = saved.getString("chosen")?.let { n -> Status.values().firstOrNull { it.name == n } }
            // A running timer does not survive recreation; onResume starts it again.
            paused = saved.getBoolean("paused")
            suspended = state == State.READY || state == State.OUTCOME
            current = queue.getOrNull(index)?.let { db.get(it) }
            render()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putLongArray("queue", queue)
        out.putInt("index", index)
        out.putString("state", state.name)
        out.putBoolean("paused", paused)
        out.putBoolean("leftForCall", leftForCall)
        out.putString("chosen", chosen?.name)
    }

    override fun onPause() {
        super.onPause()
        if (state == State.CALLING) leftForCall = true
        // Never dial while the app is in the background, but do not count that as a pause:
        // the timer starts over as soon as the app is back in front.
        if ((state == State.READY || state == State.OUTCOME) && timer != null) {
            stopTimer()
            suspended = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (state == State.CALLING && leftForCall) {
            leftForCall = false
            suspended = false
            enterOutcome()
        } else if (suspended) {
            suspended = false
            if (auto && !paused && (state == State.READY || state == State.OUTCOME)) startTimer()
            render()
        }
    }

    override fun onDestroy() {
        stopTimer()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** Shows the next lead. [callNow] dials at once (after an outcome); otherwise a countdown runs first. */
    private fun loadLead(callNow: Boolean) {
        stopTimer()
        current = null
        while (index < queue.size) {
            val lead = db.get(queue[index])
            if (lead != null) {
                current = lead
                break
            }
            index++
        }
        if (current == null) {
            state = State.DONE
            render()
            return
        }
        state = State.READY
        // Outcome and note belong to one lead; a redial of the same lead keeps them.
        chosen = if (auto) Prefs.defaultOutcome(this) else null
        b.noteInput.setText("")
        render()
        when {
            paused -> {}
            single -> placeCall()
            !auto -> {}             // pause mode: the user taps "Anrufen"
            callNow -> placeCall()
            else -> startTimer()
        }
    }

    /** Back from a call. Auto mode saves what was picked during the call and dials on at once. */
    private fun enterOutcome() {
        state = State.OUTCOME
        if (auto && !paused && !single) {
            commitOutcome()
            return
        }
        render()
    }

    /** One timer for both phases: before a call it dials, after a call it saves and moves on. */
    private fun startTimer() {
        stopTimer()
        val secs = Prefs.countdown(this)
        val onDone = { if (state == State.OUTCOME) commitOutcome() else placeCall() }
        if (secs <= 0) {
            onDone()
            return
        }
        timer = object : CountDownTimer(secs * 1000L, 200L) {
            override fun onTick(msLeft: Long) {
                val s = (msLeft + 999) / 1000
                if (state == State.OUTCOME) b.outcomeTimer.text = "Nächster Anruf in $s s"
                else b.countdown.text = "Anruf in $s s"
            }

            override fun onFinish() {
                timer = null
                onDone()
            }
        }.start()
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private fun togglePause() {
        paused = !paused
        if (paused) stopTimer() else if (state == State.READY || state == State.OUTCOME) startTimer()
        render()
    }

    private fun placeCall() {
        val lead = current ?: return
        stopTimer()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            callPermission.launch(Manifest.permission.CALL_PHONE)
            return
        }
        lead.attempts += 1
        lead.lastCall = System.currentTimeMillis()
        db.update(lead)
        state = State.CALLING
        leftForCall = false
        render()
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", lead.phone, null)))
        } catch (e: Exception) {
            state = State.READY
            paused = true
            render()
            toast("Anruf konnte nicht gestartet werden: ${e.message}")
        }
    }

    private fun commitOutcome() {
        stopTimer()
        val lead = current ?: return
        chosen?.let { lead.status = it }
        val text = b.noteInput.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            val stamp = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date())
            lead.note = listOf(lead.note, "$stamp: $text").filter { it.isNotBlank() }.joinToString("\n")
        }
        db.update(lead)
        hideKeyboard()
        if (single) {
            finish()
            return
        }
        index++
        loadLead(callNow = true)
    }

    private fun render() {
        val lead = current
        val listName = intent.getStringExtra(EXTRA_LIST)
        b.progress.text = when {
            single -> "Einzelanruf"
            else -> "Lead ${minOf(index + 1, queue.size)} von ${queue.size}" +
                if (listName != null) " · ${listName.ifEmpty { "Ohne Liste" }}" else ""
        }
        if (lead != null) {
            b.name.text = lead.name.ifBlank { "Unbekannt" }
            b.company.text = lead.company
            b.company.isVisible = lead.company.isNotBlank()
            b.phone.text = lead.phone
            b.info.text = "Status: ${lead.status.label} · Versuche: ${lead.attempts}"
            b.previousNote.text = lead.note
            b.previousNote.isVisible = lead.note.isNotBlank()
        }
        // In auto mode the outcome is picked during the call (switch to the app while talking);
        // hanging up then dials the next lead straight away.
        val duringCall = auto && state == State.CALLING
        b.leadCard.isVisible = lead != null && state != State.DONE
        b.controls.isVisible = state == State.READY
        b.outcomeBox.isVisible = state == State.OUTCOME || duringCall
        b.doneText.isVisible = state == State.DONE
        b.countdown.isVisible = state == State.READY || (state == State.CALLING && !auto)
        b.btnRedial.isVisible = state == State.OUTCOME
        when (state) {
            State.CALLING -> {
                b.countdown.text = "Anruf läuft …"
                if (auto) {
                    b.outcomeTimer.text = if (paused) "Anruf läuft · danach Stopp" else "Anruf läuft …"
                    b.outcomeHint.text = "Ergebnis: " + (chosen?.label ?: "Status bleibt") +
                        if (paused) "" else ". Nach dem Auflegen kommt sofort der nächste Anruf."
                    outcomeButtons.forEach { (status, button) -> styleOutcome(button, status) }
                }
            }
            State.READY -> {
                if (timer == null) b.countdown.text = if (paused) "Pausiert" else if (auto) "Bereit" else "Tippe auf Anrufen"
            }
            State.OUTCOME -> {
                if (auto) {
                    if (timer == null) b.outcomeTimer.text = if (paused) "Pausiert" else "Weiter …"
                    b.outcomeHint.text = "Ergebnis: " + (chosen?.label ?: "Status bleibt")
                } else {
                    b.outcomeTimer.text = "Wie lief der Anruf?"
                    b.outcomeHint.text = "Ergebnis antippen, dann kommt der nächste Lead"
                }
                outcomeButtons.forEach { (status, button) -> styleOutcome(button, status) }
            }
            else -> {}
        }
        // Pause and "Jetzt weiter" only make sense while a timer drives the session.
        b.pauseButton.isVisible = auto
        b.outcomePause.isVisible = auto
        b.nextNow.isVisible = auto && !duringCall
        val pauseText = if (paused) "Weiter" else "Pause"
        b.pauseButton.text = pauseText
        b.outcomePause.text = if (duringCall) (if (paused) "Doch weiterwählen" else "Nach diesem Anruf stoppen") else pauseText
        b.nextNow.text = if (single) "Speichern" else "Jetzt weiter"
        b.endButton.text = if (state == State.DONE) "Zurück zur Liste" else "Session beenden"
    }

    /** The selected outcome gets a check mark and stays opaque, the others fade back. */
    private fun styleOutcome(button: MaterialButton, status: Status) {
        val selected = status == chosen
        button.text = if (selected) "✓ ${status.label}" else status.label
        button.alpha = if (chosen == null || selected) 1f else 0.45f
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.root.windowToken, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
