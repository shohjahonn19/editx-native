package com.editx.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaPlayer
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import com.google.common.collect.ImmutableList
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
import androidx.compose.foundation.shape.CircleShape
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
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.floor
import kotlin.math.roundToInt

val BG = Color(0xFF02030A)
val CARD = Color(0xFF0B0D1A)
val NEON = Brush.horizontalGradient(listOf(Color(0xFF2563EB), Color(0xFF7C3AED), Color(0xFFC026D3)))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

class TSeg(val d: Double, val sp: Float, val fx: String, val amt: Float, val flash: Boolean, val fade: Boolean, val src: String, val txt: Int = 0)
class Op(val op: String, val t: Double, val d: Double)
class Song(val id: String, val title: String, val bpm: Int, val dur: Double, val emoji: String, val segs: List<TSeg>, val ops: List<Op>)
class Run(val dur: Double, val srcMs: Long, val clipMs: Long, val speed: Float, val t: TSeg)

fun loadSongs(ctx: Context): List<Song> {
    val out = ArrayList<Song>()
    for (f in (ctx.assets.list("") ?: emptyArray()).sorted()) {
        if (!f.startsWith("song_") || !f.endsWith(".json")) continue
        try {
            val j = org.json.JSONObject(ctx.assets.open(f).bufferedReader().readText())
            val sa = j.getJSONArray("segs"); val segs = ArrayList<TSeg>()
            for (i in 0 until sa.length()) {
                val o = sa.getJSONObject(i)
                segs.add(TSeg(o.getDouble("d"), o.optDouble("sp", 1.0).toFloat(), o.optString("fx", "none"), o.optDouble("amt", 0.0).toFloat(),
                    o.optBoolean("flash", false), o.optBoolean("fade", false), o.optString("src", "hi")))
            }
            val oa = j.optJSONArray("ops"); val ops = ArrayList<Op>()
            if (oa != null) for (i in 0 until oa.length()) { val o = oa.getJSONObject(i); ops.add(Op(o.getString("op"), o.getDouble("t"), o.optDouble("d", 0.0))) }
            out.add(Song(j.getString("id"), j.getString("title"), j.optInt("bpm", 0), j.getDouble("dur"), j.optString("emoji", "🎵"), segs, ops))
        } catch (e: Exception) { }
    }
    return out
}

// Qolipni foydalanuvchi videosiga joylaydi: har safar bir xil natija
fun fillTemplate(song: Song, motion: List<Long>, vdur: Long): List<Run> {
    val hi = motion; val lo = motion.reversed()
    var ih = 0; var il = 0; var t = 0.0
    val out = ArrayList<Run>()
    for (s in song.segs) {
        var sp = s.sp
        if (s.d * sp * 1000 > vdur * 0.9) sp = (vdur * 0.9 / 1000 / s.d).toFloat()
        val need = (s.d * sp * 1000).toLong()
        val top = when (s.src) {
            "lo" -> lo[il++ % lo.size]
            "seq" -> (vdur * (t / song.dur)).toLong()
            else -> hi[ih++ % hi.size]
        }
        val st = min(max(0L, top - (need * 0.3).toLong()), max(0L, vdur - need))
        out.add(Run(s.d, st, min(need, vdur - st), sp, s))
        t += s.d
    }
    return out
}

fun buildSongAudio(ctx: Context, song: Song, out: File) {
    val bytes = ctx.assets.open(song.id + ".wav").readBytes()
    val bb = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var pos = 12; var ch = 1; var sr = 44100; var dp = 44; var dl = bytes.size - 44
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4); val sz = bb.getInt(pos + 4)
        if (id == "fmt ") { ch = bb.getShort(pos + 10).toInt(); sr = bb.getInt(pos + 12) }
        if (id == "data") { dp = pos + 8; dl = min(sz, bytes.size - dp); break }
        pos += 8 + sz
    }
    val n = dl / 2; val d = ShortArray(n) { bb.getShort(dp + 2 * it) }
    val frames = n / ch
    for (o in song.ops) {
        val f0 = (o.t * sr).toInt(); val f1 = min(frames, f0 + (o.d * sr).toInt())
        if (f0 <= 0 || f0 >= frames) continue
        when (o.op) {
            "reverse" -> reverseFrames(d, ch, f0, f1)
            "tapestop" -> tapeStop(d, ch, f0, f1)
            "stutter" -> { }
        }
    }
    writeWav(d, sr, ch, out)
}

var TITLE = ""

fun textFx(text: String, big: Boolean): Effect {
    val t = text.uppercase().take(40)
    val ss = SpannableString(t)
    val size = min(if (big) 170 else 80, (3100 / max(1, t.length)))
    ss.setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    ss.setSpan(AbsoluteSizeSpan(size), 0, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val st = OverlaySettings.Builder().setOverlayFrameAnchor(0f, 0f).setBackgroundFrameAnchor(0f, if (big) 0f else -0.55f).build()
    return OverlayEffect(ImmutableList.of(TextOverlay.createStaticTextOverlay(ss, st)))
}

fun displayName(ctx: Context, uri: Uri): String {
    var n = ""
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) { val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0) n = c.getString(i) ?: "" }
    }
    return n.substringBeforeLast('.')
}

