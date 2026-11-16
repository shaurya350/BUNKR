package com.bunkr.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Calendar
import java.util.GregorianCalendar

/*
 * Exam mode.
 *  - Exam types: theory = MST 1, MST 2, FINAL.  Lab = IP 1, IP 2, FINAL EP (external practical).
 *  - Upload a datesheet (photo, screenshot, PDF or pasted text): the dates are read on the phone, you check
 *    them, and they become exams.
 *  - Reminders: 1 week / 3 days / 1 day before, and on the exam day.
 */

// ---------- Exam data ----------
const val EXAM_MST1 = 0
const val EXAM_MST2 = 1
const val EXAM_FINAL = 2
const val EXAM_IP1 = 3
const val EXAM_IP2 = 4
const val EXAM_EP = 5

val EXAM_LABELS = listOf("MST 1", "MST 2", "FINAL", "IP 1", "IP 2", "FINAL EP")

fun examIsLab(type: Int) = type >= EXAM_IP1

fun examColor(type: Int): Color = when (type) {
    EXAM_MST1 -> Sky
    EXAM_MST2 -> Yellow
    EXAM_FINAL -> Pink
    EXAM_IP1 -> Lime
    EXAM_IP2 -> Mint
    else -> Pink
}

data class Exam(
    val id: Long,
    val subject: String,
    val type: Int,          // EXAM_MST1 ... EXAM_EP
    val day: Long,          // epoch day
    val start: Int = -1,    // minutes after midnight, -1 = not known
    val end: Int = -1,      // -1 = not known
    val note: String = ""   // room, seat number ...
)

fun encodeExam(e: Exam) =
    "${e.id}|${e.type}|${e.day}|${e.start}|${e.end}|${cleanText(e.subject)}|${cleanText(e.note)}"

fun decodeExam(text: String): Exam? = runCatching {
    val p = text.removePrefix("~exam|").split("|")
    Exam(
        p[0].toLong(), p[5], p[1].toInt().coerceIn(EXAM_MST1, EXAM_EP),
        p[2].toLong(), p[3].toInt(), p[4].toInt(), p.getOrElse(6) { "" }
    )
}.getOrNull()

fun examTimeText(e: Exam) = when {
    e.start >= 0 && e.end > e.start -> "${formatTime(e.start)} - ${formatTime(e.end)}"
    e.start >= 0 -> formatTime(e.start)
    else -> "TIME NOT SET"
}

fun countdownText(days: Long) = when {
    days < 0 -> "DONE"
    days == 0L -> "TODAY"
    days == 1L -> "TOMORROW"
    else -> "IN $days DAYS"
}

// ---------- Reminders ----------
const val LEAD_WEEK = 1
const val LEAD_3D = 2
const val LEAD_1D = 4
const val LEAD_DAY = 8

/** The moment (epoch millis) a reminder of the given kind is due for this exam. */
fun reminderMoment(e: Exam, lead: Int): Long = when (lead) {
    LEAD_WEEK -> millisOf(e.day - 7, 9 * 60)
    LEAD_3D -> millisOf(e.day - 3, 9 * 60)
    LEAD_1D -> millisOf(e.day - 1, 20 * 60)
    // exam day: two hours before it starts (7 AM when the time is not known)
    else -> millisOf(
        e.day,
        if (e.start >= 0) minOf(maxOf(e.start - 120, 6 * 60), maxOf(e.start - 15, 0)) else 7 * 60
    )
}

object ExamReminders {
    private const val PREFS = "bunkr_exams"
    private const val CHANNEL = "exam_reminders"
    private const val ALARM_REQUEST = 4003

    val LEADS = listOf(LEAD_WEEK to "1 WEEK", LEAD_3D to "3 DAYS", LEAD_1D to "1 DAY", LEAD_DAY to "EXAM DAY")

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun leads(c: Context) = prefs(c).getInt("leads", LEAD_3D or LEAD_1D or LEAD_DAY)

    fun setSettings(c: Context, on: Boolean, leads: Int) {
        prefs(c).edit().putBoolean("enabled", on).putInt("leads", leads).apply()
    }

    /** A copy of the exams for the alarm, so reminders work even when the app is closed. */
    fun saveExams(c: Context, exams: List<Exam>) {
        prefs(c).edit().putString("exams", exams.joinToString("\n") { encodeExam(it) }).apply()
    }

    fun loadExams(c: Context): List<Exam> =
        (prefs(c).getString("exams", "") ?: "").lines().filter { it.isNotBlank() }.mapNotNull { decodeExam(it) }

