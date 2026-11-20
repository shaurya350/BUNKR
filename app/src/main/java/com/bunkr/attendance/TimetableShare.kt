package com.bunkr.attendance

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

// ─────────────────────────────────────────────────────────────────────────────
// Encode / decode share payload
// Format: BUNKR:<base64 of newline-delimited records>
// Records: same prefixes as internal store (~slot|, ~exam|)
// ─────────────────────────────────────────────────────────────────────────────

private const val SHARE_PREFIX = "BUNKR:"

/** Build a shareable code from slots + exams (both optional). */
fun buildShareCode(slots: List<ClassSlot>, exams: List<Exam>): String {
    val body = buildString {
        slots.forEach { append("~slot|").append(encodeSlot(it)).append('\n') }
        exams.forEach { append("~exam|").append(encodeExam(it)).append('\n') }
    }.trimEnd()
    val b64 = Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return "$SHARE_PREFIX$b64"
}

/** Parse a share code. Returns (slots, exams) or null if the code is invalid. */
fun parseShareCode(raw: String): Pair<List<ClassSlot>, List<Exam>>? {
    val trimmed = raw.trim()
    val b64Part = when {
        trimmed.startsWith(SHARE_PREFIX, ignoreCase = true) -> trimmed.substring(SHARE_PREFIX.length)
        // tolerate codes that were copy-pasted with extra whitespace
        else -> return null
    }
    return runCatching {
        val body = Base64.decode(b64Part, Base64.NO_WRAP).toString(Charsets.UTF_8)
        val slots = mutableListOf<ClassSlot>()
        val exams = mutableListOf<Exam>()
        body.lines().forEach { line ->
            when {
                line.startsWith("~slot|") -> decodeSlot(line)?.let { slots += it }
                line.startsWith("~exam|") -> decodeExam(line)?.let { exams += it }
            }
        }
        slots to exams
    }.getOrNull()
}

// ─────────────────────────────────────────────────────────────────────────────
// QR Code helpers using ZXing core
// ─────────────────────────────────────────────────────────────────────────────

fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? = runCatching {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) for (y in 0 until sizePx) {
        bmp.setPixel(x, y, if (matrix[x, y]) Ink.toArgb() else White.toArgb())
    }
    bmp
}.getOrNull()

/** Decode a QR code from a local image Uri (screenshot, photo). */
fun decodeQrFromUri(context: Context, uri: Uri): String? = runCatching {
    val input = context.contentResolver.openInputStream(uri) ?: return null
    val original = BitmapFactory.decodeStream(input) ?: return null
    input.close()

    fun tryDecode(bmp: Bitmap): String? = runCatching {
        val width = bmp.width
        val height = bmp.height
        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val reader = QRCodeReader()
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )
        try {
            reader.decode(binary, hints).text
        } finally {
            reader.reset()
        }
    }.getOrNull()

    // 1. Try decoding the original image directly
    var found = tryDecode(original)
    if (found != null) return@runCatching found

    // 2. If it's a huge screenshot (e.g. 1080x2400), scale down to ~1200px max dimension and try again
    val maxDim = maxOf(original.width, original.height)
    if (maxDim > 1200) {
        val scale = 1200f / maxDim
        val scaled = Bitmap.createScaledBitmap(
            original,
            (original.width * scale).toInt(),
            (original.height * scale).toInt(),
            true
        )
        found = tryDecode(scaled)
        if (found != null) return@runCatching found
    }

    null
}.getOrNull()