// Audio faylni dekod qiladi; cb(buf, nShorts, ch, sr, firstFrameAbs)
fun forEachPcm(ctx: Context, uri: Uri, startSec: Double, endSec: Double, cb: (ShortArray, Int, Int, Int, Long) -> Unit) {
    val ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
    var idx = -1
    for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; break }
    if (idx < 0) { ex.release(); throw IllegalStateException("audio track yoq") }
    ex.selectTrack(idx)
    val fmt = ex.getTrackFormat(idx)
    val sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    if (startSec > 0) ex.seekTo((startSec * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
    codec.configure(fmt, null, null, 0); codec.start()
    val info = MediaCodec.BufferInfo(); var inDone = false; var done = false
    val endUs = (endSec * 1e6).toLong()
    var tmp = ShortArray(8192)
    while (!done) {
        if (!inDone) {
            val i = codec.dequeueInputBuffer(10000)
            if (i >= 0) {
                val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                if (n < 0 || ex.sampleTime > endUs + 1_000_000L) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
            }
        }
        val o = codec.dequeueOutputBuffer(info, 10000)
        if (o >= 0) {
            val sb = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
            val n = sb.remaining()
            if (tmp.size < n) tmp = ShortArray(n)
            sb.get(tmp, 0, n)
            cb(tmp, n, ch, sr, info.presentationTimeUs * sr / 1_000_000L)
            codec.releaseOutputBuffer(o, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || info.presentationTimeUs > endUs) done = true
        } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) ch = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    }
    codec.stop(); codec.release(); ex.release()
}

class Feat(val hopSec: Double, val lo: FloatArray, val hi: FloatArray, val all: FloatArray, val fl: FloatArray)

fun analyzeFeatures(ctx: Context, uri: Uri, maxSec: Int): Feat {
    val lo = ArrayList<Float>(); val hi = ArrayList<Float>(); val all = ArrayList<Float>()
    val hop = 256; var sr = 44100
    var lpL = 0.0; var lpM = 0.0; var aL = 0.0; var aM = 0.0
    var aLo = 0.0; var aHi = 0.0; var aAll = 0.0; var cnt = 0
    forEachPcm(ctx, uri, 0.0, maxSec.toDouble()) { buf, n, ch, srate, _ ->
        if (aL == 0.0) { sr = srate; aL = 1 - exp(-2 * PI * 160 / sr); aM = 1 - exp(-2 * PI * 3000 / sr) }
        var i = 0
        while (i + ch <= n) {
            var x = 0.0; for (c in 0 until ch) x += buf[i + c]; x /= ch * 32768.0
            lpL += aL * (x - lpL); lpM += aM * (x - lpM)
            val h = x - lpM
            aLo += lpL * lpL; aHi += h * h; aAll += x * x; cnt++
            if (cnt == hop) {
                lo.add(sqrt(aLo / hop).toFloat()); hi.add(sqrt(aHi / hop).toFloat()); all.add(sqrt(aAll / hop).toFloat())
                aLo = 0.0; aHi = 0.0; aAll = 0.0; cnt = 0
            }
            i += ch
        }
    }
    val n = lo.size
    if (n < 800) throw IllegalStateException("musiqa juda qisqa")
    val mLo = lo.average().toFloat() + 1e-6f; val mHi = hi.average().toFloat() + 1e-6f
    val fl = FloatArray(n) { if (it == 0) 0f else max(0f, lo[it] - lo[it - 1]) / mLo + 0.5f * max(0f, hi[it] - hi[it - 1]) / mHi }
    return Feat(hop.toDouble() / sr, lo.toFloatArray(), hi.toFloatArray(), all.toFloatArray(), fl)
}

class Grid(val bpm: Double, val beats: DoubleArray, val phase: Int)

// Tempo (avtokorrelyatsiya) + zarbalarni kuzatish (PLL) + birinchi zarba (downbeat) fazasi
fun trackBeats(f: Feat): Grid {
    val fl = f.fl; val n = fl.size
    val mean = fl.average().toFloat()
    val x = FloatArray(n) { fl[it] - mean }
    fun ac(l: Int): Double { var s = 0.0; for (i in l until n) s += x[i] * x[i - l]; return s / (n - l) }
    val lmin = (60.0 / 175 / f.hopSec).toInt(); val lmax = (60.0 / 75 / f.hopSec).toInt()
    var bl = lmin; var bs = -1e18
    for (l in lmin..lmax) {
        var s = 0.0
        for (m in 1..4) if (m * l < n - 10) s += ac(m * l) / m
        val z = ln(60.0 / (l * f.hopSec) / 120.0) / 0.6
        s *= exp(-0.5 * z * z)
        if (s > bs) { bs = s; bl = l }
    }
    val a = ac(bl - 1); val b = ac(bl); val c = ac(bl + 1); val den = a - 2 * b + c
    val P = if (den < 0) bl + (0.5 * (a - c) / den).coerceIn(-1.0, 1.0) else bl.toDouble()
    var bph = 0; var bsum = -1.0
    for (ph in 0 until P.toInt()) {
        var s = 0.0; var k = 0
        while (true) { val i = (ph + k * P).toInt(); if (i >= n) break; s += fl[i]; k++ }
        if (s > bsum) { bsum = s; bph = ph }
    }
    val beats = ArrayList<Double>(); var e = bph.toDouble()
    while (e < n - 1) {
        val l0 = max(0, (e - 0.12 * P).toInt()); val h0 = min(n - 1, (e + 0.12 * P).toInt())
        var bi = e.toInt().coerceIn(0, n - 1); var bv = -1f
        for (k in l0..h0) if (fl[k] > bv) { bv = fl[k]; bi = k }
        val err = bi - e
        beats.add((e + 0.5 * err) * f.hopSec); e += P + 0.2 * err
    }
    var bp = 0; var bps = -1.0
    for (p in 0 until 4) {
        var s = 0.0; var k = p
        while (k < beats.size) { s += f.lo[(beats[k] / f.hopSec).toInt().coerceIn(0, n - 1)]; k += 4 }
        if (s > bps) { bps = s; bp = p }
    }
    return Grid(60.0 / (P * f.hopSec), beats.toDoubleArray(), bp)
}

