package com.editx.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.window.Dialog
import androidx.media3.effect.HslAdjustment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.sin
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.sqrt

val BG = Color(0xFF02030A)
val CARD = Color(0xFF0B0D1A)
val NEON = Brush.horizontalGradient(listOf(Color(0xFF2563EB), Color(0xFF7C3AED), Color(0xFFC026D3)))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

data class Style(val name: String, val tint: FloatArray, val punch: Float, val shake: Boolean, val flash: Boolean)
val STYLES = listOf(
    Style("🔥 Phonk", floatArrayOf(1.1f, .92f, .92f), .16f, true, true),
    Style("🎬 Cinema", floatArrayOf(.92f, 1f, 1.1f), .08f, false, false),
    Style("🌸 Aesthetic", floatArrayOf(1.05f, .98f, 1.08f), .10f, false, true)
)

// mood: 0 = silliq (sokin), 1 = oʻrta, 2 = keskin (energiyali)
data class Seg(val dur: Double, val srcMs: Long, val clipMs: Long, val speed: Float, val mood: Int, val drop: Boolean, val pre: Boolean)

var BIAS = 0

data class Lang(val code: String, val flag: String, val name: String)
val LANGS = listOf(
    Lang("uz", "🇺🇿", "Oʻzbekcha"), Lang("en", "🇬🇧", "English"), Lang("ru", "🇷🇺", "Русский"),
    Lang("tr", "🇹🇷", "Türkçe"), Lang("ar", "🇸🇦", "العربية")
)
val STR: Map<String, Map<String, String>> = mapOf(
    "uz" to mapOf("video" to "Video", "music" to "Musiqa", "tap" to "Tanlash", "ok" to "✓ Tanlandi",
        "hint" to "Video va musiqani tanlang.\nEditx AI ritmga moslab montaj qiladi",
        "analyzing" to "Musiqa va video tahlil qilinmoqda…", "mounting" to "Montaj: %1\$d kesim (%2\$d keskin, %3\$d silliq)…",
        "render" to "Ultra HD 4K render qilinmoqda…", "done" to "Tayyor ✅ Galereya → Movies/EditxAI", "savefail" to "Saqlab boʻlmadi",
        "error" to "Xato", "go" to "✨ AVTO-EDIT QILISH", "busy" to "ISHLANMOQDA…",
        "prompt" to "Prompt: masalan, tez va keskin phonk edit, 20 soniya", "lang" to "Til", "uhd" to "ULTRA HD 4K · 8K EFFEKT"),
    "en" to mapOf("video" to "Video", "music" to "Music", "tap" to "Tap to pick", "ok" to "✓ Selected",
        "hint" to "Pick a video and music.\nEditx AI edits to the beat",
        "analyzing" to "Analyzing music and video…", "mounting" to "Editing: %1\$d cuts (%2\$d sharp, %3\$d smooth)…",
        "render" to "Rendering Ultra HD 4K…", "done" to "Done ✅ Gallery → Movies/EditxAI", "savefail" to "Couldn't save",
        "error" to "Error", "go" to "✨ AUTO-EDIT", "busy" to "WORKING…",
        "prompt" to "Prompt: e.g. fast sharp phonk edit, 20 seconds", "lang" to "Language", "uhd" to "ULTRA HD 4K · 8K LOOK"),
    "ru" to mapOf("video" to "Видео", "music" to "Музыка", "tap" to "Выбрать", "ok" to "✓ Выбрано",
        "hint" to "Выберите видео и музыку.\nEditx AI смонтирует под ритм",
        "analyzing" to "Анализ музыки и видео…", "mounting" to "Монтаж: %1\$d склеек (%2\$d резких, %3\$d плавных)…",
        "render" to "Рендер Ultra HD 4K…", "done" to "Готово ✅ Галерея → Movies/EditxAI", "savefail" to "Не удалось сохранить",
        "error" to "Ошибка", "go" to "✨ АВТО-МОНТАЖ", "busy" to "РАБОТАЮ…",
        "prompt" to "Промпт: например, быстрый резкий фонк, 20 секунд", "lang" to "Язык", "uhd" to "ULTRA HD 4K · 8K ЭФФЕКТ"),
    "tr" to mapOf("video" to "Video", "music" to "Müzik", "tap" to "Seç", "ok" to "✓ Seçildi",
        "hint" to "Video ve müzik seçin.\nEditx AI ritme göre kurgular",
        "analyzing" to "Müzik ve video analiz ediliyor…", "mounting" to "Kurgu: %1\$d kesim (%2\$d sert, %3\$d yumuşak)…",
        "render" to "Ultra HD 4K render ediliyor…", "done" to "Hazır ✅ Galeri → Movies/EditxAI", "savefail" to "Kaydedilemedi",
        "error" to "Hata", "go" to "✨ OTO-KURGU", "busy" to "ÇALIŞIYOR…",
        "prompt" to "Prompt: örn. hızlı sert phonk edit, 20 saniye", "lang" to "Dil", "uhd" to "ULTRA HD 4K · 8K EFEKT"),
    "ar" to mapOf("video" to "فيديو", "music" to "موسيقى", "tap" to "اختر", "ok" to "✓ تم الاختيار",
        "hint" to "اختر فيديو وموسيقى.\nEditx AI يقطّع الفيديو على الإيقاع",
        "analyzing" to "جارٍ تحليل الموسيقى والفيديو…", "mounting" to "المونتاج: %1\$d قطعة (%2\$d حادة، %3\$d ناعمة)…",
        "render" to "جارٍ التصيير بدقة Ultra HD 4K…", "done" to "تم ✅ المعرض ← Movies/EditxAI", "savefail" to "تعذر الحفظ",
        "error" to "خطأ", "go" to "✨ مونتاج تلقائي", "busy" to "جارٍ العمل…",
        "prompt" to "برومبت: مثلاً مونتاج فونك سريع وحاد، 20 ثانية", "lang" to "اللغة", "uhd" to "ULTRA HD 4K · تأثير 8K")
)
fun tr(lang: String, key: String): String = STR[lang]?.get(key) ?: STR["en"]!![key] ?: key

