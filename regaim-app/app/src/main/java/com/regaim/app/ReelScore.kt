package com.regaim.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Original music, composed for each edit on the phone (no stock tracks, no licensing). Pure code, no Android.
 *
 * The score is written to the plan, so it can't loop and it can't miss the cuts:
 *  - every cut is on a beat (the plan snaps lengths to the tempo),
 *  - the opening is sparse, the music builds for two bars and arrives on the peak moment's first beat,
 *  - the progression changes at the peak, and the ending drops to a held chord under the closing card.
 *
 * Three scores: EMOTIONAL (warm piano + strings), HAPPY (acoustic guitar, claps, a bell melody),
 * CINEMATIC (piano ostinato, wide pads, low drums, a riser into the peak). [seed] varies voicings and melody.
 */
object ReelScore {
    const val RATE = 44_100

    class Track(val left: FloatArray, val right: FloatArray) {
        val frames: Int get() = left.size
    }

    // ---------- small helpers ----------

    private const val TABLE = 4096
    private val SINE = FloatArray(TABLE) { sin(2 * PI * it / TABLE).toFloat() }
    private fun sinT(phase: Double): Float {
        val p = phase - floor(phase)
        return SINE[(p * TABLE).toInt() and (TABLE - 1)]
    }
    private fun hz(midi: Double) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private class Bus(n: Int) {
        val l = FloatArray(n)
        val r = FloatArray(n)
        fun add(i: Int, v: Float, pan: Float) {
            if (i < 0 || i >= l.size) return
            l[i] += v * (1f - max(0f, pan))
            r[i] += v * (1f + min(0f, pan))
        }
    }

    // ---------- instruments ----------

    /** A piano-like note: slightly inharmonic partials, two detuned strings, hammer noise, pitch-dependent decay. */
    private fun piano(b: Bus, at: Int, midi: Int, durS: Double, vel: Float, pan: Float, rnd: java.util.Random) {
        val f = hz(midi.toDouble())
        val ring = (3.8 - (midi - 48) * 0.045).coerceIn(0.9, 4.5)
        val len = ((durS + 0.25).coerceAtMost(ring * 1.6) * RATE).toInt()
        val rel = (0.18 * RATE).toInt()
        val noteEnd = (durS * RATE).toInt()
        val parts = 6
        for (k in 1..parts) {
            val fk = f * k * sqrt(1 + 0.00035 * k * k)
            if (fk > 9000) break
            val amp = vel * (1.0 / k.toDouble().pow(1.15 + (1 - vel) * 0.6)).toFloat() * 0.5f
            val tau = ring / (1 + 0.55 * (k - 1))
            val step1 = fk / RATE
            val step2 = fk * 1.0009 / RATE
            var ph1 = rnd.nextDouble()
            var ph2 = rnd.nextDouble()
            for (i in 0 until len) {
                val t = i.toDouble() / RATE
                var env = (1 - exp(-t / 0.003)) * exp(-t / tau)
                if (i > noteEnd) env *= exp(-(i - noteEnd).toDouble() / rel)
                if (env < 0.0003 && t > 0.05) break
                val v = (sinT(ph1) + sinT(ph2)) * 0.5f * env.toFloat() * amp
                b.add(at + i, v, pan)
                ph1 += step1; ph2 += step2
            }
        }
        // Hammer: a tiny burst of soft noise.
        var lp = 0f
        for (i in 0 until (0.012 * RATE).toInt()) {
            lp += 0.3f * ((rnd.nextFloat() * 2 - 1) - lp)
            b.add(at + i, lp * 0.025f * vel * (1f - i / (0.012f * RATE)), pan)
        }
    }

    /** A plucked nylon-ish string (Karplus–Strong). */
    private fun pluck(b: Bus, at: Int, midi: Int, durS: Double, vel: Float, pan: Float, rnd: java.util.Random, bright: Float = 0.5f) {
        val f = hz(midi.toDouble())
        val n = max(2, (RATE / f).toInt())
        val buf = FloatArray(n)
        var lp = 0f
        for (i in 0 until n) { lp += bright * ((rnd.nextFloat() * 2 - 1) - lp); buf[i] = lp }
        val len = ((durS + 0.3) * RATE).toInt()
        val damp = (0.996 - (midi - 52) * 0.00025).coerceIn(0.990, 0.9975).toFloat()
        val noteEnd = (durS * RATE).toInt()
        var idx = 0
        var prev = 0f
        for (i in 0 until len) {
            val cur = buf[idx]
            val nxt = 0.5f * (cur + prev) * (if (i > noteEnd) 0.985f else damp)
            prev = cur
            buf[idx] = nxt
            idx = (idx + 1) % n
            b.add(at + i, cur * vel * 0.55f, pan)
        }
    }

