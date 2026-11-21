package com.rblxstudio

import android.opengl.Matrix
import kotlin.math.abs
import kotlin.math.max

// Física de corpo rígido (caixas): gravidade 196.2, rotação, quique (Elasticity), atrito (Friction), sono
object Physics {
    const val G = 196.2f
    private const val SUB = 4
    private const val ITER = 8
    @Volatile var touchCb: ((PartData, PartData) -> Unit)? = null
    private val rm = FloatArray(16)
    private val rr = FloatArray(16)

    // contato: normal n aponta de a para b
    private class Ct(val a: PartData, val b: PartData, val p: FloatArray, val n: FloatArray, val depth: Float) {
        val ra = FloatArray(3)
        val rb = FloatArray(3)
        val t1 = FloatArray(3)
        val t2 = FloatArray(3)
        var jn = 0f
        var j1 = 0f
        var j2 = 0f
        var bounce = 0f
        var mu = 0.3f
        var kn = 1f
        var k1 = 1f
        var k2 = 1f
    }

    private fun cross(a: FloatArray, b: FloatArray) =
        v3(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun axis(p: PartData, i: Int) = v3(p.rot[i * 4], p.rot[i * 4 + 1], p.rot[i * 4 + 2])
    private fun mass(p: PartData) = max(p.sx * p.sy * p.sz * 0.7f, 0.01f)
    private fun invM(p: PartData) = if (p.anchored || p.asleep) 0f else 1f / mass(p)

    // inércia inversa no mundo vezes v
    private fun invI(p: PartData, v: FloatArray): FloatArray {
        val out = FloatArray(3)
        if (p.anchored || p.asleep) return out
        val m = mass(p) / 12f
        val inv = floatArrayOf(
            1f / max(m * (p.sy * p.sy + p.sz * p.sz), 1e-4f),
            1f / max(m * (p.sx * p.sx + p.sz * p.sz), 1e-4f),
            1f / max(m * (p.sx * p.sx + p.sy * p.sy), 1e-4f)
        )
        for (i in 0..2) {
            val a = axis(p, i)
            val s = dot3(a, v) * inv[i]
            for (k in 0..2) out[k] += a[k] * s
        }
        return out
    }

    private fun velAt(p: PartData, r: FloatArray): FloatArray {
        if (p.anchored || p.asleep) return v3(0f, 0f, 0f)
        return add3(p.vel, cross(p.ang, r))
    }

    private fun eff(c: Ct, d: FloatArray): Float {
        val ia = cross(invI(c.a, cross(c.ra, d)), c.ra)
        val ib = cross(invI(c.b, cross(c.rb, d)), c.rb)
        return invM(c.a) + invM(c.b) + dot3(d, add3(ia, ib))
    }

    private fun apply(c: Ct, imp: FloatArray) {
        val a = c.a
        val b = c.b
        val ib = invM(b)
        if (ib > 0f) {
            for (k in 0..2) b.vel[k] += imp[k] * ib
            val w = invI(b, cross(c.rb, imp))
            for (k in 0..2) b.ang[k] += w[k]
        }
        val ia = invM(a)
        if (ia > 0f) {
            for (k in 0..2) a.vel[k] -= imp[k] * ia
            val w = invI(a, cross(c.ra, imp))
            for (k in 0..2) a.ang[k] -= w[k]
        }
    }

    // cantos de x dentro de y
    private fun corners(x: PartData, y: PartData, out: MutableList<Ct>) {
        val ax = Array(3) { axis(x, it) }
        val ay = Array(3) { axis(y, it) }
        val hx = floatArrayOf(x.sx / 2, x.sy / 2, x.sz / 2)
        val hy = floatArrayOf(y.sx / 2, y.sy / 2, y.sz / 2)
        val cy = v3(y.x, y.y, y.z)
        for (c in 0..7) {
            val s = floatArrayOf(
                if ((c and 1) != 0) 1f else -1f,
                if ((c and 2) != 0) 1f else -1f,
                if ((c and 4) != 0) 1f else -1f
            )
            val p = v3(x.x, x.y, x.z)
            for (i in 0..2) {
                val k = s[i] * hx[i]
                p[0] += ax[i][0] * k
                p[1] += ax[i][1] * k
                p[2] += ax[i][2] * k
            }
            val d = sub3(p, cy)
            var best = 0
            var bd = Float.MAX_VALUE
            var bs = 1f
            var inside = true
            for (j in 0..2) {
                val q = dot3(ay[j], d)
                val pen = hy[j] - abs(q)
                if (pen <= 0f) { inside = false; break }
                if (pen < bd) {
                    bd = pen
                    best = j
                    bs = if (q >= 0f) 1f else -1f
                }
            }
            if (inside) out.add(Ct(y, x, p, mul3(ay[best], bs), bd))
        }
    }

    private fun ortho(p: PartData) {
        val c0 = norm3(v3(p.rot[0], p.rot[1], p.rot[2]))
        var c1 = v3(p.rot[4], p.rot[5], p.rot[6])
        val k = dot3(c1, c0)
        c1 = norm3(v3(c1[0] - c0[0] * k, c1[1] - c0[1] * k, c1[2] - c0[2] * k))
        val c2 = cross(c0, c1)
        for (i in 0..2) {
            p.rot[i] = c0[i]
            p.rot[4 + i] = c1[i]
            p.rot[8 + i] = c2[i]
        }
    }

    fun wakeAll() {
        for (p in Scene.parts) {
            p.asleep = false
            p.sleepT = 0f
        }
    }

    private fun sub(h: Float, parts: List<PartData>) {
        for (p in parts) {
            if (p.anchored || p.asleep) continue
            p.vel[1] -= G * h
            val sp = len3(p.vel)
            if (sp > 250f) {
                val k = 250f / sp
                for (i in 0..2) p.vel[i] *= k
            }
            val dmp = 1f - 0.2f * h
            for (i in 0..2) p.ang[i] *= dmp
        }

        val solid = parts.filter { it.canCollide }
        val cs = ArrayList<Ct>()
        for (i in solid.indices) {
            val a = solid[i]
            val aOn = !a.anchored && !a.asleep
            for (j in i + 1 until solid.size) {
                val b = solid[j]
                val bOn = !b.anchored && !b.asleep
                if (!aOn && !bOn) continue
                if (abs(a.x - b.x) >= a.halfExtent(0) + b.halfExtent(0)) continue
                if (abs(a.y - b.y) >= a.halfExtent(1) + b.halfExtent(1)) continue
                if (abs(a.z - b.z) >= a.halfExtent(2) + b.halfExtent(2)) continue
                if (a.asleep && !b.anchored && len3(b.vel) > 1f) { a.asleep = false; a.sleepT = 0f }
                if (b.asleep && !a.anchored && len3(a.vel) > 1f) { b.asleep = false; b.sleepT = 0f }
                corners(a, b, cs)
                corners(b, a, cs)
            }
        }

        val thr = G * h * 1.5f + 0.5f
        for (c in cs) {
            val ca = v3(c.a.x, c.a.y, c.a.z)
            val cb = v3(c.b.x, c.b.y, c.b.z)
            for (k in 0..2) {
                c.ra[k] = c.p[k] - ca[k]
                c.rb[k] = c.p[k] - cb[k]
            }
            val ref = if (abs(c.n[1]) < 0.9f) v3(0f, 1f, 0f) else v3(1f, 0f, 0f)
            val t1 = norm3(cross(c.n, ref))
            val t2 = cross(c.n, t1)
            for (k in 0..2) {
                c.t1[k] = t1[k]
                c.t2[k] = t2[k]
            }
            val vn = dot3(sub3(velAt(c.b, c.rb), velAt(c.a, c.ra)), c.n)
            val e = (c.a.elasticity + c.b.elasticity) / 2f
            c.bounce = if (vn < -thr) -e * vn else 0f
            c.mu = (c.a.friction + c.b.friction) / 2f
            c.kn = max(eff(c, c.n), 1e-6f)
            c.k1 = max(eff(c, c.t1), 1e-6f)
            c.k2 = max(eff(c, c.t2), 1e-6f)
            touchCb?.invoke(c.a, c.b)
        }

        repeat(ITER) {
            for (c in cs) {
                val vn = dot3(sub3(velAt(c.b, c.rb), velAt(c.a, c.ra)), c.n)
                var dj = (c.bounce - vn) / c.kn
                val nj = max(c.jn + dj, 0f)
                dj = nj - c.jn
                c.jn = nj
                apply(c, mul3(c.n, dj))

                val lim = c.mu * c.jn
                var rel = sub3(velAt(c.b, c.rb), velAt(c.a, c.ra))
                var d1 = -dot3(rel, c.t1) / c.k1
                val n1 = (c.j1 + d1).coerceIn(-lim, lim)
                d1 = n1 - c.j1
                c.j1 = n1
                apply(c, mul3(c.t1, d1))

                rel = sub3(velAt(c.b, c.rb), velAt(c.a, c.ra))
                var d2 = -dot3(rel, c.t2) / c.k2
                val n2 = (c.j2 + d2).coerceIn(-lim, lim)
                d2 = n2 - c.j2
                c.j2 = n2
                apply(c, mul3(c.t2, d2))
            }
        }

        // tira a penetração
        for (c in cs) {
            val ia = invM(c.a)
            val ib = invM(c.b)
            val k = ia + ib
            if (k <= 0f) continue
            val corr = max(c.depth - 0.02f, 0f) * 0.2f / k
            c.a.x -= c.n[0] * corr * ia
            c.a.y -= c.n[1] * corr * ia
            c.a.z -= c.n[2] * corr * ia
            c.b.x += c.n[0] * corr * ib
            c.b.y += c.n[1] * corr * ib
            c.b.z += c.n[2] * corr * ib
        }

        for (p in parts) {
            if (p.anchored || p.asleep) continue
            p.x += p.vel[0] * h
            p.y += p.vel[1] * h
            p.z += p.vel[2] * h
            val w = len3(p.ang)
            if (w > 1e-6f) {
                Matrix.setRotateM(rm, 0, Math.toDegrees((w * h).toDouble()).toFloat(), p.ang[0] / w, p.ang[1] / w, p.ang[2] / w)
                Matrix.multiplyMM(rr, 0, rm, 0, p.rot, 0)
                System.arraycopy(rr, 0, p.rot, 0, 16)
                ortho(p)
            }
            if (len3(p.vel) < 0.35f && w < 0.25f) {
                p.sleepT += h
                if (p.sleepT > 0.5f) {
                    p.asleep = true
                    for (i in 0..2) { p.vel[i] = 0f; p.ang[i] = 0f }
                }
            } else {
                p.sleepT = 0f
            }
        }
    }

    fun step(dt: Float) {
        val parts = Scene.parts.toList()
        if (parts.none { !it.anchored }) return
        val h = dt / SUB
        for (s in 0 until SUB) sub(h, parts)
    }
}