class PromptRes(val style: Int?, val bias: Int, val len: Int?)
fun parsePrompt(p: String): PromptRes {
    val s = p.lowercase()
    fun has(vararg w: String) = w.any { s.contains(it) }
    val style = when {
        has("phonk", "фонк", "فونك") -> 0
        has("cinema", "kino", "кино", "sinema", "سينم") -> 1
        has("aesthetic", "estetik", "эстетик", "جمال") -> 2
        else -> null
    }
    val fast = has("tez", "fast", "keskin", "sharp", "energ", "быстр", "резк", "динамич", "hızlı", "hizli", "sert", "agresif", "سريع", "حاد")
    val slow = has("sekin", "slow", "silliq", "smooth", "calm", "sokin", "медлен", "плавн", "спокой", "yavaş", "yavas", "sakin", "yumuşak", "بطيء", "هاد", "ناعم")
    val bias = if (fast && !slow) 1 else if (slow && !fast) -1 else 0
    val len = Regex("(\\d{1,2})\\s*(s\\b|sec|soniya|сек|saniye|sn|ث)").find(s)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(5, 60)
    return PromptRes(style, bias, len)
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (on) Color(0xFF3B2A8F) else CARD, RoundedCornerShape(10.dp))
            .border(1.5.dp, if (on) Color(0xFFA5B4FC) else Color(0xFF1F2547), RoundedCornerShape(10.dp))
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp)
    ) { Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun Pick(icon: String, label: String, sub: String, ok: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.background(CARD, RoundedCornerShape(14.dp))
            .border(1.5.dp, if (ok) Color(0xFF6D5DFC) else Color(0xFF1F2547), RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 30.sp)
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(sub, color = Color(0xFF8F95C4), fontSize = 12.sp)
    }
}