    /** A soft string / pad voice: three detuned bright oscillators through a gentle low-pass, slow attack. */
    private fun pad(b: Bus, at: Int, midi: Int, durS: Double, vel: Float, pan: Float, cutoff: Double, vibrato: Boolean, rnd: java.util.Random) {
        val f = hz(midi.toDouble())
        val att = 0.55
        val rel = 0.9
        val len = ((durS + rel) * RATE).toInt()
        val a = (2 * PI * cutoff / RATE).let { it / (1 + it) }.toFloat()
        for ((d, p) in listOf(-0.003 to pan - 0.35f, 0.003 to pan + 0.35f)) {
            var lp1 = 0f
            var lp2 = 0f
            val phs = DoubleArray(6) { rnd.nextDouble() }
            for (i in 0 until len) {
                val t = i.toDouble() / RATE
                val env = min(1.0, t / att) * (if (t > durS) exp(-(t - durS) / (rel / 3)) else 1.0)
                val vib = if (vibrato) 1 + 0.0018 * sin(2 * PI * 5.2 * t + d * 900) else 1.0
                var s = 0f
                for (k in 1..6) {
                    s += sinT(phs[k - 1]) / k
                    phs[k - 1] += f * (1 + d) * vib * k / RATE
                }
                lp1 += a * (s - lp1)
                lp2 += a * (lp1 - lp2)
                b.add(at + i, lp2 * env.toFloat() * vel * 0.15f, p.coerceIn(-1f, 1f))
            }
        }
    }

    private fun bass(b: Bus, at: Int, midi: Int, durS: Double, vel: Float) {
        val f = hz(midi.toDouble())
        val len = ((durS + 0.15) * RATE).toInt()
        var ph = 0.0
        for (i in 0 until len) {
            val t = i.toDouble() / RATE
            val env = (1 - exp(-t / 0.006)) * (0.55 + 0.45 * exp(-t / 0.25)) * (if (t > durS) exp(-(t - durS) / 0.05) else 1.0)
            val v = (sinT(ph) + 0.28f * sinT(2 * ph) + 0.08f * sinT(3 * ph)) * env.toFloat() * vel * 0.32f
            b.add(at + i, v, 0f)
            ph += f / RATE
        }
    }

    /** A soft bell (glockenspiel-like): inharmonic partials, quick decay. */
    private fun bell(b: Bus, at: Int, midi: Int, vel: Float, pan: Float) {
        val f = hz(midi.toDouble())
        val len = (1.6 * RATE).toInt()
        for ((ratio, amp, tau) in listOf(Triple(1.0, 1.0, 0.9), Triple(2.76, 0.35, 0.35), Triple(5.4, 0.12, 0.15))) {
            var ph = 0.0
            for (i in 0 until len) {
                val t = i.toDouble() / RATE
                val env = (1 - exp(-t / 0.002)) * exp(-t / tau)
                b.add(at + i, sinT(ph) * (env * amp).toFloat() * vel * 0.16f, pan)
                ph += f * ratio / RATE
            }
        }
    }

    private fun kick(b: Bus, at: Int, vel: Float, deep: Boolean) {
        val len = ((if (deep) 1.1 else 0.35) * RATE).toInt()
        var ph = 0.0
        for (i in 0 until len) {
            val t = i.toDouble() / RATE
            val f = if (deep) 38 + 50 * exp(-t / 0.05) else 50 + 70 * exp(-t / 0.03)
            val env = exp(-t / (if (deep) 0.45 else 0.12))
            b.add(at + i, sinT(ph) * env.toFloat() * vel * (if (deep) 0.55f else 0.45f), 0f)
            ph += f / RATE
        }
    }

