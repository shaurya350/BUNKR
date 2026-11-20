package com.bunkr.attendance

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Attendance history record for a single class on a specific date.
 * status: "P" = Present, "A" = Absent, "C" = Cancelled
 */
data class AttendanceLog(
    val id: Long = System.currentTimeMillis(),
    val day: Long,            // epoch day
    val subjectId: Long,      // matches Subject.id
    val isLab: Boolean,
    val status: String,       // "P", "A", "C"
    val slotId: Long? = null,
    val note: String = ""
)

fun encodeAttendanceLog(l: AttendanceLog): String =
    "${l.id}|${l.day}|${l.subjectId}|${if (l.isLab) 1 else 0}|${l.status}|${l.slotId ?: -1L}|${cleanText(l.note)}"

fun decodeAttendanceLog(text: String): AttendanceLog? = runCatching {
    val p = text.removePrefix("~log|").split("|")
    val slot = p.getOrNull(5)?.toLongOrNull()
    AttendanceLog(
        id = p[0].toLong(),
        day = p[1].toLong(),
        subjectId = p[2].toLong(),
        isLab = p[3] == "1",
        status = p[4],
        slotId = if (slot != null && slot != -1L) slot else null,
        note = p.getOrElse(6) { "" }
    )
}.getOrNull()

/**
 * Logs or changes the attendance status for a subject section on a specific day,
 * and updates the corresponding Subject's attended/total Record accordingly.
 */
fun recordAttendanceLog(
    semester: Semester,
    day: Long,
    subjectId: Long,
    isLab: Boolean,
    newStatus: String?, // "P", "A", "C", or null (to remove log)
    slotId: Long? = null
): Semester {
    val existing = semester.attendanceLogs.firstOrNull {
        it.day == day && it.subjectId == subjectId && it.isLab == isLab && (slotId == null || it.slotId == slotId)
    }

    val oldStatus = existing?.status

    // If status didn't change, no-op
    if (oldStatus == newStatus) return semester

    // Update subject counters
    val updatedSubjects = semester.subjects.map { sub ->
        if (sub.id != subjectId) sub
        else {
            val rec = (if (isLab) sub.lab else sub.theory) ?: return@map sub
            var a = rec.attended
            var t = rec.total

            // Step 1: Revert old status effect
            when (oldStatus) {
                "P" -> {
                    if (a > 0) a -= 1
                    if (t > 0) t -= 1
                }
                "A" -> {
                    if (t > 0) t -= 1
                }
                // "C" or null had no effect on counts
            }

            // Step 2: Apply new status effect
            when (newStatus) {
                "P" -> {
                    a += 1
                    t += 1
                }
                "A" -> {
                    t += 1
                }
                // "C" or null doesn't add to counts
            }

            val newRec = rec.copy(attended = a.coerceAtLeast(0), total = t.coerceAtLeast(a.coerceAtLeast(0)))
            if (isLab) sub.copy(lab = newRec) else sub.copy(theory = newRec)
        }
    }

    // Update logs list
    val updatedLogs = if (newStatus == null) {
        if (existing != null) semester.attendanceLogs.filter { it.id != existing.id }
        else semester.attendanceLogs
    } else {
        if (existing != null) {
            semester.attendanceLogs.map { if (it.id == existing.id) it.copy(status = newStatus) else it }
        } else {
            semester.attendanceLogs + AttendanceLog(
                id = System.currentTimeMillis(),
                day = day,
                subjectId = subjectId,
                isLab = isLab,
                status = newStatus,
                slotId = slotId
            )
        }
    }

    return semester.copy(subjects = updatedSubjects, attendanceLogs = updatedLogs)
}

