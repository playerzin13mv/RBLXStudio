package com.rblxstudio

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// Play: personagem + física das peças + scripts Lua
object Play {
    @Volatile var active = false
    private val lock = Any()
    private var snapshot: List<PartData> = emptyList()
    private var treeSnap: Map<String, List<Inst>> = emptyMap()

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

    @Volatile var health = 100f
    @Volatile var walkSpeed = 16f
    @Volatile var jumpPower = 50f
    @Volatile var playerName = "Player"
    @Volatile var userId = 1L

    private var vy = 0f
    private var grounded = false
    private var spawnX = 0f
    private var spawnY = 0f
    private var spawnZ = 0f

    const val HX = 0.9f
    const val HY = 2.5f
    const val HZ = 0.9f
    private const val GRAVITY = 196.2f

    private val touching = HashSet<PartData>()
    private val pairNow = HashMap<Long, Array<PartData>>()
    private val pairPrev = HashSet<Long>()

    private fun pairKey(a: PartData, b: PartData): Long {
        val ha = System.identityHashCode(a)
        val hb = System.identityHashCode(b)
        return (minOf(ha, hb).toLong() shl 32) or (maxOf(ha, hb).toLong() and 0xffffffffL)
    }

    // here = true: "Play Here" (nasce onde a câmera está olhando)
    fun start(here: Boolean = false) {
        synchronized(lock) {
            if (active) return
            snapshot = Scene.parts.map { it.copyPart() }
            treeSnap = Scene.snapshot()
            Scene.selected = null
            Scene.selObj = null
            computeSpawn(here)

            val pl = Inst("Player", playerName)
            val ch = Inst("Model", playerName)
            ch.add(CharInst("Part", "HumanoidRootPart"))
            ch.add(CharInst("Humanoid", "Humanoid"))
            ch.add(CharInst("Part", "Head"))
            ch.parent = Scene.workspace
            val gui = Inst("PlayerGui", "PlayerGui")
            for (c in Scene.service("StarterGui").children) gui.add(c.cloneTree())
            pl.add(gui)
            Scene.players.add(pl)
            Scene.localPlayer = pl
            Scene.character = ch
            Scene.playerGui = gui

            health = 100f
            walkSpeed = 16f
            jumpPower = 50f
            touching.clear()
            pairNow.clear()
            pairPrev.clear()
            Physics.touchCb = { a, b ->
                if (Lua.touchSet.contains(a) || Lua.touchSet.contains(b)) pairNow[pairKey(a, b)] = arrayOf(a, b)
            }
            respawn()
            Cam.dist = 16f
            Cam.pitch = 0.45f
            active = true
            Lua.start()
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!active) return
            active = false
            Lua.stop()
            Physics.touchCb = null
            Scene.parts.clear()
            Scene.parts.addAll(snapshot.map { it.copyPart() })
            Scene.restore(treeSnap)
            Scene.players.children.clear()
            Scene.localPlayer = null
            Scene.character = null
            Scene.playerGui = null
            Scene.selected = null
            Scene.selObj = null
            moveX = 0f; moveY = 0f; jump = false
        }
    }

    private fun computeSpawn(here: Boolean) {
        if (here) {
            val px = Cam.target[0]
            val pz = Cam.target[2]
            val ty = Cam.target[1]
            var top = -1e9f
            var topAny = -1e9f
            for (p in Scene.parts) {
                if (!p.canCollide) continue
                if (abs(px - p.x) < p.halfExtent(0) && abs(pz - p.z) < p.halfExtent(2)) {
                    val t = p.y + p.halfExtent(1)
                    if (t > topAny) topAny = t
                    if (t <= ty + 20f && t > top) top = t
                }
            }
            val base = if (top > -1e8f) top else if (topAny > -1e8f) topAny else 0f
            spawnX = px; spawnY = base + HY + 3f; spawnZ = pz
            return
        }
        val sp = Scene.parts.firstOrNull { it.name == "SpawnLocation" }
        if (sp != null) {
            spawnX = sp.x
            spawnY = sp.y + sp.halfExtent(1) + HY + 0.5f
            spawnZ = sp.z
        } else {
            spawnX = 0f; spawnY = HY + 3f; spawnZ = 0f
        }
    }

    private fun respawn() {
        x = spawnX; y = spawnY; z = spawnZ
        vy = 0f
        health = 100f
    }

    fun respawnNow() {
        synchronized(lock) { if (active) respawn() }
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
            try {
                Lua.step(dt)
                Physics.step(dt)
            } catch (e: Throwable) {
                Out.add("Erro: " + (e.message ?: e.javaClass.simpleName), 2)
            }
            stepPlayer(dt)
            touches()
            for (p in Scene.parts) if (p.y < -500f) Scene.destroy(p)
            if (health <= 0f) {
                Lua.died()
                respawn()
            }
        }
    }

    private fun touches() {
        if (Lua.touchSet.isEmpty()) {
            touching.clear()
            pairNow.clear()
            pairPrev.clear()
            return
        }
        val root = Scene.character?.children?.firstOrNull { it.name == "HumanoidRootPart" }
        val now = HashSet<PartData>()
        for (i in Lua.touchSet) {
            if (i !is PartData) continue
            if (abs(x - i.x) < HX + 0.2f + i.halfExtent(0) &&
                abs(y - i.y) < HY + 0.2f + i.halfExtent(1) &&
                abs(z - i.z) < HZ + 0.2f + i.halfExtent(2)
            ) {
                now.add(i)
                if (!touching.contains(i)) Lua.touched(i, root)
            }
        }
        touching.clear()
        touching.addAll(now)

        val cur = HashSet<Long>()
        for ((k, pr) in pairNow) {
            cur.add(k)
            if (!pairPrev.contains(k)) {
                if (Lua.touchSet.contains(pr[0])) Lua.touched(pr[0], pr[1])
                if (Lua.touchSet.contains(pr[1])) Lua.touched(pr[1], pr[0])
            }
        }
        pairPrev.clear()
        pairPrev.addAll(cur)
        pairNow.clear()
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
        if (jump && grounded) vy = jumpPower
        vy -= GRAVITY * dt
        if (vy < -300f) vy = -300f

        val boxes = solids()
        move(boxes, 0, mx * walkSpeed * dt)
        move(boxes, 2, mz * walkSpeed * dt)
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
                } else {
                    if (d > 0f) pos[axis] = b.min(axis) - half[axis] - 0.001f
                    else pos[axis] = b.max(axis) + half[axis] + 0.001f
                    // empurra peças soltas
                    if (!b.p.anchored) {
                        b.p.vel[axis] = (if (d > 0f) 1f else -1f) * walkSpeed * 0.7f
                        b.p.asleep = false
                        b.p.sleepT = 0f
                    }
                }
            }
        }
        x = pos[0]; y = pos[1]; z = pos[2]
    }
}