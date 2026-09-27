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
import de.leaddialer.databinding.ActivityDialerBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The auto-dial loop: show lead -> countdown -> call -> (user hangs up, app comes back)
 * -> pick outcome -> next lead. The end of a call is detected by the activity resuming
 * after it left for the phone app, which needs no extra phone-state permission.
 */
class DialerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LEAD_ID = "leadId"
    }

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
        b.skipButton.setOnClickListener {
            index++
            loadLead(autoStart = true)
        }
        b.callButton.setOnClickListener { placeCall() }
        b.endButton.setOnClickListener { finish() }
        b.btnRedial.setOnClickListener { placeCall() }
        b.btnNotReached.setOnClickListener { saveOutcome(Status.NICHT_ERREICHT) }
        b.btnMailbox.setOnClickListener { saveOutcome(Status.MAILBOX) }
        b.btnCallback.setOnClickListener { saveOutcome(Status.RUECKRUF) }
        b.btnAppointment.setOnClickListener { saveOutcome(Status.TERMIN) }
        b.btnNoInterest.setOnClickListener { saveOutcome(Status.KEIN_INTERESSE) }
        b.btnWrongNumber.setOnClickListener { saveOutcome(Status.FALSCHE_NUMMER) }

        if (saved == null) {
            queue = if (single) longArrayOf(singleId) else db.queue(Prefs.maxAttempts(this)).toLongArray()
            loadLead(autoStart = true)
        } else {
            queue = saved.getLongArray("queue") ?: LongArray(0)
            index = saved.getInt("index")
            state = State.valueOf(saved.getString("state") ?: State.READY.name)
            leftForCall = saved.getBoolean("leftForCall")
            // A running countdown does not survive recreation, so come back paused.
            paused = saved.getBoolean("paused") || state == State.READY
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
    }

    override fun onPause() {
        super.onPause()
        if (state == State.CALLING) leftForCall = true
        // Never dial while the user is in another app.
        if (state == State.READY && timer != null) {
            stopTimer()
            paused = true
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        if (state == State.CALLING && leftForCall) {
            leftForCall = false
            state = State.OUTCOME
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

    private fun loadLead(autoStart: Boolean) {
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
        b.noteInput.setText("")
        render()
        if (single && autoStart) placeCall() else if (autoStart && !paused) startCountdown()
    }

    private fun startCountdown() {
        stopTimer()
        val secs = Prefs.countdown(this)
        if (secs <= 0) {
            placeCall()
            return
        }
        timer = object : CountDownTimer(secs * 1000L, 200L) {
            override fun onTick(msLeft: Long) {
                b.countdown.text = "Anruf in ${(msLeft + 999) / 1000} s"
            }

            override fun onFinish() {
                timer = null
                placeCall()
            }
        }.start()
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private fun togglePause() {
        paused = !paused
        if (paused) stopTimer() else if (state == State.READY) startCountdown()
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

    private fun saveOutcome(status: Status) {
        val lead = current ?: return
        lead.status = status
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
        loadLead(autoStart = true)
    }

    private fun render() {
        val lead = current
        b.progress.text = if (single) "Einzelanruf" else "Lead ${minOf(index + 1, queue.size)} von ${queue.size}"
        if (lead != null) {
            b.name.text = lead.name.ifBlank { "Unbekannt" }
            b.company.text = lead.company
            b.company.isVisible = lead.company.isNotBlank()
            b.phone.text = lead.phone
            b.info.text = "Status: ${lead.status.label} · Versuche: ${lead.attempts}"
            b.previousNote.text = lead.note
            b.previousNote.isVisible = lead.note.isNotBlank()
        }
        b.leadCard.isVisible = lead != null && state != State.DONE
        b.controls.isVisible = state == State.READY
        b.outcomeBox.isVisible = state == State.OUTCOME
        b.doneText.isVisible = state == State.DONE
        b.countdown.isVisible = state == State.READY || state == State.CALLING
        when (state) {
            State.CALLING -> {
                b.countdown.text = "Anruf läuft …"
            }
            State.READY -> {
                if (timer == null) b.countdown.text = if (paused) "Pausiert" else "Bereit"
            }
            else -> {}
        }
        b.pauseButton.text = if (paused) "Weiter" else "Pause"
        b.endButton.text = if (state == State.DONE) "Zurück zur Liste" else "Session beenden"
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.root.windowToken, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
