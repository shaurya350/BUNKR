package com.example.attendance // <-- change to your own package name

import android.content.Context
import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.Text as MlText
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore

// ---------- Colors (neobrutalism palette) ----------
val Ink = Color(0xFF000000)
val Cream = Color(0xFFFFF4E0)
val Yellow = Color(0xFFFFD43B)
val Pink = Color(0xFFFF6B9D)
val Lime = Color(0xFFB8F34A)
val Sky = Color(0xFF6BB5FF)
val Red = Color(0xFFFF5C5C)
val White = Color(0xFFFFFFFF)

// ---------- Data ----------
/** Attendance numbers for ONE section (theory or lab). */
data class Record(
    val attended: Int = 0,
    val total: Int = 0,
    val required: Int = 75 // percent, 1..100
)

/** A subject can have theory, lab, or both (null = that section doesn't exist). */
data class Subject(
    val id: Long,
    val name: String,
    val theory: Record?,
    val lab: Record?
)

val Record.percent: Float get() = if (total == 0) 0f else attended * 100f / total
val Record.isSafe: Boolean get() = total > 0 && attended * 100 >= required * total

/** How many more classes you can skip and still stay >= required %. */
val Record.canBunk: Int
    get() = if (total == 0) 0 else ((100 * attended) / required - total).coerceAtLeast(0)

/** Classes to attend in a row to reach required %. -1 = impossible. */
val Record.mustAttend: Int
    get() {
        if (total == 0 || isSafe) return 0
        if (required >= 100) return -1
        val y = (required * total - 100.0 * attended) / (100 - required)
        return Math.ceil(y).toInt()
    }

fun Record.present() = copy(attended = attended + 1, total = total + 1)
fun Record.absent() = copy(total = total + 1)
fun Record.undoPresent() = if (attended > 0 && total > 0) copy(attended = attended - 1, total = total - 1) else this
fun Record.undoAbsent() = if (total > attended) copy(total = total - 1) else this

fun Record.verdict(): Pair<String, Color> {
    fun plural(n: Int) = if (n == 1) "" else "es"
    return when {
        total == 0 -> "Mark your first class to get predictions." to Sky
        attended > total -> "Attended can't be more than total." to Red
        isSafe && canBunk > 0 -> "You can bunk $canBunk more class${plural(canBunk)}." to Lime
        isSafe -> "On the edge. Don't bunk the next one!" to Yellow
        mustAttend == -1 -> "Can't reach $required% anymore." to Red
        else -> "Attend next $mustAttend class${plural(mustAttend)} in a row to reach $required%." to Red
    }
}

fun pct(attended: Int, total: Int) = if (total == 0) 0f else attended * 100f / total

// ---------- Semesters ----------
// ---------- Timetable data ----------
data class ClassSlot(
    val id: Long,
    val day: Int,      // 1 = Monday ... 7 = Sunday
    val start: Int,    // minutes after midnight
    val end: Int,
    val subject: String,
    val isLab: Boolean,
    val room: String,
    val teacher: String = ""
)

val DAY_SHORT = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
val DAY_FULL = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

fun cleanText(s: String) = s.replace("|", " ").replace("\n", " ").trim()

fun encodeSlot(s: ClassSlot) =
    "${s.id}|${s.day}|${s.start}|${s.end}|${cleanText(s.subject)}|${if (s.isLab) 1 else 0}|${cleanText(s.room)}|${cleanText(s.teacher)}"

fun decodeSlot(text: String): ClassSlot? = runCatching {
    val p = text.removePrefix("~slot|").split("|")
    ClassSlot(p[0].toLong(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4], p[5] == "1", p.getOrElse(6) { "" }, p.getOrElse(7) { "" })
}.getOrNull()

fun formatTime(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "$h12:${m.toString().padStart(2, '0')} ${if (h < 12) "AM" else "PM"}"
}

/** The next moment this class starts, strictly after [from] (epoch millis). */
fun nextStartMillis(slot: ClassSlot, from: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = from
    cal.set(Calendar.HOUR_OF_DAY, slot.start / 60)
    cal.set(Calendar.MINUTE, slot.start % 60)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val calDay = if (slot.day == 7) Calendar.SUNDAY else slot.day + 1 // Calendar: Sunday = 1, Monday = 2 ...
    cal.add(Calendar.DAY_OF_YEAR, (calDay - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7)
    if (cal.timeInMillis <= from) cal.add(Calendar.DAY_OF_YEAR, 7)
    return cal.timeInMillis
}

fun nextClass(slots: List<ClassSlot>, now: Long): Pair<ClassSlot, Long>? =
    slots.map { it to nextStartMillis(it, now) }.minByOrNull { it.second }

fun todayIndex(): Int {
    val d = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    return if (d == Calendar.SUNDAY) 7 else d - 1
}

fun dayLabel(startMillis: Long): String {
    fun midnight(ms: Long) = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val today = midnight(System.currentTimeMillis())
    val target = midnight(startMillis)
    return when (Math.round((target.timeInMillis - today.timeInMillis) / 86_400_000.0).toInt()) {
        0 -> "TODAY"
        1 -> "TOMORROW"
        else -> {
            val d = target.get(Calendar.DAY_OF_WEEK)
            DAY_FULL[if (d == Calendar.SUNDAY) 6 else d - 2].uppercase()
        }
    }
}

fun untilText(startMillis: Long, now: Long): String {
    val mins = ((startMillis - now) / 60_000).toInt().coerceAtLeast(0)
    return when {
        mins < 60 -> "in $mins min"
        mins < 24 * 60 -> "in ${mins / 60}h ${mins % 60}m"
        else -> ""
    }
}

data class Semester(
    val id: Long,
    val name: String,
    val subjects: List<Subject>,
    val slots: List<ClassSlot> = emptyList()
)

data class AppData(val semesters: List<Semester>, val selectedId: Long)

val AppData.hasContent: Boolean
    get() = semesters.size > 1 || semesters.any { it.subjects.isNotEmpty() || it.slots.isNotEmpty() }

// ---------- Storage (local cache + Firebase cloud backup) ----------
class SubjectStore(context: Context, uid: String) {
    private val prefs = context.getSharedPreferences("bunkwise", Context.MODE_PRIVATE)
    private val key = "subjects_$uid"
    private val doc = Firebase.firestore.collection("users").document(uid)

    /** Local cache for this account. Adopts data saved before login existed (one time). */
    fun loadLocal(): AppData {
        var text = prefs.getString(key, null)
        if (text == null) {
            val legacy = prefs.getString("subjects", null)
            if (legacy != null) {
                prefs.edit().putString(key, legacy).remove("subjects").apply()
                text = legacy
            }
        }
        return decode(text ?: "")
    }

    fun saveLocal(data: AppData) {
        prefs.edit().putString(key, encode(data)).apply()
    }

    /** Backs up to Firestore. Works offline too: Firestore queues it and uploads when online. */
    fun saveCloud(data: AppData, onDone: (Boolean) -> Unit) {
        doc.set(mapOf("subjects" to encode(data))).addOnCompleteListener { onDone(it.isSuccessful) }
    }

    /** cloud == null && failed == false  -> this account has no backup yet. */
    fun fetchCloud(onDone: (cloud: AppData?, failed: Boolean) -> Unit) {
        doc.get().addOnCompleteListener { t ->
            if (!t.isSuccessful) {
                onDone(null, true)
            } else {
                val snap = t.result
                val text = if (snap != null && snap.exists()) snap.getString("subjects") else null
                onDone(text?.let { decode(it) }, false)
            }
        }
    }

    // Text format:
    //   @sel|<selectedSemesterId>
    //   #sem|<id>|<name>
    //   ~slot|<id>|<day>|<start>|<end>|<subject>|<lab>|<room>|<teacher>   (timetable classes of that semester)
    //   <subject lines...>   id|name|tA|tT|tR|hasLab|lA|lT|lR|hasTheory   (older 5 and 9 field lines still load)
    // Data saved before semesters existed (just subject lines) loads into "Semester 1".
    private fun parseSubject(line: String): Subject? {
        val p = line.split("|")
        return runCatching {
            when (p.size) {
                5 -> Subject(p[0].toLong(), p[1], Record(p[2].toInt(), p[3].toInt(), p[4].toInt()), null)
                9 -> Subject(
                    p[0].toLong(), p[1],
                    Record(p[2].toInt(), p[3].toInt(), p[4].toInt()),
                    if (p[5] == "1") Record(p[6].toInt(), p[7].toInt(), p[8].toInt()) else null
                )
                10 -> Subject(
                    p[0].toLong(), p[1],
                    if (p[9] == "1") Record(p[2].toInt(), p[3].toInt(), p[4].toInt()) else null,
                    if (p[5] == "1") Record(p[6].toInt(), p[7].toInt(), p[8].toInt()) else null
                )
                else -> null
            }
        }.getOrNull()
    }

    private fun clean(text: String) = text.replace("|", " ").replace("\n", " ")

    private fun encodeSubject(it: Subject): String {
        val t = it.theory
        val l = it.lab
        return "${it.id}|${clean(it.name)}|${t?.attended ?: 0}|${t?.total ?: 0}|${t?.required ?: 75}|" +
                (if (l != null) "1|${l.attended}|${l.total}|${l.required}" else "0|0|0|75") +
                "|" + (if (t != null) "1" else "0")
    }

    private fun decode(text: String): AppData {
        class Builder(
            val id: Long,
            val name: String,
            val subjects: MutableList<Subject> = mutableListOf(),
            val slots: MutableList<ClassSlot> = mutableListOf()
        )

        val builders = mutableListOf<Builder>()
        var selected = 0L
        for (line in text.lines()) {
            when {
                line.isBlank() -> {}
                line.startsWith("@sel|") -> selected = line.substringAfter("|").toLongOrNull() ?: 0L
                line.startsWith("~slot|") -> decodeSlot(line)?.let { builders.lastOrNull()?.slots?.add(it) }
                line.startsWith("#sem|") -> {
                    val p = line.split("|")
                    if (p.size >= 3) builders.add(Builder(p[1].toLongOrNull() ?: (builders.size + 1L), p[2]))
                }
                else -> parseSubject(line)?.let { sub ->
                    if (builders.isEmpty()) builders.add(Builder(1L, "Semester 1"))
                    builders.last().subjects.add(sub)
                }
            }
        }
        if (builders.isEmpty()) builders.add(Builder(1L, "Semester 1"))
        val semesters = builders.map { Semester(it.id, it.name, it.subjects.toList(), it.slots.toList()) }
        return AppData(semesters, if (semesters.any { it.id == selected }) selected else semesters.first().id)
    }

    private fun encode(data: AppData): String = buildString {
        append("@sel|").append(data.selectedId).append('\n')
        data.semesters.forEach { sem ->
            append("#sem|").append(sem.id).append('|').append(clean(sem.name)).append('\n')
            sem.subjects.forEach { append(encodeSubject(it)).append('\n') }
            sem.slots.forEach { append("~slot|").append(encodeSlot(it)).append('\n') }
        }
    }
}

// ---------- Activity ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var user by remember { mutableStateOf(Firebase.auth.currentUser) }
            var verified by remember { mutableStateOf(Firebase.auth.currentUser?.isEmailVerified == true) }
            val current = user
            val logout = {
                Reminders.clear(applicationContext)
                Firebase.auth.signOut()
                user = null
                verified = false
            }
            when {
                current == null -> AuthScreen(onLoggedIn = {
                    user = Firebase.auth.currentUser
                    verified = user?.isEmailVerified == true
                })
                // Fake or mistyped emails can never open the app: the inbox owner must click the link first.
                !verified -> VerifyEmailScreen(current, onVerified = { verified = true }, onLogout = logout)
                else -> BunkWiseApp(user = current, onLogout = logout)
            }
        }
    }
}

