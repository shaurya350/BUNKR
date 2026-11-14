package com.example.attendance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slotId = intent.getLongExtra("slotId", -1L)
        val startAt = intent.getLongExtra("startAt", 0L)
        val slot = Reminders.loadSlots(context).firstOrNull { it.id == slotId }
        if (slot != null && Reminders.enabled(context) && System.currentTimeMillis() < startAt) {
            Reminders.showNotification(context, slot, startAt)
        }
        Reminders.scheduleNext(context)
    }
}
