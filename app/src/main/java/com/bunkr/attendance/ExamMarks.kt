package com.bunkr.attendance

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.Locale

/*
 * Independent Marks tracker for exams (MST 1, MST 2, Final Exam, and lab practicals).
 * Completely separate from the exam schedule/datesheet.
 */

const val MARK_MST1 = 0
const val MARK_MST2 = 1
const val MARK_FINAL = 2
const val MARK_IP1 = 3
const val MARK_IP2 = 4
const val MARK_EP = 5

val MARK_LABELS = listOf("MST 1", "MST 2", "FINAL", "IP 1", "IP 2", "FINAL EP")

fun markIsLab(type: Int) = type >= MARK_IP1

fun markColor(type: Int): Color = when (type) {
    MARK_MST1 -> Sky
    MARK_MST2 -> Yellow
    MARK_FINAL -> Pink
    MARK_IP1 -> Lime
    MARK_IP2 -> Mint
    else -> Pink
}

data class ExamMark(
    val id: Long,
    val subject: String,
    val type: Int,          // MARK_MST1 ... MARK_EP
    val scored: Double,
    val total: Double
)

fun formatMarks(v: Double): String =
    if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

fun encodeMark(m: ExamMark): String =
    "${m.id}|${m.type}|${m.scored}|${m.total}|${cleanText(m.subject)}"

fun decodeMark(text: String): ExamMark? = runCatching {
    val p = text.removePrefix("~mark|").split("|")
    ExamMark(
        id = p[0].toLong(),
        type = p[1].toInt().coerceIn(MARK_MST1, MARK_EP),
        scored = p[2].toDouble(),
        total = p[3].toDouble(),
        subject = p[4]
    )
}.getOrNull()

