package com.bunkr.attendance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slotId = intent.getLongExtra("slotId", -1L)
        val startAt = intent.getLongExtra("startAt", 0L)
        val targetDay = epochDayOf(if (startAt > 0) startAt else System.currentTimeMillis())
        val hasExam = isExamDay(ExamReminders.loadExams(context), targetDay)
        val isOff = isDayOff(Reminders.loadDaysOff(context), targetDay)
        val slot = Reminders.loadSlots(context).firstOrNull { it.id == slotId }
            ?: Reminders.loadTempSchedule(context)?.slots?.firstOrNull { it.id == slotId }
        if (slot != null && Reminders.enabled(context) && !hasExam && !isOff && System.currentTimeMillis() < startAt) {
            Reminders.showNotification(context, slot, startAt)
        }
        Reminders.scheduleNext(context)
    }
}
