package com.bunkr.attendance

import android.app.PendingIntent
import android.content.Context
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import kotlin.math.roundToInt

/**
 * Creates custom, Neobrutalist-styled notifications for Bunkr with bold borders,
 * colored tags, offset cards, and action buttons, while maintaining full accessibility
 * and system compatibility.
 */
object NotificationDesign {

    private const val COLOR_LIME = 0xFFB8F34A.toInt()
    private const val COLOR_PINK = 0xFFFF70A6.toInt()
    private const val COLOR_SKY = 0xFF7CD5F8.toInt()
    private const val COLOR_YELLOW = 0xFFFFE500.toInt()

    fun buildClassPrompt(
        c: Context,
        slot: ClassSlot,
        channel: String,
        openApp: PendingIntent,
        presentIntent: PendingIntent,
        absentIntent: PendingIntent,
        cancelledIntent: PendingIntent
    ): NotificationCompat.Builder {
        val kindText = if (slot.isLab) "LAB" else "THEORY"
        val subjectUpper = slot.subject.ifBlank { "CLASS" }.uppercase()
        val details = "Ended at ${formatTime(slot.end)}${if (slot.room.isNotBlank()) " · Room ${slot.room}" else ""}"

        val collapsed = RemoteViews(c.packageName, R.layout.notif_class_prompt_collapsed).apply {
            setTextViewText(R.id.notif_tag_kind, kindText)
            setTextViewText(R.id.notif_subject, subjectUpper)
            setTextViewText(R.id.notif_subtitle, "Were you present? Tap to mark")
        }

        val expanded = RemoteViews(c.packageName, R.layout.notif_class_prompt).apply {
            setTextViewText(R.id.notif_tag_kind, kindText)
            setTextViewText(R.id.notif_subject, subjectUpper)
            setTextViewText(R.id.notif_subtitle, details)

            setOnClickPendingIntent(R.id.notif_btn_present, presentIntent)
            setOnClickPendingIntent(R.id.notif_btn_absent, absentIntent)
            setOnClickPendingIntent(R.id.notif_btn_cancelled, cancelledIntent)
        }

        return NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(COLOR_LIME)
            .setContentTitle("${slot.subject} ($kindText) is over")
            .setContentText("Were you present?")
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(0, "PRESENT", presentIntent)
            .addAction(0, "ABSENT", absentIntent)
            .addAction(0, "CANCELLED", cancelledIntent)
    }

    fun buildClassResult(
        c: Context,
        slot: ClassSlot,
        action: String,
        marked: ClassEndAlerts.Marked,
        channel: String,
        openApp: PendingIntent,
        undoIntent: PendingIntent
    ): NotificationCompat.Builder {
        val present = action == ClassEndAlerts.ACTION_PRESENT
        val statusText = if (present) "MARKED PRESENT" else "MARKED ABSENT"
        val kindText = if (slot.isLab) "LAB" else "THEORY"
        val r = marked.record
        val statsText = "${r.attended}/${r.total} (${r.percent.roundToInt()}%)  ·  ${r.verdict().first}"
        val subjectUpper = marked.subjectName.ifBlank { slot.subject }.uppercase()

        val collapsed = RemoteViews(c.packageName, R.layout.notif_class_result_collapsed).apply {
            setTextViewText(R.id.notif_status_tag, statusText)
            if (!present) {
                setInt(R.id.notif_status_tag, "setBackgroundResource", R.drawable.notif_tag_pink)
            }
            setTextViewText(R.id.notif_subject, subjectUpper)
            setTextViewText(R.id.notif_stats, statsText)
        }

        val expanded = RemoteViews(c.packageName, R.layout.notif_class_result).apply {
            setTextViewText(R.id.notif_status_tag, statusText)
            if (!present) {
                setInt(R.id.notif_status_tag, "setBackgroundResource", R.drawable.notif_tag_pink)
            }
            setTextViewText(R.id.notif_kind_tag, kindText)
            setTextViewText(R.id.notif_subject, subjectUpper)
            setTextViewText(R.id.notif_stats, statsText)
            setOnClickPendingIntent(R.id.notif_btn_undo, undoIntent)
        }

        return NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(if (present) COLOR_LIME else COLOR_PINK)
            .setContentTitle("${if (present) "Present" else "Absent"}: ${marked.subjectName} ($kindText)")
            .setContentText(statsText)
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(30_000)
            .setAutoCancel(true)
            .addAction(0, "UNDO", undoIntent)
    }