    /** Called on logout. */
    fun clear(c: Context) {
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(c))
        prefs(c).edit().remove("exams").apply()
    }

    private fun alarmIntent(c: Context, at: Long = 0L): PendingIntent =
        PendingIntent.getBroadcast(
            c, ALARM_REQUEST,
            Intent(c, ExamReminderReceiver::class.java).putExtra("at", at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** One alarm, always for the next reminder that is still to come. */
    fun scheduleNext(c: Context, from: Long = System.currentTimeMillis()) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(c))
        if (!enabled(c)) return

        val mask = leads(c)
        val next = loadExams(c)
            .flatMap { e -> LEADS.filter { mask and it.first != 0 }.map { reminderMoment(e, it.first) } }
            .filter { it > from }
            .minOrNull() ?: return

        val pi = alarmIntent(c, next)
        try {
            if (Reminders.canScheduleExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    /** The alarm fired: show every reminder due at that moment, then arm the next one. */
    fun onAlarm(c: Context, at: Long) {
        if (enabled(c) && at > 0) {
            val mask = leads(c)
            for (e in loadExams(c)) {
                for ((bit, _) in LEADS) {
                    if (mask and bit != 0 && reminderMoment(e, bit) == at) show(c, e, bit)
                }
            }
        }
        scheduleNext(c, maxOf(System.currentTimeMillis(), at))
    }

    private fun createChannel(c: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "Exam reminders", NotificationManager.IMPORTANCE_HIGH)
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun canNotify(c: Context) =
        Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun show(c: Context, e: Exam, lead: Int) {
        if (!canNotify(c)) return
        createChannel(c)

        val open = PendingIntent.getActivity(
            c, 2,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationDesign.buildExamReminder(
            c = c,
            e = e,
            lead = lead,
            channel = CHANNEL,
            openApp = open
        ).build()
        NotificationManagerCompat.from(c).notify(e.id.hashCode() * 31 + lead, notification)
    }
}

/** Fires when an exam reminder is due. Register in AndroidManifest.xml. */
class ExamReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ExamReminders.onAlarm(context.applicationContext, intent.getLongExtra("at", 0L))
    }
}

// ---------- Reading a datesheet ----------
data class ParsedExam(val subject: String, val day: Long, val start: Int, val end: Int)

private const val MONTH_RX =
    "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
private val MONTH_STARTS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

// Dates
private val dateIso = Regex("""\b(\d{4})\s*-\s*(\d{1,2})\s*-\s*(\d{1,2})\b""")
private val dateNumeric = Regex("""\b(\d{1,2})\s*[./-]\s*(\d{1,2})\s*[./-]\s*(\d{4}|\d{2})\b""")
private val dateDayMonth =
    Regex("""(?i)\b(\d{1,2})(?:st|nd|rd|th)?[\s,.-]*$MONTH_RX\b\.?(?:[\s,.-]*(\d{4})|-(\d{2})(?![\d:.]))?""")
private val dateMonthDay = Regex("""(?i)\b$MONTH_RX\b\.?[\s,.-]*(\d{1,2})(?:st|nd|rd|th)?\b(?:[\s,.-]*(\d{4}))?""")
// "20/11" with no year. Slash only, so a time range such as "10-12" is never read as a date.
private val dateShort = Regex("""\b(\d{1,2})\s*/\s*(\d{1,2})\b(?![\d:./-])""")

// Times
private val timeRange = Regex(
    """(?i)(\d{1,2})(?:\s*[:.]\s*(\d{2}))?\s*([ap]\s*\.?\s*m\.?)?\s*(?:-|–|—|to|till|until)\s*(\d{1,2})(?:\s*[:.]\s*(\d{2}))?\s*([ap]\s*\.?\s*m\.?)?"""
)
private val timeSingle = Regex(
    """(?i)(\d{1,2})\s*:\s*(\d{2})\s*([ap]\s*\.?\s*m\.?)?|\b(\d{1,2})\s*([ap]\s*\.?\s*m\.?)"""
)
private val noonWords = Regex("""(?i)\bnoon\b""")
private val headerTimeHint = Regex("""(?i)\b(?:time|timing|timings|session|shift|duration)\b""")

// Cleaning the subject
private val dayWords = Regex(
    """(?i)\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun)\b\.?"""
)
private val codeWords = Regex("""\b[A-Z]{2,6}[\s-]?\d{2,4}(?:-\d{1,2})?\b""")
private val headerWords = Regex("""(?i)\b(?:date|day|time|subject|paper|shift|forenoon|afternoon)\b""")
private val sessionWords = Regex("""\b(?:FN|AN)\b""")
private val annotWords = Regex("""(?i)\(\s*(?:theory|practical|th|pr)\s*\)""")
private val serialStart = Regex("""^\s*\d{1,2}\s*[.)]?\s+""")
private val isolatedBars = Regex("""(?:(?<=^)|(?<=\s))[Il!|](?=\s|$)""")
private val leadingBars = Regex("""^(?:\s*[Il!|\[\]]\s+)+""")
private val rangeLeftover = Regex("""(?:^|\s)\d{1,2}\s*[-–]\s*\d{1,2}(?=\s|$)""")
private val pipesAndUnderscores = Regex("""[|_]+""")
private val manySpaces = Regex("""\s+""")

// Lines that are not exams, and wrapped subject names
private val noteLine = Regex(
    """(?i)^\s*(?:note|notes|instruction|instructions|candidates?|students?|please|principal|controller|head|signature|dated|copy|sd|regd|registrar|dean|hod|all)\b"""
)
private val connectiveStart = Regex("""(?i)^(?:and\b|of\b|for\b|in\b|with\b|to\b|&|-|\()""")
private val connectiveEnd = Regex("""(?i)(?:\band|\bof|\bfor|\bin|\bwith|\bto|&|-|,)$""")

private fun validDay(y: Int, m0: Int, d: Int): Long? {
    if (y !in 2000..2100 || m0 !in 0..11 || d < 1) return null
    val max = GregorianCalendar(y, m0, 1).getActualMaximum(Calendar.DAY_OF_MONTH)
    return if (d <= max) dayOfYmd(y, m0, d) else null
}

private class DateHit(val day: Long, val range: IntRange)

/** Finds a date: 2026-12-12, 12/12/2026, 12.12.26, 12 Dec 2026, 12th December, Dec 12, 20-Nov-26 or 12/12. */
private fun findDate(line: String, today: Long): DateHit? {
    val thisYear = ymdOf(today).first
    fun monthIndex(name: String) = MONTH_STARTS.indexOfFirst { name.lowercase().startsWith(it) }
    // no year written: use this year, or next year if that date is long past
    fun withYear(m0: Int, d: Int, yearText: String): Long? {
        if (yearText.isNotEmpty()) {
            val y = yearText.toInt().let { if (it < 100) it + 2000 else it }
            return validDay(y, m0, d)
        }
        val a = validDay(thisYear, m0, d) ?: return null
        return if (a < today - 120) (validDay(thisYear + 1, m0, d) ?: a) else a
    }

    dateIso.find(line)?.let { m ->
        validDay(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
            ?.let { return DateHit(it, m.range) }
    }
    dateNumeric.find(line)?.let { m ->
        var y = m.groupValues[3].toInt()
        if (y < 100) y += 2000
        validDay(y, m.groupValues[2].toInt() - 1, m.groupValues[1].toInt())?.let { return DateHit(it, m.range) }
    }
    dateDayMonth.find(line)?.let { m ->
        val year = m.groupValues[3].ifEmpty { m.groupValues[4] }
        withYear(monthIndex(m.groupValues[2]), m.groupValues[1].toInt(), year)?.let { return DateHit(it, m.range) }
    }
    dateMonthDay.find(line)?.let { m ->
        withYear(monthIndex(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3])?.let {
            return DateHit(it, m.range)
        }
    }
    dateShort.find(line)?.let { m ->
        withYear(m.groupValues[2].toInt() - 1, m.groupValues[1].toInt(), "")?.let { return DateHit(it, m.range) }
    }
    return null
}

private class TimeHit(val start: Int, val end: Int, val range: IntRange)

private fun meridiem(s: String): Boolean? = s.firstOrNull { it.isLetter() }?.lowercaseChar()?.let { it == 'p' }
private fun hour24(h: Int, pm: Boolean) = (h % 12) + if (pm) 12 else 0
private fun guessHour(h: Int) = if (h in 1..6) h + 12 else h // nobody sits an exam at 3 AM

/** Finds "10:00 AM - 1:00 PM", "10.00-13.00", "9 AM to 12 Noon", or a single start time. */
private fun findTimes(line: String): TimeHit? {
    for (m in timeRange.findAll(line)) {
        val h1 = m.groupValues[1].toInt()
        val h2 = m.groupValues[4].toInt()
        val m1 = m.groupValues[2].ifEmpty { "0" }.toInt()
        val m2 = m.groupValues[5].ifEmpty { "0" }.toInt()
        val pm1 = meridiem(m.groupValues[3])
        val pm2 = meridiem(m.groupValues[6])
        // "10-12" alone could be anything: a time needs minutes or AM/PM somewhere
        val detail = m.groupValues[2].isNotEmpty() || m.groupValues[5].isNotEmpty() || pm1 != null || pm2 != null
        if (!detail || h1 > 23 || h2 > 23 || m1 > 59 || m2 > 59) continue

        val s: Int
        val e: Int
        when {
            pm1 != null && pm2 != null -> {
                s = hour24(h1, pm1) * 60 + m1
                e = hour24(h2, pm2) * 60 + m2
            }
            pm2 != null -> { // "10:00 - 1:00 PM"
                e = hour24(h2, pm2) * 60 + m2
                val same = hour24(h1, pm2) * 60 + m1
                s = if (same >= e) hour24(h1, !pm2) * 60 + m1 else same
            }
            pm1 != null -> { // "10:00 AM - 1:00"
                s = hour24(h1, pm1) * 60 + m1
                val same = hour24(h2, pm1) * 60 + m2
                e = if (same <= s) hour24(h2, !pm1) * 60 + m2 else same
            }
            h1 >= 13 || h2 >= 13 -> { // 24 hour clock
                s = h1 * 60 + m1
                e = h2 * 60 + m2
            }
            else -> {
                s = guessHour(h1) * 60 + m1
                val guessed = guessHour(h2) * 60 + m2
                e = if (guessed <= s) guessed + 12 * 60 else guessed
            }
        }
        if (e > s && e <= 24 * 60) return TimeHit(s, e, m.range)
    }

    timeSingle.find(line)?.let { m ->
        val colon = m.groupValues[1].isNotEmpty()
        val h = (if (colon) m.groupValues[1] else m.groupValues[4]).toInt()
        val min = if (colon) m.groupValues[2].toInt() else 0
        val pm = meridiem(if (colon) m.groupValues[3] else m.groupValues[5])
        if (h <= 23 && min <= 59) {
            val s = (if (pm != null) hour24(h, pm) else guessHour(h)) * 60 + min
            return TimeHit(s, -1, m.range)
        }
    }
    return null
}

/** "DATA STRUCTURES AND ALGORITHMS" -> "Data Structures and Algorithms". Short vowel-less words (DBMS, SQL) and OS, CN stay. */
private fun niceCase(s: String): String = s.split(' ').joinToString(" ") { w ->
    val lower = w.lowercase()
    val hasVowel = w.any { it in "aeiouAEIOU" }
    when {
        lower in setOf("and", "of", "for", "the", "to", "in", "with") -> lower
        w.length <= 2 || (w.length <= 4 && !hasVowel) -> w
        else -> lower.replaceFirstChar { it.uppercase() }
    }
}

/**
 * What is left of a row once the date and time are taken away: the subject name and, separately, a subject
 * code such as BTCS-301-18 (used as the name when the datesheet has no names).
 */
private fun cleanSubject(s: String): Pair<String?, String?> {
    var t = s
    // table borders read as the letter I: only treated that way when there are several
    if (isolatedBars.findAll(t).count() >= 2) t = t.replace(isolatedBars, " ")
    t = t.replace(leadingBars, " ").replace(dayWords, " ")
    val code = codeWords.find(t)?.value?.trim()
    t = t.replace(codeWords, " ").replace(headerWords, " ").replace(sessionWords, " ").replace(annotWords, " ")
    t = t.replace(serialStart, " ").replace(rangeLeftover, " ")
    t = t.replace(pipesAndUnderscores, " ").replace(manySpaces, " ")
        .trim(' ', '-', ':', '.', ',', '(', ')', '[', ']', '/')
    if (t.count { it.isLetter() } < 3) return null to code
    if (t.none { it.isLowerCase() } && t.length > 6) t = niceCase(t)
    return t to code
}

/** Uses the name of a subject you already track when the datesheet name clearly is that subject. */
private fun matchSubject(name: String, known: List<String>): String {
    val key = subjectKey(name)
    known.firstOrNull { subjectKey(it) == key }?.let { return it }
    known.firstOrNull {
        val k = subjectKey(it)
        k.length >= 4 && key.length >= 4 && (key.contains(k) || k.contains(key))
    }?.let { return it }
    return linkSubject(name, known)
}

private fun normalizeLine(raw: String): String =
    raw.replace('\u00a0', ' ').replace('\t', ' ').replace(noonWords, "PM").replace(manySpaces, " ").trim()

/**
 * Turns the text of a datesheet into exams. Works with:
 *  - one table row per line, in any column order, with the date and the subject together
 *  - a date heading ("Saturday 12 December 2026", with or without a time) followed by its subjects
 *  - a time written once for the whole sheet ("Exam time: 10:00 AM - 1:00 PM")
 *  - subject names that wrap onto a second line, and subject codes with no names
 * Lines such as notes, signatures and headings are skipped.
 */
fun parseDatesheet(text: String, known: List<String>, today: Long): List<ParsedExam> {
    class Row(var subject: String, val day: Long, var start: Int, var end: Int)

    val out = mutableListOf<Row>()
    var pending: Triple<Long, Int, Int>? = null // date heading: day, start, end
    var pendingRun = false                       // the line before was the heading, or a subject that used it
    var defaultStart = -1
    var defaultEnd = -1
    var groupStart = -1                          // the latest time given on a date heading
    var groupEnd = -1
    var last: Row? = null

    for (raw in text.lines()) {
        val line = normalizeLine(raw)
        if (line.length < 3) continue
        if (noteLine.containsMatchIn(line)) {
            last = null
            pendingRun = false
            continue
        }

        val dateHit = findDate(line, today)
        val noDate = if (dateHit != null) line.replaceRange(dateHit.range, " ") else line
        val timeHit = findTimes(noDate)
        val noTime = if (timeHit != null) noDate.replaceRange(timeHit.range, " ") else noDate
        val (cleaned, code) = cleanSubject(noTime)
        val name: String? = cleaned ?: code
        var s = timeHit?.start ?: -1
        var e = timeHit?.end ?: -1

        // A time on its own line: the time of the date heading above, or of the whole datesheet
        if (dateHit == null && timeHit != null && (name == null || headerTimeHint.containsMatchIn(line))) {
            val p = pending
            if (p != null && pendingRun) {
                pending = Triple(p.first, s, e)
                groupStart = s
                groupEnd = e
            } else {
                defaultStart = s
                defaultEnd = e
            }
            continue
        }

        // A wrapped subject name: "Engineering Physics and" + "Materials Science"
        val prev = last
        if (dateHit == null && timeHit == null && name != null && prev != null &&
            (connectiveEnd.containsMatchIn(prev.subject) || connectiveStart.containsMatchIn(name))
        ) {
            prev.subject = matchSubject((prev.subject + " " + name).trim(), known)
            continue
        }

        val p = pending
        when {
            dateHit != null && name != null -> {
                val row = Row(matchSubject(name, known), dateHit.day, s, e)
                out += row
                last = row
                pending = null
                pendingRun = false
            }
            dateHit != null -> { // a date heading
                if (timeHit != null) {
                    groupStart = s
                    groupEnd = e
                } else if (groupStart >= 0) { // the next heading repeats the time of the one before
                    s = groupStart
                    e = groupEnd
                }
                pending = Triple(dateHit.day, s, e)
                pendingRun = true
                last = null
            }
            p != null && name != null && (timeHit != null || pendingRun || matchSubject(name, known) in known) -> {
                val row = Row(
                    matchSubject(name, known), p.first,
                    if (timeHit != null) s else p.second,
                    if (timeHit != null) e else p.third
                )
                out += row
                last = row
                pendingRun = true
            }
            else -> {
                last = null
                pendingRun = false
            }
        }
    }

    for (r in out) {
        if (r.start < 0 && defaultStart >= 0) {
            r.start = defaultStart
            r.end = defaultEnd
        }
    }
    return out
        .sortedWith(compareBy<Row>({ it.day }, { it.start }))
        .distinctBy { subjectKey(it.subject) to it.day }
        .map { ParsedExam(it.subject, it.day, it.start, it.end) }
}

/** Guesses MST 1 / MST 2 / FINAL / IP 1 / IP 2 / FINAL EP from the words on the datesheet. */
fun guessExamType(text: String): Int? {
    val t = text.lowercase()
    fun has(rx: String) = Regex(rx).containsMatchIn(t)
    return when {
        has("""\bip[\s-]*(?:1|i)\b|internal\s+practical[\s-]*(?:1|i)\b""") -> EXAM_IP1
        has("""\bip[\s-]*(?:2|ii)\b|internal\s+practical[\s-]*(?:2|ii)\b""") -> EXAM_IP2
        has("""\bep\b|external\s+practical|final\s+practical|end[\s-]*sem\w*\s+practical""") -> EXAM_EP
        has("""\bmst[\s-]*(?:1|i)\b|sessional[\s-]*(?:1|i)\b|mid[\s-]*sem\w*[\s-]*(?:1|i)\b""") -> EXAM_MST1
        has("""\bmst[\s-]*(?:2|ii)\b|sessional[\s-]*(?:2|ii)\b|mid[\s-]*sem\w*[\s-]*(?:2|ii)\b""") -> EXAM_MST2
        has("""\bfinal\b|end[\s-]*sem|\bese\b""") -> EXAM_FINAL
        else -> null
    }
}

// ---------- Reading a datesheet: PDF ----------
private fun renderPdfPages(context: Context, uri: Uri, maxPages: Int): List<Bitmap> {
    val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
    try {
        val renderer = PdfRenderer(fd)
        try {
            val pages = mutableListOf<Bitmap>()
            for (i in 0 until minOf(renderer.pageCount, maxPages)) {
                val page = renderer.openPage(i)
                try {
                    val scale = 1800f / page.width
                    val bmp = Bitmap.createBitmap(
                        (page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(android.graphics.Color.WHITE) // a PDF page is transparent until painted
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pages.add(bmp)
                } finally {
                    page.close()
                }
            }
            return pages
        } finally {
            renderer.close()
        }
    } finally {
        fd.close()
    }
}

/** Reads the text of the first pages of a PDF on the phone (the pages are drawn, then read like a photo). */
fun recognizePdf(context: Context, uri: Uri, onDone: (String?) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    Thread {
        val pages = try {
            renderPdfPages(context, uri, 5)
        } catch (e: Exception) {
            emptyList()
        }
        main.post {
            if (pages.isEmpty()) {
                onDone(null)
                return@post
            }
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val out = StringBuilder()
            fun next(i: Int) {
                if (i >= pages.size) {
                    onDone(out.toString())
                    return
                }
                recognizer.process(InputImage.fromBitmap(pages[i], 0))
                    .addOnSuccessListener {
                        out.append(rowsFromOcr(it)).append('\n')
                        next(i + 1)
                    }
                    .addOnFailureListener { next(i + 1) }
            }
            next(0)
        }
    }.start()
}

// ---------- Screen ----------
@Composable
private fun ExamCard(e: Exam, today: Long, onEdit: () -> Unit) {
    val daysLeft = e.day - today
    val parts = dateText(e.day).split(" ")
    NeoCard(color = if (daysLeft < 0) White else Cream) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(62.dp)) {
                if (parts.size >= 3) {
                    Text(parts[0], fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(parts[1], fontWeight = FontWeight.Black, fontSize = 28.sp)
                    Text(parts[2], fontWeight = FontWeight.Black, fontSize = 12.sp)
                } else {
                    Text(dateText(e.day), fontWeight = FontWeight.Black, fontSize = 14.sp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(e.subject.ifBlank { "Exam" }, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(EXAM_LABELS[e.type], examColor(e.type))
                    Spacer(Modifier.width(8.dp))
                    Text(countdownText(daysLeft), fontWeight = FontWeight.Black, fontSize = 13.sp)
                }
                Text(examTimeText(e), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                if (e.note.isNotBlank()) Text(e.note, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Box(
                Modifier.background(Yellow).border(3.dp, Ink).clickable { onEdit() }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) { Text("EDIT", fontWeight = FontWeight.Black, fontSize = 12.sp) }
        }
    }
}

@Composable
fun ExamScreen(
    exams: List<Exam>,
    onEdit: (Exam) -> Unit
) {
    val context = LocalContext.current
    val today = epochDayOf(System.currentTimeMillis())
    var filter by remember { mutableStateOf(0) } // 0 all, 1 theory, 2 lab
    var enabled by remember { mutableStateOf(ExamReminders.enabled(context)) }
    var leads by remember { mutableStateOf(ExamReminders.leads(context)) }
    var blocked by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        blocked = !granted
    }

    fun save(on: Boolean, mask: Int) {
        enabled = on
        leads = mask
        ExamReminders.setSettings(context, on, mask)
        ExamReminders.scheduleNext(context)
    }

    val sorted = exams.sortedWith(compareBy<Exam>({ it.day }, { it.start }))
    val upcoming = sorted.filter { it.day >= today }
    val done = sorted.filter { it.day < today }.reversed()
    fun shown(e: Exam) = when (filter) {
        1 -> !examIsLab(e.type)
        2 -> examIsLab(e.type)
        else -> true
    }

    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Next exam
        val next = upcoming.firstOrNull()
        if (next == null) {
            NeoCard(color = Sky) {
                Text("NO UPCOMING EXAMS", fontWeight = FontWeight.Black, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tap DATESHEET to upload a photo or PDF of your datesheet, or + ADD EXAM to add one yourself.",
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            NeoCard(color = Pink) {
                Text("NEXT EXAM", fontWeight = FontWeight.Black, fontSize = 12.sp)
                Text(countdownText(next.day - today), fontWeight = FontWeight.Black, fontSize = 30.sp)
                Spacer(Modifier.height(2.dp))
                Text(next.subject.ifBlank { "Exam" }, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(EXAM_LABELS[next.type], White)
                    Spacer(Modifier.width(8.dp))
                    Text("${dateText(next.day)}  ·  ${examTimeText(next)}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                if (next.note.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(next.note, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        // Reminders
        NeoCard(color = Yellow) {
            Text("EXAM REMINDERS", fontWeight = FontWeight.Black, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            CheckRow("REMIND ME BEFORE EXAMS", enabled) {
                val on = !enabled
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                save(on, leads)
            }
            if (enabled) {
                Spacer(Modifier.height(10.dp))
                FieldLabel("REMIND ME")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ExamReminders.LEADS) { (bit, label) ->
                        SemChip(label, leads and bit != 0, Lime) {
                            val mask = leads xor bit
                            if (mask != 0) save(true, mask)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "1 week and 3 days: 9 AM. 1 day: 8 PM. Exam day: 2 hours before it starts (7 AM if no time is set).",
                    fontWeight = FontWeight.Bold, fontSize = 12.sp
                )
                if (blocked) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Notifications are blocked for this app. Turn them on in your phone's Settings > Apps > Notifications.",
                        fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )
                }
                if (!Reminders.canScheduleExact(context)) {
                    Spacer(Modifier.height(8.dp))
                    Text("For on-time reminders, allow exact alarms for this app.", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    NeoButton("ALLOW EXACT ALARMS", White) {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                        )
                    }
                }
            }
        }

        if (exams.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SemChip("ALL", filter == 0, Yellow) { filter = 0 }
                SemChip("THEORY", filter == 1, Sky) { filter = 1 }
                SemChip("LAB", filter == 2, Pink) { filter = 2 }
            }

            val up = upcoming.filter { shown(it) }
            if (up.isNotEmpty()) {
                Text("UPCOMING (${up.size})", fontWeight = FontWeight.Black, fontSize = 14.sp)
                up.forEach { e ->
                    key(e.id) { ExamCard(e, today) { onEdit(e) } }
                }
            }
            val past = done.filter { shown(it) }
            if (past.isNotEmpty()) {
                Text("DONE (${past.size})", fontWeight = FontWeight.Black, fontSize = 14.sp)
                past.forEach { e ->
                    key(e.id) { ExamCard(e, today) { onEdit(e) } }
                }
            }
        }
    }
}

// ---------- Add / edit one exam ----------
@Composable
fun ExamDialog(
    initial: Exam?,
    subjectNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (Exam) -> Unit,
    onDelete: (() -> Unit)?
) {
    val context = LocalContext.current
    val today = remember { epochDayOf(System.currentTimeMillis()) }
    var subject by remember { mutableStateOf(initial?.subject ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: EXAM_MST1) }
    var day by remember { mutableStateOf(initial?.day ?: today) }
    var hasTime by remember { mutableStateOf((initial?.start ?: -1) >= 0) }
    var start by remember { mutableStateOf(initial?.start?.takeIf { it >= 0 } ?: (10 * 60)) }
    var end by remember { mutableStateOf(initial?.end?.takeIf { it >= 0 } ?: (13 * 60)) }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pickDate() {
        val (y, m, d) = ymdOf(day)
        DatePickerDialog(context, { _, yy, mm, dd -> day = dayOfYmd(yy, mm, dd) }, y, m, d).show()
    }

    fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(context, { _, h, m -> onPicked(h * 60 + m) }, current / 60, current % 60, false).show()
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.padding(end = 6.dp, bottom = 6.dp)) {
            Column(
                Modifier
                    .neoShadow(6.dp)
                    .background(Yellow)
                    .border(3.dp, Ink)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(if (initial == null) "ADD EXAM" else "EDIT EXAM", fontWeight = FontWeight.Black, fontSize = 22.sp)

                Column {
                    FieldLabel("EXAM TYPE")
                    Text("THEORY", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (t in EXAM_MST1..EXAM_FINAL) SemChip(EXAM_LABELS[t], type == t, examColor(t)) { type = t }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("LAB", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (t in EXAM_IP1..EXAM_EP) SemChip(EXAM_LABELS[t], type == t, examColor(t)) { type = t }
                    }
                }

                Column {
                    FieldLabel("SUBJECT")
                    NeoField(subject, { subject = it }, "e.g. Data Structures")
                    if (subjectNames.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(subjectNames) { n -> SemChip(n, subject == n, Lime) { subject = n } }
                        }
                    }
                }

                Column {
                    FieldLabel("DATE")
                    NeoButton(dateText(day), White) { pickDate() }
                }

                CheckRow("SET THE TIME", hasTime) { hasTime = !hasTime }
                if (hasTime) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            FieldLabel("STARTS")
                            NeoButton(formatTime(start), White) {
                                pickTime(start) {
                                    start = it
                                    if (end <= it) end = minOf(it + 180, 23 * 60 + 59)
                                }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            FieldLabel("ENDS")
                            NeoButton(formatTime(end), White) { pickTime(end) { end = it } }
                        }
                    }
                }

                Column {
                    FieldLabel("ROOM / SEAT (OPTIONAL)")
                    NeoField(note, { note = it }, "e.g. Block B, Room 204")
                }

                error?.let { Text(it, fontWeight = FontWeight.Black) }

                if (onDelete != null) {
                    NeoButton(if (confirmDelete) "TAP AGAIN TO DELETE" else "DELETE EXAM", Red) {
                        if (confirmDelete) onDelete() else confirmDelete = true
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeoButton("CANCEL", White, Modifier.weight(1f), onDismiss)
                    NeoButton("SAVE", Lime, Modifier.weight(1f)) {
                        when {
                            subject.isBlank() -> error = "Enter a subject name."
                            hasTime && end <= start -> error = "The end time must be after the start time."
                            else -> onSave(
                                Exam(
                                    id = initial?.id ?: System.currentTimeMillis(),
                                    subject = subject.trim(),
                                    type = type,
                                    day = day,
                                    start = if (hasTime) start else -1,
                                    end = if (hasTime) end else -1,
                                    note = note.trim()
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- Upload a datesheet ----------
class ExamImportRow(subject: String, day: Long, start: Int, end: Int) {
    var selected by mutableStateOf(true)
    var subject by mutableStateOf(subject)
    var day by mutableStateOf(day)
    var start by mutableStateOf(start)
    var end by mutableStateOf(end)
}

@Composable
fun ExamImportDialog(
    subjectNames: List<String>,
    onDismiss: () -> Unit,
    onApply: (type: Int, rows: List<ParsedExam>) -> Unit
) {
    val context = LocalContext.current
    val today = remember { epochDayOf(System.currentTimeMillis()) }
    var stage by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(EXAM_MST1) }
    var typeGuessed by remember { mutableStateOf(false) }
    var rawText by remember { mutableStateOf("") } // what the reader found in the file, kept so it can be fixed
    val rows = remember { mutableStateListOf<ExamImportRow>() }

    fun handleText(text: String?) {
        loading = false
        if (text.isNullOrBlank()) {
            status = "Couldn't read any text from that file. Try a sharper, straight-on photo, " +
                    "paste the text, or add the exams with + ADD EXAM."
            stage = 0
            return
        }
        rawText = text
        val parsed = parseDatesheet(text, subjectNames, today)
        if (parsed.isEmpty()) {
            status = "I read some text but found no exam dates in it. Check the text below: each exam needs its " +
                    "date and subject on the same line. Fix it and tap READ AGAIN."
            stage = 2
            return
        }
        val guess = guessExamType(text)
        type = guess ?: EXAM_MST1
        typeGuessed = guess != null
        rows.clear()
        rows.addAll(parsed.map { ExamImportRow(it.subject, it.day, it.start, it.end) })
        status = null
        stage = 1
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            loading = true
            status = null
            recognizeText(context, uri) { handleText(it) }
        }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            loading = true
            status = null
            recognizePdf(context, uri) { handleText(it) }
        }
    }

    fun pickDate(current: Long, onPicked: (Long) -> Unit) {
        val (y, m, d) = ymdOf(current)
        DatePickerDialog(context, { _, yy, mm, dd -> onPicked(dayOfYmd(yy, mm, dd)) }, y, m, d).show()
    }

    fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        val c = if (current >= 0) current else 10 * 60
        TimePickerDialog(context, { _, h, m -> onPicked(h * 60 + m) }, c / 60, c % 60, false).show()
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.padding(end = 6.dp, bottom = 6.dp)) {
            Column(
                Modifier
                    .neoShadow(6.dp)
                    .background(Yellow)
                    .border(3.dp, Ink)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("UPLOAD DATESHEET", fontWeight = FontWeight.Black, fontSize = 22.sp)

                if (stage == 0) {
                    if (pasting) {
                        Column {
                            FieldLabel("PASTE YOUR DATESHEET")
                            NeoField(
                                pasteText, { pasteText = it },
                                "12/12/2026  10:00 AM - 1:00 PM  Data Structures\n15/12/2026  10:00 AM - 1:00 PM  Physics",
                                singleLine = false, minLines = 7
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NeoButton("BACK", White, Modifier.weight(1f)) { pasting = false }
                            NeoButton("READ TEXT", Lime, Modifier.weight(1f)) { handleText(pasteText) }
                        }
                    } else {
                        NeoButton("PICK PHOTO / SCREENSHOT", Sky) {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        NeoButton("PICK PDF", Pink) { pdfPicker.launch("application/pdf") }
                        NeoButton("PASTE TEXT", Lime) { pasting = true }
                        Text(
                            "The datesheet is read on your phone and never uploaded. It works best on a table with " +
                                    "one subject per row and the date (and time) on the same row. You check everything " +
                                    "before it is added.",
                            fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                    }
                    if (loading) {
                        Box(Modifier.fillMaxWidth().background(Sky).border(3.dp, Ink).padding(10.dp)) {
                            Text("READING...", fontWeight = FontWeight.Black)
                        }
                    }
                    status?.let {
                        Box(Modifier.fillMaxWidth().background(White).border(3.dp, Ink).padding(10.dp)) {
                            Text(it, fontWeight = FontWeight.Black)
                        }
                    }
                    if (!pasting) NeoButton("CANCEL", White, onClick = onDismiss)
                } else if (stage == 2) {
                    Text("WHAT THE APP READ", fontWeight = FontWeight.Black)
                    Text(
                        "This is the text found in your file. Fix any wrong date or subject here (one exam per " +
                                "line, with its date and subject together), then tap READ AGAIN.",
                        fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )
                    NeoField(rawText, { rawText = it }, "", singleLine = false, minLines = 10)
                    if (rows.isNotEmpty()) {
                        Text(
                            "Reading again replaces the exams you were reviewing.",
                            fontWeight = FontWeight.Bold, fontSize = 12.sp
                        )
                    }
                    status?.let {
                        Box(Modifier.fillMaxWidth().background(White).border(3.dp, Ink).padding(10.dp)) {
                            Text(it, fontWeight = FontWeight.Black)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeoButton("BACK", White, Modifier.weight(1f)) {
                            status = null
                            stage = if (rows.isNotEmpty()) 1 else 0
                        }
                        NeoButton("READ AGAIN", Lime, Modifier.weight(1f)) { handleText(rawText) }
                    }
                } else {
                    val picked = rows.filter { it.selected }
                    Text("REVIEW (${picked.size}/${rows.size} selected)", fontWeight = FontWeight.Black)

                    Column {
                        FieldLabel("EXAM TYPE" + if (typeGuessed) " (GUESSED FROM THE DATESHEET)" else " (PICK ONE)")
                        Text("THEORY", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (t in EXAM_MST1..EXAM_FINAL) SemChip(EXAM_LABELS[t], type == t, examColor(t)) { type = t }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("LAB", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (t in EXAM_IP1..EXAM_EP) SemChip(EXAM_LABELS[t], type == t, examColor(t)) { type = t }
                        }
                    }

                    Text(
                        "Check each subject, date and time. Untick anything that isn't an exam. " +
                                "Exams you already have for this type are updated, not repeated.",
                        fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeoButton("SEE READ TEXT", White, Modifier.weight(1f)) { stage = 2 }
                        NeoButton("+ ADD ROW", Sky, Modifier.weight(1f)) {
                            rows.add(ExamImportRow("", today, -1, -1))
                        }
                    }

                    rows.forEach { row ->
                        Column(
                            Modifier.fillMaxWidth().background(Cream).border(3.dp, Ink).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .background(if (row.selected) Lime else White)
                                        .border(3.dp, Ink)
                                        .clickable { row.selected = !row.selected },
                                    contentAlignment = Alignment.Center
                                ) { if (row.selected) Text("X", fontWeight = FontWeight.Black) }
                                Spacer(Modifier.width(10.dp))
                                Box(Modifier.weight(1f)) {
                                    NeoField(row.subject, { row.subject = it }, "Subject")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NeoButton(dateText(row.day), White, Modifier.weight(1.4f)) {
                                    pickDate(row.day) { row.day = it }
                                }
                                NeoButton(if (row.start >= 0) formatTime(row.start) else "NO TIME", White, Modifier.weight(1f)) {
                                    pickTime(row.start) {
                                        row.start = it
                                        if (row.end <= it) row.end = minOf(it + 180, 23 * 60 + 59)
                                    }
                                }
                                NeoButton(if (row.end > row.start && row.end >= 0) formatTime(row.end) else "NO END", White, Modifier.weight(1f)) {
                                    pickTime(if (row.end >= 0) row.end else (if (row.start >= 0) row.start else 10 * 60) + 180) { row.end = it }
                                }
                            }
                            if (row.day < today) {
                                Text(
                                    "THIS DATE HAS ALREADY PASSED. CHECK THE DAY, MONTH AND YEAR.",
                                    fontWeight = FontWeight.Black, fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeoButton("BACK", White, Modifier.weight(1f)) { stage = 0 }
                        NeoButton("ADD ${picked.size}", Lime, Modifier.weight(1f)) {
                            val ok = picked.filter { it.subject.isNotBlank() }
                            if (ok.isEmpty()) status = "Tick at least one exam with a subject name."
                            else onApply(type, ok.map { ParsedExam(it.subject.trim(), it.day, it.start, it.end) })
                        }
                    }
                    status?.let { Text(it, fontWeight = FontWeight.Black) }
                }
            }
        }
    }
}