class GenreCfg(val pat: Map<String, List<String>>, val punch: Float, val shake: Boolean, val flash: Boolean, val text: Int, val spd: Float)
val GENRE_NAMES = listOf("🔥 Phonk", "🇧🇷 Funk", "⚡ Aura", "🎬 Cinema", "🎵 Pop")
val GC = listOf(
    GenreCfg(mapOf("calm" to listOf("4", "2 2"), "mid" to listOf("2 2", "1 1 2"), "build1" to listOf("1 1 1 1"), "build2" to listOf("1 1 .5 .5 .5 .5"),
        "drop" to listOf("1.5 1.5 1", "1 .5 .5 1 1", ".5 .5 1 .5 .5 1", "1 1 .5 .5 1"), "break" to listOf("4", "2 2")), .14f, true, true, 2, 1f),
    GenreCfg(mapOf("calm" to listOf("2 2"), "mid" to listOf("1.5 1.5 1", "2 2"), "build1" to listOf("1 1 1 1"), "build2" to listOf(".5 .5 .5 .5 .5 .5 .5 .5"),
        "drop" to listOf("1.5 1.5 1", "1.5 1.5 .5 .5", ".5 .5 .5 .5 .5 .5 .5 .5", "1 .5 .5 1.5 .5"), "break" to listOf("2 2")), .14f, true, true, 2, 1.1f),
    GenreCfg(mapOf("calm" to listOf("4"), "mid" to listOf("2 2", "4"), "build1" to listOf("2 2"), "build2" to listOf("1 1 1 1"),
        "drop" to listOf("2 2", "1 1 2", "1 1 1 1"), "break" to listOf("4")), .18f, true, true, 2, .9f),
    GenreCfg(mapOf("calm" to listOf("4"), "mid" to listOf("4", "2 2"), "build1" to listOf("4"), "build2" to listOf("2 2"),
        "drop" to listOf("2 2", "1 1 2"), "break" to listOf("4")), .07f, false, false, 1, .7f),
    GenreCfg(mapOf("calm" to listOf("4"), "mid" to listOf("2 2", "4"), "build1" to listOf("2 2"), "build2" to listOf("1 1 1 1"),
        "drop" to listOf("2 2", "1 1 2", "1 1 1 1"), "break" to listOf("4")), .09f, false, true, 1, .9f)
)

fun segFor(role: String, d: Double, first: Boolean, last: Boolean, k: Int, c: GenreCfg): TSeg = when (role) {
    "calm", "break" -> TSeg(d, .7f, "push", .12f, false, true, "lo")
    "mid" -> TSeg(d, if (k % 2 == 0) 1f else 1.1f, "punch", c.punch * .5f, false, false, "seq")
    "build1" -> TSeg(d, 1f, "push", .1f, false, false, "seq")
    "build2" -> if (last) TSeg(d, .5f, "push", .2f, false, false, "lo") else TSeg(d, 1.2f, "punch", c.punch * .7f, false, false, "hi")
    else -> {
        val sp = 1f + (floatArrayOf(1f, 1.4f, 1f, 1.7f, 1.2f)[k % 5] - 1f) * c.spd
        if (first) TSeg(d, 1f, if (c.shake) "shake" else "punch", c.punch + .06f, c.flash, false, "hi")
        else TSeg(d, sp, if (k % 2 == 1) "punch" else "none", c.punch, false, false, "hi")
    }
}

class UserPlan(val song: Song, val startSec: Double, val bpm: Double, val dropSec: Double)

