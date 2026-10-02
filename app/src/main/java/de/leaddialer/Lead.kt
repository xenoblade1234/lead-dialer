package de.leaddialer

import android.content.Context

enum class Status(val label: String, val color: Int) {
    NEU("Neu", 0xFF78909C.toInt()),
    NICHT_ERREICHT("Nicht erreicht", 0xFFFFA000.toInt()),
    MAILBOX("Mailbox", 0xFFFB8C00.toInt()),
    RUECKRUF("Rückruf", 0xFF1E88E5.toInt()),
    TERMIN("Termin", 0xFF43A047.toInt()),
    KEIN_INTERESSE("Kein Interesse", 0xFFE53935.toInt()),
    FALSCHE_NUMMER("Falsche Nummer", 0xFF6D4C41.toInt());

    companion object {
        fun parse(name: String?): Status = values().firstOrNull { it.name == name } ?: NEU
    }
}

data class Lead(
    var id: Long = 0,
    var name: String,
    var phone: String,
    var company: String = "",
    var note: String = "",
    var status: Status = Status.NEU,
    var attempts: Int = 0,
    var lastCall: Long = 0,
    /** Name of the import this lead came from; "" for leads from before lists existed. */
    var list: String = "",
)

object Prefs {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun countdown(c: Context) = prefs(c).getInt("countdown", 5)
    fun maxAttempts(c: Context) = prefs(c).getInt("maxAttempts", 3)

    /** Outcome saved when the after-call timer runs out untouched; null keeps the status as it was. */
    fun defaultOutcome(c: Context): Status? =
        prefs(c).getString("defaultOutcome", Status.MAILBOX.name).let { n -> Status.values().firstOrNull { it.name == n } }

    /** Selected list on the main screen; null means all lists. */
    fun selectedList(c: Context): String? = prefs(c).getString("selectedList", null)

    fun setSelectedList(c: Context, list: String?) {
        prefs(c).edit().putString("selectedList", list).apply()
    }

    fun save(c: Context, countdown: Int, maxAttempts: Int, defaultOutcome: Status?) {
        prefs(c).edit()
            .putInt("countdown", countdown)
            .putInt("maxAttempts", maxAttempts)
            .putString("defaultOutcome", defaultOutcome?.name ?: "NONE")
            .apply()
    }
}