    private fun noiseHit(b: Bus, at: Int, durS: Double, vel: Float, pan: Float, hp: Float, lp: Float, rnd: java.util.Random, bursts: Int = 1) {
        val len = (durS * RATE).toInt()
        var l1 = 0f
        var h1 = 0f
        var prevIn = 0f
        for (i in 0 until len) {
            val t = i.toDouble() / RATE
            var env = exp(-t / (durS / 4)).toFloat()
            if (bursts > 1) { val gap = 0.011; val k = (t / gap).toInt(); if (k < bursts - 1) env = exp(-(t - k * gap) / 0.004).toFloat() }
            val x = rnd.nextFloat() * 2 - 1
            h1 = hp * (h1 + x - prevIn); prevIn = x
            l1 += lp * (h1 - l1)
            b.add(at + i, l1 * env * vel, pan)
        }
    }

    /** Filtered noise rising into the peak (cinematic), [lenS] long, ending exactly at [end]. */
    private fun riser(b: Bus, end: Int, lenS: Double, vel: Float, rnd: java.util.Random) {
        val len = (lenS * RATE).toInt()
        val start = end - len
        var l1 = 0f
        for (i in 0 until len) {
            val p = i.toFloat() / len
            val a = 0.02f + 0.25f * p * p
            l1 += a * ((rnd.nextFloat() * 2 - 1) - l1)
            val v = l1 * p * p * vel * 0.35f
            b.add(start + i, v, sin(i * 0.00012).toFloat() * 0.5f)
        }
    }

    // ---------- harmony ----------

    private class Chord(val root: Int, val notes: IntArray)

    private fun triad(root: Int, minor: Boolean): Chord = Chord(root, intArrayOf(root, root + (if (minor) 3 else 4), root + 7))

    private fun progressions(style: ReelStyle): Triple<List<Chord>, List<Chord>, Chord> = when (style) {
        // C major: Am F C G → (peak) F G C Am, ending on C.
        ReelStyle.EMOTIONAL -> Triple(
            listOf(triad(57, true), triad(53, false), triad(60, false), triad(55, false)),
            listOf(triad(53, false), triad(55, false), triad(60, false), triad(57, true)),
            triad(60, false),
        )
        // G major: G D Em C → (peak) C D G Em, ending on G.
        ReelStyle.HAPPY -> Triple(
            listOf(triad(55, false), triad(62, false), triad(52, true), triad(60, false)),
            listOf(triad(60, false), triad(62, false), triad(55, false), triad(52, true)),
            triad(55, false),
        )
        // D minor: Dm Bb F C → (peak) Bb F C Dm, ending on F (warm, open).
        ReelStyle.CINEMATIC -> Triple(
            listOf(triad(50, true), triad(58, false), triad(53, false), triad(60, false)),
            listOf(triad(58, false), triad(53, false), triad(60, false), triad(50, true)),
            triad(53, false),
        )
    }

    private fun scale(style: ReelStyle): IntArray = when (style) {
        ReelStyle.EMOTIONAL -> intArrayOf(0, 2, 4, 5, 7, 9, 11).map { it + 60 }.toIntArray()
        ReelStyle.HAPPY -> intArrayOf(0, 2, 4, 6, 7, 9, 11).map { it + 55 }.toIntArray()
        ReelStyle.CINEMATIC -> intArrayOf(0, 2, 3, 5, 7, 8, 10).map { it + 50 }.toIntArray()
    }

    // ---------- the score ----------

