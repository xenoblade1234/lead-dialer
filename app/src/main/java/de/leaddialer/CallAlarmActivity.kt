package de.leaddialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * Shown when a planned call is due, also over the lock screen. Counts down, then dials; the
 * user can cancel or call at once. Several calls due at the same minute run one after another,
 * the next one only once the previous call has really ended.
 */
class CallAlarmActivity : AppCompatActivity() {

    companion object {
        const val COUNTDOWN_S = 10
        private const val POLL_MS = 500L
        private const val CALL_GRACE_MS = 2500L
    }

    private val ids = ArrayList<Long>()
    private var index = 0
    private var missed = false
    private var calling = false
    private var callStartedAt = 0L
    private var idlePolls = 0
    private var timer: CountDownTimer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private lateinit var title: TextView
    private lateinit var name: TextView
    private lateinit var phone: TextView
    private lateinit var countdown: TextView
    private lateinit var callNow: MaterialButton
    private lateinit var cancel: MaterialButton

    private val watchCall = object : Runnable {
        override fun run() {
            if (!calling) return
            val fresh = SystemClock.elapsedRealtime() - callStartedAt < CALL_GRACE_MS
            val inCall = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
            idlePolls = if (fresh || inCall) 0 else idlePolls + 1
            if (idlePolls >= 2) {
                calling = false
                next()
            } else {
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        take(intent)
        show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Another call came due while this screen is open: queue it behind the current one.
        val before = ids.size
        take(intent)
        if (before == 0 || index >= before) show()
    }

    private fun take(intent: Intent) {
        intent.getLongArrayExtra(CallScheduler.EXTRA_IDS)?.forEach { if (it !in ids) ids.add(it) }
        missed = intent.getBooleanExtra(CallScheduler.EXTRA_MISSED, false)
        NotificationManagerCompat.from(this).cancel(if (missed) CallScheduler.NOTIFY_MISSED else CallScheduler.NOTIFY_DUE)
    }

    override fun onResume() {
        super.onResume()
        if (calling) {
            idlePolls = 0
            handler.removeCallbacks(watchCall)
            handler.post(watchCall)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(watchCall)
    }

    override fun onDestroy() {
        timer?.cancel()
        handler.removeCallbacks(watchCall)
        super.onDestroy()
    }

    private fun current(): Schedule? = ids.getOrNull(index)?.let { LeadDb(this).schedule(it) }

    private fun show() {
        timer?.cancel()
        val s = current()
        if (s == null) {
            if (index < ids.size) { index++; show() } else finish()
            return
        }
        title.text = if (missed) "Verpasster geplanter Anruf" else "Geplanter Anruf"
        name.text = s.name.ifBlank { "Ohne Namen" }
        phone.text = s.phone
        callNow.text = "Jetzt anrufen"
        cancel.text = if (missed) "Nicht nachholen" else "Abbrechen"
        if (missed) {
            countdown.text = "Geplant: " + ScheduleMath.describe(s)
            return
        }
        timer = object : CountDownTimer(COUNTDOWN_S * 1000L, 200L) {
            override fun onTick(ms: Long) {
                countdown.text = "Anruf in ${(ms + 999) / 1000} s"
            }

            override fun onFinish() {
                timer = null
                dial()
            }
        }.start()
    }

    private fun dial() {
        timer?.cancel()
        val s = current() ?: return next()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Anruf-Berechtigung fehlt. Öffne den Lead Dialer einmal und erlaube Anrufe.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            calling = true
            callStartedAt = SystemClock.elapsedRealtime()
            countdown.text = "Anruf läuft …"
            startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", s.phone, null)))
        } catch (e: Exception) {
            calling = false
            Toast.makeText(this, "Anruf konnte nicht gestartet werden: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun next() {
        timer?.cancel()
        index++
        if (index < ids.size) show() else finish()
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        title = text(16f).apply { alpha = 0.7f }
        name = text(32f, bold = true)
        phone = text(22f)
        countdown = text(26f, bold = true).apply { setPadding(0, (32 * dp).toInt(), 0, (32 * dp).toInt()) }
        callNow = MaterialButton(this).apply {
            textSize = 20f
            setOnClickListener { dial() }
        }
        cancel = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            textSize = 18f
            setOnClickListener { next() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = (24 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(name)
            addView(phone)
            addView(countdown)
            val lp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (64 * dp).toInt()).apply { topMargin = (12 * dp).toInt() } }
            addView(callNow, lp())
            addView(cancel, lp())
        }
        setContentView(root)
    }
}
