package com.rblxstudio

import android.opengl.Matrix
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Tool { SELECT, MOVE, SCALE, ROTATE }

object Editor {
    @Volatile var tool = Tool.SELECT
    @Volatile var snapMove = true
    @Volatile var snapRot = true
    @Volatile var activeHandle = -1
    var moveStep = 1f
    var rotStep = 15f
    var onChanged: ((Boolean) -> Unit)? = null

    val axisColors = arrayOf(
        floatArrayOf(0.9f, 0.25f, 0.25f),
        floatArrayOf(0.3f, 0.8f, 0.3f),
        floatArrayOf(0.25f, 0.5f, 0.95f)
    )

    private var dragAxis = 0
    private var dragDir = v3(1f, 0f, 0f)
    private var s0 = 0f
    private var a0 = 0f
    private var startPos = v3(0f, 0f, 0f)
    private var startSize = 0f
    private val startRot = FloatArray(16)

    class Handle(val axis: Int, val sign: Float, val center: FloatArray, val ori: FloatArray?, val half: FloatArray)

    fun k() = max(Cam.dist * 0.04f, 0.3f)
    fun radius(p: PartData) = 0.5f * sqrt(p.sx * p.sx + p.sy * p.sy + p.sz * p.sz)

    fun axisVec(i: Int): FloatArray = when (i) {
        0 -> v3(1f, 0f, 0f)
        1 -> v3(0f, 1f, 0f)
        else -> v3(0f, 0f, 1f)
    }

    fun handles(p: PartData): List<Handle> {
        val k = k()
        val c = v3(p.x, p.y, p.z)
        val out = ArrayList<Handle>()
        when (tool) {
            Tool.MOVE -> {
                val rr = radius(p)
                for (i in 0..2) {
                    val d = axisVec(i)
                    val shaft = FloatArray(3) { 0.06f * k }
                    shaft[i] = k
                    out.add(Handle(i, 1f, add3(c, mul3(d, rr + 2f * k)), null, shaft))
                    out.add(Handle(i, 1f, add3(c, mul3(d, rr + 3.6f * k)), null, floatArrayOf(0.4f * k, 0.4f * k, 0.4f * k)))
                }
            }
            Tool.SCALE -> {
                for (i in 0..2) {
                    for (s in floatArrayOf(1f, -1f)) {
                        val d = v3(p.rot[i * 4], p.rot[i * 4 + 1], p.rot[i * 4 + 2])
                        out.add(Handle(i, s, add3(c, mul3(d, s * (p.size(i) / 2 + 0.6f * k))), p.rot, floatArrayOf(0.3f * k, 0.3f * k, 0.3f * k)))
                    }
                }
            }
            else -> {}
        }
        return out
    }

    private fun axisParam(o: FloatArray, d: FloatArray, p0: FloatArray, dir: FloatArray): Float {
        val w0 = sub3(p0, o)
        val b = dot3(dir, d)
        val den = 1f - b * b
        if (den < 1e-4f) return Float.NaN
        return (b * dot3(d, w0) - dot3(dir, w0)) / den
    }

    private fun rayPlane(o: FloatArray, d: FloatArray, c: FloatArray, n: FloatArray): FloatArray? {
        val den = dot3(d, n)
        if (abs(den) < 1e-5f) return null
        val t = dot3(sub3(c, o), n) / den
        if (t < 0f) return null
        return add3(o, mul3(d, t))
    }

    private fun angleOf(q: FloatArray, axis: Int): Float = when (axis) {
        0 -> atan2(q[2], q[1])
        1 -> atan2(q[0], q[2])
        else -> atan2(q[1], q[0])
    }

    fun grab(x: Float, y: Float): Boolean {
        val p = Scene.selected ?: return false
        if (tool == Tool.SELECT) return false
        val ray = Cam.ray(x, y)
        val o = ray[0]
        val d = ray[1]
        val k = k()
        val c = v3(p.x, p.y, p.z)
        if (tool == Tool.ROTATE) {
            val rad = radius(p) + 1.5f * k
            var best = -1
            var bestErr = 0.6f * k + 0.15f
            var bestHit: FloatArray? = null
            for (i in 0..2) {
                val hit = rayPlane(o, d, c, axisVec(i)) ?: continue
                val err = abs(len3(sub3(hit, c)) - rad)
                if (err < bestErr) {
                    bestErr = err
                    best = i
                    bestHit = hit
                }
            }
            if (best < 0 || bestHit == null) return false
            dragAxis = best
            a0 = angleOf(sub3(bestHit, c), best)
            System.arraycopy(p.rot, 0, startRot, 0, 16)
            activeHandle = best * 2
            return true
        }
        var bestT = Float.MAX_VALUE
        var bestH: Handle? = null
        for (h in handles(p)) {
            val ph = FloatArray(3) { h.half[it] + 0.35f * k }
            val t = rayObb(o, d, h.center, h.ori, ph)
            if (t >= 0f && t < bestT) {
                bestT = t
                bestH = h
            }
        }
        val h = bestH ?: return false
        dragAxis = h.axis
        dragDir = if (tool == Tool.MOVE) axisVec(h.axis)
        else mul3(v3(p.rot[h.axis * 4], p.rot[h.axis * 4 + 1], p.rot[h.axis * 4 + 2]), h.sign)
        startPos = c
        startSize = p.size(h.axis)
        s0 = axisParam(o, d, c, dragDir)
        if (s0.isNaN()) return false
        activeHandle = h.axis * 2 + (if (h.sign > 0f) 0 else 1)
        return true
    }