@Composable
fun LangDialog(cur: String, title: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF0B0D1A), RoundedCornerShape(20.dp))
                .border(1.5.dp, NEON, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("🌐  $title", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            LANGS.forEach { l ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (l.code == cur) Color(0xFF1B1A5A) else Color(0xFF121528), RoundedCornerShape(12.dp))
                        .clickable { onPick(l.code) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(l.flag, fontSize = 22.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(l.name, color = Color.White, modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (l.code == cur) Text("✓", color = Color(0xFFA5B4FC), fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("editx", Context.MODE_PRIVATE) }
    var lang by remember {
        mutableStateOf(prefs.getString("lang", null) ?: java.util.Locale.getDefault().language.let { c -> if (LANGS.any { it.code == c }) c else "uz" })
    }
    var showLang by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf<Uri?>(null) }
    var music by remember { mutableStateOf<Uri?>(null) }
    var style by remember { mutableStateOf(0) }
    var len by remember { mutableStateOf(30) }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val pv = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) video = it }
    val pm = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) music = it }
    val cl = LANGS.first { it.code == lang }

    if (showLang) LangDialog(lang, tr(lang, "lang"), { c -> lang = c; prefs.edit().putString("lang", c).apply(); showLang = false }, { showLang = false })

    Column(
        Modifier.fillMaxSize().background(BG).verticalScroll(rememberScrollState()).padding(16.dp).padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(40.dp))
            Spacer(Modifier.width(10.dp))
            Text("EDITX AI", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
            Row(
                Modifier.background(CARD, RoundedCornerShape(20.dp)).border(1.5.dp, NEON, RoundedCornerShape(20.dp))
                    .clickable { showLang = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(cl.flag, fontSize = 16.sp); Spacer(Modifier.width(6.dp))
                Text(cl.code.uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(" ▾", color = Color(0xFFA5B4FC), fontSize = 13.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pick("🎞️", tr(lang, "video"), if (video != null) tr(lang, "ok") else tr(lang, "tap"), video != null, Modifier.weight(1f)) { pv.launch("video/*") }
            Pick("🎵", tr(lang, "music"), if (music != null) tr(lang, "ok") else tr(lang, "tap"), music != null, Modifier.weight(1f)) { pm.launch("audio/*") }
        }
        Image(
            painterResource(R.drawable.logo), null,
            Modifier.fillMaxWidth().height(170.dp).background(Color.Black, RoundedCornerShape(10.dp)).border(1.5.dp, Color(0xFF6D5DFC), RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit
        )
        Text(tr(lang, "uhd"), color = Color(0xFFA5B4FC), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(status ?: tr(lang, "hint"), color = Color(0xFF8F95C4), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        BasicTextField(
            value = prompt, onValueChange = { prompt = it },
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(Color.White),
            modifier = Modifier.fillMaxWidth().background(CARD, RoundedCornerShape(12.dp)).border(1.5.dp, Color(0xFF1F2547), RoundedCornerShape(12.dp)).padding(14.dp),
            decorationBox = { inner ->
                Box { if (prompt.isEmpty()) Text(tr(lang, "prompt"), color = Color(0xFF6B7099), fontSize = 14.sp); inner() }
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { STYLES.forEachIndexed { i, s -> Chip(s.name, style == i) { style = i } } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(15, 30, 45).forEach { Chip("$it s", len == it) { len = it } } }
        Box(
            Modifier.fillMaxWidth().background(NEON, RoundedCornerShape(12.dp))
                .clickable(enabled = !busy && video != null && music != null) {
                    scope.launch {
                        busy = true
                        try {
                            val pr = parsePrompt(prompt)
                            val sIdx = pr.style ?: style
                            val lenSec = pr.len ?: len
                            BIAS = pr.bias
                            status = tr(lang, "analyzing")
                            val v = video!!; val m = music!!
                            val (segs, total, an) = withContext(Dispatchers.Default) {
                                val mdur = durationMs(ctx, m) / 1000.0
                                val total = min(lenSec.toDouble(), mdur)
                                val an = analyzeMusic(ctx, m, total.toInt() + 1)
                                val vdur = durationMs(ctx, v)
                                val motion = motionOrder(ctx, v, vdur)
                                Triple(plan(an, total, motion, vdur), total, an)
                            }
                            status = String.format(java.util.Locale.US, tr(lang, "mounting"), segs.size, segs.count { it.mood == 2 }, segs.count { it.mood == 0 })
                            val wav = withContext(Dispatchers.IO) { val o = File(ctx.cacheDir, "music_edit.wav"); if (buildAudio(an, segs, total, o)) o else null }
                            status = tr(lang, "render")
                            val f = runExport(ctx, v, if (wav != null) Uri.fromFile(wav) else m, segs, total, STYLES[sIdx])
                            val ok = withContext(Dispatchers.IO) { saveToGallery(ctx, f) }
                            status = if (ok) tr(lang, "done") else tr(lang, "savefail")
                        } catch (e: Throwable) { status = tr(lang, "error") + ": " + e.message }
                        busy = false
                    }
                }.padding(16.dp),
            contentAlignment = Alignment.Center
        ) { Text(if (busy) tr(lang, "busy") else tr(lang, "go"), color = Color.White, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp) }
    }
}

fun durationMs(ctx: Context, uri: Uri): Long {
    val r = MediaMetadataRetriever(); r.setDataSource(ctx, uri)
    val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
    r.release(); return d
}

fun motionOrder(ctx: Context, uri: Uri, durMs: Long): List<Long> {
    val r = MediaMetadataRetriever(); r.setDataSource(ctx, uri)
    val n = max(4, min(30, (durMs / 1000).toInt()))
    val res = ArrayList<Pair<Long, Long>>(); var prev: IntArray? = null
    for (i in 0 until n) {
        val t = durMs * (2 * i + 1) / (2 * n)
        val b = r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 32, 18)
        var m = 0L
        if (b != null) {
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val p = prev
            if (p != null && p.size == px.size) for (k in px.indices) m += abs((px[k] shr 8 and 255) - (p[k] shr 8 and 255))
            prev = px
        }
        res.add(Pair(t, m))
    }
    r.release()
    return res.sortedByDescending { it.second }.map { it.first }
}

class Analysis(val beats: List<Double>, val str: List<Float>, val lvl: IntArray, val hopSec: Double, val dropT: Double, val pcm: ShortArray = ShortArray(0), val sr: Int = 44100, val ch: Int = 2)

fun meanOf(a: FloatArray, from: Int, to: Int): Float {
    val f = max(0, from); val t = min(a.size, to)
    if (t <= f) return 0f
    var s = 0f; for (k in f until t) s += a[k]
    return s / (t - f)
}

fun levelAt(a: Analysis, t: Double): Int {
    if (a.lvl.isEmpty()) return 1
    return (t / a.hopSec).toInt().coerceIn(0, a.lvl.size - 1)
        .let { (a.lvl[it] + BIAS).coerceIn(0, 2) }
}

fun analyzeMusic(ctx: Context, uri: Uri, maxSec: Int): Analysis {
    val ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
    var idx = -1
    for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; break }
    val hop = 512; val env = ArrayList<Float>(); var sr = 44100; var pcm = ShortArray(0); var pn = 0; var pch = 2
    if (idx >= 0) {
        ex.selectTrack(idx)
        val fmt = ex.getTrackFormat(idx)
        sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); pcm = ShortArray(sr * 2 * (maxSec + 1))
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var acc = 0.0; var cnt = 0; var frames = 0L
        val info = MediaCodec.BufferInfo(); var inDone = false; var done = false
        while (!done && frames < sr.toLong() * maxSec) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10000)
            if (o >= 0) {
                val sb = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                for (j in 0 until sb.limit()) if (pn < pcm.size) pcm[pn++] = sb.get(j)
                var k = 0
                while (k < sb.limit()) {
                    val x = sb.get(k) / 32768.0; acc += x * x; cnt++; frames++
                    if (cnt == hop) { env.add(sqrt(acc / hop).toFloat()); acc = 0.0; cnt = 0 }
                    k += ch
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) ch = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        }
        codec.stop(); codec.release(); pch = ch
    }
    ex.release()
    val e = env.toFloatArray(); val n = e.size; val hopSec = hop.toDouble() / sr
    if (n < 100) {
        val g = ArrayList<Double>(); var t = 0.5; while (t < maxSec) { g.add(t); t += 0.5 }
        return Analysis(g, g.map { 2f }, IntArray(1) { 1 }, 1.0, -1.0)
    }
    val fl = FloatArray(n) { if (it == 0) 0f else max(0f, e[it] - e[it - 1]) }
    val beats = ArrayList<Double>(); val str = ArrayList<Float>(); var last = -1.0
    for (i in 1 until n - 1) {
        val m = meanOf(fl, i - 43, i + 43)
        if (fl[i] > m * 1.5f + 1e-4f && fl[i] >= fl[i - 1] && fl[i] >= fl[i + 1]) {
            val t = i * hopSec
            if (t - last > 0.25) { beats.add(t); str.add(fl[i] / (m + 1e-6f)); last = t }
        }
    }
    val w = max(2, (1.0 / hopSec).toInt())
    val en = FloatArray(n) { meanOf(e, it - w / 2, it + w / 2) }
    val sorted = en.sortedArray()
    val p35 = sorted[(n * 0.35).toInt()]; val p65 = sorted[(n * 0.65).toInt().coerceAtMost(n - 1)]
    val lvl = IntArray(n) { if (en[it] < p35) 0 else if (en[it] > p65) 2 else 1 }
    var dropT = -1.0; var best = 1.35f
    for (b in beats) {
        val i = (b / hopSec).toInt()
        if (b < maxSec * 0.2 || i - w < 0 || i + w >= n) continue
        val r = meanOf(e, i, i + w) / (meanOf(e, i - w, i) + 1e-6f)
        if (r > best) { best = r; dropT = b }
    }
    if (beats.size < 4) { beats.clear(); str.clear(); var t = 0.5; while (t < maxSec) { beats.add(t); str.add(2f); t += 0.5 } }
    return Analysis(beats, str, lvl, hopSec, dropT, pcm.copyOf(pn), sr, pch)
}

fun plan(a: Analysis, total: Double, motion: List<Long>, vdur: Long): List<Seg> {
    val cuts = mutableListOf(0.0); var s = 0.0
    for (i in a.beats.indices) {
        val b = a.beats[i]
        if (b >= total - 0.4) break
        val lv = levelAt(a, b); val len = b - s
        val minLen = when (lv) { 2 -> 0.35; 1 -> 0.9; else -> 2.2 }
        val maxLen = when (lv) { 2 -> 1.2; 1 -> 2.4; else -> 4.5 }
        val thr = when (lv) { 2 -> 1.5f; 1 -> 2.2f; else -> 3.0f }
        val isDrop = a.dropT > 0 && abs(b - a.dropT) < 0.25 && len > 0.3
        if (isDrop || (len >= minLen && a.str[i] >= thr) || len >= maxLen) { cuts.add(b); s = b }
    }
    if (cuts.size > 1 && total - cuts.last() < 0.5) cuts.removeAt(cuts.size - 1)
    cuts.add(total)
    val ctr = intArrayOf(0, 0, 0)
    val out = ArrayList<Seg>()
    for (i in 0 until cuts.size - 1) {
        val d = cuts[i + 1] - cuts[i]
        var mood = max(levelAt(a, cuts[i] + 0.05), levelAt(a, cuts[i] + d / 2))
        val isDrop = a.dropT > 0 && abs(cuts[i] - a.dropT) < 0.3
        val pre = a.dropT > 0 && !isDrop && abs(cuts[i + 1] - a.dropT) < 0.3
        if (isDrop) mood = 2
        if (pre) mood = 0
        val c = ctr[mood]; ctr[mood] = c + 1
        var sp = when {
            pre -> .4f
            isDrop -> 1.3f
            mood == 2 -> floatArrayOf(1f, 1.4f, 1.8f)[c % 3]
            mood == 1 -> floatArrayOf(1f, 1.15f)[c % 2]
            else -> floatArrayOf(.7f, .6f, .8f)[c % 3]
        }
        if (d * sp * 1000 > vdur * 0.9) sp = (vdur * 0.9 / 1000 / d).toFloat()
        val need = (d * sp * 1000).toLong()
        val half = motion.size / 2 + 1
        val pool = when (mood) { 2 -> motion.take(half); 0 -> motion.takeLast(half); else -> motion }
        val top = pool[c % pool.size]
        val src = min(max(0L, top - (need * 0.3).toLong()), max(0L, vdur - need))
        out.add(Seg(d, src, min(need, vdur - src), sp, mood, isDrop, pre))
    }
    return out
}

fun relSec(b: AtomicLong, t: Long): Float { b.compareAndSet(-1L, t); return (t - b.get()) / 1_000_000f }

fun punchFx(amount: Float, shake: Boolean): Effect {
    val b = AtomicLong(-1L)
    return MatrixTransformation { t ->
        val x = relSec(b, t); val k = max(0f, 1f - x * 4f); val q = k * k
        val sc = 1f + amount * q + (if (shake) 0.05f else 0f)
        Matrix().apply { postScale(sc, sc); if (shake) postTranslate(sin(x * 90f) * 0.04f * q, cos(x * 77f) * 0.04f * q) }
    }
}

fun pushFx(dur: Float, amount: Float): Effect {
    val b = AtomicLong(-1L)
    return MatrixTransformation { t ->
        val sc = 1f + amount * min(1f, relSec(b, t) / max(0.1f, dur))
        Matrix().apply { postScale(sc, sc) }
    }
}

fun fadeFx(dur: Float, fade: Float): Effect {
    val b = AtomicLong(-1L)
    return RgbMatrix { t, _ ->
        val x = relSec(b, t)
        val a = min(min(1f, x / fade), max(0f, (dur - x) / fade)).coerceIn(0f, 1f)
        floatArrayOf(a, 0f, 0f, 0f, 0f, a, 0f, 0f, 0f, 0f, a, 0f, 0f, 0f, 0f, 1f)
    }
}

fun flashFx(peak: Float): Effect {
    val b = AtomicLong(-1L)
    return RgbMatrix { t, _ ->
        val k = max(0f, 1f - relSec(b, t) * 6f); val a = 1f + peak * k * k
        floatArrayOf(a, 0f, 0f, 0f, 0f, a, 0f, 0f, 0f, 0f, a, 0f, 0f, 0f, 0f, 1f)
    }
}

fun fxList(s: Seg, st: Style): List<Effect> {
    val l = ArrayList<Effect>()
    if (s.speed != 1f) l.add(SpeedChangeEffect(s.speed))
    when (s.mood) {
        2 -> l.add(punchFx(st.punch + (if (s.drop) 0.08f else 0f), st.shake || s.drop))
        1 -> l.add(punchFx(st.punch * 0.45f, false))
        else -> l.add(pushFx(s.dur.toFloat(), if (s.pre) 0.22f else 0.12f))
    }
    if (s.mood == 0) l.add(fadeFx(s.dur.toFloat(), 0.3f))
    else if (st.flash && (s.mood == 2 || s.drop)) l.add(flashFx(if (s.drop) 1.6f else 0.7f))
    l.add(HslAdjustment.Builder().adjustSaturation(10f).build())
    l.add(Contrast(0.1f))
    l.add(RgbAdjustment.Builder().setRedScale(st.tint[0]).setGreenScale(st.tint[1]).setBlueScale(st.tint[2]).build())
    l.add(Presentation.createForWidthAndHeight(2160, 3840, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
    return l
}

fun reverseFrames(d: ShortArray, ch: Int, f0: Int, f1: Int) {
    var a = f0; var b = f1 - 1
    while (a < b) {
        for (c in 0 until ch) { val i = a * ch + c; val j = b * ch + c; val t = d[i]; d[i] = d[j]; d[j] = t }
        a++; b--
    }
}

fun tapeStop(d: ShortArray, ch: Int, f0: Int, f1: Int) {
    val len = f1 - f0; if (len < 10) return
    val src = d.copyOfRange(f0 * ch, f1 * ch)
    var pos = 0.0
    for (i in 0 until len) {
        val rate = 1.0 - 0.9 * i / len
        val g = 1.0 - i.toDouble() / len
        val p = pos.toInt()
        for (c in 0 until ch) {
            val idx = p * ch + c
            d[(f0 + i) * ch + c] = if (idx < src.size) (src[idx] * g).toInt().toShort() else 0
        }
        pos += rate
    }
}

fun stutter(d: ShortArray, ch: Int, f0: Int, sliceF: Int, reps: Int) {
    val sl = d.copyOfRange(f0 * ch, (f0 + sliceF) * ch)
    for (r in 1 until reps) { val o = (f0 + r * sliceF) * ch; if (o + sl.size <= d.size) sl.copyInto(d, o) }
}

fun writeWav(d: ShortArray, sr: Int, ch: Int, f: File) {
    val bb = java.nio.ByteBuffer.allocate(44 + d.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    bb.put("RIFF".toByteArray()); bb.putInt(36 + d.size * 2); bb.put("WAVEfmt ".toByteArray()); bb.putInt(16)
    bb.putShort(1); bb.putShort(ch.toShort()); bb.putInt(sr); bb.putInt(sr * ch * 2); bb.putShort((ch * 2).toShort()); bb.putShort(16)
    bb.put("data".toByteArray()); bb.putInt(d.size * 2)
    for (v in d) bb.putShort(v)
    f.writeBytes(bb.array())
}

// Musiqaning oʻzini ham tahrirlaydi: drop oldidan teskari (rewind), keskin joylarda takror (stutter), oxirida tape-stop
fun buildAudio(a: Analysis, segs: List<Seg>, total: Double, out: File): Boolean {
    if (a.pcm.isEmpty()) return false
    val ch = a.ch; val sr = a.sr
    val frames = min((total * sr).toInt(), a.pcm.size / ch)
    if (frames < sr) return false
    val d = a.pcm.copyOf(frames * ch)
    var t = 0.0
    segs.forEachIndexed { i, s ->
        val end = ((t + s.dur) * sr).toInt()
        if (s.mood == 2 && !s.drop && i % 3 == 1) {
            val sl = (0.12 * sr).toInt(); val f0 = end - sl * 2
            if (f0 > 0 && end <= frames) stutter(d, ch, f0, sl, 2)
        }
        t += s.dur
    }
    if (a.dropT > 0) {
        val dr = (a.dropT * sr).toInt(); val l = (0.6 * sr).toInt()
        if (dr - l > 0 && dr < frames) reverseFrames(d, ch, dr - l, dr)
    }
    val tail = (0.6 * sr).toInt()
    if (frames > tail * 3) tapeStop(d, ch, frames - tail, frames)
    writeWav(d, sr, ch, out)
    return true
}

suspend fun runExport(ctx: Context, video: Uri, music: Uri, segs: List<Seg>, total: Double, st: Style): File =
    suspendCancellableCoroutine { cont ->
        val out = File(ctx.cacheDir, "editx_${System.currentTimeMillis()}.mp4")
        val items = segs.map { s ->
            val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.srcMs).setEndPositionMs(s.srcMs + s.clipMs).build()
            EditedMediaItem.Builder(MediaItem.Builder().setUri(video).setClippingConfiguration(clip).build())
                .setRemoveAudio(true).setEffects(Effects(emptyList(), fxList(s, st))).build()
        }
        val aClip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(0).setEndPositionMs((total * 1000).toLong()).build()
        val aItem = EditedMediaItem.Builder(MediaItem.Builder().setUri(music).setClippingConfiguration(aClip).build()).setRemoveVideo(true).build()
        val comp = Composition.Builder(listOf(EditedMediaItemSequence(items), EditedMediaItemSequence(listOf(aItem)))).build()
        val t = Transformer.Builder(ctx).setEncoderFactory(DefaultEncoderFactory.Builder(ctx).setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(45_000_000).build()).build()).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) { cont.resume(out) }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { cont.resumeWithException(exportException) }
        }).build()
        t.start(comp, out.absolutePath)
        cont.invokeOnCancellation { t.cancel() }
    }

fun saveToGallery(ctx: Context, f: File): Boolean {
    val v = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EditxAI")
    }
    val u = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return false
    ctx.contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } }
    return true
}
