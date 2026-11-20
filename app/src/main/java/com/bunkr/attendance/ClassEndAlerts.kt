package com.bunkr.attendance

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Mark your attendance" notification that appears when a class ends.
 * The PRESENT / ABSENT buttons save the attendance straight from the notification, no need to open the app.
 *
 * How it works:
 *  1. ONE alarm is always set for the next class END (same idea as Reminders.scheduleNext).
 *  2. When it fires, ClassEndReceiver shows a heads-up notification for each class that just ended
 *     and sets the following alarm.
 *  3. A button press goes to AttendanceActionReceiver, which updates the saved data (SubjectStore),
 *     backs it up to Firestore, and swaps the notification for a short confirmation with UNDO.
 *  4. CANCELLED changes nothing (the class didn't happen, so it isn't counted).
 *  5. No prompt is shown on holidays / leave days. Leave that counts as present is added instead.
 *  6. The home screen widget (BunkrWidget) sends the same buttons to AttendanceActionReceiver.
 */
object ClassEndAlerts {
    private const val PREFS = "bunkwise_class_end"
    private const val CHANNEL = "mark_attendance"
    private const val ALARM_REQUEST = 4002

    const val ACTION_PRESENT = "P"
    const val ACTION_ABSENT = "A"
    const val ACTION_UNDO_PRESENT = "UP"
    const val ACTION_UNDO_ABSENT = "UA"
    const val ACTION_CANCELLED = "C"
    const val ACTION_UNDO_CANCELLED = "UC"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("enabled", on).apply()
    }

    // ---------- Scheduling ----------

    private fun alarmIntent(c: Context, endAt: Long = 0L): PendingIntent =
        PendingIntent.getBroadcast(
            c, ALARM_REQUEST,
            Intent(c, ClassEndReceiver::class.java).putExtra("endAt", endAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Sets ONE alarm for the next class end. Call it whenever the timetable or the setting changes. */
    fun scheduleNext(c: Context, from: Long = System.currentTimeMillis()) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(c))
        if (!enabled(c)) return

        val regular = Reminders.loadSlots(c)
        val temp = Reminders.loadTempSchedule(c)
        val offs = Reminders.loadDaysOff(c)
        val exams = ExamReminders.loadExams(c)

        val startDay = epochDayOf(from)
        var earliestSlot: ClassSlot? = null
        var earliestEnd = Long.MAX_VALUE

        for (d in startDay..(startDay + 14)) {
            if (isDayOff(offs, d)) continue
            if (isExamDay(exams, d)) continue
            val daySlots = effectiveSlotsForDay(regular, temp, d)
            for (slot in daySlots) {
                if (slot.end <= slot.start) continue
                val endMs = millisOf(d, slot.end)
                if (endMs > from && endMs < earliestEnd) {
                    earliestEnd = endMs
                    earliestSlot = slot
                }
            }
            if (earliestEnd != Long.MAX_VALUE && earliestEnd <= millisOf(d, 23 * 60 + 59)) {
                break
            }
        }

        if (earliestSlot == null || earliestEnd == Long.MAX_VALUE) return

        val pi = alarmIntent(c, earliestEnd)
        try {
            if (Reminders.canScheduleExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliestEnd, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliestEnd, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliestEnd, pi)
        }
    }

    /** Called on logout. */
    fun clear(c: Context) {
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(c))
    }

    /** The alarm fired: prompt for every class that ended at [endAt], then arm the next alarm. */
    fun onAlarm(c: Context, endAt: Long) {
        if (enabled(c) && endAt > 0) {
            val cal = Calendar.getInstance().apply { timeInMillis = endAt }
            val d = cal.get(Calendar.DAY_OF_WEEK)
            val day = if (d == Calendar.SUNDAY) 7 else d - 1
            val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

            val today = epochDayOf(endAt)
            val offs = Reminders.loadDaysOff(c).filter { it.covers(today) }
            val hasExam = isExamDay(ExamReminders.loadExams(c), today)
            when {
                // Exam days: no classes scheduled, nothing to mark.
                hasExam -> {}
                // Holiday (or leave that isn't counted): nothing to mark.
                offs.any { it.kind == OFF_HOLIDAY || !it.leavePresent } -> {}
                // Leave that counts as present: add it instead of asking.
                offs.isNotEmpty() -> catchUpLeaves(c)
                else -> {
                    val regular = Reminders.loadSlots(c)
                    val temp = Reminders.loadTempSchedule(c)
                    effectiveSlotsForDay(regular, temp, today)
                        .filter { it.day == day && it.end == minute && TodayMarks.get(c, it.id, today) == null }
                        .forEach { showPrompt(c, it) }
                }
            }
        }
        scheduleNext(c, maxOf(System.currentTimeMillis(), endAt))
    }

    /** Adds leave classes that have ended since the last time (see applyLeaves), and backs the result up. */
    private fun catchUpLeaves(c: Context) {
        val uid = Firebase.auth.currentUser?.uid ?: return
        val store = SubjectStore(c, uid)
        val base = store.loadLocal()
        val updated = applyLeaves(base)
        if (updated !== base) {
            store.saveLocal(updated)
            store.saveCloud(updated) { }
        }
    }

    // ---------- Notifications ----------

    private fun createChannel(c: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_HIGH is what lets the notification "float" on screen (heads-up).
            val channel = NotificationChannel(CHANNEL, "Mark attendance after class", NotificationManager.IMPORTANCE_HIGH)
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun canNotify(c: Context) =
        Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notifId(slot: ClassSlot) = slot.id.hashCode()

    /** Removes the prompt / confirmation notification of this class (used when it was marked from the widget). */
    fun dismiss(c: Context, slot: ClassSlot) {
        NotificationManagerCompat.from(c).cancel(notifId(slot))
    }

    /** Finds a timetable class by id: the copy kept for alarms first, then the saved data. */
    fun slotById(c: Context, id: Long): ClassSlot? {
        Reminders.loadSlots(c).firstOrNull { it.id == id }?.let { return it }
        Reminders.loadTempSchedule(c)?.slots?.firstOrNull { it.id == id }?.let { return it }
        val uid = Firebase.auth.currentUser?.uid ?: return null
        val data = SubjectStore(c, uid).loadLocal()
        val sem = data.semesters.firstOrNull { it.id == data.selectedId } ?: return null
        sem.slots.firstOrNull { it.id == id }?.let { return it }
        return sem.tempSchedule?.slots?.firstOrNull { it.id == id }
    }

    private fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0,
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * Intent for one notification button. Android treats two intents as the same one when only their extras differ,
     * so each button gets its own data URI. That keeps every button's PendingIntent separate.
     */
    private fun actionIntent(c: Context, slot: ClassSlot, action: String): PendingIntent {
        val intent = Intent(c, AttendanceActionReceiver::class.java)
            .setData(Uri.parse("bunkr://mark/${slot.id}/$action"))
            .putExtra("slotId", slot.id)
            .putExtra("action", action)
            .putExtra("day", epochDayOf(System.currentTimeMillis())) // the day this class happened
        return PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** "<Subject> is over. Were you present?" with PRESENT and ABSENT buttons. */
    @SuppressLint("MissingPermission")
    fun showPrompt(c: Context, slot: ClassSlot) {
        if (!canNotify(c)) return
        createChannel(c)
        val notification = NotificationDesign.buildClassPrompt(
            c = c,
            slot = slot,
            channel = CHANNEL,
            openApp = openApp(c),
            presentIntent = actionIntent(c, slot, ACTION_PRESENT),
            absentIntent = actionIntent(c, slot, ACTION_ABSENT),
            cancelledIntent = actionIntent(c, slot, ACTION_CANCELLED)
        ).build()
        NotificationManagerCompat.from(c).notify(notifId(slot), notification)
    }

    /** "Class cancelled": nothing is added to attended or total. UNDO brings the question back. */
    @SuppressLint("MissingPermission")
    fun showCancelled(c: Context, slot: ClassSlot) {
        if (!canNotify(c)) return
        createChannel(c)
        val notification = NotificationDesign.buildClassCancelled(
            c = c,
            slot = slot,
            channel = CHANNEL,
            openApp = openApp(c),
            undoIntent = actionIntent(c, slot, ACTION_UNDO_CANCELLED)
        ).build()
        NotificationManagerCompat.from(c).notify(notifId(slot), notification)
    }

    /** Replaces the prompt with "Marked present: 41/50 (82%) ..." and an UNDO button. Disappears by itself. */
    @SuppressLint("MissingPermission")
    fun showResult(c: Context, slot: ClassSlot, action: String, marked: Marked) {
        if (!canNotify(c)) return
        createChannel(c)
        val present = action == ACTION_PRESENT
        val undoAction = if (present) ACTION_UNDO_PRESENT else ACTION_UNDO_ABSENT
        val notification = NotificationDesign.buildClassResult(
            c = c,
            slot = slot,
            action = action,
            marked = marked,
            channel = CHANNEL,
            openApp = openApp(c),
            undoIntent = actionIntent(c, slot, undoAction)
        ).build()
        NotificationManagerCompat.from(c).notify(notifId(slot), notification)
    }

    /** Shown when a button can't be applied (not logged in, or no matching subject). */
    @SuppressLint("MissingPermission")
    fun showProblem(c: Context, slot: ClassSlot, message: String) {
        if (!canNotify(c)) return
        createChannel(c)
        val notification = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFFF4444.toInt())
            .setContentTitle("Couldn't mark ${slot.subject}")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp(c))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(c).notify(notifId(slot), notification)
    }

    // ---------- Updating the saved attendance ----------

    data class Marked(val data: AppData, val subjectName: String, val record: Record)

    /**
     * Applies one button press to the saved data (selected semester) and saves it locally.
     * Returns null when the class can't be matched to a subject that has that section (theory / lab).
     */
    fun applyAction(store: SubjectStore, slot: ClassSlot, action: String, day: Long = epochDayOf(System.currentTimeMillis())): Marked? {
        val data = store.loadLocal()
        val sem = data.semesters.firstOrNull { it.id == data.selectedId } ?: return null

        // Same name matching the timetable import uses ("DSA" -> "Data Structures").
        val linked = linkSubject(slot.subject, sem.subjects.map { it.name })
        val sub = sem.subjects.firstOrNull { it.name == linked } ?: return null
        val rec = (if (slot.isLab) sub.lab else sub.theory) ?: return null

        val newRec = when (action) {
            ACTION_PRESENT -> rec.present()
            ACTION_ABSENT -> rec.absent()
            ACTION_UNDO_PRESENT -> rec.undoPresent()
            else -> rec.undoAbsent()
        }
        val newSub = if (slot.isLab) sub.copy(lab = newRec) else sub.copy(theory = newRec)

        // Record attendance log for day-by-day history
        val logStatus = when (action) {
            ACTION_PRESENT -> "P"
            ACTION_ABSENT -> "A"
            ACTION_CANCELLED -> "C"
            else -> null
        }
        val updatedLogs = if (logStatus == null) {
            sem.attendanceLogs.filter { !(it.day == day && it.subjectId == sub.id && it.isLab == slot.isLab && it.slotId == slot.id) }
        } else {
            val exist = sem.attendanceLogs.firstOrNull { it.day == day && it.subjectId == sub.id && it.isLab == slot.isLab && it.slotId == slot.id }
            if (exist != null) sem.attendanceLogs.map { if (it.id == exist.id) it.copy(status = logStatus) else it }
            else sem.attendanceLogs + AttendanceLog(id = System.currentTimeMillis(), day = day, subjectId = sub.id, isLab = slot.isLab, status = logStatus, slotId = slot.id)
        }

        val updatedSem = sem.copy(
            subjects = sem.subjects.map { if (it.id == sub.id) newSub else it },
            attendanceLogs = updatedLogs
        )
        val newData = data.copy(
            semesters = data.semesters.map { s -> if (s.id == sem.id) updatedSem else s }
        )
        store.saveLocal(newData)
        return Marked(newData, sub.name, newRec)
    }
}

