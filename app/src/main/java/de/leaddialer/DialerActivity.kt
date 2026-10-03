package de.leaddialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * The dial loop: show lead -> call -> call really ends -> save outcome -> next lead.
 *
 * Auto mode dials the next lead the moment a call is over; the outcome is picked during the
 * call (the user switches to the app while talking), else the default outcome is saved.
 * Pause mode waits for an outcome tap and a tap on "Anrufen".
 *
 * Call end is read from the audio mode (MODE_IN_CALL while a call is up), which needs no
 * phone-state permission. Coming back to the app is NOT proof the call is over: the user may
 * switch here mid-call, and dialing while a call is still up is refused by Android.
 */
class DialerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LEAD_ID = "leadId"
        const val EXTRA_LIST = "list"
        /** true: dial the next lead by itself after each call; false: wait for an outcome and a tap. */
        const val EXTRA_AUTO = "auto"

        private const val POLL_MS = 500L
        /** Consecutive idle polls before a call counts as over, so a disconnecting call is not mistaken for idle. */
        private const val IDLE_POLLS = 2
        /** The audio mode can lag behind a fresh call; ignore "idle" right after dialing. */
        private const val CALL_GRACE_MS = 2500L
    }

    private enum class State { READY, CALLING, OUTCOME, DONE }

    private lateinit var b: ActivityDialerBinding
    private lateinit var db: LeadDb
    private val auto by lazy { intent.getBooleanExtra(EXTRA_AUTO, true) }
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val handler = Handler(Looper.getMainLooper())
    private var queue = LongArray(0)
    private var index = 0
    private var state = State.READY
    /** Only ever set by the user (auto mode): no further call is started on its own. */
    private var paused = false
    private var single = false
    private var current: Lead? = null
    /** Outcome saved for the current lead; null keeps the old status. */
    private var chosen: Status? = null
    private var callStartedAt = 0L
    private var idlePolls = 0

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

    private val watchCall = object : Runnable {
        override fun run() {
            if (state != State.CALLING) return
            val fresh = SystemClock.elapsedRealtime() - callStartedAt < CALL_GRACE_MS
            idlePolls = if (fresh || inCall()) 0 else idlePolls + 1
            if (idlePolls >= IDLE_POLLS) onCallEnded() else handler.postDelayed(this, POLL_MS)
        }
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
            loadLead(dial = auto && !paused)
        }
        b.callButton.setOnClickListener { placeCall() }
        b.nextNow.setOnClickListener {
            // During a call this is the manual fallback if the end of a call is not detected.
            if (state == State.CALLING) onCallEnded() else commitOutcome()
        }
        b.endButton.setOnClickListener { finish() }
        b.btnRedial.setOnClickListener { placeCall() }
        outcomeButtons.forEach { (status, button) ->
            button.setOnClickListener {
                if (state == State.OUTCOME && (!auto || single)) {
                    chosen = status
                    commitOutcome()
                } else {
                    // During a call (or while paused) a tap only picks; tapping it again clears it.
                    chosen = if (chosen == status) null else status
                    render()
                }
            }
        }

        if (saved == null) {
            queue = if (single) longArrayOf(singleId)
            else db.queue(Prefs.maxAttempts(this), intent.getStringExtra(EXTRA_LIST)).toLongArray()
            loadLead(dial = auto || single)
        } else {
            queue = saved.getLongArray("queue") ?: LongArray(0)
            index = saved.getInt("index")
            state = State.valueOf(saved.getString("state") ?: State.READY.name)
            paused = saved.getBoolean("paused")
            chosen = saved.getString("chosen")?.let { n -> Status.values().firstOrNull { it.name == n } }
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
        out.putString("chosen", chosen?.name)
    }

    override fun onResume() {
        super.onResume()
        if (state == State.CALLING) {
            render()
            idlePolls = 0
            handler.removeCallbacks(watchCall)
            handler.post(watchCall)
        }
    }

    override fun onPause() {
        super.onPause()
        // A new call can only be started from the foreground; watching resumes on return.
        handler.removeCallbacks(watchCall)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchCall)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun inCall(): Boolean = when (audio.mode) {
        AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE -> true
        else -> false
    }

    /** Shows the next lead and, when [dial] is set, calls it straight away. */
    private fun loadLead(dial: Boolean) {
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
        if (dial) placeCall()
    }

    private fun onCallEnded() {
        handler.removeCallbacks(watchCall)
        state = State.OUTCOME
        if (auto && !paused && !single) commitOutcome() else render()
    }

    private fun togglePause() {
        paused = !paused
        if (!paused) {
            when (state) {
                State.READY -> placeCall()
                State.OUTCOME -> commitOutcome()
                else -> {}
            }
        }
        render()
    }

    private fun placeCall() {
        val lead = current ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            callPermission.launch(Manifest.permission.CALL_PHONE)
            return
        }
        if (inCall()) {
            // Never fire a call into a running one (e.g. a call that was already up at start).
            state = State.READY
            paused = true
            render()
            toast("Es läuft noch ein Anruf. Beende ihn, dann tippe auf Anrufen.")
            return
        }
        lead.attempts += 1
        lead.lastCall = System.currentTimeMillis()
        db.update(lead)
        state = State.CALLING
        callStartedAt = SystemClock.elapsedRealtime()
        idlePolls = 0
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
        loadLead(dial = auto && !paused)
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
        val calling = state == State.CALLING
        b.leadCard.isVisible = lead != null && state != State.DONE
        b.controls.isVisible = state == State.READY
        b.outcomeBox.isVisible = calling || state == State.OUTCOME
        b.doneText.isVisible = state == State.DONE
        b.countdown.isVisible = state == State.READY
        b.btnRedial.isVisible = state == State.OUTCOME
        b.pauseButton.isVisible = auto
        b.pauseButton.text = if (paused) "Weiter" else "Pause"
        b.outcomePause.isVisible = auto && !single
        b.countdown.text = when {
            paused -> "Pausiert"
            auto -> "Wählt …"
            else -> "Tippe auf Anrufen"
        }
        val picked = "Ergebnis: " + (chosen?.label ?: "Status bleibt")
        when (state) {
            State.CALLING -> {
                b.outcomeTimer.text = "Anruf läuft …"
                b.outcomeHint.text = when {
                    !auto || single -> "$picked. Kannst du jetzt schon antippen."
                    paused -> "$picked. Nach dem Auflegen wird gestoppt."
                    else -> "$picked. Nach dem Auflegen kommt sofort der nächste Anruf."
                }
                b.outcomePause.text = if (paused) "Doch weiterwählen" else "Nach diesem Anruf stoppen"
                b.nextNow.isVisible = true
                b.nextNow.text = "Anruf ist beendet"
            }
            State.OUTCOME -> {
                b.outcomeTimer.text = if (auto && paused) "Pausiert" else "Wie lief der Anruf?"
                b.outcomeHint.text = if (!auto) "Ergebnis antippen, dann kommt der nächste Lead" else picked
                b.outcomePause.text = "Weiter"
                b.nextNow.isVisible = single && auto
                b.nextNow.text = "Speichern"
            }
            else -> b.nextNow.isVisible = false
        }
        outcomeButtons.forEach { (status, button) -> styleOutcome(button, status) }
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