// ─────────────────────────────────────────────────────────────────────────────
// Share Dialog — Export tab
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TimetableShareDialog(
    slots: List<ClassSlot>,
    exams: List<Exam>,
    onDismiss: () -> Unit,
    onImport: (List<ClassSlot>, List<Exam>) -> Unit
) {
    var tab by remember { mutableStateOf(0) } // 0 = Share  1 = Import

    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.padding(end = 6.dp, bottom = 6.dp)) {
            Column(
                Modifier
                    .neoShadow(6.dp)
                    .background(Cream)
                    .border(3.dp, Ink)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("SHARE TIMETABLE", fontWeight = FontWeight.Black, fontSize = 22.sp)

                // Tab switcher
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabButton(
                        "MY QR / CODE",
                        Sky,
                        tab == 0,
                        Modifier.weight(1f),
                        fontSize = 12.sp
                    ) { tab = 0 }
                    TabButton(
                        "SCAN / IMPORT",
                        Mint,
                        tab == 1,
                        Modifier.weight(1f),
                        fontSize = 12.sp
                    ) { tab = 1 }
                }

                if (tab == 0) {
                    ShareExportTab(slots = slots, exams = exams)
                } else {
                    ShareImportTab(onImport = onImport, onDismiss = onDismiss)
                }

                NeoButton("CLOSE", White, onClick = onDismiss)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Export Tab
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShareExportTab(slots: List<ClassSlot>, exams: List<Exam>) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var shareWhat by remember { mutableStateOf(0) } // 0 = Both  1 = Timetable only  2 = Exams only
    var copied by remember { mutableStateOf(false) }

    val selectedSlots = if (shareWhat == 2) emptyList() else slots
    val selectedExams = if (shareWhat == 1) emptyList() else exams
    val code = remember(shareWhat, slots, exams) { buildShareCode(selectedSlots, selectedExams) }

    // QR bitmap (heavy — produce off main thread lazily)
    var qrBitmap by remember(code) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(code) {
        qrBitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            generateQrBitmap(code, 400)
        }
    }

    // What to share toggle
    if (slots.isNotEmpty() && exams.isNotEmpty()) {
        Column {
            FieldLabel("SHARE WHAT?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SemChip("BOTH", shareWhat == 0, Lime) { shareWhat = 0; copied = false }
                SemChip("TIMETABLE", shareWhat == 1, Sky) { shareWhat = 1; copied = false }
                SemChip("EXAMS", shareWhat == 2, Pink) { shareWhat = 2; copied = false }
            }
        }
    }

    if (selectedSlots.isEmpty() && selectedExams.isEmpty()) {
        NeoCard(color = Yellow) {
            Text(
                "Nothing to share yet. Add classes or exams first.",
                fontWeight = FontWeight.Bold
            )
        }
        return
    }

    // Stats strip
    NeoCard(color = Lime) {
        Text(
            buildString {
                if (selectedSlots.isNotEmpty()) append("${selectedSlots.size} CLASS${if (selectedSlots.size != 1) "ES" else ""}")
                if (selectedSlots.isNotEmpty() && selectedExams.isNotEmpty()) append(" + ")
                if (selectedExams.isNotEmpty()) append("${selectedExams.size} EXAM${if (selectedExams.size != 1) "S" else ""}")
            },
            fontWeight = FontWeight.Black,
            fontSize = 18.sp
        )
        Text("ready to share", fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }

    // QR Code
    Text("QR CODE", fontWeight = FontWeight.Black, fontSize = 13.sp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(White)
            .border(3.dp, Ink),
        contentAlignment = Alignment.Center
    ) {
        val bmp = qrBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "QR Code",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("Generating…", fontWeight = FontWeight.Bold, color = Ink)
        }
    }

    // Code box
    Text("OR COPY THE CODE BELOW", fontWeight = FontWeight.Black, fontSize = 13.sp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(White)
            .border(3.dp, Ink)
            .horizontalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        Text(
            code,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
            maxLines = 3
        )
    }

    // Action buttons
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NeoButton(
            if (copied) "✓ COPIED!" else "COPY CODE",
            if (copied) Lime else White,
            Modifier.weight(1f)
        ) {
            clipboard.setText(AnnotatedString(code))
            copied = true
        }
        NeoButton("SHARE ↗", Sky, Modifier.weight(1f)) {
            val shareText = buildString {
                appendLine("Hey! Here's my class timetable from BUNKR 📅")
                appendLine()
                appendLine("Paste this code inside the app → Timetable → Share → Import Code tab:")
                appendLine()
                appendLine(code)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
                putExtra(Intent.EXTRA_SUBJECT, "My BUNKR Timetable")
            }
            context.startActivity(Intent.createChooser(intent, "Share Timetable"))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Import Tab
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShareImportTab(
    onImport: (List<ClassSlot>, List<Exam>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Pair<List<ClassSlot>, List<Exam>>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var readingImage by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    fun parse(input: String = code) {
        error = null
        val raw = input.trim()
        if (raw.isBlank()) { error = "Paste or scan a share code first."; return }
        val result = parseShareCode(raw)
        if (result == null) { error = "Invalid code. Make sure it starts with BUNKR:"; return }
        if (result.first.isEmpty() && result.second.isEmpty()) {
            error = "The code has no classes or exams."
            return
        }
        preview = result
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            error = null
            readingImage = true
            coroutineScope.launch {
                val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    decodeQrFromUri(context, uri)
                }
                readingImage = false
                if (decoded != null && decoded.startsWith("BUNKR:", ignoreCase = true)) {
                    code = decoded
                    parse(decoded)
                } else if (decoded != null) {
                    error = "QR code detected in image, but it's not a BUNKR timetable (missing 'BUNKR:' prefix)."
                } else {
                    error = "Couldn't detect a QR code in that screenshot. Make sure the QR code is clearly visible and not cropped."
                }
            }
        }
    }

    fun startQrScan() {
        error = null
        scanning = true
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        val scanner = GmsBarcodeScanning.getClient(context, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                scanning = false
                val scanned = barcode.rawValue?.trim() ?: ""
                if (scanned.startsWith("BUNKR:", ignoreCase = true)) {
                    code = scanned
                    parse(scanned)
                } else if (scanned.isNotBlank()) {
                    error = "QR code scanned, but it's not a BUNKR timetable (missing 'BUNKR:' prefix)."
                }
            }
            .addOnCanceledListener {
                scanning = false
            }
            .addOnFailureListener { e ->
                scanning = false
                error = "Scanner error: ${e.localizedMessage ?: "Could not start scanner"}"
            }
    }

    Text(
        "Scan a friend's QR code, upload a screenshot, or paste a share code.",
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp
    )

    // Primary action buttons: CAMERA SCAN & SCREENSHOT PICKER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        NeoButton(
            text = if (scanning) "OPENING..." else "📷 SCAN QR",
            color = Mint,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            maxLines = 1,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
        ) {
            if (!scanning && !readingImage) startQrScan()
        }
        NeoButton(
            text = if (readingImage) "READING..." else "🖼️ UPLOAD SCREENSHOT",
            color = Sky,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            maxLines = 1,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
        ) {
            if (!scanning && !readingImage) {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(2.dp)
                .background(Ink)
        )
        Text("OR ENTER CODE", fontWeight = FontWeight.Black, fontSize = 11.sp, color = Ink)
        Box(
            Modifier
                .weight(1f)
                .height(2.dp)
                .background(Ink)
        )
    }

    // Paste area
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 70.dp)
            .background(White)
            .border(3.dp, Ink)
            .padding(10.dp)
    ) {
        BasicTextField(
            value = code,
            onValueChange = { code = it; error = null; preview = null },
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Ink
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (code.isEmpty()) {
            Text(
                "Paste BUNKR:... code here",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Ink.copy(alpha = 0.4f)
            )
        }
    }

    // Paste from clipboard helper
    NeoButton("PASTE FROM CLIPBOARD", White) {
        val txt = clipboard.getText()?.text ?: ""
        code = txt
        error = null
        preview = null
        if (txt.startsWith("BUNKR:", ignoreCase = true)) {
            parse(txt)
        }
    }

    error?.let {
        Text(it, color = Red, fontWeight = FontWeight.Black, fontSize = 13.sp)
    }

    // Preview of decoded data
    val p = preview
    if (p != null) {
        NeoCard(color = Lime) {
            Text("READY TO IMPORT ✓", fontWeight = FontWeight.Black, fontSize = 15.sp)
            if (p.first.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "• ${p.first.size} class slot${if (p.first.size != 1) "s" else ""} across ${p.first.map { it.day }.distinct().size} day${if (p.first.map { it.day }.distinct().size != 1) "s" else ""}",
                    fontWeight = FontWeight.Bold
                )
            }
            if (p.second.isNotEmpty()) {
                Text(
                    "• ${p.second.size} exam${if (p.second.size != 1) "s" else ""}",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeoButton("CLEAR", White, Modifier.weight(1f)) {
                preview = null
                code = ""
            }
            NeoButton("IMPORT →", Lime, Modifier.weight(1f)) {
                onImport(p.first, p.second)
                onDismiss()
            }
        }
    } else {
        NeoButton("READ CODE", Lime) { parse() }
    }
}