@Composable
fun MarksScreen(
    marks: List<ExamMark>,
    subjects: List<Subject>,
    onUpdateMarks: (List<ExamMark>) -> Unit,
    onOpenAddMarks: () -> Unit
) {
    var filter by remember { mutableStateOf(0) } // 0 = ALL, 1 = THEORY, 2 = LAB
    var editingSubject by remember { mutableStateOf<String?>(null) }

    val validMarks = marks.filter { it.total > 0 }
    val totalScored = validMarks.sumOf { it.scored }
    val totalMax = validMarks.sumOf { it.total }
    val overallPct = if (totalMax > 0) (totalScored * 100.0 / totalMax) else 0.0

    val mst1Marks = validMarks.filter { it.type == MARK_MST1 }
    val mst1Pct = if (mst1Marks.isNotEmpty()) mst1Marks.sumOf { it.scored } * 100.0 / mst1Marks.sumOf { it.total } else null

    val mst2Marks = validMarks.filter { it.type == MARK_MST2 }
    val mst2Pct = if (mst2Marks.isNotEmpty()) mst2Marks.sumOf { it.scored } * 100.0 / mst2Marks.sumOf { it.total } else null

    val finalMarks = validMarks.filter { it.type == MARK_FINAL }
    val finalPct = if (finalMarks.isNotEmpty()) finalMarks.sumOf { it.scored } * 100.0 / finalMarks.sumOf { it.total } else null

    val labMarks = validMarks.filter { markIsLab(it.type) }
    val labPct = if (labMarks.isNotEmpty()) labMarks.sumOf { it.scored } * 100.0 / labMarks.sumOf { it.total } else null

    val subjectNames = (subjects.map { it.name } + marks.map { it.subject })
        .filter { it.isNotBlank() }
        .distinctBy { subjectKey(it) }

    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Summary Card
        NeoCard(color = if (validMarks.isNotEmpty()) Mint else Cream) {
            Text("EXAM MARKS OVERVIEW", fontWeight = FontWeight.Black, fontSize = 14.sp)
            if (validMarks.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${String.format(Locale.US, "%.1f", overallPct)}%",
                    fontWeight = FontWeight.Black,
                    fontSize = 34.sp
                )
                Text(
                    "Total: ${formatMarks(totalScored)} / ${formatMarks(totalMax)} (${validMarks.size} exam${if (validMarks.size == 1) "" else "s"} recorded)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    mst1Pct?.let { Tag("MST 1: ${it.toInt()}%", Sky) }
                    mst2Pct?.let { Tag("MST 2: ${it.toInt()}%", Yellow) }
                    finalPct?.let { Tag("FINAL: ${it.toInt()}%", Pink) }
                    labPct?.let { Tag("LABS: ${it.toInt()}%", Lime) }
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text("NO MARKS RECORDED YET", fontWeight = FontWeight.Black, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Store and track your marks for MST 1, MST 2, and Final exams across all your subjects.",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                NeoButton("+ ENTER MARKS", White) { onOpenAddMarks() }
            }
        }

        // Filter chips
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SemChip("ALL", filter == 0, Yellow) { filter = 0 }
            SemChip("THEORY", filter == 1, Sky) { filter = 1 }
            SemChip("LAB", filter == 2, Pink) { filter = 2 }
        }

        // Subjects list
        if (subjectNames.isEmpty()) {
            NeoCard(color = Sky) {
                Text("NO SUBJECTS YET", fontWeight = FontWeight.Black, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text("Add subjects in Attendance, or tap below to store marks for a new subject.", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                NeoButton("+ ENTER MARKS", White) { onOpenAddMarks() }
            }
        } else {
            subjectNames.forEach { subName ->
                val subMarks = marks.filter { subjectKey(it.subject) == subjectKey(subName) }
                val validSubMarks = subMarks.filter { it.total > 0 }
                val subScored = validSubMarks.sumOf { it.scored }
                val subMax = validSubMarks.sumOf { it.total }
                val subPct = if (subMax > 0) (subScored * 100.0 / subMax) else null

                val mst1 = subMarks.firstOrNull { it.type == MARK_MST1 }
                val mst2 = subMarks.firstOrNull { it.type == MARK_MST2 }
                val finalExam = subMarks.firstOrNull { it.type == MARK_FINAL }
                val ip1 = subMarks.firstOrNull { it.type == MARK_IP1 }
                val ip2 = subMarks.firstOrNull { it.type == MARK_IP2 }
                val ep = subMarks.firstOrNull { it.type == MARK_EP }

                val hasAnyLab = ip1 != null || ip2 != null || ep != null
                val isTheoryOnly = filter == 1
                val isLabOnly = filter == 2

                if (isLabOnly && !hasAnyLab) return@forEach
                if (isTheoryOnly && mst1 == null && mst2 == null && finalExam == null && hasAnyLab) return@forEach

                NeoCard(color = White) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(subName, fontWeight = FontWeight.Black, fontSize = 16.sp)
                                if (subPct != null) {
                                    Spacer(Modifier.height(2.dp))
                                    val badgeColor = if (subPct >= 75) Lime else if (subPct >= 50) Yellow else Pink
                                    Tag(
                                        "${String.format(Locale.US, "%.1f", subPct)}% (${formatMarks(subScored)}/${formatMarks(subMax)})",
                                        badgeColor
                                    )
                                } else {
                                    Spacer(Modifier.height(2.dp))
                                    Text("No marks recorded yet", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                            Box(
                                Modifier
                                    .background(Yellow)
                                    .border(2.dp, Ink)
                                    .clickable { editingSubject = subName }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    if (validSubMarks.isNotEmpty()) "EDIT MARKS" else "ENTER MARKS",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Theory marks row
                        if (!isLabOnly) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                @Composable
                                fun Badge(label: String, mark: ExamMark?) {
                                    val text = if (mark != null && mark.total > 0) "$label: ${formatMarks(mark.scored)}/${formatMarks(mark.total)}" else "$label: —"
                                    val col = if (mark != null && mark.total > 0) markColor(mark.type) else Cream
                                    Box(
                                        Modifier
                                            .background(col)
                                            .border(1.5.dp, Ink)
                                            .clickable { editingSubject = subName }
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(text, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }

                                Badge("MST 1", mst1)
                                Badge("MST 2", mst2)
                                Badge("FINAL", finalExam)
                            }
                        }

                        // Lab marks row
                        if (hasAnyLab && !isTheoryOnly) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                @Composable
                                fun LabBadge(label: String, mark: ExamMark?) {
                                    val text = if (mark != null && mark.total > 0) "$label: ${formatMarks(mark.scored)}/${formatMarks(mark.total)}" else "$label: —"
                                    val col = if (mark != null && mark.total > 0) markColor(mark.type) else Cream
                                    Box(
                                        Modifier
                                            .background(col)
                                            .border(1.5.dp, Ink)
                                            .clickable { editingSubject = subName }
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text(text, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }

                                LabBadge("IP 1", ip1)
                                LabBadge("IP 2", ip2)
                                LabBadge("EP", ep)
                            }
                        }
                    }
                }
            }
        }
    }

    editingSubject?.let { subName ->
        SubjectMarksDialog(
            subjectName = subName,
            marks = marks,
            onDismiss = { editingSubject = null },
            onSave = { updated ->
                val otherMarks = marks.filter { subjectKey(it.subject) != subjectKey(subName) }
                onUpdateMarks(otherMarks + updated)
                editingSubject = null
            }
        )
    }
}

@Composable
fun SubjectMarksDialog(
    subjectName: String,
    marks: List<ExamMark>,
    onDismiss: () -> Unit,
    onSave: (List<ExamMark>) -> Unit
) {
    val existing = remember(subjectName, marks) {
        marks.filter { subjectKey(it.subject) == subjectKey(subjectName) }
    }
    val theoryTypes = listOf(MARK_MST1, MARK_MST2, MARK_FINAL)
    val labTypes = listOf(MARK_IP1, MARK_IP2, MARK_EP)

    val hasLabMarksInitially = remember {
        existing.any { markIsLab(it.type) }
    }
    var showLabs by remember { mutableStateOf(hasLabMarksInitially) }

    val scores = remember {
        mutableStateMapOf<Int, String>().apply {
            (theoryTypes + labTypes).forEach { t ->
                val m = existing.firstOrNull { it.type == t }
                put(t, m?.let { formatMarks(it.scored) } ?: "")
            }
        }
    }
    val totals = remember {
        mutableStateMapOf<Int, String>().apply {
            (theoryTypes + labTypes).forEach { t ->
                val m = existing.firstOrNull { it.type == t }
                put(
                    t,
                    m?.let { formatMarks(it.total) }
                        ?: if (t == MARK_FINAL) "100" else if (t == MARK_EP) "50" else "30"
                )
            }
        }
    }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.padding(end = 6.dp, bottom = 6.dp)) {
            Column(
                Modifier
                    .neoShadow(6.dp)
                    .background(Cream)
                    .border(3.dp, Ink)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(subjectName.uppercase(), fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text("STORE & UPDATE MARKS", fontWeight = FontWeight.Bold, fontSize = 13.sp)

                Text("THEORY EXAMS", fontWeight = FontWeight.Black, fontSize = 14.sp)
                theoryTypes.forEach { t ->
                    val scoredStr = scores[t] ?: ""
                    val totalStr = totals[t] ?: ""
                    val sVal = scoredStr.toDoubleOrNull()
                    val tVal = totalStr.toDoubleOrNull()
                    val pct = if (sVal != null && tVal != null && tVal > 0) (sVal * 100.0 / tVal) else null

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(White)
                            .border(2.dp, Ink)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Tag(MARK_LABELS[t], markColor(t))
                            pct?.let {
                                val pillColor = if (it >= 75) Lime else if (it >= 50) Yellow else Pink
                                Tag("${String.format(Locale.US, "%.1f", it)}%", pillColor)
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(Modifier.weight(1f)) {
                                NeoField(
                                    scoredStr,
                                    { scores[t] = it },
                                    "Scored",
                                    keyboard = KeyboardType.Decimal
                                )
                            }
                            Text("/", fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Box(Modifier.weight(1f)) {
                                NeoField(
                                    totalStr,
                                    { totals[t] = it },
                                    "Total",
                                    keyboard = KeyboardType.Decimal
                                )
                            }
                        }
                    }
                }

                CheckRow("PRACTICAL / LAB EXAMS", showLabs) { showLabs = !showLabs }
                if (showLabs) {
                    labTypes.forEach { t ->
                        val scoredStr = scores[t] ?: ""
                        val totalStr = totals[t] ?: ""
                        val sVal = scoredStr.toDoubleOrNull()
                        val tVal = totalStr.toDoubleOrNull()
                        val pct = if (sVal != null && tVal != null && tVal > 0) (sVal * 100.0 / tVal) else null

                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(White)
                                .border(2.dp, Ink)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Tag(MARK_LABELS[t], markColor(t))
                                pct?.let {
                                    val pillColor = if (it >= 75) Lime else if (it >= 50) Yellow else Pink
                                    Tag("${String.format(Locale.US, "%.1f", it)}%", pillColor)
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(Modifier.weight(1f)) {
                                    NeoField(
                                        scoredStr,
                                        { scores[t] = it },
                                        "Scored",
                                        keyboard = KeyboardType.Decimal
                                    )
                                }
                                Text("/", fontWeight = FontWeight.Black, fontSize = 18.sp)
                                Box(Modifier.weight(1f)) {
                                    NeoField(
                                        totalStr,
                                        { totals[t] = it },
                                        "Total",
                                        keyboard = KeyboardType.Decimal
                                    )
                                }
                            }
                        }
                    }
                }

                error?.let { Text(it, fontWeight = FontWeight.Black, color = Red, fontSize = 13.sp) }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeoButton("CANCEL", White, Modifier.weight(1f), onDismiss)
                    NeoButton("SAVE MARKS", Lime, Modifier.weight(1f)) {
                        val typesToCheck = if (showLabs) theoryTypes + labTypes else theoryTypes
                        val result = mutableListOf<ExamMark>()
                        val baseId = System.currentTimeMillis()

                        for (t in typesToCheck) {
                            val sStr = scores[t]?.trim() ?: ""
                            val tStr = totals[t]?.trim() ?: ""

                            if (sStr.isBlank() && tStr.isBlank()) {
                                continue
                            }

                            if (sStr.isNotBlank() && tStr.isBlank()) {
                                error = "Please enter total marks for ${MARK_LABELS[t]}."
                                return@NeoButton
                            }
                            if (sStr.isBlank() && tStr.isNotBlank()) {
                                continue
                            }

                            val s = sStr.toDoubleOrNull()
                            val tot = tStr.toDoubleOrNull()
                            if (s == null) {
                                error = "Invalid marks scored for ${MARK_LABELS[t]}."
                                return@NeoButton
                            }
                            if (tot == null || tot <= 0) {
                                error = "Total marks for ${MARK_LABELS[t]} must be > 0."
                                return@NeoButton
                            }
                            if (s < 0) {
                                error = "Marks scored for ${MARK_LABELS[t]} cannot be negative."
                                return@NeoButton
                            }
                            if (s > tot) {
                                error = "Marks scored for ${MARK_LABELS[t]} cannot exceed total ($tot)."
                                return@NeoButton
                            }

                            val existingId = existing.firstOrNull { it.type == t }?.id ?: (baseId + t)
                            result.add(ExamMark(existingId, subjectName.trim(), t, s, tot))
                        }

                        onSave(result)
                    }
                }
            }
        }
    }
}