    fun compose(plan: ReelPlan): Track {
        val style = plan.style
        val rnd = java.util.Random(31L * plan.seed + style.ordinal)
        val beatS = 60.0 / plan.bpm
        val barS = beatS * 4
        val totalS = plan.totalMs / 1000.0 // exactly the film; the last chord fades over the closing card
        val n = (totalS * RATE).toInt()
        val dry = Bus(n)
        val wet = Bus(n)
        val bars = (plan.totalMs / 1000.0 / barS).toInt().coerceAtLeast(1)
        fun barOf(ms: Long) = (ms / 1000.0 / barS).toInt()
        fun s(sec: Double) = (sec * RATE).toInt()

        val shots = plan.shots
        val introEnd = (if (shots.size > 1) barOf(shots[1].atMs) else 1).coerceIn(1, 2)
        val peakBar = if (shots.isEmpty()) bars / 2 else barOf(shots[plan.peakIndex.coerceIn(0, shots.size - 1)].atMs).coerceAtLeast(introEnd)
        val buildStart = max(introEnd, peakBar - 2)
        val outroBar = if (shots.isEmpty()) bars - 2 else barOf(shots.last().atMs).coerceAtLeast(peakBar + 1)
        val finalBar = bars - 1
        val (verse, peakProg, finalChord) = progressions(style)
        val inv = rnd.nextInt(3) // voicing variation
        val sc = scale(style)

        fun chordAt(bar: Int): Chord = when {
            bar >= finalBar -> finalChord
            bar >= peakBar -> peakProg[(bar - peakBar) % 4]
            else -> verse[bar % 4]
        }
        fun voiced(c: Chord, base: Int): IntArray {
            val v = c.notes.map { var m = it; while (m < base) m += 12; while (m >= base + 12) m -= 12; m }.sorted().toMutableList()
            repeat(inv) { v.add(v.removeAt(0) + 12) }
            return v.toIntArray()
        }
        fun intensity(bar: Int): Float = when {
            bar < introEnd -> 0.35f
            bar < buildStart -> 0.6f
            bar < peakBar -> 0.6f + 0.25f * (bar - buildStart + 1) / max(1, peakBar - buildStart)
            bar < outroBar -> 1f
            else -> 0.45f
        }

        // A short melody motif for the peak, varied by the seed.
        val rhythms = listOf(doubleArrayOf(1.5, 0.5, 2.0), doubleArrayOf(1.0, 1.0, 2.0), doubleArrayOf(0.5, 0.5, 1.0, 2.0), doubleArrayOf(2.0, 1.0, 1.0))
        val motif = rhythms[rnd.nextInt(rhythms.size)]

        for (bar in 0..finalBar) {
            val t0 = bar * barS
            if (t0 >= totalS) break
            val c = chordAt(bar)
            val e = intensity(bar)
            val last = bar == finalBar
            when (style) {
                ReelStyle.EMOTIONAL -> {
                    val v = voiced(c, 60)
                    // Broken chords in eighths (quarters in the intro and the ending).
                    val pattern = intArrayOf(0, 1, 2, 1, 0 + 3, 2, 1, 2)
                    val steps = if (bar < introEnd || bar >= outroBar) 4 else 8
                    val stepS = barS / steps
                    if (last) {
                        for ((k, m) in v.withIndex()) piano(dry, s(t0 + k * 0.05), m, barS * 1.5, 0.5f, -0.2f + k * 0.2f, rnd)
                        piano(dry, s(t0), c.root - 12, barS * 1.5, 0.55f, 0f, rnd)
                    } else for (k in 0 until steps) {
                        val idx = pattern[k % pattern.size]
                        val m = if (idx >= 3) v[0] + 12 else v[idx]
                        piano(dry, s(t0 + k * stepS), m, stepS * 2.2, (0.32f + 0.18f * e) * (if (k == 0) 1.15f else 1f), (idx - 1) * 0.25f, rnd)
                    }
                    if (!last) piano(dry, s(t0), c.root - 12, barS * 0.95, 0.42f + 0.1f * e, 0f, rnd)
                    // Strings join after the intro and swell into the peak.
                    if (bar >= introEnd) for (m in voiced(c, 55)) pad(wet, s(t0), m, barS, 0.55f * e + 0.15f, 0f, 1400.0 + 1600 * e, true, rnd)
                    // The melody: on the peak and the bar after it.
                    if (bar == peakBar || bar == peakBar + 1) {
                        var tt = t0
                        for ((k, d) in motif.withIndex()) {
                            val m = v[(k + bar) % 3] + 12
                            piano(dry, s(tt), m, beatS * d, 0.62f, 0.15f, rnd)
                            tt += beatS * d
                        }
                    }
                }
                ReelStyle.HAPPY -> {
                    val v = voiced(c, 52) + voiced(c, 64)
                    // Strum pattern (eighths): D . D U . U D U
                    val strums = if (bar < introEnd || bar >= outroBar) listOf(0 to true, 4 to true) else listOf(0 to true, 2 to true, 3 to false, 5 to false, 6 to true, 7 to false)
                    if (last) {
                        for ((k, m) in v.withIndex()) pluck(dry, s(t0 + k * 0.018), m, barS * 1.5, 0.5f, -0.4f + k * 0.15f, rnd, 0.45f)
                    } else for ((j, st) in strums.withIndex()) {
                        val (pos, down) = st
                        val ts = t0 + pos * beatS / 2
                        // Each strum rings until the next one (no gaps), the last one into the next bar.
                        val nextPos = strums.getOrNull(j + 1)?.first ?: 8
                        val ring = (nextPos - pos) * beatS / 2 + 0.06
                        val order = if (down) v.toList() else v.reversed().take(4)
                        for ((k, m) in order.withIndex()) pluck(dry, s(ts + k * 0.011), m, ring, (if (down) 0.38f else 0.26f) * (0.75f + 0.35f * e), -0.4f + k * 0.15f, rnd, if (down) 0.5f else 0.65f)
                    }
                    // A light warm pad under the guitar after the intro.
                    if (bar >= introEnd && !last) for (m in voiced(c, 60)) pad(wet, s(t0), m, barS, 0.18f + 0.12f * e, 0f, 1800.0, false, rnd)
                    if (bar >= introEnd && !last) {
                        bass(dry, s(t0), c.root - 24, beatS * 1.4, 0.8f * e + 0.2f)
                        bass(dry, s(t0 + beatS * 1.5), c.root - 24, beatS * 0.45, 0.5f * e)
                        bass(dry, s(t0 + beatS * 2), c.notes[2] - 24, beatS * 1.4, 0.7f * e + 0.2f)
                    }
                    if (bar >= introEnd && bar < outroBar) {
                        kick(dry, s(t0), 0.55f * e, false); kick(dry, s(t0 + 2 * beatS), 0.5f * e, false)
                        noiseHit(dry, s(t0 + beatS), 0.18, 0.5f * e, 0.15f, 0.95f, 0.35f, rnd, 3)
                        noiseHit(dry, s(t0 + 3 * beatS), 0.18, 0.5f * e, 0.15f, 0.95f, 0.35f, rnd, 3)
                        for (k in 0 until 8) noiseHit(dry, s(t0 + k * beatS / 2 + 0.01), 0.07, (if (k % 2 == 1) 0.16f else 0.1f) * e, 0.35f, 0.99f, 0.6f, rnd)
                    }
                    if (bar >= peakBar && bar < outroBar) {
                        // A bell melody over the peak.
                        var tt = t0
                        for ((k, d) in motif.withIndex()) {
                            bell(wet, s(tt), sc[(k * 2 + bar) % sc.size] + 12, 0.7f, 0.25f)
                            tt += beatS * d
                        }
                    }
                }
                ReelStyle.CINEMATIC -> {
                    val v = voiced(c, 57)
                    // Piano ostinato in eighths: root, fifth, octave, fifth…
                    val ost = intArrayOf(v[0], v[2], v[0] + 12, v[2], v[1] + 12, v[2], v[0] + 12, v[1])
                    if (!last) for (k in 0 until 8) piano(dry, s(t0 + k * beatS / 2), ost[k], beatS * 0.9, (0.24f + 0.16f * e) * (if (k % 4 == 0) 1.2f else 1f), if (k % 2 == 0) -0.25f else 0.25f, rnd)
                    else for ((k, m) in v.withIndex()) piano(dry, s(t0 + k * 0.06), m, barS * 1.5, 0.45f, -0.2f + 0.2f * k, rnd)
                    // A wide pad all the way; low and dark in the intro, opening up toward the peak.
                    for (m in voiced(c, 48)) pad(wet, s(t0), m, barS, 0.45f + 0.35f * e, 0f, 700.0 + 2400 * e, false, rnd)
                    if (bar >= introEnd) bass(dry, s(t0), c.root - 24, barS * 0.95, 0.5f + 0.4f * e)
                    // Low drums from the build on; a riser lands on the peak.
                    if (bar >= buildStart && bar < outroBar) { kick(dry, s(t0), 0.7f * e, true); if (bar >= peakBar) kick(dry, s(t0 + 2.5 * beatS), 0.45f * e, true) }
                    if (bar == peakBar && peakBar > introEnd) riser(wet, s(t0), min(barS * 2, t0), 0.9f, rnd)
                    if (bar == peakBar || bar == peakBar + 1) {
                        var tt = t0
                        for ((k, d) in motif.withIndex()) {
                            pad(wet, s(tt), v[(k + bar) % 3] + 12, beatS * d, 0.5f, 0.1f, 3200.0, true, rnd)
                            tt += beatS * d
                        }
                    }
                }
            }
            // A soft cymbal swell / crash where the music arrives at the peak (all styles).
            if (bar == peakBar && peakBar > 0) noiseHit(wet, s(t0), 2.2, if (style == ReelStyle.HAPPY) 0.18f else 0.14f, 0f, 0.995f, 0.5f, rnd)
        }

        // ---------- room and master ----------
        val room = if (style == ReelStyle.HAPPY) 0.62f else 0.8f
        val (rl, rr) = reverb(dry, wet, room)
        val outL = FloatArray(n)
        val outR = FloatArray(n)
        val wetMix = if (style == ReelStyle.HAPPY) 0.22f else 0.32f
        for (i in 0 until n) {
            outL[i] = dry.l[i] + wet.l[i] * 0.85f + rl[i] * wetMix
            outR[i] = dry.r[i] + wet.r[i] * 0.85f + rr[i] * wetMix
        }
        // Gentle level and soft limit; short fade in, and the end rings out.
        var peak = 1e-4f
        for (i in 0 until n) peak = max(peak, max(abs(outL[i]), abs(outR[i])))
        val g = 0.9f / peak * 1.6f
        val fadeOut = (2.0 * RATE).toInt()
        for (i in 0 until n) {
            var k = g
            if (i < 400) k *= i / 400f
            if (i > n - fadeOut) k *= (n - i).toFloat() / fadeOut
            outL[i] = (tanh(outL[i] * k) * 0.92f)
            outR[i] = (tanh(outR[i] * k) * 0.92f)
        }
        return Track(outL, outR)
    }