// ---------- Neobrutalism building blocks ----------
/** Hard, un-blurred offset shadow. Apply BEFORE background/border. */
fun Modifier.neoShadow(distance: Dp = 5.dp, color: Color = Ink): Modifier = this.drawBehind {
    val d = distance.toPx()
    drawRect(color = color, topLeft = Offset(d, d), size = size)
}

@Composable
fun NeoCard(
    modifier: Modifier = Modifier,
    color: Color = White,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier.padding(end = 6.dp, bottom = 6.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .neoShadow(6.dp)
                .background(color)
                .border(3.dp, Ink)
                .padding(16.dp),
            content = content
        )
    }
}

@Composable
fun NeoButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shift by animateDpAsState(if (pressed) 4.dp else 0.dp, label = "shift")

    Box(modifier.padding(end = 4.dp, bottom = 4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .offset(shift, shift)
                .neoShadow(4.dp - shift)
                .background(color)
                .border(3.dp, Ink)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = Ink, fontWeight = FontWeight.Black, fontSize = 14.sp)
        }
    }
}

@Composable
fun NeoField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    keyboard: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    minLines: Int = 1
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ink),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = visualTransformation,
        modifier = Modifier
            .fillMaxWidth()
            .background(White)
            .border(3.dp, Ink)
            .padding(12.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, color = Color.Gray, fontWeight = FontWeight.Bold)
                inner()
            }
        }
    )
}

@Composable
fun NeoProgress(fraction: Float, required: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(White)
            .border(3.dp, Ink)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(color)
        )
        // black marker showing the required %
        Row(Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(required.coerceIn(0.001f, 1f)))
            Box(Modifier.width(4.dp).fillMaxHeight().background(Ink))
            if (required < 1f) Spacer(Modifier.weight(1f - required))
        }
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .background(color)
            .border(3.dp, Ink)
            .padding(horizontal = 10.dp, vertical = 2.dp)
    ) { Text(text, fontWeight = FontWeight.Black, fontSize = 14.sp, color = Ink) }
}

@Composable
fun FieldLabel(text: String) {
    Text(text, fontWeight = FontWeight.Black, fontSize = 12.sp, color = Ink)
    Spacer(Modifier.height(4.dp))
}

// ---------- ERP import: reading + parsing ----------
data class ParsedRow(val name: String, val attended: Int, val total: Int, val isLab: Boolean)

private val labWord = Regex("""(?i)\blab(?:oratory)?\b""")

fun normName(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

/** Same key for a subject's theory and lab entries ("Physics" and "Physics Lab" match). */
fun subjectKey(name: String) = normName(name.replace(labWord, " "))

/** Lab entries lose the word "Lab" so they merge into their theory subject. */
private fun displayName(name: String, isLab: Boolean): String {
    val base = if (isLab) name.replace(labWord, " ") else name
    return base.replace(Regex("""\s+"""), " ").trim(' ', '-', ':', '.', '(', ')')
}

private val skipNames = setOf("total", "overall", "grandtotal", "subject", "subjects", "course", "average")

/**
 * Turns text (ERP screenshot, CSV, pasted table) into subject rows.
 * First tries the ERP "card" layout, then falls back to one-subject-per-line tables.
 */
fun parseAttendanceText(text: String, defaultLab: Boolean = false): List<ParsedRow> {
    val cards = parseCards(text, defaultLab)
    if (cards.isNotEmpty()) return cards
    return text.lines().mapNotNull { runCatching { parseAttendanceLine(it, defaultLab) }.getOrNull() }
}

/**
 * Card layout, top to bottom:
 *   Subject name (may wrap onto 2 lines)
 *   [ Theory ]  or  [ Practical ]
 *   73.53 %
 *   Total: 34   Present: 25   Absent: 9
 */
private fun parseCards(text: String, defaultLab: Boolean): List<ParsedRow> {
    val tagInBrackets = Regex("""(?i)\[\s*(theory|practical|lab)\s*]""")
    val labeled = Regex("""(?i)\b(total|present|absent)\s*[:=\-]?\s*(\d+)""")
    val percentOnly = Regex("""^\d{1,3}(?:\.\d+)?\s*%$""")

    val rows = mutableListOf<ParsedRow>()
    val pending = mutableListOf<String>() // name lines of the card being read
    var tag: String? = null
    var total: Int? = null
    var present: Int? = null

    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) continue

        // Line with "Total: 34", "Present: 25", "Absent: 9" (in any order, one or several per line)
        val values = labeled.findAll(line).toList()
        if (values.isNotEmpty()) {
            for (m in values) {
                val n = m.groupValues[2].toIntOrNull() ?: continue
                when (m.groupValues[1].lowercase()) {
                    "total" -> total = n
                    "present" -> present = n
                }
            }
            val t = total
            val p = present
            if (t != null && p != null) {
                val isLab = when (tag) {
                    "practical", "lab" -> true
                    "theory" -> false
                    else -> defaultLab || labWord.containsMatchIn(pending.joinToString(" "))
                }
                val name = displayName(pending.takeLast(3).joinToString(" "), isLab)
                if (t in 1..1500 && p in 0..t && name.count { it.isLetter() } >= 2) {
                    rows.add(ParsedRow(name, p, t, isLab))
                }
                pending.clear()
                tag = null
                total = null
                present = null
            }
            continue
        }

        val tagMatch = tagInBrackets.find(line)
        val bare = line.lowercase().filter { it.isLetter() }
        when {
            tagMatch != null -> {
                tag = tagMatch.groupValues[1].lowercase()
                val rest = line.replace(tagInBrackets, "").trim()
                if (rest.count { it.isLetter() } >= 3) pending.add(rest)
            }
            bare == "theory" || bare == "practical" -> tag = bare
            // the overall % shown above the first card is not part of any subject name
            percentOnly.matches(line) -> if (tag == null && rows.isEmpty()) pending.clear()
            line.count { it.isLetter() } < 3 -> {} // icons, arrows, clock digits
            bare == "attendance" || bare == "overallattendance" -> {}
            else -> pending.add(line)
        }
    }
    return rows
}

private fun parseAttendanceLine(raw: String, defaultLab: Boolean): ParsedRow? {
    val line = raw.replace(Regex("[,;\t|\"]"), " ").trim()
    if (line.length < 4) return null
    val tokens = line.split(Regex("""\s+""")).toMutableList()
    if (tokens.size > 3 && tokens[0].matches(Regex("""^\d+\.?$"""))) tokens.removeAt(0) // serial number

    val nameTokens = mutableListOf<String>()
    val ints = mutableListOf<Int>()
    var percent: Double? = null
    var fraction: Pair<Int, Int>? = null

    for (tk in tokens) {
        val frac = Regex("""^(\d+)/(\d+)$""").matchEntire(tk)
        val pct = Regex("""^(\d{1,3}(?:\.\d+)?)%$""").matchEntire(tk)
        when {
            frac != null -> fraction = frac.groupValues[1].toInt() to frac.groupValues[2].toInt()
            pct != null -> percent = pct.groupValues[1].toDouble()
            tk.matches(Regex("""^\d+$""")) -> ints.add(tk.toInt())
            tk.matches(Regex("""^\d+\.\d+$""")) -> if (percent == null) percent = tk.toDouble()
            tk.matches(Regex("""^[A-Za-z]{2,5}-?\d{2,4}[A-Za-z]?$""")) -> {} // course code like CS201
            else -> nameTokens.add(tk)
        }
    }

    var pair: Pair<Int, Int>? = fraction
    if (pair == null) {
        val nums = ints.toMutableList()
        // "45 38 84": the last number may be a percentage written without a % sign
        if (percent == null && nums.size >= 3 && nums.last() in 0..100) {
            val lo = minOf(nums[0], nums[1])
            val hi = maxOf(nums[0], nums[1])
            if (hi > 0 && Math.abs(lo * 100.0 / hi - nums.last()) <= 1.5) {
                percent = nums.last().toDouble()
                nums.removeAt(nums.size - 1)
            }
        }
        pair = when {
            nums.size >= 3 && nums[0] == nums[1] + nums[2] -> nums[1] to nums[0] // total, present, absent
            nums.size >= 3 && nums[0] + nums[1] == nums[2] -> nums[0] to nums[2] // present, absent, total
            nums.size >= 2 -> {
                val x = nums[0]
                val y = nums[1]
                val p = percent
                if (p != null) {
                    val xOfY = if (y > 0) x * 100.0 / y else Double.MAX_VALUE
                    val yOfX = if (x > 0) y * 100.0 / x else Double.MAX_VALUE
                    if (Math.abs(xOfY - p) <= Math.abs(yOfX - p)) x to y else y to x
                } else minOf(x, y) to maxOf(x, y)
            }
            else -> null
        }
    }
    val (attended, total) = pair ?: return null

    val rawName = nameTokens.joinToString(" ")
    val isLab = defaultLab || labWord.containsMatchIn(rawName)
    val name = displayName(rawName, isLab)
    if (name.count { it.isLetter() } < 2) return null
    if (normName(name) in skipNames) return null
    if (total !in 1..1500 || attended !in 0..total) return null
    return ParsedRow(name, attended, total, isLab)
}

