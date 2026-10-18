package com.editx.ai

import android.content.ContentValues
import android.content.Context
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
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
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

data class Style(val name: String, val step: Int, val speeds: FloatArray, val fx: IntArray, val tint: FloatArray)
val STYLES = listOf(
    Style("🔥 Phonk", 1, floatArrayOf(1f, .5f, 1.6f, 1f, 2f), intArrayOf(0, 1, 2, 3, 0), floatArrayOf(1.25f, .85f, .85f)),
    Style("🎬 Cinema", 2, floatArrayOf(.7f, 1f, .6f), intArrayOf(0, 4, 0), floatArrayOf(.85f, 1f, 1.2f)),
    Style("🌸 Aesthetic", 2, floatArrayOf(1f, .8f, 1.2f), intArrayOf(3, 0, 1), floatArrayOf(1.1f, .95f, 1.15f))
)

data class Seg(val dur: Double, val srcMs: Long, val clipMs: Long, val speed: Float, val fx: Int)

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip10().background(if (on) Color(0xFF3B2A8F) else CARD)
            .border(1.5.dp, if (on) Color(0xFFA5B4FC) else Color(0xFF1F2547), RoundedCornerShape(10.dp))
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp)
    ) { Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}
fun Modifier.clip10(): Modifier = this.then(Modifier.background(Color.Transparent, RoundedCornerShape(10.dp)))

@Composable
fun Pick(icon: String, label: String, ok: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.background(CARD, RoundedCornerShape(14.dp))
            .border(1.5.dp, if (ok) Color(0xFF6D5DFC) else Color(0xFF1F2547), RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 30.sp)
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(if (ok) "✓ Tanlandi" else "Tanlash", color = Color(0xFF8F95C4), fontSize = 12.sp)
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var video by remember { mutableStateOf<Uri?>(null) }
    var music by remember { mutableStateOf<Uri?>(null) }
    var style by remember { mutableStateOf(0) }
    var len by remember { mutableStateOf(30) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Video va musiqani tanlang.\nEditx AI ritmga moslab montaj qiladi") }
    val pv = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) video = it }
    val pm = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) music = it }

    Column(Modifier.fillMaxSize().background(BG).padding(16.dp).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(40.dp))
            Text("EDITX AI", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pick("🎞️", "Video", video != null, Modifier.weight(1f)) { pv.launch("video/*") }
            Pick("🎵", "Musiqa", music != null, Modifier.weight(1f)) { pm.launch("audio/*") }
        }
        Image(painterResource(R.drawable.logo), null, Modifier.fillMaxWidth().weight(1f).background(Color.Black, RoundedCornerShape(10.dp)).border(1.5.dp, Color(0xFF6D5DFC), RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
        Text(status, color = Color(0xFF8F95C4), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { STYLES.forEachIndexed { i, s -> Chip(s.name, style == i) { style = i } } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(15, 30, 45).forEach { Chip("$it s", len == it) { len = it } } }
        Box(
            Modifier.fillMaxWidth().background(NEON, RoundedCornerShape(12.dp))
                .clickable(enabled = !busy && video != null && music != null) {
                    scope.launch {
                        busy = true
                        try {
                            status = "Musiqa va video tahlil qilinmoqda…"
                            val v = video!!; val m = music!!
                            val (segs, total) = withContext(Dispatchers.Default) {
                                val mdur = durationMs(ctx, m) / 1000.0
                                val total = min(len.toDouble(), mdur)
                                val beats = beatTimes(ctx, m, total.toInt() + 1)
                                val vdur = durationMs(ctx, v)
                                val motion = motionOrder(ctx, v, vdur)
                                Pair(plan(beats, total, STYLES[style], motion, vdur), total)
                            }
                            status = "Montaj qilinmoqda (${segs.size} kesim)…"
                            val f = runExport(ctx, v, m, segs, total, STYLES[style])
                            val ok = withContext(Dispatchers.IO) { saveToGallery(ctx, f) }
                            status = if (ok) "Tayyor ✅ Galereya → Movies/EditxAI" else "Saqlab boʻlmadi"
                        } catch (e: Throwable) { status = "Xato: ${e.message}" }
                        busy = false
                    }
                }.padding(16.dp),
            contentAlignment = Alignment.Center
        ) { Text(if (busy) "ISHLANMOQDA…" else "✨ AVTO-EDIT QILISH", color = Color.White, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp) }
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

fun beatTimes(ctx: Context, uri: Uri, maxSec: Int): List<Double> {
    val ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
    var idx = -1
    for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; break }
    val beats = ArrayList<Double>()
    if (idx >= 0) {
        ex.selectTrack(idx)
        val fmt = ex.getTrackFormat(idx)
        val sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        val hop = 512; val env = ArrayList<Float>(); var acc = 0.0; var cnt = 0; var frames = 0L
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
        codec.stop(); codec.release()
        val fl = FloatArray(env.size) { if (it == 0) 0f else max(0f, env[it] - env[it - 1]) }
        var last = -1.0
        for (i in 1 until fl.size - 1) {
            var m = 0f; var c = 0
            for (k in max(0, i - 43) until min(fl.size, i + 43)) { m += fl[k]; c++ }
            m /= c
            if (fl[i] > m * 1.6f + 1e-4f && fl[i] >= fl[i - 1] && fl[i] >= fl[i + 1]) {
                val t = i * hop.toDouble() / sr
                if (t - last > 0.3) { beats.add(t); last = t }
            }
        }
    }
    ex.release()
    if (beats.size < 4) { beats.clear(); var t = 0.5; while (t < maxSec) { beats.add(t); t += 0.5 } }
    return beats
}

fun plan(beats: List<Double>, total: Double, st: Style, motion: List<Long>, vdur: Long): List<Seg> {
    val pts = mutableListOf(0.0)
    beats.forEachIndexed { i, b -> if (i % st.step == 0 && b > pts.last() + 0.3 && b < total - 0.3) pts.add(b) }
    pts.add(total)
    val out = ArrayList<Seg>()
    for (i in 0 until pts.size - 1) {
        val d = pts[i + 1] - pts[i]
        var sp = st.speeds[i % st.speeds.size]
        if (d * sp * 1000 > vdur * 0.9) sp = (vdur * 0.9 / 1000 / d).toFloat()
        val need = (d * sp * 1000).toLong()
        val top = motion[i % motion.size]
        val src = min(max(0L, top - (need * 0.3).toLong()), max(0L, vdur - need))
        out.add(Seg(d, src, min(need, vdur - src), sp, st.fx[i % st.fx.size]))
    }
    return out
}

fun fxList(s: Seg, st: Style): List<Effect> {
    val l = ArrayList<Effect>()
    l.add(SpeedChangeEffect(s.speed))
    when (s.fx) {
        0 -> l.add(ScaleAndRotateTransformation.Builder().setScale(1.15f, 1.15f).build())
        1 -> l.add(Brightness(0.2f))
        2 -> l.add(Contrast(0.5f))
        3 -> l.add(RgbAdjustment.Builder().setRedScale(1.3f).setBlueScale(0.8f).build())
    }
    l.add(RgbAdjustment.Builder().setRedScale(st.tint[0]).setGreenScale(st.tint[1]).setBlueScale(st.tint[2]).build())
    l.add(Presentation.createForAspectRatio(9f / 16f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
    return l
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
        val t = Transformer.Builder(ctx).addListener(object : Transformer.Listener {
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