@Composable
fun AttendanceHistoryDialog(
    semester: Semester,
    onDismiss: () -> Unit,
    onUpdateSemester: (Semester) -> Unit
) {
    val context = LocalContext.current
    val today = remember { epochDayOf(System.currentTimeMillis()) }
    var selectedDay by remember { mutableStateOf(today) }
    var filterSubjectId by remember { mutableStateOf<Long?>(null) } // null = all

    // Quick range of past 14 days + today
    val pastDays = remember(today) { (0..14).map { today - it } }

    val daysExams = semester.exams.filter { it.day == selectedDay }
    val daysOff = semester.daysOff.filter { it.covers(selectedDay) }

    fun pickCustomDate() {
        val (y, m, d) = ymdOf(selectedDay)
        DatePickerDialog(context, { _, yy, mm, dd ->
            selectedDay = dayOfYmd(yy, mm, dd)
        }, y, m, d).show()
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.padding(end = 6.dp, bottom = 6.dp)) {
            Column(
                Modifier
                    .neoShadow(6.dp)
                    .background(Cream)
                    .border(3.dp, Ink)
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("ATTENDANCE HISTORY", fontWeight = FontWeight.Black, fontSize = 20.sp)
                        Text(
                            dateText(selectedDay),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Ink
                        )
                    }
                    Box(
                        Modifier
                            .background(Sky)
                            .border(2.dp, Ink)
                            .clickable { pickCustomDate() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("CALENDAR", fontWeight = FontWeight.Black, fontSize = 12.sp)
                    }
                }

                // Date chips row
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FieldLabel("SELECT DATE")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(pastDays) { d ->
                            val isSel = d == selectedDay
                            val label = when (d) {
                                today -> "TODAY"
                                today - 1 -> "Y'DAY"
                                else -> "${DAY_SHORT[weekdayOf(d) - 1]} ${dateText(d).substringAfter(" ")}"
                            }
                            SemChip(label, isSel, if (d == today) Lime else Lavender) {
                                selectedDay = d
                            }
                        }
                    }
                }

                // Day badges (Holidays, Leave, Exams)
                if (daysExams.isNotEmpty() || daysOff.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        daysExams.forEach { e ->
                            Tag("EXAM: ${e.subject} (${EXAM_LABELS[e.type]})", Pink)
                        }
                        daysOff.forEach { o ->
                            Tag(if (o.kind == OFF_HOLIDAY) "HOLIDAY: ${o.name}" else "LEAVE: ${o.name}", Yellow)
                        }
                    }
                }

                // Subject filter tabs
                if (semester.subjects.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            SemChip("ALL SUBJECTS", filterSubjectId == null, White) {
                                filterSubjectId = null
                            }
                        }
                        items(semester.subjects) { s ->
                            SemChip(s.name.uppercase(), filterSubjectId == s.id, Sky) {
                                filterSubjectId = s.id
                            }
                        }
                    }
                }

                // Scheduled classes on that day vs manual logs
                val wd = weekdayOf(selectedDay)
                val daySlots = effectiveSlotsForDay(semester.slots, semester.tempSchedule, selectedDay)
                    .sortedBy { it.start }

                val displayedSubjects = semester.subjects.filter {
                    filterSubjectId == null || it.id == filterSubjectId
                }

                Text(
                    "CLASSES & LOGS (${DAY_FULL[wd - 1].uppercase()})",
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp
                )

                // List of items
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (displayedSubjects.isEmpty()) {
                        item {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(White)
                                    .border(2.dp, Ink)
                                    .padding(14.dp)
                            ) {
                                Text("No subjects added yet.", fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        // Section 1: Classes from timetable on this day
                        if (daySlots.isNotEmpty()) {
                            items(daySlots.filter { slot ->
                                val linked = linkSubject(slot.subject, semester.subjects.map { it.name })
                                val sub = semester.subjects.firstOrNull { it.name == linked }
                                filterSubjectId == null || sub?.id == filterSubjectId
                            }) { slot ->
                                val linked = linkSubject(slot.subject, semester.subjects.map { it.name })
                                val sub = semester.subjects.firstOrNull { it.name == linked }
                                if (sub != null) {
                                    val log = semester.attendanceLogs.firstOrNull {
                                        it.day == selectedDay && it.subjectId == sub.id && it.isLab == slot.isLab &&
                                                (it.slotId == slot.id || it.slotId == null)
                                    }
                                    ClassDayLogRow(
                                        title = "${slot.subject.uppercase()}${if (slot.isLab) " (LAB)" else " (THEORY)"}",
                                        subtitle = "${formatTime(slot.start)} - ${formatTime(slot.end)}${if (slot.room.isNotBlank()) " · Rm ${slot.room}" else ""}",
                                        currentStatus = log?.status,
                                        onSelectStatus = { newStatus ->
                                            val updated = recordAttendanceLog(
                                                semester = semester,
                                                day = selectedDay,
                                                subjectId = sub.id,
                                                isLab = slot.isLab,
                                                newStatus = newStatus,
                                                slotId = slot.id
                                            )
                                            onUpdateSemester(updated)
                                        }
                                    )
                                }
                            }
                        }

                        // Section 2: Other subjects or extra entries not tied to a specific slot
                        items(displayedSubjects) { sub ->
                            // Show theory section if present and wasn't already covered in day slots
                            if (sub.theory != null && daySlots.none {
                                    val linked = linkSubject(it.subject, semester.subjects.map { s -> s.name })
                                    linked == sub.name && !it.isLab
                                }) {
                                val log = semester.attendanceLogs.firstOrNull {
                                    it.day == selectedDay && it.subjectId == sub.id && !it.isLab
                                }
                                ClassDayLogRow(
                                    title = "${sub.name.uppercase()} (THEORY)",
                                    subtitle = "Extra / Unscheduled class",
                                    currentStatus = log?.status,
                                    onSelectStatus = { newStatus ->
                                        val updated = recordAttendanceLog(
                                            semester = semester,
                                            day = selectedDay,
                                            subjectId = sub.id,
                                            isLab = false,
                                            newStatus = newStatus
                                        )
                                        onUpdateSemester(updated)
                                    }
                                )
                            }

                            // Show lab section if present and wasn't covered in day slots
                            if (sub.lab != null && daySlots.none {
                                    val linked = linkSubject(it.subject, semester.subjects.map { s -> s.name })
                                    linked == sub.name && it.isLab
                                }) {
                                val log = semester.attendanceLogs.firstOrNull {
                                    it.day == selectedDay && it.subjectId == sub.id && it.isLab
                                }
                                ClassDayLogRow(
                                    title = "${sub.name.uppercase()} (LAB)",
                                    subtitle = "Extra / Unscheduled lab",
                                    currentStatus = log?.status,
                                    onSelectStatus = { newStatus ->
                                        val updated = recordAttendanceLog(
                                            semester = semester,
                                            day = selectedDay,
                                            subjectId = sub.id,
                                            isLab = true,
                                            newStatus = newStatus
                                        )
                                        onUpdateSemester(updated)
                                    }
                                )
                            }
                        }
                    }
                }

                NeoButton("CLOSE", White) { onDismiss() }
            }
        }
    }
}