/** Rebuilds table rows from OCR output by grouping text pieces that sit on the same height. */
private fun rowsFromOcr(text: MlText): String {
    val pieces = text.textBlocks.flatMap { it.lines }.mapNotNull { l -> l.boundingBox?.let { l to it } }
        .sortedBy { it.second.centerY() }
    val rows = mutableListOf<MutableList<Pair<MlText.Line, android.graphics.Rect>>>()
    for (item in pieces) {
        val last = rows.lastOrNull()
        if (last != null &&
            Math.abs(item.second.centerY() - last.map { it.second.centerY() }.average()) < item.second.height() * 0.6
        ) last.add(item) else rows.add(mutableListOf(item))
    }
    return rows.joinToString("\n") { r -> r.sortedBy { it.second.left }.joinToString(" ") { it.first.text } }
}

/** On-device text recognition (ML Kit). The image never leaves the phone. */
fun recognizeText(context: Context, uri: Uri, onDone: (String?) -> Unit) {
    try {
        val image = InputImage.fromFilePath(context, uri)
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(image)
            .addOnSuccessListener { onDone(rowsFromOcr(it)) }
            .addOnFailureListener { onDone(null) }
    } catch (e: Exception) {
        onDone(null)
    }
}

fun readTextFile(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()

// ---------- ERP import: dialog ----------
class ImportRow(name: String, attended: Int, total: Int, isLab: Boolean) {
    var selected by mutableStateOf(true)
    var name by mutableStateOf(name)
    var attended by mutableStateOf(attended.toString())
    var total by mutableStateOf(total.toString())
    var isLab by mutableStateOf(isLab)

    val valid: Boolean
        get() {
            val a = attended.toIntOrNull() ?: return false
            val t = total.toIntOrNull() ?: return false
            return name.isNotBlank() && t > 0 && a in 0..t
        }
}

@Composable
fun ImportRowCard(row: ImportRow, existing: List<Subject>) {
    val updates = existing.any { subjectKey(it.name) == subjectKey(row.name) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Cream)
            .border(3.dp, Ink)
            .padding(10.dp),
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
            Tag(
                when {
                    !row.valid -> "CHECK"
                    updates -> "UPDATES EXISTING"
                    else -> "NEW SUBJECT"
                },
                when {
                    !row.valid -> Red
                    updates -> Yellow
                    else -> Lime
                }
            )
        }
        NeoField(row.name, { row.name = it }, "Subject name")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SemChip("THEORY", !row.isLab, Sky) { row.isLab = false }
            SemChip("LAB", row.isLab, Pink) { row.isLab = true }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                FieldLabel("ATTENDED")
                NeoField(row.attended, { row.attended = it.filter(Char::isDigit).take(4) }, "0", KeyboardType.Number)
            }
            Column(Modifier.weight(1f)) {
                FieldLabel("TOTAL")
                NeoField(row.total, { row.total = it.filter(Char::isDigit).take(4) }, "0", KeyboardType.Number)
            }
        }
    }
}

