package com.bunkr.attendance

import android.app.DatePickerDialog
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * Holidays, leave and the skip planner.
 *
 *  - HOLIDAY: no classes. Skipped by the reminders, the after-class prompt and the skip planner.
 *  - LEAVE (duty / medical): either counts as PRESENT (each class is added as attended once it ends)
 *    or is NOT COUNTED (ignored, like a holiday).
 *  - Skip planner: "what happens to my percentages if I miss these days?", worked out from the timetable.
 *
 * Dates are stored as "epoch days" (days since 1970-01-01, in the phone's local calendar).
 */

// ---------- Dates ----------
private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

fun ymdOf(day: Long): Triple<Int, Int, Int> {
    val c = Calendar.getInstance(UTC)
    c.timeInMillis = day * 86_400_000L
    return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
}

fun dayOfYmd(y: Int, m: Int, d: Int): Long {
    val c = Calendar.getInstance(UTC)
    c.clear()
    c.set(y, m, d)
    return c.timeInMillis / 86_400_000L
}

/** The local calendar date of [millis], as an epoch day. */
fun epochDayOf(millis: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = millis
    return dayOfYmd(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
}

/** Local time [minutes] after midnight on epoch day [day], as epoch millis. */
fun millisOf(day: Long, minutes: Int = 0): Long {
    val (y, m, d) = ymdOf(day)
    val c = Calendar.getInstance()
    c.clear()
    c.set(y, m, d, minutes / 60, minutes % 60, 0)
    return c.timeInMillis
}

/** 1 = Monday ... 7 = Sunday, the same numbering as ClassSlot.day. (Epoch day 0 was a Thursday.) */
fun weekdayOf(day: Long): Int = ((day + 3) % 7).toInt() + 1

fun dateText(day: Long): String {
    val f = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    f.timeZone = UTC
    return f.format(Date(day * 86_400_000L)).uppercase()
}

// ---------- Holidays and leave: data ----------
const val OFF_HOLIDAY = 0
const val OFF_LEAVE = 1

data class DayOff(
    val id: Long,
    val name: String,
    val kind: Int,                      // OFF_HOLIDAY or OFF_LEAVE
    val startDay: Long,                 // epoch day, inclusive
    val endDay: Long,                   // epoch day, inclusive
    val leavePresent: Boolean = true,   // leave only: true = counts as present, false = not counted
    val appliedUntil: Long = 0L         // leave only: classes that ended before this moment are already added
)

fun DayOff.covers(day: Long) = day in startDay..endDay

fun isDayOff(offs: List<DayOff>, day: Long) = offs.any { it.covers(day) }

fun encodeDayOff(d: DayOff) =
    "${d.id}|${d.kind}|${d.startDay}|${d.endDay}|${if (d.leavePresent) 1 else 0}|${d.appliedUntil}|${cleanText(d.name)}"

fun decodeDayOff(text: String): DayOff? = runCatching {
    val p = text.removePrefix("~off|").split("|")
    DayOff(
        id = p[0].toLong(),
        name = p.getOrElse(6) { "" },
        kind = p[1].toInt(),
        startDay = p[2].toLong(),
        endDay = p[3].toLong(),
        leavePresent = p[4] == "1",
        appliedUntil = p[5].toLong()
    )
}.getOrNull()

/** Like nextStartMillis, but moves on to the following week while the class would fall on a holiday or leave day. */
fun nextStartOutsideDaysOff(slot: ClassSlot, from: Long, offs: List<DayOff>): Long {
    var t = nextStartMillis(slot, from)
    var guard = 0
    while (guard++ < 60 && isDayOff(offs, epochDayOf(t))) t = nextStartMillis(slot, t)
    return t
}

// ---------- Counting classes from the timetable ----------
/** The weekly timetable classes of one section (theory or lab) of a subject, matched by name like the notifications do. */
fun sectionSlots(sem: Semester, subject: Subject, isLab: Boolean): List<ClassSlot> {
    val names = sem.subjects.map { it.name }
    return sem.slots.filter { it.isLab == isLab && linkSubject(it.subject, names) == subject.name }
}

/**
 * How many class occurrences end after [after] and up to [until] (epoch millis) on the days
 * [firstDay]..[lastDay], leaving out the days where [skipDay] is true.
 */
fun countEnded(
    slots: List<ClassSlot>,
    firstDay: Long,
    lastDay: Long,
    after: Long,
    until: Long,
    skipDay: (Long) -> Boolean
): Int {
    var n = 0
    var d = firstDay
    while (d <= lastDay) {
        if (!skipDay(d)) {
            val wd = weekdayOf(d)
            for (s in slots) {
                if (s.day != wd) continue
                val end = millisOf(d, s.end)
                if (end > after && end <= until) n++
            }
        }
        d++
    }
    return n
}

/**
 * Adds every leave class (leave that "counts as present") to the attendance once that class has ended.
 * Safe to call again and again: each leave remembers up to when it has been added.
 * Returns the SAME object when nothing changed.
 */
fun applyLeaves(data: AppData, now: Long = System.currentTimeMillis()): AppData {
    var changed = false
    val semesters = data.semesters.map { sem ->
        var subjects = sem.subjects
        var offs = sem.daysOff
        for (off in sem.daysOff) {
            if (off.kind != OFF_LEAVE || !off.leavePresent) continue
            val limit = minOf(now, millisOf(off.endDay + 1, 0))
            if (off.appliedUntil >= limit) continue

            // A day that is also a holiday, or already covered by an older leave, is not counted twice.
            val others = sem.daysOff.filter {
                it.id != off.id && (it.kind == OFF_HOLIDAY || (it.leavePresent && it.id < off.id))
            }
            var added = false
            subjects = subjects.map { sub ->
                var s = sub
                for (lab in listOf(false, true)) {
                    val rec = (if (lab) s.lab else s.theory) ?: continue
                    val n = countEnded(
                        sectionSlots(sem, sub, lab), off.startDay, off.endDay, off.appliedUntil, limit
                    ) { d -> isDayOff(others, d) }
                    if (n > 0) {
                        val newRec = rec.copy(attended = rec.attended + n, total = rec.total + n)
                        s = if (lab) s.copy(lab = newRec) else s.copy(theory = newRec)
                        added = true
                    }
                }
                s
            }
            if (added) {
                offs = offs.map { if (it.id == off.id) it.copy(appliedUntil = limit) else it }
                changed = true
            }
        }
        if (offs === sem.daysOff && subjects === sem.subjects) sem else sem.copy(subjects = subjects, daysOff = offs)
    }
    return if (changed) data.copy(semesters = semesters) else data
}

// ---------- Skip planner: the maths ----------
/** One theory or lab section and what happens to it if the chosen days are missed. */
data class PlanRow(val name: String, val isLab: Boolean, val before: Record, val after: Record, val missed: Int)

/**
 * What happens if you miss every class from [from] to [to] (epoch days).
 * Assumes you attend every other class between now and [from]. Holidays and leave days are not "missed".
 * Sections with no classes in that range (or no attendance data yet) are left out.
 */
fun planSkip(sem: Semester, from: Long, to: Long, now: Long = System.currentTimeMillis()): List<PlanRow> {
    val today = epochDayOf(now)
    val offs = sem.daysOff
    val rows = mutableListOf<PlanRow>()
    for (sub in sem.subjects) for (lab in listOf(false, true)) {
        val rec = (if (lab) sub.lab else sub.theory) ?: continue
        if (rec.total == 0) continue
        val slots = sectionSlots(sem, sub, lab)
        if (slots.isEmpty()) continue

        val missed = countEnded(slots, from, to, now, Long.MAX_VALUE) { isDayOff(offs, it) }
        if (missed == 0) continue

        // Classes between now and the skipped days: assumed attended, except holidays / not-counted leave.
        val attendedFirst = if (from > today) {
            countEnded(slots, today, from - 1, now, Long.MAX_VALUE) { d ->
                offs.any { it.covers(d) && !(it.kind == OFF_LEAVE && it.leavePresent) }
            }
        } else 0

        val after = Record(rec.attended + attendedFirst, rec.total + attendedFirst + missed, rec.required)
        rows += PlanRow(sub.name, lab, rec, after, missed)
    }
    return rows
}

// ---------- Skip planner: screen ----------
@Composable
private fun DayChip(text: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    val shift = if (selected) 3.dp else 0.dp
    Box(Modifier.padding(end = 4.dp, bottom = 4.dp)) {
        Box(
            Modifier
                .offset(shift, shift)
                .neoShadow(4.dp - shift)
                .background(color)
                .border(3.dp, Ink)
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) { Text(text, fontWeight = FontWeight.Black, fontSize = 13.sp, color = Ink) }
    }
}

@Composable
fun SkipPlannerDialog(semester: Semester, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val today = remember { epochDayOf(System.currentTimeMillis()) }
    var from by remember { mutableStateOf(today + 1) }
    var to by remember { mutableStateOf(today + 1) }

    fun pick(current: Long, onPicked: (Long) -> Unit) {
        val (y, m, d) = ymdOf(current)
        DatePickerDialog(context, { _, yy, mm, dd -> onPicked(dayOfYmd(yy, mm, dd)) }, y, m, d).apply {
            datePicker.minDate = millisOf(today, 0)
        }.show()
    }

    val rows = remember(semester, from, to) { planSkip(semester, from, to) }
    val nextDays = remember(semester, today) { (1L..14L).map { n -> (today + n).let { d -> d to planSkip(semester, d, d) } } }

    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.padding(end = 6.dp, bottom = 6.dp)) {
            Column(
                Modifier
                    .neoShadow(6.dp)
                    .background(Sky)
                    .border(3.dp, Ink)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("SKIP PLANNER", fontWeight = FontWeight.Black, fontSize = 22.sp)

                if (semester.slots.isEmpty()) {
                    Text(
                        "Add your timetable first (TIMETABLE tab) so the planner knows which classes you would miss.",
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        "Pick the days you want to miss. This assumes you attend every other class until then.",
                        fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            FieldLabel("FROM")
                            NeoButton(dateText(from), White) {
                                pick(from) {
                                    from = it
                                    if (to < it) to = it
                                }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            FieldLabel("TO")
                            NeoButton(dateText(to), White) { pick(to) { to = maxOf(it, from) } }
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(listOf(1 to "1 DAY", 2 to "2 DAYS", 3 to "3 DAYS", 7 to "1 WEEK")) { (n, label) ->
                            SemChip(label, (to - from + 1).toInt() == n, Yellow) { to = from + n - 1 }
                        }
                    }

                    // Overall verdict
                    val bad = rows.filter { !it.after.isSafe }
                    val verdictColor = when {
                        rows.isEmpty() -> White
                        bad.isEmpty() -> Lime
                        else -> Red
                    }
                    val verdictText = when {
                        rows.isEmpty() -> "No classes to miss on these dates (or there is no attendance data for them yet)."
                        bad.isEmpty() -> "SAFE TO SKIP. Every subject stays above its limit."
                        else -> "NOT SAFE. " + bad.joinToString(", ") { "${it.name} ${if (it.isLab) "lab" else "theory"}" } +
                                " would drop below the limit."
                    }
                    Box(Modifier.fillMaxWidth().background(verdictColor).border(3.dp, Ink).padding(10.dp)) {
                        Text(verdictText, fontWeight = FontWeight.Black)
                    }

                    // Per subject
                    rows.forEach { r ->
                        val color = when {
                            !r.after.isSafe -> Red
                            r.after.canBunk == 0 -> Yellow
                            else -> Lime
                        }
                        Box(Modifier.fillMaxWidth().background(color).border(3.dp, Ink).padding(10.dp)) {
                            Column {
                                Text(
                                    "${r.name.uppercase()} (${if (r.isLab) "LAB" else "THEORY"})",
                                    fontWeight = FontWeight.Black, fontSize = 14.sp
                                )
                                Text(
                                    "Miss ${r.missed} class${if (r.missed == 1) "" else "es"}: " +
                                            "${Math.round(r.before.percent)}% -> ${Math.round(r.after.percent)}%",
                                    fontWeight = FontWeight.Bold, fontSize = 13.sp
                                )
                                Text(r.after.verdict().first, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }

                    // Which single days are safe in the next two weeks
                    Column {
                        FieldLabel("SKIPPING JUST ONE DAY, NEXT 14 DAYS")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(nextDays) { (d, r) ->
                                val color = when {
                                    r.isEmpty() -> White
                                    r.all { it.after.isSafe } -> Lime
                                    else -> Pink
                                }
                                DayChip(dateText(d), color, from == d && to == d) {
                                    from = d
                                    to = d
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Green = safe to skip, pink = would drop a subject below its limit, white = no classes. Tap a day to check it.",
                            fontWeight = FontWeight.Bold, fontSize = 12.sp
                        )
                    }
                }

                NeoButton("CLOSE", White, onClick = onDismiss)
            }
        }
    }
}

// ---------- Holidays and leave: screens ----------
private fun rangeText(d: DayOff) =
    if (d.startDay == d.endDay) dateText(d.startDay) else "${dateText(d.startDay)} to ${dateText(d.endDay)}"

private fun typeText(d: DayOff) = when {
    d.kind == OFF_HOLIDAY -> "HOLIDAY: NO CLASSES"
    d.leavePresent -> "LEAVE: COUNTS AS PRESENT"
    else -> "LEAVE: NOT COUNTED"
}

@Composable
fun DaysOffDialog(daysOff: List<DayOff>, onChange: (List<DayOff>) -> Unit, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf(false) }

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
                Text("HOLIDAYS & LEAVE", fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text(
                    "No reminders on holidays and leave days. Leave that counts as present is added to your attendance as each class ends.",
                    fontWeight = FontWeight.Bold, fontSize = 13.sp
                )

                if (daysOff.isEmpty()) {
                    Text("Nothing added yet.", fontWeight = FontWeight.Black)
                }
                daysOff.sortedBy { it.startDay }.forEach { off ->
                    Row(
                        Modifier.fillMaxWidth().background(White).border(3.dp, Ink).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(off.name.uppercase(), fontWeight = FontWeight.Black, fontSize = 14.sp)
                            Text(rangeText(off), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(typeText(off), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .background(Red)
                                .border(3.dp, Ink)
                                .clickable { onChange(daysOff.filter { it.id != off.id }) }
                                .padding(horizontal = 10.dp, vertical = 2.dp)
                        ) { Text("X", fontWeight = FontWeight.Black, fontSize = 14.sp) }
                    }
                }

                NeoButton("+ ADD", Lime) { adding = true }
                Text(
                    "Deleting a leave does not take back classes it already added to your attendance.",
                    fontWeight = FontWeight.Bold, fontSize = 12.sp
                )
                NeoButton("CLOSE", White, onClick = onDismiss)
            }
        }
    }

    if (adding) {
        DayOffEditDialog(
            onDismiss = { adding = false },
            onSave = {
                onChange(daysOff + it)
                adding = false
            }
        )
    }
}

@Composable
fun DayOffEditDialog(onDismiss: () -> Unit, onSave: (DayOff) -> Unit) {
    val context = LocalContext.current
    val today = remember { epochDayOf(System.currentTimeMillis()) }
    var kind by remember { mutableStateOf(OFF_HOLIDAY) }
    var name by remember { mutableStateOf("") }
    var from by remember { mutableStateOf(today) }
    var to by remember { mutableStateOf(today) }
    var present by remember { mutableStateOf(true) }
    var addPast by remember { mutableStateOf(false) }

    fun pick(current: Long, onPicked: (Long) -> Unit) {
        val (y, m, d) = ymdOf(current)
        DatePickerDialog(context, { _, yy, mm, dd -> onPicked(dayOfYmd(yy, mm, dd)) }, y, m, d).show()
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
                Text("ADD HOLIDAY / LEAVE", fontWeight = FontWeight.Black, fontSize = 22.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SemChip("HOLIDAY", kind == OFF_HOLIDAY, Sky) { kind = OFF_HOLIDAY }
                    SemChip("LEAVE", kind == OFF_LEAVE, Pink) { kind = OFF_LEAVE }
                }
                Text(
                    if (kind == OFF_HOLIDAY) "No classes on these days." else "Duty leave, medical leave or any excused absence.",
                    fontWeight = FontWeight.Bold, fontSize = 13.sp
                )

                Column {
                    FieldLabel("NAME (OPTIONAL)")
                    NeoField(name, { name = it }, if (kind == OFF_HOLIDAY) "e.g. Diwali break" else "e.g. Medical leave")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel("FROM")
                        NeoButton(dateText(from), White) {
                            pick(from) {
                                from = it
                                if (to < it) to = it
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        FieldLabel("TO")
                        NeoButton(dateText(to), White) { pick(to) { to = maxOf(it, from) } }
                    }
                }

                if (kind == OFF_LEAVE) {
                    Column {
                        FieldLabel("LEAVE COUNTS AS")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SemChip("PRESENT", present, Lime) { present = true }
                            SemChip("NOT COUNTED", !present, Sky) { present = false }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (present) "Each class in these dates is added to your attended and total once it ends."
                            else "These classes are ignored: they change neither attended nor total.",
                            fontWeight = FontWeight.Bold, fontSize = 12.sp
                        )
                    }
                    if (present && from <= today) {
                        CheckRow("ALSO ADD CLASSES ALREADY OVER", addPast) { addPast = !addPast }
                        Text(
                            "Leave this off if you already marked those days yourself, or they would be counted twice.",
                            fontWeight = FontWeight.Bold, fontSize = 12.sp
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeoButton("CANCEL", White, Modifier.weight(1f), onDismiss)
                    NeoButton("SAVE", Lime, Modifier.weight(1f)) {
                        val now = System.currentTimeMillis()
                        onSave(
                            DayOff(
                                id = now,
                                name = name.trim().ifEmpty { if (kind == OFF_LEAVE) "Leave" else "Holiday" },
                                kind = kind,
                                startDay = from,
                                endDay = to,
                                leavePresent = present,
                                appliedUntil = if (kind == OFF_LEAVE && present && addPast) 0L else now
                            )
                        )
                    }
                }
            }
        }
    }
}