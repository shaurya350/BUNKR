package com.bunkr.attendance

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.util.Calendar

/**
 * Which classes were already marked (from a notification or the widget), so the widget can show
 * "PRESENT / ABSENT / CANCELLED" instead of asking again. Marks made inside the app screens are not tracked here.
 * Status: "P" = present, "A" = absent, "C" = cancelled.
 */
object TodayMarks {
    private fun prefs(c: Context) = c.getSharedPreferences("bunkr_marks", Context.MODE_PRIVATE)
    private fun key(slotId: Long, day: Long) = "$slotId|$day"

    fun get(c: Context, slotId: Long, day: Long): String? = prefs(c).getString(key(slotId, day), null)

    fun set(c: Context, slotId: Long, day: Long, status: String?) {
        val p = prefs(c)
        val e = p.edit()
        if (status == null) e.remove(key(slotId, day)) else e.putString(key(slotId, day), status)
        // forget marks older than two weeks
        p.all.keys
            .filter { (it.substringAfter('|').toLongOrNull() ?: Long.MAX_VALUE) < day - 14 }
            .forEach { e.remove(it) }
        e.apply()
    }

    fun clear(c: Context) {
        prefs(c).edit().clear().apply()
    }
}

/**
 * Home screen widget: today's classes with P / A / C buttons, your overall percentage and the subject
 * with the lowest attendance. Built with plain RemoteViews (no extra libraries).
 */
class BunkrWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { update(context, appWidgetManager, it) }
        scheduleNextRefresh(context)
    }

    // Called when the widget is resized, so more or fewer classes can be shown.
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        update(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refreshAll(context)
    }

    override fun onDisabled(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(refreshIntent(context))
    }

    companion object {
        const val ACTION_REFRESH = "com.bunkr.attendance.WIDGET_REFRESH"
        private const val REFRESH_REQUEST = 5001

        /** Redraws every BUNKR widget on the home screen. Cheap when there are none. */
        fun refreshAll(c: Context) {
            val app = c.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, BunkrWidget::class.java))
            if (ids.isEmpty()) return
            ids.forEach { update(app, manager, it) }
            scheduleNextRefresh(app)
        }

        private fun update(c: Context, manager: AppWidgetManager, id: Int) {
            manager.updateAppWidget(id, buildViews(c, manager.getAppWidgetOptions(id)))
        }

        private fun refreshIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
            c, REFRESH_REQUEST,
            Intent(c, BunkrWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /** Redraw when the next class starts or ends (or just after midnight), so the buttons appear on time. */
        private fun scheduleNextRefresh(c: Context) {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val now = System.currentTimeMillis()
            val today = epochDayOf(now)
            var next = millisOf(today + 1, 1) // one minute past midnight
            val regular = Reminders.loadSlots(c)
            val temp = Reminders.loadTempSchedule(c)
            if (!isExamDay(ExamReminders.loadExams(c), today)) {
                effectiveSlotsForDay(regular, temp, today).forEach { s ->
                    for (minute in listOf(s.start, s.end)) {
                        val t = millisOf(today, minute)
                        if (t > now && t < next) next = t
                    }
                }
            }
            // Not exact on purpose: a widget a minute late is fine and this needs no special permission.
            am.setAndAllowWhileIdle(AlarmManager.RTC, next, refreshIntent(c))
        }

        // ---------- Drawing ----------

        /** How many class rows fit: about 42dp each below roughly 72dp of header and footer. */
        private fun maxRows(options: Bundle?): Int {
            val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            return if (heightDp <= 0) 3 else ((heightDp - 72) / 42).coerceIn(1, 6)
        }

        private fun showMessage(views: RemoteViews, text: String): RemoteViews {
            views.setViewVisibility(R.id.widget_message, View.VISIBLE)
            views.setViewVisibility(R.id.widget_rows, View.GONE)
            views.setTextViewText(R.id.widget_message, text)
            return views
        }

        private fun actionPi(c: Context, slot: ClassSlot, day: Long, action: String): PendingIntent {
            // A different address than the notification buttons, so the two never share a PendingIntent.
            val intent = Intent(c, AttendanceActionReceiver::class.java)
                .setData(Uri.parse("bunkr://widget/${slot.id}/$action"))
                .putExtra("slotId", slot.id)
                .putExtra("action", action)
                .putExtra("day", day)
                .putExtra("from", "widget")
            return PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun buildViews(c: Context, options: Bundle?): RemoteViews {
            val views = RemoteViews(c.packageName, R.layout.widget_bunkr)

            // Tapping the widget background opens the app.
            val open = PendingIntent.getActivity(
                c, 1,
                Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)

            val uid = Firebase.auth.currentUser?.uid
                ?: return showMessage(views, "Open BUNKR and log in.")
            val data = SubjectStore(c, uid).loadLocal()
            val sem = data.semesters.firstOrNull { it.id == data.selectedId } ?: data.semesters.firstOrNull()
                ?: return showMessage(views, "Open BUNKR to set up a semester.")

            // ----- Overall percentage and lowest subject -----
            val sections = sem.subjects
                .flatMap { s ->
                    listOfNotNull(
                        s.theory?.let { Triple(s.name, false, it) },
                        s.lab?.let { Triple(s.name, true, it) }
                    )
                }
                .filter { it.third.total > 0 }

            if (sections.isEmpty()) {
                views.setTextViewText(R.id.widget_overall, "--")
                views.setTextViewText(R.id.widget_footer, "NO ATTENDANCE YET")
                views.setInt(R.id.widget_footer, "setBackgroundResource", R.drawable.widget_white)
            } else {
                val sumA = sections.sumOf { it.third.attended }
                val sumT = sections.sumOf { it.third.total }
                val overall = pct(sumA, sumT)
                val requiredAvg = sections.map { it.third.required }.average()
                views.setTextViewText(R.id.widget_overall, "${Math.round(overall)}%")
                views.setInt(
                    R.id.widget_overall, "setBackgroundResource",
                    if (overall >= requiredAvg) R.drawable.widget_lime else R.drawable.widget_red
                )

                val (name, isLab, rec) = sections.minByOrNull { it.third.percent }!!
                views.setTextViewText(
                    R.id.widget_footer,
                    "LOWEST: ${name.uppercase()}${if (isLab) " LAB" else ""} ${Math.round(rec.percent)}%"
                )
                views.setInt(
                    R.id.widget_footer, "setBackgroundResource",
                    when {
                        !rec.isSafe -> R.drawable.widget_red
                        rec.canBunk == 0 -> R.drawable.widget_yellow
                        else -> R.drawable.widget_lime
                    }
                )
            }

            // ----- Today's classes -----
            val now = System.currentTimeMillis()
            val today = epochDayOf(now)
            val cal = Calendar.getInstance()
            val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

            val offs = sem.daysOff.filter { it.covers(today) }
            offs.firstOrNull { it.kind == OFF_HOLIDAY }?.let {
                return showMessage(views, "HOLIDAY: ${it.name.uppercase()}")
            }
            offs.firstOrNull()?.let {
                val note = if (it.leavePresent) "counts as present" else "not counted"
                return showMessage(views, "ON LEAVE: ${it.name.uppercase()}\n($note)")
            }

            val todaysExams = sem.exams.filter { it.day == today }
            if (todaysExams.isNotEmpty()) {
                val examNames = todaysExams.joinToString(", ") { "${it.subject} (${EXAM_LABELS[it.type]})" }
                return showMessage(views, "EXAM TODAY:\n$examNames")
            }

            val todays = effectiveSlotsForDay(sem.slots, sem.tempSchedule, today).sortedBy { it.start }
            if (todays.isEmpty()) return showMessage(views, "NO CLASSES TODAY")

            class Item(val slot: ClassSlot, val status: String?, val ended: Boolean)

            val items = todays.map { Item(it, TodayMarks.get(c, it.id, today), nowMinutes >= it.end) }
            // If not everything fits: classes waiting to be marked first, then upcoming ones, then finished ones.
            fun priority(i: Item) = when {
                i.ended && i.status == null -> 0
                !i.ended -> 1
                else -> 2
            }
            val shown = items
                .sortedWith(compareBy({ priority(it) }, { it.slot.start }))
                .take(maxRows(options))
                .sortedBy { it.slot.start }

            views.setViewVisibility(R.id.widget_message, View.GONE)
            views.setViewVisibility(R.id.widget_rows, View.VISIBLE)
            views.removeAllViews(R.id.widget_rows)

            val names = sem.subjects.map { it.name }
            for (item in shown) {
                val linked = linkSubject(item.slot.subject, names)
                val sub = sem.subjects.firstOrNull { it.name == linked }
                val rec = if (item.slot.isLab) sub?.lab else sub?.theory
                views.addView(R.id.widget_rows, buildRow(c, item.slot, item.status, item.ended, today, rec))
            }

            // Some classes did not fit: say how many, and that stretching the widget shows more.
            val hidden = items.size - shown.size
            if (hidden > 0) views.setTextViewText(R.id.widget_title, "BUNKR  ·  TODAY (+$hidden MORE)")
            return views
        }

        private fun buildRow(c: Context, slot: ClassSlot, status: String?, ended: Boolean, today: Long, rec: Record?): RemoteViews {
            val row = RemoteViews(c.packageName, R.layout.widget_row)
            row.setTextViewText(R.id.row_title, "${slot.subject} (${if (slot.isLab) "LAB" else "TH"})")

            val time = "${formatTime(slot.start)} - ${formatTime(slot.end)}"
            val tail = when (status) {
                "P" -> "PRESENT"
                "A" -> "ABSENT"
                "C" -> "CANCELLED"
                else -> if (rec != null && rec.total > 0) "${Math.round(rec.percent)}%" else ""
            }
            row.setTextViewText(R.id.row_sub, if (tail.isEmpty()) time else "$time  ·  $tail")

            when {
                status != null -> {
                    // Already marked: one UNDO button.
                    row.setViewVisibility(R.id.row_btn_a, View.GONE)
                    row.setViewVisibility(R.id.row_btn_c, View.GONE)
                    row.setTextViewText(R.id.row_btn_p, "UNDO")
                    row.setInt(R.id.row_btn_p, "setBackgroundResource", R.drawable.widget_white)
                    val undo = when (status) {
                        "P" -> ClassEndAlerts.ACTION_UNDO_PRESENT
                        "A" -> ClassEndAlerts.ACTION_UNDO_ABSENT
                        else -> ClassEndAlerts.ACTION_UNDO_CANCELLED
                    }
                    row.setOnClickPendingIntent(R.id.row_btn_p, actionPi(c, slot, today, undo))
                }
                ended -> {
                    row.setOnClickPendingIntent(R.id.row_btn_p, actionPi(c, slot, today, ClassEndAlerts.ACTION_PRESENT))
                    row.setOnClickPendingIntent(R.id.row_btn_a, actionPi(c, slot, today, ClassEndAlerts.ACTION_ABSENT))
                    row.setOnClickPendingIntent(R.id.row_btn_c, actionPi(c, slot, today, ClassEndAlerts.ACTION_CANCELLED))
                }
                else -> {
                    // Class not over yet: nothing to mark.
                    row.setViewVisibility(R.id.row_btn_p, View.GONE)
                    row.setViewVisibility(R.id.row_btn_a, View.GONE)
                    row.setViewVisibility(R.id.row_btn_c, View.GONE)
                }
            }
            return row
        }
    }
}