    /** A small stereo room (Schroeder: 4 combs + 2 all-passes per side). */
    private fun reverb(dry: Bus, wet: Bus, room: Float): Pair<FloatArray, FloatArray> {
        val n = dry.l.size
        fun side(input: (Int) -> Float, spread: Int): FloatArray {
            val combs = intArrayOf(1116, 1188, 1277, 1356).map { it + spread }
            val out = FloatArray(n)
            for (d in combs) {
                val buf = FloatArray(d)
                var idx = 0
                var lp = 0f
                for (i in 0 until n) {
                    val y = buf[idx]
                    lp = y * 0.72f + lp * 0.28f
                    buf[idx] = input(i) * 0.25f + lp * room
                    idx = (idx + 1) % d
                    out[i] += y
                }
            }
            for (d in intArrayOf(556 + spread, 441 + spread)) {
                val buf = FloatArray(d)
                var idx = 0
                for (i in 0 until n) {
                    val bo = buf[idx]
                    val x = out[i]
                    val y = -x + bo
                    buf[idx] = x + bo * 0.5f
                    idx = (idx + 1) % d
                    out[i] = y
                }
            }
            return out
        }
        return side({ dry.l[it] * 0.6f + wet.l[it] }, 0) to side({ dry.r[it] * 0.6f + wet.r[it] }, 23)
    }