// Qoʻshiqning haqiqiy zarbalari asosida edit rejasini tuzadi
fun buildPlan(f: Feat, g: Grid, genre: Int, durSec: Double, title: String, startSec: Double? = null): UserPlan {
    val c = GC[genre]; val bt = g.beats; val hs = f.hopSec
    fun idx(t: Double) = (t / hs).toInt().coerceIn(0, f.all.size - 1)
    val nB = bt.size - 1
    fun mean(a: FloatArray, t0: Double, t1: Double): Double {
        val i0 = idx(t0); val i1 = max(i0 + 1, idx(t1)); var s = 0.0
        for (i in i0 until min(i1, a.size)) s += a[i]
        return s / (i1 - i0)
    }
    val loB = DoubleArray(nB) { mean(f.lo, bt[it], bt[it + 1]) }; val eB = DoubleArray(nB) { mean(f.all, bt[it], bt[it + 1]) }
    val mxL = (loB.maxOrNull() ?: 1.0) + 1e-9; val mxE = (eB.maxOrNull() ?: 1.0) + 1e-9
    val cb = DoubleArray(nB) { loB[it] / mxL + 0.5 * eB[it] / mxE }
    var dropBeat = -1; var bestJ = 0.28
    for (k in 4 until nB - 3) {
        val sc = (cb[k] + cb[k + 1] + cb[k + 2] + cb[k + 3]) / 4 - (cb[k - 1] + cb[k - 2] + cb[k - 3] + cb[k - 4]) / 4
        if (sc > bestJ) { bestJ = sc; dropBeat = k }
    }
    val ph = if (dropBeat >= 0) dropBeat % 4 else g.phase
    val bars = (nB - ph) / 4
    if (bars < 4) throw IllegalStateException("musiqa juda qisqa")
    val barE = DoubleArray(bars) { mean(f.all, bt[ph + 4 * it], bt[ph + 4 * it + 4]) }
    val dropBar = if (dropBeat >= 0) (dropBeat - ph) / 4 else -1
    val beatDur = 60.0 / g.bpm
    val B = max(2, min(bars, (durSec / (4 * beatDur)).roundToInt()))
    var sb = 0
    if (startSec != null) { var jj = 0; while (jj < bars - 1 && bt[ph + 4 * jj] < startSec - 0.05) jj++; sb = jj }
    else if (dropBar >= 0) sb = max(0, dropBar - max(2, (B * 0.3).roundToInt()))
    else { var best = -1.0; for (j in 0..bars - B) { var sm = 0.0; for (k in 0 until B) sm += barE[j + k]; if (sm > best) { best = sm; sb = j } } }
    sb = min(sb, bars - B).coerceAtLeast(0)
    val dropRel = if (dropBar >= 0 && dropBar - sb in 0 until B) dropBar - sb else -1
    val win = DoubleArray(B) { barE[sb + it] }; val sorted = win.sortedArray()
    val p35 = sorted[(B * 0.35).toInt()]; val p65 = sorted[min(B - 1, (B * 0.65).toInt())]
    fun lvl(j: Int) = if (win[j] < p35) 0 else if (win[j] > p65) 2 else 1
    val roles = Array(B) { j ->
        if (dropRel >= 0) when {
            j == dropRel - 1 -> "build2"
            j == dropRel - 2 -> "build1"
            j >= dropRel -> if (j == B - 1 && B - dropRel >= 3) "final" else if (lvl(j) == 0 && j > dropRel + 1) "break" else "drop"
            else -> if (lvl(j) == 0) "calm" else "mid"
        } else when (lvl(j)) { 2 -> "drop"; 1 -> "mid"; else -> "calm" }
    }
    val t0 = bt[ph + 4 * sb]
    fun bpos(b: Double): Double { val i = floor(b).toInt().coerceIn(0, nB - 1); return bt[i] + (bt[i + 1] - bt[i]) * (b - i) }
    fun snap(t: Double): Double {
        val c0 = idx(t - 0.12 * beatDur); val c1 = idx(t + 0.12 * beatDur)
        var bi = idx(t); var bv = 0f
        for (k in c0..c1) if (f.fl[k] > bv) { bv = f.fl[k]; bi = k }
        return if (bv > 2.0f) bi * hs else t
    }
    val minBeats = when (genre) { 0, 1 -> 1.5; 2 -> 2.0; 3 -> 3.0; else -> 2.0 }
    val minLen = max(0.9, minBeats * beatDur)
    val segs = ArrayList<TSeg>(); val ops = ArrayList<Op>()
    var prev = 0.0; var dropClip = -1.0
    for (j in 0 until B) {
        val role = roles[j]
        val pats = c.pat[if (role == "final") "drop" else role] ?: c.pat["mid"]!!
        val pat = pats[(if (dropRel >= 0 && j >= dropRel) j - dropRel else j) % pats.size].split(" ").map { it.toFloat() }
        val bi0 = (ph + 4 * (sb + j)).toDouble()
        var p = 0f
        if (j == dropRel) dropClip = bpos(bi0) - t0
        val ends = ArrayList<Double>()
        pat.forEachIndexed { k, ln ->
            p += ln
            var tEnd = bpos(bi0 + p)
            if (k != pat.size - 1 && p != floor(p)) tEnd = snap(tEnd)
            ends.add(tEnd - t0)
        }
        val acc = ArrayList<Double>(); var st = prev
        for ((ix, te) in ends.withIndex()) {
            val isLast = ix == ends.size - 1
            if (te - st >= minLen || isLast) {
                if (isLast && te - st < minLen && acc.isNotEmpty()) acc[acc.size - 1] = te else acc.add(te)
                st = te
            }
        }
        acc.forEachIndexed { k, te ->
            val d = te - prev
            var sg = segFor(role, d, k == 0, k == acc.size - 1, k, c)
            if (k < 2 && (j == dropRel || (dropRel < 0 && j == 0))) sg = TSeg(sg.d, sg.sp, sg.fx, sg.amt, sg.flash, sg.fade, sg.src, c.text)
            segs.add(sg); prev += d
        }
    }
    if (dropClip > 0.7) { val rd = min(0.6, beatDur * 1.2); ops.add(Op("reverse", dropClip - rd, rd)) }
    if (roles[B - 1] == "final") ops.add(Op("tapestop", prev - beatDur, beatDur))
    return UserPlan(Song("user", title, g.bpm.roundToInt(), prev, "🎧", segs, ops), t0, g.bpm, if (dropClip >= 0) dropClip + t0 else -1.0)
}