    fun drag(x: Float, y: Float) {
        if (activeHandle < 0) return
        val p = Scene.selected ?: return
        val ray = Cam.ray(x, y)
        val o = ray[0]
        val d = ray[1]
        if (tool == Tool.ROTATE) {
            val c = v3(p.x, p.y, p.z)
            val hit = rayPlane(o, d, c, axisVec(dragAxis)) ?: return
            var deg = Math.toDegrees((angleOf(sub3(hit, c), dragAxis) - a0).toDouble()).toFloat()
            deg %= 360f
            if (deg > 180f) deg -= 360f
            if (deg < -180f) deg += 360f
            if (snapRot) deg = (deg / rotStep).roundToInt() * rotStep
            val r = FloatArray(16)
            val out = FloatArray(16)
            val a = axisVec(dragAxis)
            Matrix.setRotateM(r, 0, deg, a[0], a[1], a[2])
            Matrix.multiplyMM(out, 0, r, 0, startRot, 0)
            System.arraycopy(out, 0, p.rot, 0, 16)
        } else {
            val s = axisParam(o, d, startPos, dragDir)
            if (s.isNaN()) return
            var delta = s - s0
            if (snapMove) delta = (delta / moveStep).roundToInt() * moveStep
            if (tool == Tool.MOVE) {
                p.x = startPos[0] + dragDir[0] * delta
                p.y = startPos[1] + dragDir[1] * delta
                p.z = startPos[2] + dragDir[2] * delta
            } else {
                val ns = max(0.05f, startSize + delta)
                val ad = ns - startSize
                p.setSize(dragAxis, ns)
                p.x = startPos[0] + dragDir[0] * ad / 2
                p.y = startPos[1] + dragDir[1] * ad / 2
                p.z = startPos[2] + dragDir[2] * ad / 2
            }
        }
        onChanged?.invoke(false)
    }

    fun release() { activeHandle = -1 }

    fun pick(x: Float, y: Float): PartData? {
        val ray = Cam.ray(x, y)
        var best: PartData? = null
        var bt = Float.MAX_VALUE
        for (p in Scene.parts) {
            val t = rayObb(ray[0], ray[1], v3(p.x, p.y, p.z), p.rot, floatArrayOf(p.sx / 2, p.sy / 2, p.sz / 2))
            if (t >= 0f && t < bt) {
                bt = t
                best = p
            }
        }
        return best
    }

    fun tap(x: Float, y: Float) = select(pick(x, y))

    fun select(p: PartData?) {
        Scene.selected = p
        onChanged?.invoke(true)
    }

    fun addPart() {
        val ray = Cam.ray(Cam.w / 2f, Cam.h / 2f)
        val hit = rayPlane(ray[0], ray[1], v3(0f, 0f, 0f), v3(0f, 1f, 0f))
        val px = if (hit != null) hit[0].roundToInt().toFloat() else 0f
        val pz = if (hit != null) hit[2].roundToInt().toFloat() else 0f
        val p = PartData("Part", px, 0.5f, pz, 4f, 1f, 2f, 0.64f, 0.635f, 0.647f)
        Scene.parts.add(p)
        select(p)
    }

    fun copy() { Scene.clipboard = Scene.selected?.copyPart() }

    fun cut() {
        copy()
        delete()
    }

    fun paste() {
        val c = Scene.clipboard ?: return
        val p = c.copyPart()
        Scene.parts.add(p)
        select(p)
    }

    fun duplicate() {
        val s = Scene.selected ?: return
        val p = s.copyPart()
        p.x += 2f
        Scene.parts.add(p)
        select(p)
    }

    fun delete() {
        val s = Scene.selected ?: return
        Scene.parts.remove(s)
        select(null)
    }

    fun setColor(r: Float, g: Float, b: Float) {
        val s = Scene.selected ?: return
        s.r = r
        s.g = g
        s.b = b
        onChanged?.invoke(false)
    }

    fun toggleAnchor() {
        val s = Scene.selected ?: return
        s.anchored = !s.anchored
        onChanged?.invoke(false)
    }
}