@Composable
private fun ClassDayLogRow(
    title: String,
    subtitle: String,
    currentStatus: String?,
    onSelectStatus: (String?) -> Unit
) {
    val cardColor = when (currentStatus) {
        "P" -> Lime
        "A" -> Pink
        "C" -> Yellow
        else -> White
    }

    Box(
        Modifier
            .fillMaxWidth()
            .background(cardColor)
            .border(2.dp, Ink)
            .padding(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Black, fontSize = 14.sp)
                Text(subtitle, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                if (currentStatus != null) {
                    val statusLabel = when (currentStatus) {
                        "P" -> "MARKED PRESENT"
                        "A" -> "MARKED ABSENT"
                        "C" -> "CANCELLED"
                        else -> ""
                    }
                    Text(
                        statusLabel,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp,
                        color = Ink
                    )
                }
            }

            // Quick status buttons P / A / C
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusChip("P", currentStatus == "P", Lime) {
                    onSelectStatus(if (currentStatus == "P") null else "P")
                }
                StatusChip("A", currentStatus == "A", Pink) {
                    onSelectStatus(if (currentStatus == "A") null else "A")
                }
                StatusChip("C", currentStatus == "C", Yellow) {
                    onSelectStatus(if (currentStatus == "C") null else "C")
                }
            }
        }
    }
}

@Composable
private fun StatusChip(
    text: String,
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .background(if (selected) activeColor else White)
            .border(if (selected) 2.5.dp else 1.5.dp, Ink)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(
            text,
            fontWeight = FontWeight.Black,
            fontSize = 13.sp,
            color = Ink
        )
    }
}