fun applyOps(d: ShortArray, sr: Int, ch: Int, ops: List<Op>) {
    val frames = d.size / ch
    for (o in ops) {
        val f0 = (o.t * sr).toInt(); val f1 = min(frames, f0 + (o.d * sr).toInt())
        if (f0 <= 0 || f0 >= frames) continue
        when (o.op) {
            "reverse" -> reverseFrames(d, ch, f0, f1)
            "tapestop" -> tapeStop(d, ch, f0, f1)
            "stutter" -> { }
        }
    }
}

fun buildUserAudio(ctx: Context, uri: Uri, plan: UserPlan, out: File) {
    val total = plan.song.dur; val start = plan.startSec
    var d: ShortArray? = null; var sr = 44100; var ch = 2; var startF = 0L
    forEachPcm(ctx, uri, start, start + total + 0.5) { buf, n, c, s, af ->
        if (d == null) { sr = s; ch = c; startF = (start * s).toLong(); d = ShortArray((total * s).toInt() * c) }
        val arr = d!!
        val frames = n / c
        for (fi in 0 until frames) {
            val rel = af + fi - startF
            if (rel < 0 || (rel + 1) * c > arr.size) continue
            for (cc in 0 until c) arr[(rel * c + cc).toInt()] = buf[fi * c + cc]
        }
    }
    val arr = d ?: throw IllegalStateException("audio")
    applyOps(arr, sr, ch, plan.song.ops)
    writeWav(arr, sr, ch, out)
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (on) Color(0xFF3B2A8F) else CARD, RoundedCornerShape(10.dp))
            .border(1.5.dp, if (on) Color(0xFFA5B4FC) else Color(0xFF1F2547), RoundedCornerShape(10.dp))
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp)
    ) { Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

data class Lang(val code: String, val flag: String, val name: String)
val LANGS = listOf(
    Lang("uz", "🇺🇿", "Oʻzbekcha"), Lang("en", "🇬🇧", "English"), Lang("ru", "🇷🇺", "Русский"),
    Lang("tr", "🇹🇷", "Türkçe"), Lang("ar", "🇸🇦", "العربية")
)
val STR: Map<String, Map<String, String>> = mapOf(
    "uz" to mapOf("video" to "Video", "songs" to "MUSIQA QOLIPI", "tap" to "Tanlash", "ok" to "✓ Tanlandi",
        "hint" to "Videoni tanlang va musiqa qolipini belgilang.\nEditx AI videoni tayyor qolipga joylaydi",
        "prep" to "Video tahlil qilinmoqda…", "render" to "Ultra HD 4K render qilinmoqda…", "done" to "Tayyor ✅ Galereya → Movies/EditxAI",
        "savefail" to "Saqlab boʻlmadi", "error" to "Xato", "go" to "✨ QOLIPGA JOYLASH", "busy" to "ISHLANMOQDA…",
        "lang" to "Til", "uhd" to "ULTRA HD 4K", "nosongs" to "Musiqalar topilmadi", "mymusic" to "Mening musiqam", "ana" to "Musiqa chuqur tahlil qilinmoqda…", "found" to "Ritm topildi: %1\$d BPM, %2\$d kesim", "titleph" to "Ekranda chiqadigan matn (qoʻshiq nomi)", "pickpart" to "Musiqaning qaysi qismi? Chiziqni suring"),
    "en" to mapOf("video" to "Video", "songs" to "MUSIC TEMPLATE", "tap" to "Tap to pick", "ok" to "✓ Selected",
        "hint" to "Pick a video and a music template.\nEditx AI fits your video into it",
        "prep" to "Analyzing video…", "render" to "Rendering Ultra HD 4K…", "done" to "Done ✅ Gallery → Movies/EditxAI",
        "savefail" to "Couldn't save", "error" to "Error", "go" to "✨ APPLY TEMPLATE", "busy" to "WORKING…",
        "lang" to "Language", "uhd" to "ULTRA HD 4K", "nosongs" to "No songs found", "mymusic" to "My music", "ana" to "Deep-analyzing the music…", "found" to "Beat found: %1\$d BPM, %2\$d cuts", "titleph" to "On-screen text (song name)", "pickpart" to "Which part of the song? Drag the line"),
    "ru" to mapOf("video" to "Видео", "songs" to "ШАБЛОН МУЗЫКИ", "tap" to "Выбрать", "ok" to "✓ Выбрано",
        "hint" to "Выберите видео и шаблон музыки.\nEditx AI вставит видео в готовый шаблон",
        "prep" to "Анализ видео…", "render" to "Рендер Ultra HD 4K…", "done" to "Готово ✅ Галерея → Movies/EditxAI",
        "savefail" to "Не удалось сохранить", "error" to "Ошибка", "go" to "✨ ПРИМЕНИТЬ ШАБЛОН", "busy" to "РАБОТАЮ…",
        "lang" to "Язык", "uhd" to "ULTRA HD 4K", "nosongs" to "Песни не найдены", "mymusic" to "Моя музыка", "ana" to "Глубокий анализ музыки…", "found" to "Ритм найден: %1\$d BPM, %2\$d склеек", "titleph" to "Текст на экране (название песни)", "pickpart" to "Какую часть песни взять? Двигайте линию"),
    "tr" to mapOf("video" to "Video", "songs" to "MÜZİK ŞABLONU", "tap" to "Seç", "ok" to "✓ Seçildi",
        "hint" to "Video seçin ve bir müzik şablonu belirleyin.\nEditx AI videoyu hazır şablona yerleştirir",
        "prep" to "Video analiz ediliyor…", "render" to "Ultra HD 4K render ediliyor…", "done" to "Hazır ✅ Galeri → Movies/EditxAI",
        "savefail" to "Kaydedilemedi", "error" to "Hata", "go" to "✨ ŞABLONU UYGULA", "busy" to "ÇALIŞIYOR…",
        "lang" to "Dil", "uhd" to "ULTRA HD 4K", "nosongs" to "Şarkı bulunamadı", "mymusic" to "Müziğim", "ana" to "Müzik derinlemesine analiz ediliyor…", "found" to "Ritim bulundu: %1\$d BPM, %2\$d kesim", "titleph" to "Ekranda görünecek yazı (şarkı adı)", "pickpart" to "Şarkının hangi bölümü? Çizgiyi kaydırın"),
    "ar" to mapOf("video" to "فيديو", "songs" to "قالب الموسيقى", "tap" to "اختر", "ok" to "✓ تم الاختيار",
        "hint" to "اختر فيديو وقالب موسيقى.\nEditx AI يضع الفيديو في القالب الجاهز",
        "prep" to "جارٍ تحليل الفيديو…", "render" to "جارٍ التصيير بدقة Ultra HD 4K…", "done" to "تم ✅ المعرض ← Movies/EditxAI",
        "savefail" to "تعذر الحفظ", "error" to "خطأ", "go" to "✨ تطبيق القالب", "busy" to "جارٍ العمل…",
        "lang" to "اللغة", "uhd" to "ULTRA HD 4K", "nosongs" to "لا توجد أغانٍ", "mymusic" to "موسيقاي", "ana" to "جارٍ تحليل الموسيقى بعمق…", "found" to "تم إيجاد الإيقاع: %1\$d BPM، %2\$d قطعة", "titleph" to "النص على الشاشة (اسم الأغنية)", "pickpart" to "أي جزء من الأغنية؟ حرّك الخط")
)
fun tr(lang: String, key: String): String = STR[lang]?.get(key) ?: STR["en"]!![key] ?: key

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
fun SongRow(s: Song, sel: Boolean, playing: Boolean, onSel: () -> Unit, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (sel) Color(0xFF1B1A5A) else CARD, RoundedCornerShape(12.dp))
            .border(1.5.dp, if (sel) Color(0xFF6D5DFC) else Color(0xFF1F2547), RoundedCornerShape(12.dp))
            .clickable { onSel() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(s.emoji, fontSize = 26.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("${s.bpm} BPM · ${s.dur.toInt()} s", color = Color(0xFF8F95C4), fontSize = 12.sp)
        }
        Box(Modifier.size(40.dp).background(NEON, CircleShape).clickable { onPlay() }, contentAlignment = Alignment.Center) {
            Text(if (playing) "⏸" else "▶", color = Color.White, fontSize = 16.sp)
        }
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

class Loaded(val f: Feat, val g: Grid, val w: FloatArray, val sug: Double)

@Composable
fun MusicLine(wave: FloatArray, total: Double, winSec: Double, startSec: Double, playing: Boolean, label: String, onSeek: (Double) -> Unit, onToggle: () -> Unit) {
    fun mmss(t: Double) = String.format(java.util.Locale.US, "%d:%02d", (t / 60).toInt(), (t % 60).toInt())
    Column(
        Modifier.fillMaxWidth().background(CARD, RoundedCornerShape(12.dp)).border(1.5.dp, Color(0xFF1F2547), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, color = Color(0xFFA5B4FC), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(NEON, CircleShape).clickable { onToggle() }, contentAlignment = Alignment.Center) {
                Text(if (playing) "⏸" else "▶", color = Color.White, fontSize = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            Canvas(
                Modifier.weight(1f).height(70.dp)
                    .pointerInput(total, winSec) { detectTapGestures { o -> onSeek(((o.x / size.width).toDouble() * total).coerceIn(0.0, max(0.0, total - winSec))) } }
                    .pointerInput(total, winSec) { detectDragGestures(onDrag = { change, _ -> change.consume(); onSeek(((change.position.x / size.width).toDouble() * total).coerceIn(0.0, max(0.0, total - winSec))) }) }
            ) {
                val n = wave.size; val bw = size.width / n
                val a = (startSec / total * n).toInt(); val b = ((startSec + winSec) / total * n).toInt()
                for (i in 0 until n) {
                    val h = max(3f, wave[i] * size.height)
                    drawRect(if (i in a until b) Color(0xFF8B5CF6) else Color(0xFF3A3F6B), Offset(i * bw + 1f, (size.height - h) / 2), Size(max(1f, bw - 2f), h))
                }
            }
        }
        Text(mmss(startSec) + " – " + mmss(min(total, startSec + winSec)), color = Color(0xFF8F95C4), fontSize = 12.sp)
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
    val songs = remember { loadSongs(ctx) }
    var sel by remember { mutableStateOf(0) }
    var useUser by remember { mutableStateOf(false) }
    var userMusic by remember { mutableStateOf<Uri?>(null) }
    var userName by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf(0) }
    var len by remember { mutableStateOf(30) }
    var title by remember { mutableStateOf("") }
    var video by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    val mp = remember { arrayOfNulls<MediaPlayer>(1) }
    val mpu = remember { arrayOfNulls<MediaPlayer>(1) }
    var feat by remember { mutableStateOf<Feat?>(null) }
    var grid by remember { mutableStateOf<Grid?>(null) }
    var wave by remember { mutableStateOf<FloatArray?>(null) }
    var startSel by remember { mutableStateOf(0.0) }
    var userPlaying by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { mp[0]?.release(); mpu[0]?.release() } }
    fun stopPreview() { mp[0]?.release(); mp[0] = null; playing = null; mpu[0]?.let { if (it.isPlaying) it.pause() }; userPlaying = false }
    fun playUser(startS: Double) {
        val u = userMusic ?: return
        try {
            val p = mpu[0] ?: MediaPlayer().also { it.setDataSource(ctx, u); it.prepare(); it.setOnCompletionListener { userPlaying = false }; mpu[0] = it }
            mp[0]?.release(); mp[0] = null; playing = null
            p.seekTo((startS * 1000).toInt()); if (!p.isPlaying) p.start(); userPlaying = true
        } catch (e: Exception) { status = tr(lang, "error") + ": " + e.message }
    }
    val pv = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) video = it }
    val pm = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        if (it != null) {
            val uri: Uri = it
            stopPreview(); mpu[0]?.release(); mpu[0] = null
            userMusic = uri; useUser = true; userName = displayName(ctx, uri); title = userName.take(30)
            feat = null; grid = null; wave = null
            scope.launch {
                status = tr(lang, "ana")
                try {
                    val r = withContext(Dispatchers.Default) {
                        val f = analyzeFeatures(ctx, uri, 300); val g = trackBeats(f)
                        val nb = 160
                        val w = FloatArray(nb) { i -> val a = i * f.all.size / nb; val b = max(a + 1, (i + 1) * f.all.size / nb); var sm = 0f; for (k in a until min(b, f.all.size)) sm += f.all[k]; sm / (b - a) }
                        val mx = w.maxOrNull() ?: 1f
                        for (i in w.indices) w[i] = w[i] / (mx + 1e-6f)
                        val sug = try { buildPlan(f, g, 0, 30.0, "", null).startSec } catch (e: Exception) { 0.0 }
                        Loaded(f, g, w, sug)
                    }
                    feat = r.f; grid = r.g; wave = r.w; startSel = r.sug; status = null
                } catch (e: Throwable) { status = tr(lang, "error") + ": " + e.message }
            }
        }
    }
    val cl = LANGS.first { it.code == lang }

    if (showLang) LangDialog(lang, tr(lang, "lang"), { c -> lang = c; prefs.edit().putString("lang", c).apply(); showLang = false }, { showLang = false })

    Column(
        Modifier.fillMaxSize().background(BG).verticalScroll(rememberScrollState()).padding(16.dp).padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
        Pick("🎞️", tr(lang, "video"), if (video != null) tr(lang, "ok") else tr(lang, "tap"), video != null, Modifier.fillMaxWidth()) { pv.launch("video/*") }
        Text(tr(lang, "songs"), color = Color(0xFFA5B4FC), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Row(
            Modifier.fillMaxWidth().background(if (useUser) Color(0xFF1B1A5A) else CARD, RoundedCornerShape(12.dp))
                .border(1.5.dp, if (useUser) Color(0xFF6D5DFC) else Color(0xFF1F2547), RoundedCornerShape(12.dp))
                .clickable { stopPreview(); if (userMusic != null && !useUser) useUser = true else pm.launch("audio/*") }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🎧", fontSize = 26.sp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tr(lang, "mymusic"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(if (userMusic != null) userName else tr(lang, "tap"), color = Color(0xFF8F95C4), fontSize = 12.sp, maxLines = 1)
            }
            if (useUser) Text("✓", color = Color(0xFFA5B4FC), fontSize = 18.sp)
        }
        if (useUser) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GENRE_NAMES.forEachIndexed { i, n -> Chip(n, genre == i) { genre = i } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(15, 30, 45).forEach { Chip("$it s", len == it) { len = it } } }
            BasicTextField(
                value = title, onValueChange = { title = it.take(30) },
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth().background(CARD, RoundedCornerShape(12.dp)).border(1.5.dp, Color(0xFF1F2547), RoundedCornerShape(12.dp)).padding(14.dp),
                decorationBox = { inner -> Box { if (title.isEmpty()) Text(tr(lang, "titleph"), color = Color(0xFF6B7099), fontSize = 14.sp); inner() } }
            )
        }
        val wv = wave; val ft = feat
        if (useUser && wv != null && ft != null) {
            MusicLine(wv, ft.all.size * ft.hopSec, len.toDouble(), startSel, userPlaying, tr(lang, "pickpart"),
                { st -> startSel = st; playUser(st) },
                { if (userPlaying) { mpu[0]?.pause(); userPlaying = false } else playUser(startSel) })
        }
        if (songs.isEmpty()) Text(tr(lang, "nosongs"), color = Color(0xFF8F95C4), fontSize = 14.sp)
        songs.forEachIndexed { i, s ->
            SongRow(s, !useUser && sel == i, playing == s.id, { sel = i; useUser = false }) {
                if (playing == s.id) stopPreview() else {
                    stopPreview()
                    try {
                        val fd = ctx.assets.openFd(s.id + ".wav")
                        val p = MediaPlayer(); p.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length); fd.close()
                        p.setOnCompletionListener { playing = null }; p.prepare(); p.start(); mp[0] = p; playing = s.id
                    } catch (e: Exception) { status = tr(lang, "error") + ": " + e.message }
                }
            }
        }
        Image(
            painterResource(R.drawable.logo), null,
            Modifier.fillMaxWidth().height(110.dp).background(Color.Black, RoundedCornerShape(10.dp)).border(1.5.dp, Color(0xFF6D5DFC), RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit
        )
        Text(tr(lang, "uhd"), color = Color(0xFFA5B4FC), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(status ?: tr(lang, "hint"), color = Color(0xFF8F95C4), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Box(
            Modifier.fillMaxWidth().background(NEON, RoundedCornerShape(12.dp))
                .clickable(enabled = !busy && video != null && (if (useUser) (userMusic != null && feat != null) else songs.isNotEmpty())) {
                    stopPreview()
                    scope.launch {
                        busy = true
                        try {
                            val v = video!!
                            val song: Song; val wav: File
                            if (useUser) {
                                val um = userMusic!!
                                val f1 = feat!!; val g1 = grid!!; val st1 = startSel
                                val plan = withContext(Dispatchers.Default) { buildPlan(f1, g1, genre, len.toDouble(), title, st1) }
                                status = String.format(java.util.Locale.US, tr(lang, "found"), plan.bpm.roundToInt(), plan.song.segs.size)
                                TITLE = title.trim()
                                song = plan.song
                                wav = withContext(Dispatchers.IO) { val o = File(ctx.cacheDir, "music_edit.wav"); buildUserAudio(ctx, um, plan, o); o }
                            } else {
                                TITLE = ""
                                song = songs[sel]
                                wav = withContext(Dispatchers.IO) { val o = File(ctx.cacheDir, "music_edit.wav"); buildSongAudio(ctx, song, o); o }
                            }
                            status = tr(lang, "prep")
                            val runs = withContext(Dispatchers.Default) {
                                val vd = durationMs(ctx, v)
                                if (vd <= 0) throw IllegalStateException("video duration")
                                fillTemplate(song, motionOrder(ctx, v, vd), vd)
                            }
                            status = tr(lang, "render")
                            val f = runExport(ctx, v, Uri.fromFile(wav), runs, song.dur)
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

fun fxList(r: Run): List<Effect> {
    val t = r.t; val l = ArrayList<Effect>()
    if (r.speed != 1f) l.add(SpeedChangeEffect(r.speed))
    when (t.fx) {
        "punch" -> l.add(punchFx(t.amt, false))
        "shake" -> l.add(punchFx(t.amt, true))
        "push" -> l.add(pushFx(r.dur.toFloat(), t.amt))
    }
    if (t.fade) l.add(fadeFx(r.dur.toFloat(), 0.3f))
    if (t.flash) l.add(flashFx(1.2f))
    l.add(Presentation.createForWidthAndHeight(2160, 3840, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
    if (t.txt > 0 && TITLE.isNotBlank()) l.add(textFx(TITLE.trim(), t.txt == 2))
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

suspend fun runExport(ctx: Context, video: Uri, music: Uri, segs: List<Run>, total: Double): File =
    suspendCancellableCoroutine { cont ->
        val out = File(ctx.cacheDir, "editx_${System.currentTimeMillis()}.mp4")
        val items = segs.map { s ->
            val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.srcMs).setEndPositionMs(s.srcMs + s.clipMs).build()
            EditedMediaItem.Builder(MediaItem.Builder().setUri(video).setClippingConfiguration(clip).build())
                .setRemoveAudio(true).setEffects(Effects(emptyList(), fxList(s))).build()
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