/** Fires when a class ends. Register in AndroidManifest.xml. */
class ClassEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Stay alive a few seconds so a leave backup to Firestore can be queued before the process is frozen.
        val pending = goAsync()
        try {
            ClassEndAlerts.onAlarm(context.applicationContext, intent.getLongExtra("endAt", 0L))
        } finally {
            Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 3_000)
        }
    }
}

/** Handles the PRESENT / ABSENT / CANCELLED / UNDO buttons of the notification and of the home screen widget. */
class AttendanceActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val slotId = intent.getLongExtra("slotId", -1L)
        val action = intent.getStringExtra("action") ?: return
        val fromWidget = intent.getStringExtra("from") == "widget"
        val slot = ClassEndAlerts.slotById(app, slotId) ?: return
        // The day the class took place (set when the button was made), so a late tap is filed under that day.
        val day = intent.getLongExtra("day", epochDayOf(System.currentTimeMillis()))

        // CANCELLED and its UNDO never change the saved attendance.
        if (action == ClassEndAlerts.ACTION_CANCELLED || action == ClassEndAlerts.ACTION_UNDO_CANCELLED) {
            val cancelled = action == ClassEndAlerts.ACTION_CANCELLED
            TodayMarks.set(app, slot.id, day, if (cancelled) "C" else null)
            BunkrWidget.refreshAll(app)
            when {
                fromWidget -> ClassEndAlerts.dismiss(app, slot)
                cancelled -> ClassEndAlerts.showCancelled(app, slot)
                else -> ClassEndAlerts.showPrompt(app, slot)
            }
            return
        }

        val uid = Firebase.auth.currentUser?.uid
        if (uid == null) {
            ClassEndAlerts.showProblem(app, slot, "You're logged out. Open BUNKR and log in, then mark it there.")
            return
        }

        // Keep the process alive until the cloud backup is queued.
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        fun finish() {
            if (finished.compareAndSet(false, true)) pending.finish()
        }

        try {
            val store = SubjectStore(app, uid)
            val marked = ClassEndAlerts.applyAction(store, slot, action, day)
            if (marked == null) {
                ClassEndAlerts.showProblem(
                    app, slot,
                    "No matching ${if (slot.isLab) "lab" else "theory"} subject for \"${slot.subject}\" in your attendance list. Tap to open BUNKR."
                )
                finish()
                return
            }

            val undo = action == ClassEndAlerts.ACTION_UNDO_PRESENT || action == ClassEndAlerts.ACTION_UNDO_ABSENT
            TodayMarks.set(
                app, slot.id, day,
                when (action) {
                    ClassEndAlerts.ACTION_PRESENT -> "P"
                    ClassEndAlerts.ACTION_ABSENT -> "A"
                    else -> null
                }
            )
            BunkrWidget.refreshAll(app)

            when {
                fromWidget -> ClassEndAlerts.dismiss(app, slot) // the widget already shows the result
                undo -> ClassEndAlerts.showPrompt(app, slot)    // after an undo, ask again
                else -> ClassEndAlerts.showResult(app, slot, action, marked)
            }

            // The local save above is what counts. Firestore queues this write on disk even when offline,
            // so don't wait for the server forever.
            store.saveCloud(marked.data) { finish() }
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 8_000)
        } catch (e: Exception) {
            finish()
        }
    }
}