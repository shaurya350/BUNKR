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
 */
object ClassEndAlerts {
    private const val PREFS = "bunkwise_class_end"
    private const val CHANNEL = "mark_attendance"
    private const val ALARM_REQUEST = 4002

    const val ACTION_PRESENT = "P"
    const val ACTION_ABSENT = "A"
    const val ACTION_UNDO_PRESENT = "UP"
    const val ACTION_UNDO_ABSENT = "UA"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("enabled", on).apply()
    }

    // ---------- Scheduling ----------

    /** The next moment this class ENDS, strictly after [from] (epoch millis). Same logic as nextStartMillis. */
    private fun nextEndMillis(slot: ClassSlot, from: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = from
        cal.set(Calendar.HOUR_OF_DAY, slot.end / 60)
        cal.set(Calendar.MINUTE, slot.end % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val calDay = if (slot.day == 7) Calendar.SUNDAY else slot.day + 1 // Calendar: Sunday = 1, Monday = 2 ...
        cal.add(Calendar.DAY_OF_YEAR, (calDay - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7)
        if (cal.timeInMillis <= from) cal.add(Calendar.DAY_OF_YEAR, 7)
        return cal.timeInMillis
    }

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

        val next = Reminders.loadSlots(c)
            .filter { it.end > it.start }
            .map { it to nextEndMillis(it, from) }
            .minByOrNull { it.second } ?: return

        val pi = alarmIntent(c, next.second)
        try {
            if (Reminders.canScheduleExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.second, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.second, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.second, pi)
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
            Reminders.loadSlots(c).filter { it.day == day && it.end == minute }.forEach { showPrompt(c, it) }
        }
        scheduleNext(c, maxOf(System.currentTimeMillis(), endAt))
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

    private fun notifId(slot: ClassSlot) = slot.id.hashCode()

    private fun kindOf(slot: ClassSlot) = if (slot.isLab) "Lab" else "Theory"

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
        return PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** "<Subject> is over. Were you present?" with PRESENT and ABSENT buttons. */
    @SuppressLint("MissingPermission")
    fun showPrompt(c: Context, slot: ClassSlot) {
        if (!canNotify(c)) return
        createChannel(c)
        val notification = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm) // swap for your own icon later
            .setContentTitle("${slot.subject} (${kindOf(slot)}) is over")
            .setContentText("Were you present?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(c))
            .addAction(0, "PRESENT", actionIntent(c, slot, ACTION_PRESENT))
            .addAction(0, "ABSENT", actionIntent(c, slot, ACTION_ABSENT))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(c).notify(notifId(slot), notification)
    }

    /** Replaces the prompt with "Marked present: 41/50 (82%) ..." and an UNDO button. Disappears by itself. */
    @SuppressLint("MissingPermission")
    fun showResult(c: Context, slot: ClassSlot, action: String, marked: Marked) {
        if (!canNotify(c)) return
        createChannel(c)
        val present = action == ACTION_PRESENT
        val r = marked.record
        val text = "${r.attended}/${r.total} (${Math.round(r.percent)}%). ${r.verdict().first}"
        val notification = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("${if (present) "Present" else "Absent"}: ${marked.subjectName} (${kindOf(slot)})")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true) // no second sound / pop-up for this update
            .setContentIntent(openApp(c))
            .addAction(0, "UNDO", actionIntent(c, slot, if (present) ACTION_UNDO_PRESENT else ACTION_UNDO_ABSENT))
            .setTimeoutAfter(30_000)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(c).notify(notifId(slot), notification)
    }

    /** Shown when a button can't be applied (not logged in, or no matching subject). */
    @SuppressLint("MissingPermission")
    fun showProblem(c: Context, slot: ClassSlot, message: String) {
        if (!canNotify(c)) return
        createChannel(c)
        val notification = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
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
    fun applyAction(store: SubjectStore, slot: ClassSlot, action: String): Marked? {
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
        val newData = data.copy(
            semesters = data.semesters.map { s ->
                if (s.id == sem.id) s.copy(subjects = s.subjects.map { if (it.id == sub.id) newSub else it }) else s
            }
        )
        store.saveLocal(newData)
        return Marked(newData, sub.name, newRec)
    }
}

/** Fires when a class ends. Register in AndroidManifest.xml. */
class ClassEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ClassEndAlerts.onAlarm(context.applicationContext, intent.getLongExtra("endAt", 0L))
    }
}

/** Handles the PRESENT / ABSENT / UNDO buttons. Register in AndroidManifest.xml. */
class AttendanceActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val slotId = intent.getLongExtra("slotId", -1L)
        val action = intent.getStringExtra("action") ?: return
        val slot = Reminders.loadSlots(app).firstOrNull { it.id == slotId } ?: return

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
            val marked = ClassEndAlerts.applyAction(store, slot, action)
            if (marked == null) {
                ClassEndAlerts.showProblem(
                    app, slot,
                    "No matching ${if (slot.isLab) "lab" else "theory"} subject for \"${slot.subject}\" in your attendance list. Tap to open BUNKR."
                )
                finish()
                return
            }

            if (action == ClassEndAlerts.ACTION_UNDO_PRESENT || action == ClassEndAlerts.ACTION_UNDO_ABSENT) {
                ClassEndAlerts.showPrompt(app, slot) // after an undo, ask again
            } else {
                ClassEndAlerts.showResult(app, slot, action, marked)
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