    fun buildClassCancelled(
        c: Context,
        slot: ClassSlot,
        channel: String,
        openApp: PendingIntent,
        undoIntent: PendingIntent
    ): NotificationCompat.Builder {
        val kindText = if (slot.isLab) "LAB" else "THEORY"
        val subjectUpper = slot.subject.ifBlank { "CLASS" }.uppercase()
        val message = "Not counted. Your attendance is unchanged."

        val collapsed = RemoteViews(c.packageName, R.layout.notif_class_result_collapsed).apply {
            setTextViewText(R.id.notif_status_tag, "CANCELLED")
            setInt(R.id.notif_status_tag, "setBackgroundResource", R.drawable.notif_tag_yellow)
            setTextViewText(R.id.notif_subject, subjectUpper)
            setTextViewText(R.id.notif_stats, message)
        }

        val expanded = RemoteViews(c.packageName, R.layout.notif_class_result).apply {
            setTextViewText(R.id.notif_status_tag, "CANCELLED")
            setInt(R.id.notif_status_tag, "setBackgroundResource", R.drawable.notif_tag_yellow)
            setTextViewText(R.id.notif_kind_tag, kindText)
            setTextViewText(R.id.notif_subject, subjectUpper)
            setTextViewText(R.id.notif_stats, message)
            setOnClickPendingIntent(R.id.notif_btn_undo, undoIntent)
        }

        return NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(COLOR_YELLOW)
            .setContentTitle("Cancelled: ${slot.subject} ($kindText)")
            .setContentText(message)
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(30_000)
            .setAutoCancel(true)
            .addAction(0, "UNDO", undoIntent)
    }

    fun buildClassReminder(
        c: Context,
        slot: ClassSlot,
        startAt: Long,
        channel: String,
        openApp: PendingIntent
    ): NotificationCompat.Builder {
        val mins = ((startAt - System.currentTimeMillis()) / 60_000).toInt().coerceAtLeast(1)
        val countdownText = if (mins <= 1) "STARTING NOW" else "IN $mins MIN"
        val kindText = if (slot.isLab) "LAB" else "THEORY"
        val subjectUpper = slot.subject.ifBlank { "CLASS" }.uppercase()
        val details = buildString {
            append("Starts at ").append(formatTime(slot.start))
            if (slot.room.isNotBlank()) append("  ·  Room ").append(slot.room)
            if (slot.teacher.isNotBlank()) append("  ·  ").append(slot.teacher)
        }

        val collapsed = RemoteViews(c.packageName, R.layout.notif_class_reminder_collapsed).apply {
            setTextViewText(R.id.notif_countdown, countdownText)
            setTextViewText(R.id.notif_subject, subjectUpper)
            setTextViewText(R.id.notif_time, details)
        }

        val expanded = RemoteViews(c.packageName, R.layout.notif_class_reminder).apply {
            setTextViewText(R.id.notif_countdown, countdownText)
            setTextViewText(R.id.notif_kind, kindText)
            setTextViewText(R.id.notif_subject, subjectUpper)
            setTextViewText(R.id.notif_time, details)
        }

        return NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(COLOR_SKY)
            .setContentTitle("Next class: ${slot.subject} ($kindText)")
            .setContentText(details)
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
    }

    fun buildExamReminder(
        c: Context,
        e: Exam,
        lead: Int,
        channel: String,
        openApp: PendingIntent
    ): NotificationCompat.Builder {
        val whenText = when (lead) {
            LEAD_WEEK -> "IN 1 WEEK"
            LEAD_3D -> "IN 3 DAYS"
            LEAD_1D -> "TOMORROW"
            else -> if (e.start >= 0) "TODAY AT ${formatTime(e.start)}" else "TODAY"
        }
        val typeText = EXAM_LABELS.getOrNull(e.type) ?: "EXAM"
        val subjectUpper = e.subject.ifBlank { "EXAM" }.uppercase()
        val details = buildString {
            append(dateText(e.day))
            if (e.start >= 0) append("  ·  ").append(examTimeText(e))
            if (e.note.isNotBlank()) append("  ·  ").append(e.note)
        }

        val collapsed = RemoteViews(c.packageName, R.layout.notif_exam_reminder_collapsed).apply {
            setTextViewText(R.id.notif_lead, whenText)
            setTextViewText(R.id.notif_subject, subjectUpper)
            setTextViewText(R.id.notif_details, details)
        }

        val expanded = RemoteViews(c.packageName, R.layout.notif_exam_reminder).apply {
            setTextViewText(R.id.notif_lead, whenText)
            setTextViewText(R.id.notif_type, typeText)
            setTextViewText(R.id.notif_subject, subjectUpper)
            setTextViewText(R.id.notif_details, details)
        }

        return NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(COLOR_PINK)
            .setContentTitle("${e.subject} $typeText ($whenText)")
            .setContentText(details)
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
    }
}