@Composable
fun ImportDialog(
    semesterName: String,
    startOnLab: Boolean,
    existing: List<Subject>,
    onDismiss: () -> Unit,
    onApply: (rows: List<ParsedRow>) -> Unit
) {
    val context = LocalContext.current
    val rows = remember { mutableStateListOf<ImportRow>() }
    var stage by remember { mutableStateOf(0) } // 0 = choose source, 1 = review
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }

    fun handleText(text: String?) {
        loading = false
        val parsed = if (text.isNullOrBlank()) emptyList() else parseAttendanceText(text, startOnLab)
        if (parsed.isEmpty()) {
            status = "Couldn't find any subjects. Make sure the Total / Present numbers are visible, " +
                    "or try PASTE TEXT."
        } else {
            rows.clear()
            rows.addAll(parsed.map { ImportRow(it.name, it.attended, it.total, it.isLab) })
            status = null
            stage = 1
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            loading = true
            status = null
            recognizeText(context, uri) { handleText(it) }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            loading = true
            status = null
            handleText(readTextFile(context, uri))
        }
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
                Text("IMPORT ATTENDANCE", fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text("Into: ${semesterName.uppercase()}", fontWeight = FontWeight.Bold, fontSize = 12.sp)

                if (stage == 0) {
                    if (pasting) {
                        Column {
                            FieldLabel("PASTE YOUR ERP ATTENDANCE TEXT")
                            NeoField(
                                pasteText, { pasteText = it }, "Subject name\n[ Theory ]\nTotal: 34 Present: 25",
                                singleLine = false, minLines = 6
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NeoButton("BACK", White, Modifier.weight(1f)) { pasting = false }
                            NeoButton("READ TEXT", Lime, Modifier.weight(1f)) { handleText(pasteText) }
                        }
                    } else {
                        NeoButton("PICK SCREENSHOT", Sky) {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        NeoButton("PICK CSV / TEXT FILE", Pink) { filePicker.launch("*/*") }
                        NeoButton("PASTE TEXT", Lime) { pasting = true }
                        Text(
                            "Tip: take a screenshot of your ERP attendance page. If it has more subjects than fit " +
                                    "on one screen, import them in parts. Theory and Practical entries of the same " +
                                    "subject are merged automatically.",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
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
                } else {
                    Text(
                        "REVIEW (${rows.count { it.selected }}/${rows.size} selected)",
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "Each row is marked THEORY or LAB. Tap to change it, fix any wrong name or number, " +
                                "and untick rows you don't want.",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    rows.forEach { ImportRowCard(it, existing) }

                    val ready = rows.filter { it.selected && it.valid }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeoButton("BACK", White, Modifier.weight(1f)) { stage = 0 }
                        NeoButton("IMPORT ${ready.size}", Lime, Modifier.weight(1f)) {
                            if (ready.isNotEmpty()) {
                                onApply(
                                    ready.map {
                                        ParsedRow(it.name.trim(), it.attended.toInt(), it.total.toInt(), it.isLab)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Timetable: notifications ----------
object Reminders {
    private const val PREFS = "bunkwise_reminders"
    private const val CHANNEL = "next_class"
    private const val REQUEST = 4001

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun lead(c: Context) = prefs(c).getInt("lead", 10)

    fun setSettings(c: Context, enabled: Boolean, leadMinutes: Int) {
        prefs(c).edit().putBoolean("enabled", enabled).putInt("lead", leadMinutes).apply()
    }

    /** The receiver reads classes from here, so reminders also work offline and after a reboot. */
    fun saveSlots(c: Context, slots: List<ClassSlot>) {
        prefs(c).edit().putString("slots", slots.joinToString("\n") { encodeSlot(it) }).apply()
    }

    fun loadSlots(c: Context): List<ClassSlot> =
        (prefs(c).getString("slots", "") ?: "").lines().filter { it.isNotBlank() }.mapNotNull { decodeSlot(it) }

    fun createChannel(c: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "Next class reminders", NotificationManager.IMPORTANCE_HIGH)
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun alarmIntent(c: Context, slotId: Long = -1L, startAt: Long = 0L): PendingIntent {
        val intent = Intent(c, ReminderReceiver::class.java).putExtra("slotId", slotId).putExtra("startAt", startAt)
        return PendingIntent.getBroadcast(
            c, REQUEST, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun canScheduleExact(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    /** Sets ONE alarm for the next upcoming reminder. When it fires, the receiver sets the following one. */
    fun scheduleNext(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(c))
        if (!enabled(c)) return

        val leadMs = lead(c) * 60_000L
        val now = System.currentTimeMillis()
        val next = loadSlots(c).map { it to nextStartMillis(it, now + leadMs) }.minByOrNull { it.second } ?: return
        val trigger = next.second - leadMs
        val pi = alarmIntent(c, next.first.id, next.second)
        try {
            if (canScheduleExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    /** Called on logout: stop alerts and forget the previous account's classes. */
    fun clear(c: Context) {
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(c))
        prefs(c).edit().remove("slots").apply()
    }

    @SuppressLint("MissingPermission")
    fun showNotification(c: Context, slot: ClassSlot, startAt: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(c)

        val mins = ((startAt - System.currentTimeMillis()) / 60_000).toInt().coerceAtLeast(1)
        val kind = if (slot.isLab) "Lab" else "Theory"
        val room = if (slot.room.isNotBlank()) " - Room ${slot.room}" else ""
        val who = if (slot.teacher.isNotBlank()) " - ${slot.teacher}" else ""
        val open = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm) // swap for your own icon later
            .setContentTitle("Next class: ${slot.subject} ($kind)")
            .setContentText("Starts at ${formatTime(slot.start)} (in $mins min)$room$who")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(c).notify(2001, notification)
    }
}



/** Re-creates the alarm after a phone restart or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == Intent.ACTION_MY_PACKAGE_REPLACED || 
            action == "android.intent.action.QUICKBOOT_POWERON") {
            Reminders.scheduleNext(context)
        }
    }
}

// ---------- Timetable: reading a typed / pasted / photographed timetable ----------
data class ParsedSlot(
    val day: Int,
    val start: Int,
    val end: Int,
    val subject: String,
    val isLab: Boolean,
    val groups: List<String> = emptyList(), // batches this class is for; empty = everyone
    val room: String = "",
    val teacher: String = ""
)

private val dayWordRegex = Regex("""(?i)^(mon|tue|wed|thu|fri|sat|sun)[a-z]*\.?""")
private val timeRangeRegex =
    Regex("""(?i)(\d{1,2})[:.](\d{2})\s*([ap]\.?m\.?)?\s*(?:-|–|—|to)\s*(\d{1,2})[:.](\d{2})\s*([ap]\.?m\.?)?""")
private val breakNames = setOf("break", "lunch", "lunchbreak", "recess", "free", "freeperiod", "interval", "library")

private fun hourTo24(h: Int, raw: String, meridiem: Char?): Int? {
    if (h > 23) return null
    return when {
        meridiem == 'a' -> if (h == 12) 0 else h
        meridiem == 'p' -> if (h == 12) 12 else (h % 12) + 12
        raw.startsWith("0") || h >= 12 -> h // written like 24-hour time, or noon
        h in 1..7 -> h + 12                  // "1:00" in a college day means 1 PM
        else -> h
    }
}

private fun toRange(h1s: String, m1s: String, a1: String, h2s: String, m2s: String, a2: String): Pair<Int, Int>? {
    val h1 = h1s.toIntOrNull() ?: return null
    val h2 = h2s.toIntOrNull() ?: return null
    val m1 = m1s.toIntOrNull() ?: return null
    val m2 = m2s.toIntOrNull() ?: return null
    if (m1 > 59 || m2 > 59) return null
    val mer1 = a1.lowercase().firstOrNull()
    val mer2 = a2.lowercase().firstOrNull()

    var s = hourTo24(h1, h1s, mer1 ?: mer2) ?: return null
    var e = hourTo24(h2, h2s, mer2 ?: mer1) ?: return null
    if (s >= e) { // e.g. "11:00 - 12:00 PM" or "11:00 AM - 1:00": flip the guessed AM/PM
        if (mer1 == null && mer2 != null) s = hourTo24(h1, h1s, if (mer2 == 'p') 'a' else 'p') ?: s
        else if (mer2 == null && mer1 != null) e = hourTo24(h2, h2s, if (mer1 == 'a') 'p' else 'a') ?: e
    }
    val start = s * 60 + m1
    val end = e * 60 + m2
    return if (end > start) start to end else null
}

/**
 * Reads day-wise timetables such as:
 *   Monday
 *   9:00 - 10:00  Data Structures
 *   10:00 - 11:00 Physics Lab
 * or one class per line:  "Tue 9:00-10:00 Maths".
 */
fun parseTimetableText(text: String): List<ParsedSlot> {
    val out = mutableListOf<ParsedSlot>()
    var day: Int? = null
    var pending: Pair<Int, Int>? = null

    fun add(range: Pair<Int, Int>, name: String) {
        val d = day ?: return
        val subject = name.trim()
        if (normName(subject) in breakNames) return
        val lab = labWord.containsMatchIn(subject) || subject.contains("practical", ignoreCase = true)
        out.add(ParsedSlot(d, range.first, range.second, subject, lab))
    }

    for (raw in text.lines()) {
        var line = raw.trim()
        if (line.isEmpty()) continue

        // Day header, alone ("Monday") or at the start of a class line ("Tue 9:00-10:00 Maths")
        val dm = dayWordRegex.find(line)
        if (dm != null) {
            val word = dm.value.trimEnd('.').lowercase()
            val index = DAY_FULL.indexOfFirst { it.lowercase().startsWith(word) }
            if (word.length >= 3 && index >= 0) {
                day = index + 1
                pending = null
                line = line.substring(dm.value.length).trim(' ', ':', '-', ',')
            }
        }

        val tm = timeRangeRegex.find(line)
        if (tm != null) {
            val g = tm.groupValues
            val range = toRange(g[1], g[2], g[3], g[4], g[5], g[6]) ?: continue
            val rest = line.removeRange(tm.range).trim(' ', '-', ':', ',', '|', '(', ')')
            if (rest.count { it.isLetter() } >= 2) add(range, rest) else pending = range
        } else {
            val p = pending
            if (p != null && line.count { it.isLetter() } >= 2) {
                add(p, line)
                pending = null
            }
        }
    }
    return out
}

// ---------- Timetable: grid layout (days down the side, periods across the top) ----------
/** One piece of text found by the text recognizer, with its position on the picture. */
data class OcrBox(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val cx: Int get() = (left + right) / 2
    val cy: Int get() = (top + bottom) / 2
    val height: Int get() = bottom - top
}

/** Splits a recognized line wherever there is a big gap, so text from neighbouring cells never gets glued together. */
private fun boxesFromLine(line: MlText.Line): List<OcrBox> {
    val out = mutableListOf<OcrBox>()
    val words = mutableListOf<MlText.Element>()
    fun flush() {
        val rects = words.mapNotNull { it.boundingBox }
        if (rects.isNotEmpty()) {
            out.add(
                OcrBox(
                    words.joinToString(" ") { it.text },
                    rects.minOf { it.left }, rects.minOf { it.top }, rects.maxOf { it.right }, rects.maxOf { it.bottom }
                )
            )
        }
        words.clear()
    }
    for (w in line.elements.sortedBy { it.boundingBox?.left ?: 0 }) {
        val b = w.boundingBox ?: continue
        val prev = words.lastOrNull()?.boundingBox
        if (prev != null && b.left - prev.right > 2.5 * maxOf(b.height(), prev.height())) flush()
        words.add(w)
    }
    flush()
    return out
}

/** Like recognizeText, but also returns where each piece of text sits (needed for grid timetables). */
fun recognizeLayout(context: Context, uri: Uri, onDone: (List<OcrBox>?, String?) -> Unit) {
    try {
        val image = InputImage.fromFilePath(context, uri)
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(image)
            .addOnSuccessListener { t -> onDone(t.textBlocks.flatMap { it.lines }.flatMap { boxesFromLine(it) }, rowsFromOcr(t)) }
            .addOnFailureListener { onDone(null, null) }
    } catch (e: Exception) {
        onDone(null, null)
    }
}

private fun dayFromLabel(text: String): Int? {
    val w = text.trim().trimEnd('.', ':').lowercase()
    if (w.length < 3) return null
    val i = DAY_FULL.indexOfFirst { it.lowercase().startsWith(w) }
    return if (i >= 0) i + 1 else null
}

private val stopWords = setOf("for", "and", "of", "the", "to", "in", "using", "with", "a", "an")

/** "BEEE" matches "Basic Electrical and electronic Engineering", "Phy" matches "Physics", "Maths" matches "Mathematics". */
private fun abbreviationMatches(abbr: String, full: String): Boolean {
    val a = abbr.lowercase().filter { it.isLetter() }
    if (a.length < 2) return false
    val words = full.lowercase().split(Regex("""[^a-z0-9]+""")).filter { it.isNotEmpty() }
    val initials = words.filter { it !in stopWords }.map { it.first() }.joinToString("")
    return initials.startsWith(a) || words.any { it.startsWith(a) || (a.length >= 4 && it.startsWith(a.dropLast(1))) }
}

/** Gives a timetable abbreviation the full name of the matching attendance subject, when there is exactly one. */
fun linkSubject(name: String, existing: List<String>): String {
    existing.firstOrNull { normName(it) == normName(name) }?.let { return it }
    val hits = existing.filter { abbreviationMatches(name, it) }
    return if (hits.size == 1) hits[0] else name
}

private val batchKeyword = Regex("""(?i)^(batch|group|grp|gp)[\s\-:]*""")
private val batchPart = Regex("""^([A-Za-z]{1,2})-?(\d{1,2})$""")

/**
 * Reads batch / group tags written on a timetable cell and returns the batches the class is for:
 * "CSE H1" -> [H1], "A1" -> [A1], "CSE A1 & A2" -> [A1, A2], "A1/A2" -> [A1, A2], "Batch 2" -> [BATCH 2], "G-1" -> [G1].
 * Returns null when the text is not a batch tag (a teacher name, a room, ...).
 */
fun parseBatchTag(text: String): List<String>? {
    val raw = text.trim()
    if (raw.isEmpty()) return null
    var keyword: String? = null
    var body = raw
    batchKeyword.find(body)?.let {
        keyword = it.groupValues[1].uppercase()
        body = body.substring(it.value.length)
    }

    fun parts(s: String): List<String>? {
        val items = s.split(Regex("""\s*(?:&|/|,|\+|\band\b)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return null
        val out = mutableListOf<String>()
        for (item in items) {
            val m = batchPart.matchEntire(item)
            when {
                m != null -> out.add(m.groupValues[1].uppercase() + m.groupValues[2])
                keyword != null && item.matches(Regex("""\d{1,2}""")) -> out.add("$keyword $item")
                else -> return null
            }
        }
        return out
    }

    parts(body)?.let { return it }
    // a department / section code in front, such as "CSE " or "CSE-"
    val stripped = body.replace(Regex("""^[A-Za-z]{2,6}[\s\-]+"""), "")
    if (stripped != body) parts(stripped)?.let { return it }
    // the same without a space, e.g. "CSEH1" (text recognition sometimes drops the space)
    for (k in minOf(6, body.length - 1) downTo 2) {
        if (body.substring(0, k).all { it.isLetter() }) parts(body.substring(k))?.let { return it }
    }
    return null
}

/**
 * Reads a grid timetable from text positions:
 *  - the period times in the header row become the columns,
 *  - the day names on the left become the rows,
 *  - big text inside a cell is the subject, small text is the room, the batch (CSE H1 / H2) or the teacher,
 *  - a subject centred on the line between two periods (a lab) covers both periods.
 */
fun parseTimetableGrid(boxes: List<OcrBox>): List<ParsedSlot> {
    class Col(val start: Int, val end: Int, val cx: Int)

    // 1. columns
    val headerHits = boxes.mapNotNull { b ->
        val g = timeRangeRegex.find(b.text)?.groupValues ?: return@mapNotNull null
        val r = toRange(g[1], g[2], g[3], g[4], g[5], g[6]) ?: return@mapNotNull null
        Triple(Col(r.first, r.second, b.cx), b.cy, b.bottom)
    }
    if (headerHits.size < 3) return emptyList()
    val headerY = headerHits.map { it.second }.sorted()[headerHits.size / 2]
    val header = headerHits.filter { Math.abs(it.second - headerY) < 25 }.sortedBy { it.first.cx }
    val cols = ArrayList<Col>()
    for (h in header) if (cols.isEmpty() || h.first.cx - cols.last().cx > 20) cols.add(h.first)
    if (cols.size < 3) return emptyList()
    val headerBottom = header.maxOf { it.third }
    val colW = (cols.last().cx - cols.first().cx).toDouble() / (cols.size - 1)
    val gridLeft = cols.first().cx - colW / 2
    val gridRight = cols.last().cx + colW / 2

    // 2. rows
    val dayRows = mutableListOf<Pair<Int, Int>>() // day number to its vertical position
    for (b in boxes.sortedBy { it.cy }) {
        if (b.cx >= gridLeft || b.cy <= headerBottom) continue
        val d = dayFromLabel(b.text) ?: continue
        if (dayRows.none { it.first == d }) dayRows.add(d to b.cy)
    }
    if (dayRows.isEmpty()) return emptyList()
    val rowH = if (dayRows.size > 1) (dayRows.last().second - dayRows.first().second).toDouble() / (dayRows.size - 1) else colW
    val gridBottom = dayRows.last().second + rowH / 2
    fun dayAt(y: Int) = dayRows.minByOrNull { Math.abs(it.second - y) }!!.first

    // 3. text inside the grid
    val content = boxes.filter { it.cx > gridLeft && it.cx < gridRight && it.cy > headerBottom && it.cy < gridBottom }
    if (content.isEmpty()) return emptyList()
    val heights = content.map { it.height }.sorted()
    val bigH = heights[(heights.size * 0.85).toInt().coerceAtMost(heights.size - 1)]
    val defaultRoom = boxes.firstOrNull {
        it.cy < headerY - 5 && Regex("""(?i)^(?:LH|LT|RM|ROOM)\s*-?\s*\d+[A-Z]?$""").matches(it.text.trim())
    }?.text?.trim() ?: ""

    class Placed(val box: OcrBox, val day: Int, val c0: Int, val c1: Int) {
        var room = ""
        val groups = mutableListOf<String>()
        val teacher = mutableListOf<String>()
    }

    val placed = content.filter { it.height >= 0.6 * bigH && it.text.count { c -> c.isLetter() } >= 2 }.map { b ->
        val t = (b.cx - cols.first().cx) / colW
        val fl = Math.floor(t).toInt()
        val onBoundary = (t - fl) in 0.3..0.7 // centred between two periods: a lab covering both
        val first = (if (onBoundary) fl else Math.round(t).toInt()).coerceIn(0, cols.size - 1)
        val last = (if (onBoundary) fl + 1 else first).coerceIn(first, cols.size - 1)
        Placed(b, dayAt(b.cy), first, last)
    }

    // 4. attach rooms, batches and teachers to the nearest subject in the same cell
    val roomRegex = Regex("""^\d{2,4}[A-Za-z]?$""")
    for (s in content.filter { it.height < 0.6 * bigH }) {
        val d = dayAt(s.cy)
        val owner = placed
            .filter { it.day == d && s.cx >= gridLeft + it.c0 * colW && s.cx <= gridLeft + (it.c1 + 1) * colW }
            .minByOrNull { Math.abs(it.box.cy - s.cy) } ?: continue
        val text = s.text.trim()
        val batch = parseBatchTag(text)
        when {
            roomRegex.matches(text) -> owner.room = text
            batch != null -> batch.forEach { if (it !in owner.groups) owner.groups.add(it) }
            text.count { it.isLetter() } >= 3 -> owner.teacher.add(text)
        }
    }

    // 5. one entry per cell
    return placed.mapNotNull { p ->
        val name = p.box.text.trim()
        if (normName(name) in breakNames) return@mapNotNull null
        val lab = labWord.containsMatchIn(name) || name.contains("practical", ignoreCase = true)
        val subject = displayName(name, lab)
        if (subject.count { it.isLetter() } < 2) return@mapNotNull null
        ParsedSlot(
            p.day, cols[p.c0].start, cols[p.c1].end, subject, lab,
            p.groups.toList(), p.room.ifBlank { defaultRoom }, p.teacher.joinToString(", ")
        )
    }.sortedWith(compareBy({ it.day }, { it.start }))
}

// ---------- Timetable: screen ----------
@Composable
fun ScreenSwitch(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TabButton("ATTENDANCE", Lime, selected == 0, Modifier.weight(1f)) { onSelect(0) }
        TabButton("TIMETABLE", Yellow, selected == 1, Modifier.weight(1f)) { onSelect(1) }
    }
}

@Composable
fun SlotCard(slot: ClassSlot, onEdit: () -> Unit, onDelete: () -> Unit) {
    NeoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(86.dp)) {
                Text(formatTime(slot.start), fontWeight = FontWeight.Black, fontSize = 14.sp)
                Text(formatTime(slot.end), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(slot.subject, fontWeight = FontWeight.Black, fontSize = 16.sp)
                if (slot.teacher.isNotBlank()) Text(slot.teacher, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(if (slot.isLab) "LAB" else "THEORY", if (slot.isLab) Pink else Sky)
                    if (slot.room.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text("Room ${slot.room}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                Box(
                    Modifier.background(Yellow).border(3.dp, Ink).clickable { onEdit() }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) { Text("EDIT", fontWeight = FontWeight.Black, fontSize = 12.sp) }
                Box(
                    Modifier.background(Red).border(3.dp, Ink).clickable { onDelete() }
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                ) { Text("X", fontWeight = FontWeight.Black, fontSize = 14.sp) }
            }
        }
    }
}

@Composable
fun TimetableScreen(slots: List<ClassSlot>, onEdit: (ClassSlot) -> Unit, onDelete: (ClassSlot) -> Unit) {
    val context = LocalContext.current
    val next = remember(slots) { nextClass(slots, System.currentTimeMillis()) }
    var day by rememberSaveable { mutableStateOf(todayIndex()) }
    var enabled by remember { mutableStateOf(Reminders.enabled(context)) }
    var lead by remember { mutableStateOf(Reminders.lead(context)) }
    var blocked by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        blocked = !granted
    }

    fun saveReminder(on: Boolean, minutes: Int) {
        enabled = on
        lead = minutes
        Reminders.setSettings(context, on, minutes)
        Reminders.createChannel(context)
        Reminders.scheduleNext(context)
    }

    val daySlots = slots.filter { it.day == day }.sortedBy { it.start }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Next class
        item {
            NeoCard(color = Lime) {
                Text("NEXT CLASS", fontWeight = FontWeight.Black, fontSize = 14.sp)
                if (next == null) {
                    Spacer(Modifier.height(4.dp))
                    Text("No classes added yet. Tap + ADD CLASS or IMPORT.", fontWeight = FontWeight.Bold)
                } else {
                    val (slot, at) = next
                    Text(slot.subject.uppercase(), fontWeight = FontWeight.Black, fontSize = 24.sp)
                    Text(
                        "${dayLabel(at)}, ${formatTime(slot.start)} - ${formatTime(slot.end)}",
                        fontWeight = FontWeight.Bold
                    )
                    val until = untilText(at, System.currentTimeMillis())
                    val room = if (slot.room.isNotBlank()) "Room ${slot.room}" else ""
                    val info = listOf(until, room, slot.teacher).filter { it.isNotEmpty() }.joinToString("  -  ")
                    if (info.isNotEmpty()) Text(info, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        // Reminder settings
        item {
            NeoCard(color = Yellow) {
                Text("CLASS REMINDERS", fontWeight = FontWeight.Black, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                CheckRow("NOTIFY ME BEFORE EACH CLASS", enabled) {
                    val turnOn = !enabled
                    if (turnOn && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    saveReminder(turnOn, lead)
                }
                if (enabled) {
                    Spacer(Modifier.height(12.dp))
                    Text("REMIND ME", fontWeight = FontWeight.Black, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(listOf(5, 10, 15, 30)) { m ->
                            SemChip("$m MIN BEFORE", lead == m, Lime) { saveReminder(true, m) }
                        }
                    }
                    if (blocked) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Notifications are blocked for this app. Turn them on in your phone's Settings > Apps > Notifications.",
                            fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                    }
                    if (!Reminders.canScheduleExact(context)) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "For on-time alerts, allow exact alarms for this app.",
                            fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        NeoButton("ALLOW EXACT ALARMS", White) {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }
                }
            }
        }

        // Day selector
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items((1..7).toList()) { d -> SemChip(DAY_SHORT[d - 1], d == day, Sky) { day = d } }
            }
        }

        if (daySlots.isEmpty()) {
            item {
                NeoCard(color = Sky) {
                    Text("NO CLASSES ON ${DAY_FULL[day - 1].uppercase()}", fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
            }
        }
        items(daySlots, key = { it.id }) { slot ->
            SlotCard(slot, onEdit = { onEdit(slot) }, onDelete = { onDelete(slot) })
        }
    }
}

@Composable
fun SlotDialog(
    initial: ClassSlot?,
    defaultDay: Int,
    subjectNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (days: Set<Int>, start: Int, end: Int, subject: String, isLab: Boolean, room: String, teacher: String) -> Unit
) {
    val context = LocalContext.current
    var subject by remember { mutableStateOf(initial?.subject ?: "") }
    var days by remember { mutableStateOf(setOf(initial?.day ?: defaultDay)) }
    var start by remember { mutableStateOf(initial?.start ?: 9 * 60) }
    var end by remember { mutableStateOf(initial?.end ?: 10 * 60) }
    var isLab by remember { mutableStateOf(initial?.isLab ?: false) }
    var room by remember { mutableStateOf(initial?.room ?: "") }
    var teacher by remember { mutableStateOf(initial?.teacher ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

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
                Text(if (initial == null) "ADD CLASS" else "EDIT CLASS", fontWeight = FontWeight.Black, fontSize = 22.sp)

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
                    FieldLabel("DAYS (TAP SEVERAL FOR THE SAME CLASS)")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items((1..7).toList()) { d ->
                            SemChip(DAY_SHORT[d - 1], d in days, Sky) {
                                days = if (d in days) { if (days.size > 1) days - d else days } else days + d
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel("STARTS")
                        NeoButton(formatTime(start), White) {
                            pickTime(start) {
                                start = it
                                if (end <= it) end = minOf(it + 60, 23 * 60 + 59)
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        FieldLabel("ENDS")
                        NeoButton(formatTime(end), White) { pickTime(end) { end = it } }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SemChip("THEORY", !isLab, Sky) { isLab = false }
                    SemChip("LAB", isLab, Pink) { isLab = true }
                }

                Column {
                    FieldLabel("ROOM (OPTIONAL)")
                    NeoField(room, { room = it }, "e.g. 204")
                }

                Column {
                    FieldLabel("TEACHER (OPTIONAL)")
                    NeoField(teacher, { teacher = it }, "e.g. Dr. Harsimran")
                }

                error?.let { Text(it, fontWeight = FontWeight.Black) }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeoButton("CANCEL", White, Modifier.weight(1f), onDismiss)
                    NeoButton("SAVE", Lime, Modifier.weight(1f)) {
                        when {
                            subject.isBlank() -> error = "Enter a subject name."
                            end <= start -> error = "End time must be after the start time."
                            else -> onSave(days, start, end, subject.trim(), isLab, room.trim(), teacher.trim())
                        }
                    }
                }
            }
        }
    }
}

class SlotImportRow(val slot: ParsedSlot) {
    var selected by mutableStateOf(true)
    var subject by mutableStateOf(slot.subject)
    var isLab by mutableStateOf(slot.isLab)
}

@Composable
fun TimetableImportDialog(
    subjectNames: List<String>,
    onDismiss: () -> Unit,
    onApply: (List<ParsedSlot>) -> Unit
) {
    val context = LocalContext.current
    val rows = remember { mutableStateListOf<SlotImportRow>() }
    var stage by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") } // your batch, e.g. A1 or H2; "" = show every batch

    fun handleSlots(parsed: List<ParsedSlot>) {
        loading = false
        if (parsed.isEmpty()) {
            status = "Couldn't read any classes. Try a clearer, straight-on screenshot of the whole timetable, " +
                    "or add classes with + ADD CLASS."
            return
        }
        // "BEEE" becomes "Basic Electrical and electronic Engineering" when you already track that subject
        val linked = parsed.map { it.copy(subject = linkSubject(it.subject, subjectNames)) }
        rows.clear()
        rows.addAll(linked.map { SlotImportRow(it) })
        group = linked.flatMap { it.groups }.distinct().sorted().firstOrNull() ?: ""
        status = null
        stage = 1
    }

    fun handleText(text: String?) {
        handleSlots(if (text.isNullOrBlank()) emptyList() else parseTimetableText(text))
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            loading = true
            status = null
            recognizeLayout(context, uri) { boxes, text ->
                val grid = if (boxes != null) parseTimetableGrid(boxes) else emptyList()
                if (grid.isNotEmpty()) handleSlots(grid) else handleText(text)
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            loading = true
            status = null
            handleText(readTextFile(context, uri))
        }
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
                Text("IMPORT TIMETABLE", fontWeight = FontWeight.Black, fontSize = 22.sp)

                if (stage == 0) {
                    if (pasting) {
                        Column {
                            FieldLabel("PASTE YOUR TIMETABLE")
                            NeoField(
                                pasteText, { pasteText = it }, "Monday\n9:00 - 10:00 Data Structures\n10:00 - 11:00 Physics",
                                singleLine = false, minLines = 7
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NeoButton("BACK", White, Modifier.weight(1f)) { pasting = false }
                            NeoButton("READ TEXT", Lime, Modifier.weight(1f)) { handleText(pasteText) }
                        }
                    } else {
                        NeoButton("PICK TIMETABLE SCREENSHOT", Sky) {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        NeoButton("PICK CSV / TEXT FILE", Pink) { filePicker.launch("*/*") }
                        NeoButton("PASTE TEXT", Lime) { pasting = true }
                        Text(
                            "Works with a screenshot of your grid timetable (days down the side, periods across " +
                                    "the top) and with day-wise lists. If your timetable has batches such as CSE H1 / H2, " +
                                    "you pick yours before importing.",
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
                } else {
                    val groups = rows.flatMap { it.slot.groups }.distinct().sorted()
                    val shown = rows.filter { group == "" || it.slot.groups.isEmpty() || group in it.slot.groups }
                    val picked = shown.filter { it.selected }
                    fun overlaps(r: SlotImportRow) = picked.any {
                        it !== r && it.slot.day == r.slot.day && it.slot.start < r.slot.end && r.slot.start < it.slot.end
                    }

                    Text("REVIEW (${shown.count { it.selected }}/${shown.size} selected)", fontWeight = FontWeight.Black)

                    if (groups.isNotEmpty()) {
                        Column {
                            FieldLabel("YOUR LAB / TUTORIAL BATCH")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(groups) { g -> SemChip(g, g == group, Lime) { group = g } }
                                item { SemChip("ALL", group == "", Yellow) { group = "" } }
                            }
                            Text(
                                "Only your batch's classes are added. Classes for everyone are always included. " +
                                        "ALL shows every batch.",
                                fontWeight = FontWeight.Bold, fontSize = 12.sp
                            )
                        }
                    }

                    Text(
                        "Check the names, set THEORY or LAB, and untick classes you don't want. " +
                                "If a day or time is wrong, untick it and add it with + ADD CLASS.",
                        fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )
                    shown.forEach { row ->
                        Column(
                            Modifier.fillMaxWidth().background(Cream).border(3.dp, Ink).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(28.dp).background(if (row.selected) Lime else White).border(3.dp, Ink)
                                        .clickable { row.selected = !row.selected },
                                    contentAlignment = Alignment.Center
                                ) { if (row.selected) Text("X", fontWeight = FontWeight.Black) }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "${DAY_SHORT[row.slot.day - 1]}  ${formatTime(row.slot.start)} - ${formatTime(row.slot.end)}",
                                    fontWeight = FontWeight.Black
                                )
                            }
                            if (row.selected && overlaps(row)) {
                                Tag("OVERLAPS ANOTHER CLASS - UNTICK ONE", Red)
                            }
                            NeoField(row.subject, { row.subject = it }, "Subject")
                            val extra = listOf(
                                if (row.slot.room.isNotBlank()) "Room ${row.slot.room}" else "",
                                row.slot.teacher
                            ).filter { it.isNotBlank() }.joinToString("  -  ")
                            if (extra.isNotEmpty()) Text(extra, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SemChip("THEORY", !row.isLab, Sky) { row.isLab = false }
                                SemChip("LAB", row.isLab, Pink) { row.isLab = true }
                            }
                        }
                    }
                    val ready = shown.filter { it.selected && it.subject.isNotBlank() }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeoButton("BACK", White, Modifier.weight(1f)) { stage = 0 }
                        NeoButton("IMPORT ${ready.size}", Lime, Modifier.weight(1f)) {
                            if (ready.isNotEmpty()) {
                                onApply(ready.map { it.slot.copy(subject = it.subject.trim(), isLab = it.isLab) })
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Semester switcher ----------
@Composable
fun SemChip(text: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    val shift by animateDpAsState(if (selected) 3.dp else 0.dp, label = "chip")
    Box(Modifier.padding(end = 4.dp, bottom = 4.dp)) {
        Box(
            Modifier
                .offset(shift, shift)
                .neoShadow(4.dp - shift)
                .background(if (selected) color else White)
                .border(3.dp, Ink)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(text.uppercase(), fontWeight = FontWeight.Black, fontSize = 14.sp, color = Ink)
        }
    }
}

@Composable
fun SemesterBar(
    semesters: List<Semester>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onManage: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SEMESTER", modifier = Modifier.weight(1f), fontWeight = FontWeight.Black, fontSize = 12.sp)
            Box(
                Modifier
                    .background(Yellow)
                    .border(3.dp, Ink)
                    .clickable { onManage() }
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            ) { Text("EDIT", fontWeight = FontWeight.Black, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(semesters, key = { it.id }) { sem ->
                SemChip(sem.name, sem.id == selectedId, Yellow) { onSelect(sem.id) }
            }
            item { SemChip("+ NEW", false, Lime) { onNew() } }
        }
    }
}

@Composable
fun SemesterDialog(
    title: String,
    initialName: String,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var confirm by remember { mutableStateOf(false) }
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
                Text(title, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Column {
                    FieldLabel("SEMESTER NAME")
                    NeoField(name, { name = it }, "e.g. Semester 4")
                }
                error?.let { Text(it, fontWeight = FontWeight.Black) }
                if (canDelete) {
                    NeoButton(
                        if (confirm) "TAP AGAIN: DELETE ALL ITS SUBJECTS" else "DELETE SEMESTER",
                        Red
                    ) { if (confirm) onDelete() else confirm = true }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeoButton("CANCEL", White, Modifier.weight(1f), onDismiss)
                    NeoButton("SAVE", Lime, Modifier.weight(1f)) {
                        if (name.isBlank()) error = "Enter a semester name." else onSave(name.trim())
                    }
                }
            }
        }
    }
}

// ---------- Login screen ----------
@Composable
fun AuthScreen(onLoggedIn: () -> Unit) {
    val auth = Firebase.auth
    var signUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun submit() {
        val e = email.trim()
        if (e.isEmpty() || password.isEmpty()) {
            message = "Enter your email and password."
            return
        }
        if (signUp && password.length < 8) {
            message = "Use a password of at least 8 characters."
            return
        }
        loading = true
        message = null
        val task = if (signUp) auth.createUserWithEmailAndPassword(e, password)
        else auth.signInWithEmailAndPassword(e, password)
        task.addOnCompleteListener { t ->
            loading = false
            if (t.isSuccessful) {
                if (signUp) auth.currentUser?.sendEmailVerification()
                onLoggedIn()
            } else {
                message = t.exception?.localizedMessage ?: "Something went wrong. Try again."
            }
        }
    }

    fun googleSignIn() {
        loading = true
        message = null
        scope.launch {
            try {
                // R.string.default_web_client_id is generated by the google-services plugin
                val option = GetSignInWithGoogleOption.Builder(context.getString(R.string.default_web_client_id)).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = CredentialManager.create(context).getCredential(context, request)
                val cred = result.credential
                if (cred is CustomCredential &&
                    cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                        .addOnCompleteListener { t ->
                            loading = false
                            if (t.isSuccessful) onLoggedIn()
                            else message = t.exception?.localizedMessage ?: "Google sign-in failed."
                        }
                } else {
                    loading = false
                    message = "Unexpected sign-in response. Try again."
                }
            } catch (e: GetCredentialException) {
                loading = false
                message = e.localizedMessage ?: "Google sign-in was cancelled."
            }
        }
    }

    fun resetPassword() {
        val e = email.trim()
        if (e.isEmpty()) {
            message = "Type your email first, then tap FORGOT PASSWORD."
            return
        }
        auth.sendPasswordResetEmail(e).addOnCompleteListener { t ->
            message = if (t.isSuccessful) "Reset link sent to $e. Check your inbox."
            else t.exception?.localizedMessage ?: "Couldn't send the reset email."
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cream)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Yellow)
                .border(3.dp, Ink)
                .padding(20.dp)
        ) {
            Column {
                Text(
                    "BUNK WISE",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = Ink
                )
                Text("Log in to back up your attendance.", fontWeight = FontWeight.Bold, color = Ink)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TabButton("LOGIN", Sky, !signUp, Modifier.weight(1f)) { signUp = false; message = null }
            TabButton("SIGN UP", Pink, signUp, Modifier.weight(1f)) { signUp = true; message = null }
        }

        Box(Modifier.padding(16.dp)) {
            NeoCard {
                FieldLabel("EMAIL")
                NeoField(email, { email = it.trim() }, "you@college.edu", KeyboardType.Email)
                Spacer(Modifier.height(12.dp))
                FieldLabel("PASSWORD")
                NeoField(password, { password = it }, "min 8 characters for sign up", KeyboardType.Password, PasswordVisualTransformation())
                Spacer(Modifier.height(14.dp))

                message?.let {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Yellow)
                            .border(3.dp, Ink)
                            .padding(10.dp)
                    ) { Text(it, fontWeight = FontWeight.Black) }
                    Spacer(Modifier.height(14.dp))
                }

                NeoButton(
                    text = when {
                        loading -> "PLEASE WAIT..."
                        signUp -> "CREATE ACCOUNT"
                        else -> "LOGIN"
                    },
                    color = Lime
                ) { if (!loading) submit() }

                if (!signUp) NeoButton("FORGOT PASSWORD?", White) { resetPassword() }

                Spacer(Modifier.height(10.dp))
                Text(
                    "OR",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.height(10.dp))
                NeoButton("G   CONTINUE WITH GOOGLE", White) { if (!loading) googleSignIn() }
            }
        }
    }
}

// ---------- Email verification screen ----------
@Composable
fun VerifyEmailScreen(user: FirebaseUser, onVerified: () -> Unit, onLogout: () -> Unit) {
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun checkVerified() {
        loading = true
        message = null
        user.reload().addOnCompleteListener { r ->
            if (r.isSuccessful && user.isEmailVerified) {
                // Refresh the sign-in token so the server also sees the email as verified.
                user.getIdToken(true).addOnCompleteListener {
                    loading = false
                    onVerified()
                }
            } else {
                loading = false
                message = if (r.isSuccessful) "Not verified yet. Open the link in the email first."
                else r.exception?.localizedMessage ?: "Couldn't check. Check your internet and try again."
            }
        }
    }

    fun resend() {
        user.sendEmailVerification().addOnCompleteListener { t ->
            message = if (t.isSuccessful) "Verification email sent again. Check spam too."
            else t.exception?.localizedMessage ?: "Couldn't send it. Wait a minute and try again."
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cream)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Yellow)
                .border(3.dp, Ink)
                .padding(20.dp)
        ) {
            Column {
                Text(
                    "BUNK WISE",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = Ink
                )
                Text("Verify your email to continue.", fontWeight = FontWeight.Bold, color = Ink)
            }
        }

        Box(Modifier.padding(16.dp)) {
            NeoCard {
                Text("CHECK YOUR INBOX", fontWeight = FontWeight.Black, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "We sent a verification link to ${user.email ?: "your email"}. " +
                            "Tap the link, then come back and press the button below. Check your spam folder too.",
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(14.dp))

                message?.let {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Yellow)
                            .border(3.dp, Ink)
                            .padding(10.dp)
                    ) { Text(it, fontWeight = FontWeight.Black) }
                    Spacer(Modifier.height(14.dp))
                }

                NeoButton(if (loading) "CHECKING..." else "I HAVE VERIFIED MY EMAIL", Lime) {
                    if (!loading) checkVerified()
                }
                NeoButton("RESEND EMAIL", Sky) { resend() }
                NeoButton("WRONG EMAIL? LOG OUT", White) { onLogout() }
            }
        }
    }
}

// ---------- Main screen ----------
@Composable
fun BunkWiseApp(user: FirebaseUser, onLogout: () -> Unit) {
    val context = LocalContext.current
    val store = remember(user.uid) { SubjectStore(context.applicationContext, user.uid) }
    var data by remember(user.uid) { mutableStateOf(store.loadLocal()) }
    val current = data.semesters.firstOrNull { it.id == data.selectedId } ?: data.semesters.first()
    val subjects = current.subjects // subjects of the selected semester
    // After reinstalling there is no local data, so wait for the cloud copy before showing the list.
    var ready by remember(user.uid) { mutableStateOf(data.hasContent) }
    var dirty by remember(user.uid) { mutableStateOf(false) }
    var syncStatus by remember(user.uid) { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var screen by rememberSaveable { mutableStateOf(0) } // 0 = attendance, 1 = timetable
    var showSlotDialog by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf<ClassSlot?>(null) }
    var showSlotImport by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Subject?>(null) }
    var semDialog by remember { mutableStateOf(0) } // 0 = none, 1 = new semester, 2 = edit semester
    var tab by rememberSaveable { mutableStateOf(0) } // 0 = THEORY, 1 = LAB

    LaunchedEffect(user.uid) {
        syncStatus = "SYNCING..."
        store.fetchCloud { cloud, failed ->
            when {
                cloud != null -> {
                    // Restore from the cloud (unless the user already changed something meanwhile).
                    if (!dirty) {
                        data = cloud
                        store.saveLocal(cloud)
                    }
                    syncStatus = "SYNCED"
                }
                !failed -> {
                    // First login on this account: upload whatever is on the phone.
                    if (data.hasContent) store.saveCloud(data) { }
                    syncStatus = "SYNCED"
                }
                else -> syncStatus = "OFFLINE"
            }
            ready = true
        }
    }

    fun commit(new: AppData) {
        data = new
        dirty = true
        store.saveLocal(new)
        syncStatus = "SYNCING..."
        store.saveCloud(new) { ok -> syncStatus = if (ok) "SYNCED" else "SYNC FAILED" }
    }

    /** Saves a new subject list into the selected semester. */
    fun update(new: List<Subject>) {
        commit(data.copy(semesters = data.semesters.map { if (it.id == current.id) it.copy(subjects = new) else it }))
    }

    /** Saves a new timetable into the selected semester. */
    fun updateSlots(new: List<ClassSlot>) {
        commit(data.copy(semesters = data.semesters.map { if (it.id == current.id) it.copy(slots = new) else it }))
    }

    // Keep the reminder alarm in step with the selected semester's timetable.
    LaunchedEffect(current.id, current.slots) {
        Reminders.saveSlots(context, current.slots)
        Reminders.scheduleNext(context)
    }

    if (!ready) {
        Box(
            Modifier.fillMaxSize().background(Cream).statusBarsPadding().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            NeoCard(color = Yellow) {
                Text("LOADING YOUR DATA...", fontWeight = FontWeight.Black, fontSize = 20.sp)
                Spacer(Modifier.height(4.dp))
                Text("Restoring your subjects from the cloud.", fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Cream)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Yellow)
                    .border(3.dp, Ink)
                    .padding(20.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "BUNK WISE",
                            modifier = Modifier.weight(1f),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = Ink
                        )
                        Box(
                            Modifier
                                .background(Red)
                                .border(3.dp, Ink)
                                .clickable { onLogout() }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text("LOGOUT", fontWeight = FontWeight.Black, fontSize = 12.sp, color = Ink) }
                    }
                    Text("Skip smart. Stay above the line.", fontWeight = FontWeight.Bold, color = Ink)
                    Text(
                        "${user.email ?: ""}  -  $syncStatus",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Ink
                    )
                }
            }

            ScreenSwitch(selected = screen, onSelect = { screen = it })

            SemesterBar(
                semesters = data.semesters,
                selectedId = current.id,
                onSelect = { commit(data.copy(selectedId = it)) },
                onNew = { semDialog = 1 },
                onManage = { semDialog = 2 }
            )

            if (screen == 1) {
                TimetableScreen(
                    slots = current.slots,
                    onEdit = {
                        editingSlot = it
                        showSlotDialog = true
                    },
                    onDelete = { slot -> updateSlots(current.slots.filter { it.id != slot.id }) }
                )
            } else {
                TabSwitch(selected = tab, onSelect = { tab = it })

                val visible = if (tab == 0) subjects.filter { it.theory != null } else subjects.filter { it.lab != null }

                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    item { OverallCard(subjects, tab) }

                    if (visible.isEmpty()) {
                        item {
                            NeoCard(color = if (tab == 0) Sky else Pink) {
                                if (tab == 0) {
                                    Text("NO SUBJECTS IN ${current.name.uppercase()} YET", fontWeight = FontWeight.Black, fontSize = 18.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Text("Tap + ADD SUBJECT to start tracking.", fontWeight = FontWeight.Bold)
                                } else {
                                    Text("NO LAB SUBJECTS", fontWeight = FontWeight.Black, fontSize = 18.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Add a subject with a lab, or tap EDIT on a subject and tick the lab box.",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    items(visible, key = { it.id }) { s ->
                        SubjectCard(
                            s = s,
                            tab = tab,
                            onChange = { changed -> update(subjects.map { if (it.id == s.id) changed else it }) },
                            onEdit = { editing = s },
                            onDelete = {
                                // Removes the subject from THIS tab only. If no section is left, it is deleted.
                                val updated = if (tab == 0) s.copy(theory = null) else s.copy(lab = null)
                                if (updated.theory == null && updated.lab == null) update(subjects.filter { it.id != s.id })
                                else update(subjects.map { if (it.id == s.id) updated else it })
                            }
                        )
                    }
                }
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (screen == 0) {
                NeoButton("IMPORT", Sky, Modifier.weight(1f)) { showImport = true }
                NeoButton("+ ADD SUBJECT", Lime, Modifier.weight(1f)) { showAdd = true }
            } else {
                NeoButton("IMPORT", Sky, Modifier.weight(1f)) { showSlotImport = true }
                NeoButton("+ ADD CLASS", Lime, Modifier.weight(1f)) {
                    editingSlot = null
                    showSlotDialog = true
                }
            }
        }
    }

    if (showAdd) {
        SubjectDialog(
            title = "NEW SUBJECT",
            initial = null,
            startWithLab = tab == 1,
            onDismiss = { showAdd = false },
            onSave = { name, theory, lab ->
                update(subjects + Subject(System.currentTimeMillis(), name, theory, lab))
                showAdd = false
            }
        )
    }

    if (showSlotDialog) {
        SlotDialog(
            initial = editingSlot,
            defaultDay = todayIndex(),
            subjectNames = subjects.map { it.name },
            onDismiss = {
                showSlotDialog = false
                editingSlot = null
            },
            onSave = { days, start, end, subject, isLab, room, teacher ->
                val old = editingSlot
                var list = if (old != null) current.slots.filter { it.id != old.id } else current.slots
                val base = System.currentTimeMillis()
                // one class entry per ticked day
                days.sorted().forEachIndexed { i, d ->
                    list = list + ClassSlot(base + i, d, start, end, subject, isLab, room, teacher)
                }
                updateSlots(list)
                showSlotDialog = false
                editingSlot = null
            }
        )
    }

    if (showSlotImport) {
        TimetableImportDialog(
            subjectNames = subjects.map { it.name },
            onDismiss = { showSlotImport = false },
            onApply = { parsed ->
                var list = current.slots
                val base = System.currentTimeMillis()
                parsed.forEachIndexed { i, p ->
                    val duplicate = list.any {
                        it.day == p.day && it.start == p.start && normName(it.subject) == normName(p.subject)
                    }
                    if (!duplicate) list = list + ClassSlot(base + i, p.day, p.start, p.end, p.subject, p.isLab, p.room, p.teacher)
                }
                updateSlots(list)
                showSlotImport = false
            }
        )
    }

    if (showImport) {
        ImportDialog(
            semesterName = current.name,
            startOnLab = tab == 1,
            existing = subjects,
            onDismiss = { showImport = false },
            onApply = { rows ->
                var list = subjects
                rows.forEach { r ->
                    // "Physics" (theory) and "Physics Lab" (practical) land in the same subject
                    val key = subjectKey(r.name)
                    val idx = list.indexOfFirst { subjectKey(it.name) == key }
                    list = if (idx >= 0) {
                        // Subject already exists: update its numbers, keep the required %.
                        list.mapIndexed { i, sub ->
                            when {
                                i != idx -> sub
                                r.isLab -> sub.copy(lab = (sub.lab ?: Record()).copy(attended = r.attended, total = r.total))
                                else -> sub.copy(theory = (sub.theory ?: Record()).copy(attended = r.attended, total = r.total))
                            }
                        }
                    } else {
                        list + Subject(
                            System.currentTimeMillis() + list.size,
                            r.name,
                            if (r.isLab) null else Record(r.attended, r.total),
                            if (r.isLab) Record(r.attended, r.total) else null
                        )
                    }
                }
                update(list)
                tab = if (rows.all { it.isLab }) 1 else 0
                showImport = false
            }
        )
    }

    if (semDialog == 1) {
        SemesterDialog(
            title = "NEW SEMESTER",
            initialName = "Semester ${data.semesters.size + 1}",
            canDelete = false,
            onDismiss = { semDialog = 0 },
            onSave = { name ->
                val id = System.currentTimeMillis()
                commit(data.copy(semesters = data.semesters + Semester(id, name, emptyList()), selectedId = id))
                semDialog = 0
            },
            onDelete = {}
        )
    }

    if (semDialog == 2) {
        SemesterDialog(
            title = "EDIT SEMESTER",
            initialName = current.name,
            canDelete = data.semesters.size > 1,
            onDismiss = { semDialog = 0 },
            onSave = { name ->
                commit(data.copy(semesters = data.semesters.map { if (it.id == current.id) it.copy(name = name) else it }))
                semDialog = 0
            },
            onDelete = {
                val remaining = data.semesters.filter { it.id != current.id }
                commit(AppData(remaining, remaining.first().id))
                semDialog = 0
            }
        )
    }

    editing?.let { target ->
        SubjectDialog(
            title = "EDIT SUBJECT",
            initial = target,
            onDismiss = { editing = null },
            onSave = { name, theory, lab ->
                update(subjects.map {
                    if (it.id == target.id) it.copy(name = name, theory = theory, lab = lab) else it
                })
                editing = null
            }
        )
    }
}

@Composable
fun TabSwitch(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TabButton("THEORY", Sky, selected == 0, Modifier.weight(1f)) { onSelect(0) }
        TabButton("LAB", Pink, selected == 1, Modifier.weight(1f)) { onSelect(1) }
    }
}

/** Selected tab looks pressed-in and colored; the other is raised and white. */
@Composable
fun TabButton(text: String, color: Color, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shift by animateDpAsState(if (selected) 4.dp else 0.dp, label = "tabShift")
    Box(modifier.padding(end = 4.dp, bottom = 4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .offset(shift, shift)
                .neoShadow(4.dp - shift)
                .background(if (selected) color else White)
                .border(3.dp, Ink)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, fontWeight = FontWeight.Black, fontSize = 16.sp, color = Ink)
        }
    }
}

@Composable
fun MiniStat(label: String, value: String, detail: String, color: Color, modifier: Modifier) {
    Column(
        modifier
            .background(color)
            .border(3.dp, Ink)
            .padding(10.dp)
    ) {
        Text(label, fontWeight = FontWeight.Black, fontSize = 12.sp)
        Text(value, fontWeight = FontWeight.Black, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
        Text(detail, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun OverallCard(subjects: List<Subject>, tab: Int) {
    val records = if (tab == 0) subjects.mapNotNull { it.theory } else subjects.mapNotNull { it.lab }
    val a = records.sumOf { it.attended }
    val t = records.sumOf { it.total }
    val onTrack = records.count { it.isSafe }

    NeoCard(color = if (tab == 0) Sky else Pink) {
        Text(if (tab == 0) "THEORY OVERALL" else "LAB OVERALL", fontWeight = FontWeight.Black, fontSize = 14.sp)
        Text(
            if (t == 0) "--" else "${"%.1f".format(pct(a, t))}%",
            fontWeight = FontWeight.Black,
            fontSize = 44.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniStat("CLASSES", "$a/$t", "attended / total", White, Modifier.weight(1f))
            MiniStat("ON TRACK", "$onTrack/${records.size}", "subjects safe", Lime, Modifier.weight(1f))
        }
    }
}

@Composable
fun SubjectCard(
    s: Subject,
    tab: Int,
    onChange: (Subject) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    NeoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.name.uppercase(),
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Black,
                fontSize = 20.sp
            )
            Box(
                Modifier
                    .background(Yellow)
                    .border(3.dp, Ink)
                    .clickable { onEdit() }
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            ) { Text("EDIT", fontWeight = FontWeight.Black, fontSize = 14.sp) }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .background(Red)
                    .border(3.dp, Ink)
                    .clickable { onDelete() }
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            ) { Text("X", fontWeight = FontWeight.Black, fontSize = 16.sp) }
        }

        Spacer(Modifier.height(12.dp))
        if (tab == 0) {
            s.theory?.let { th -> SectionBlock("THEORY", Sky, th) { onChange(s.copy(theory = it)) } }
        } else {
            s.lab?.let { lab -> SectionBlock("LAB", Pink, lab) { onChange(s.copy(lab = it)) } }
        }
    }
}

/** One self-contained section (theory or lab) with its own numbers and buttons. */
@Composable
fun SectionBlock(title: String, tagColor: Color, r: Record, onChange: (Record) -> Unit) {
    val barColor = when {
        r.total == 0 -> Sky
        r.isSafe -> Lime
        else -> Red
    }
    val (verdict, verdictColor) = r.verdict()

    Column(
        Modifier
            .fillMaxWidth()
            .background(Cream)
            .border(3.dp, Ink)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(title, tagColor)
            Spacer(Modifier.weight(1f))
            Text("min ${r.required}%", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${"%.1f".format(r.percent)}%",
                fontWeight = FontWeight.Black,
                fontSize = 32.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${r.attended}/${r.total}",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 5.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
        NeoProgress(r.percent / 100f, r.required / 100f, barColor)
        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .background(verdictColor)
                .border(3.dp, Ink)
                .padding(10.dp)
        ) { Text(verdict, fontWeight = FontWeight.Black) }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeoButton("PRESENT", Lime, Modifier.weight(1f)) { onChange(r.present()) }
            NeoButton("ABSENT", Pink, Modifier.weight(1f)) { onChange(r.absent()) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeoButton("UNDO P", White, Modifier.weight(1f)) { onChange(r.undoPresent()) }
            NeoButton("UNDO A", White, Modifier.weight(1f)) { onChange(r.undoAbsent()) }
        }
    }
}

// ---------- Add / Edit dialog ----------
/** Holds the text the user types for one section. */
class RecordInput(initial: Record?) {
    var attended by mutableStateOf(initial?.attended?.toString() ?: "")
    var total by mutableStateOf(initial?.total?.toString() ?: "")
    var required by mutableStateOf(initial?.required?.toString() ?: "75")

    val record: Record
        get() = Record(
            attended.toIntOrNull() ?: 0,
            total.toIntOrNull() ?: 0,
            (required.toIntOrNull() ?: 75).coerceIn(1, 100)
        )
}

@Composable
fun RecordFields(title: String, tagColor: Color, input: RecordInput) {
    val r = input.record
    val (verdict, verdictColor) = r.verdict()

    Column(
        Modifier
            .fillMaxWidth()
            .background(Cream)
            .border(3.dp, Ink)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Tag(title, tagColor)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                FieldLabel("ATTENDED")
                NeoField(input.attended, { input.attended = it.filter(Char::isDigit).take(4) }, "0", KeyboardType.Number)
            }
            Column(Modifier.weight(1f)) {
                FieldLabel("TOTAL")
                NeoField(input.total, { input.total = it.filter(Char::isDigit).take(4) }, "0", KeyboardType.Number)
            }
        }
        Column {
            FieldLabel("REQUIRED %")
            NeoField(input.required, { input.required = it.filter(Char::isDigit).take(3) }, "75", KeyboardType.Number)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(verdictColor)
                .border(3.dp, Ink)
                .padding(10.dp)
        ) {
            Column {
                Text(
                    "ATTENDANCE: ${"%.1f".format(r.percent)}%",
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Text(verdict, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.clickable { onToggle() }, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(28.dp)
                .background(if (checked) Lime else White)
                .border(3.dp, Ink),
            contentAlignment = Alignment.Center
        ) { if (checked) Text("X", fontWeight = FontWeight.Black) }
        Spacer(Modifier.width(10.dp))
        Text(label, fontWeight = FontWeight.Black)
    }
}

/**
 * Add / edit a subject. A subject can have theory, lab, or both.
 * startWithLab: when adding from the LAB tab, only the lab section is ticked at first.
 */
@Composable
fun SubjectDialog(
    title: String,
    initial: Subject?,
    onDismiss: () -> Unit,
    onSave: (name: String, theory: Record?, lab: Record?) -> Unit,
    startWithLab: Boolean = false
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var hasTheory by remember { mutableStateOf(if (initial != null) initial.theory != null else !startWithLab) }
    var hasLab by remember { mutableStateOf(if (initial != null) initial.lab != null else startWithLab) }
    val theoryInput = remember { RecordInput(initial?.theory) }
    val labInput = remember { RecordInput(initial?.lab) }
    var error by remember { mutableStateOf<String?>(null) }

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
                Text(title, fontWeight = FontWeight.Black, fontSize = 22.sp)

                Column {
                    FieldLabel("SUBJECT NAME")
                    NeoField(name, { name = it }, "e.g. Physics")
                }

                CheckRow("THIS SUBJECT HAS THEORY", hasTheory) { hasTheory = !hasTheory }
                if (hasTheory) RecordFields("THEORY", Sky, theoryInput)

                CheckRow("THIS SUBJECT HAS A LAB", hasLab) { hasLab = !hasLab }
                if (hasLab) RecordFields("LAB", Pink, labInput)

                error?.let { Text(it, color = Ink, fontWeight = FontWeight.Black) }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeoButton("CANCEL", White, Modifier.weight(1f), onDismiss)
                    NeoButton("SAVE", Lime, Modifier.weight(1f)) {
                        val t = theoryInput.record
                        val l = labInput.record
                        when {
                            name.isBlank() -> error = "Enter a subject name."
                            !hasTheory && !hasLab -> error = "Tick at least one: theory or lab."
                            hasTheory && t.attended > t.total -> error = "Theory: attended can't be more than total."
                            hasLab && l.attended > l.total -> error = "Lab: attended can't be more than total."
                            else -> onSave(name.trim(), if (hasTheory) t else null, if (hasLab) l else null)
                        }
                    }
                }
            }
        }
    }
}