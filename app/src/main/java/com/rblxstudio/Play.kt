package com.rblxstudio

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// Play próprio: personagem andando na cena com física simples (gravidade, pulo, colisão com caixas)
object Play {
    @Volatile var active = false
    private val lock = Any()
    private var snapshot: List<PartData> = emptyList()

    // entrada (preenchida pela UI)
    @Volatile var moveX = 0f   // direita +
    @Volatile var moveY = 0f   // frente +
    @Volatile var jump = false

    // personagem (centro da caixa de colisão)
    @Volatile var x = 0f
    @Volatile var y = 0f
    @Volatile var z = 0f
    @Volatile var facing = 0f  // graus
    @Volatile var walkT = 0f
    @Volatile var moving = false
    private var vy = 0f
    private var grounded = false
    private var spawnX = 0f
    private var spawnY = 0f
    private var spawnZ = 0f

    const val HX = 0.9f
    const val HY = 2.5f
    const val HZ = 0.9f
    private const val SPEED = 16f
    private const val GRAVITY = 196.2f
    private const val JUMP = 50f

    fun start() {
        synchronized(lock) {
            if (active) return
            snapshot = Scene.parts.map { it.copyPart() }
            Scene.selected = null
            val sp = Scene.parts.firstOrNull { it.name == "SpawnLocation" }
            if (sp != null) {
                spawnX = sp.x
                spawnY = sp.y + sp.halfExtent(1) + HY + 0.5f
                spawnZ = sp.z
            } else {
                spawnX = 0f; spawnY = HY + 3f; spawnZ = 0f
            }
            respawn()
            Cam.dist = 16f
            Cam.pitch = 0.45f
            active = true
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!active) return
            active = false
            Scene.parts.clear()
            Scene.parts.addAll(snapshot.map { it.copyPart() })
            Scene.selected = null
            moveX = 0f; moveY = 0f; jump = false
        }
    }

    private fun respawn() {
        x = spawnX; y = spawnY; z = spawnZ
        vy = 0f
    }

    private class Box(val p: PartData) {
        val c = floatArrayOf(p.x, p.y, p.z)
        val h = floatArrayOf(p.halfExtent(0), p.halfExtent(1), p.halfExtent(2))
        fun min(i: Int) = c[i] - h[i]
        fun max(i: Int) = c[i] + h[i]
    }

    private fun solids(): List<Box> = Scene.parts.filter { it.canCollide }.map { Box(it) }

    fun update(dt: Float) {
        synchronized(lock) {
            if (!active) return
            stepParts(dt)
            stepPlayer(dt)
        }
    }

    // peças sem Anchored caem e param em cima das outras
    private fun stepParts(dt: Float) {
        val boxes = solids()
        for (u in Scene.parts) {
            if (u.anchored) continue
            u.vy -= GRAVITY * dt
            if (u.vy < -300f) u.vy = -300f
            val hx = u.halfExtent(0)
            val hy = u.halfExtent(1)
            val hz = u.halfExtent(2)
            val oldBottom = u.y - hy
            var ny = u.y + u.vy * dt
            if (u.canCollide) {
                for (o in boxes) {
                    if (o.p === u) continue
                    val overX = abs(u.x - o.c[0]) < hx + o.h[0]
                    val overZ = abs(u.z - o.c[2]) < hz + o.h[2]
                    if (overX && overZ && u.vy <= 0f && oldBottom >= o.max(1) - 0.05f && ny - hy < o.max(1)) {
                        ny = o.max(1) + hy
                        u.vy = 0f
                    }
                }
            }
            u.y = ny
            if (u.y < -500f) {
                u.y = -500f
                u.vy = 0f
            }
        }
    }

    private fun stepPlayer(dt: Float) {
        val cy = Cam.yaw
        val fx = -sin(cy)
        val fz = -cos(cy)
        val rx = cos(cy)
        val rz = -sin(cy)
        var mx = fx * moveY + rx * moveX
        var mz = fz * moveY + rz * moveX
        val len = hypot(mx, mz)
        if (len > 1f) { mx /= len; mz /= len }
        val power = if (len > 1f) 1f else len
        moving = power > 0.1f
        if (moving) {
            val target = Math.toDegrees(atan2(mx, mz).toDouble()).toFloat()
            var d = target - facing
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            facing += d * 0.35f
            walkT += dt * power
        }
        if (jump && grounded) vy = JUMP
        vy -= GRAVITY * dt
        if (vy < -300f) vy = -300f

        val boxes = solids()
        move(boxes, 0, mx * SPEED * dt)
        move(boxes, 2, mz * SPEED * dt)
        grounded = false
        move(boxes, 1, vy * dt)
        if (y < -300f) respawn()

        Cam.target[0] = x
        Cam.target[1] = y + 1.5f
        Cam.target[2] = z
    }

    private fun move(boxes: List<Box>, axis: Int, d: Float) {
        if (d == 0f) return
        val pos = floatArrayOf(x, y, z)
        val half = floatArrayOf(HX, HY, HZ)
        pos[axis] += d
        for (b in boxes) {
            var hit = true
            for (i in 0..2) {
                if (pos[i] - half[i] >= b.max(i) || pos[i] + half[i] <= b.min(i)) { hit = false; break }
            }
            if (!hit) continue
            if (axis == 1) {
                if (d > 0f) {
                    pos[1] = b.min(1) - half[1] - 0.001f
                    if (vy > 0f) vy = 0f
                } else {
                    pos[1] = b.max(1) + half[1] + 0.001f
                    grounded = true
                    vy = 0f
                }
            } else {
                val rise = b.max(1) - (pos[1] - half[1])
                if (grounded && rise > 0f && rise <= 1.1f) {
                    pos[1] = b.max(1) + half[1] + 0.002f // sobe degrau pequeno
                } else if (d > 0f) {
                    pos[axis] = b.min(axis) - half[axis] - 0.001f
                } else {
                    pos[axis] = b.max(axis) + half[axis] + 0.001f
                }
            }
        }
        x = pos[0]; y = pos[1]; z = pos[2]
    }
}