@Composable
fun NewSubjectMarksDialog(
    knownSubjects: List<String>,
    onDismiss: () -> Unit,
    onSelectSubject: (String) -> Unit
) {
    var subjectInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.padding(end = 6.dp, bottom = 6.dp)) {
            Column(
                Modifier
                    .neoShadow(6.dp)
                    .background(Yellow)
                    .border(3.dp, Ink)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("ADD SUBJECT MARKS", fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text("Select an existing subject or type a new one:", fontWeight = FontWeight.Bold, fontSize = 13.sp)

                if (knownSubjects.isNotEmpty()) {
                    FieldLabel("EXISTING SUBJECTS")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(knownSubjects) { name ->
                            SemChip(name, subjectInput.equals(name, ignoreCase = true), Lime) {
                                subjectInput = name
                            }
                        }
                    }
                }

                FieldLabel("SUBJECT NAME")
                NeoField(subjectInput, { subjectInput = it }, "e.g. Operating Systems")

                error?.let { Text(it, fontWeight = FontWeight.Black, color = Red, fontSize = 13.sp) }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeoButton("CANCEL", White, Modifier.weight(1f), onDismiss)
                    NeoButton("CONTINUE", Lime, Modifier.weight(1f)) {
                        val trimmed = subjectInput.trim()
                        if (trimmed.isBlank()) {
                            error = "Enter or select a subject name."
                        } else {
                            onSelectSubject(trimmed)
                        }
                    }
                }
            }
        }
    }
}