    /** 16-bit stereo WAV. */
    fun wav(t: Track, out: java.io.File) {
        val n = t.frames
        val bytes = ByteArray(n * 4)
        for (i in 0 until n) {
            val l = (t.left[i] * 32767).toInt().coerceIn(-32767, 32767)
            val r = (t.right[i] * 32767).toInt().coerceIn(-32767, 32767)
            bytes[i * 4] = (l and 0xff).toByte(); bytes[i * 4 + 1] = ((l shr 8) and 0xff).toByte()
            bytes[i * 4 + 2] = (r and 0xff).toByte(); bytes[i * 4 + 3] = ((r shr 8) and 0xff).toByte()
        }
        java.io.FileOutputStream(out).use { f ->
            fun le(v: Int, k: Int) = ByteArray(k) { ((v shr (8 * it)) and 0xff).toByte() }
            f.write("RIFF".toByteArray()); f.write(le(36 + bytes.size, 4)); f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray()); f.write(le(16, 4)); f.write(le(1, 2)); f.write(le(2, 2))
            f.write(le(RATE, 4)); f.write(le(RATE * 4, 4)); f.write(le(4, 2)); f.write(le(16, 2))
            f.write("data".toByteArray()); f.write(le(bytes.size, 4)); f.write(bytes)
        }
    }
}
