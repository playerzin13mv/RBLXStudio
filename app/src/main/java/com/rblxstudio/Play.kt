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

    // Shift Lock, tap-to-move e morte
    @Volatile var shiftLock = false
    @Volatile var dead = false
    @Volatile var lookHeld = false      // dedo arrastando a câmera
    private var tapOn = false
    private var tapX = 0f
    private var tapZ = 0f
    private var stuckT = 0f
    private var lastPX = 0f
    private var lastPZ = 0f
    private var deadT = 0f
    const val RESPAWN_TIME = 3f

    // pedaços do personagem quando morre (igual ao Roblox: despedaça)
    class Frag(var px: Float, var py: Float, var pz: Float, val vx0: Float, val vy0: Float, val vz0: Float,
               val hx: Float, val hy: Float, val hz: Float, val part: Int) {
        var vx = vx0; var vy = vy0; var vz = vz0
        var rx = 0f; var ry = 0f; var rz = 0f
        var wx = 0f; var wy = 0f; var wz = 0f
        var rest = false
    }
    val frags = ArrayList<Frag>()

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
            dead = false
            frags.clear()
            tapOn = false
            shiftLock = false
            Cam.shoulder = 0f
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
            dead = false
            frags.clear()
            tapOn = false
            shiftLock = false
            Cam.shoulder = 0f
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
        dead = false
        frags.clear()
        tapOn = false
    }

    // "Reiniciar personagem" do menu: no Roblox o personagem também se despedaça
    fun respawnNow() {
        synchronized(lock) { if (active && !dead) die() }
    }

    fun applyShiftLock(on: Boolean) {
        shiftLock = on
        Cam.shoulder = if (on) 1.75f else 0f
    }

    // anda até um ponto do mundo (toque para mover)
    fun walkTo(wx: Float, wz: Float) {
        tapX = wx; tapZ = wz
        tapOn = true
        stuckT = 0f
    }

    // pontos do corpo (centro relativo, meio-tamanho): torso, cabeça, braço dir, braço esq, perna dir, perna esq
    private val PART_OFF = arrayOf(
        floatArrayOf(0f, 0.5f, 0f), floatArrayOf(0f, 1.9f, 0f),
        floatArrayOf(-1.5f, 0.5f, 0f), floatArrayOf(1.5f, 0.5f, 0f),
        floatArrayOf(-0.5f, -1.5f, 0f), floatArrayOf(0.5f, -1.5f, 0f)
    )
    private val PART_HALF = arrayOf(
        floatArrayOf(1f, 1f, 0.5f), floatArrayOf(0.6f, 0.6f, 0.6f),
        floatArrayOf(0.5f, 1f, 0.5f), floatArrayOf(0.5f, 1f, 0.5f),
        floatArrayOf(0.5f, 1f, 0.5f), floatArrayOf(0.5f, 1f, 0.5f)
    )

    private fun die() {
        if (dead) return
        dead = true
        deadT = 0f
        health = 0f
        tapOn = false
        moveX = 0f; moveY = 0f
        frags.clear()
        val rnd = java.util.Random()
        val fr = Math.toRadians(facing.toDouble())
        val cf = cos(fr).toFloat()
        val sf = sin(fr).toFloat()
        for (i in 0..5) {
            val o = PART_OFF[i]
            val h = PART_HALF[i]
            // posição no mundo (gira junto com o personagem)
            val wx = x + o[0] * cf + o[2] * sf
            val wz = z - o[0] * sf + o[2] * cf
            val f = Frag(wx, y + o[1], wz,
                (wx - x) * 5f + (rnd.nextFloat() - 0.5f) * 14f,
                18f + rnd.nextFloat() * 22f,
                (wz - z) * 5f + (rnd.nextFloat() - 0.5f) * 14f,
                h[0], h[1], h[2], i)
            f.wx = (rnd.nextFloat() - 0.5f) * 520f
            f.wy = (rnd.nextFloat() - 0.5f) * 520f
            f.wz = (rnd.nextFloat() - 0.5f) * 520f
            f.rx = 0f; f.ry = facing
            frags.add(f)
        }
        Lua.died()
    }

    private fun stepFrags(dt: Float) {
        val boxes = solids()
        for (f in frags) {
            if (f.rest) continue
            f.vy -= GRAVITY * dt
            if (f.vy < -300f) f.vy = -300f
            val oldBottom = f.py - f.hy
            f.px += f.vx * dt
            f.py += f.vy * dt
            f.pz += f.vz * dt
            f.rx += f.wx * dt
            f.ry += f.wy * dt
            f.rz += f.wz * dt
            val r = maxOf(f.hx, f.hz)
            for (b in boxes) {
                if (f.px + r <= b.min(0) || f.px - r >= b.max(0)) continue
                if (f.pz + r <= b.min(2) || f.pz - r >= b.max(2)) continue
                val top = b.max(1)
                val bottom = f.py - f.hy
                if (f.vy <= 0f && bottom <= top && oldBottom >= top - 1.5f) {
                    f.py = top + f.hy
                    f.vy = -f.vy * 0.25f
                    f.vx *= 0.7f
                    f.vz *= 0.7f
                    f.wx *= 0.6f; f.wy *= 0.6f; f.wz *= 0.6f
                    if (Math.abs(f.vy) < 4f) {
                        f.vy = 0f; f.vx = 0f; f.vz = 0f
                        f.wx = 0f; f.wy = 0f; f.wz = 0f
                        f.rest = true
                    }
                    break
                }
            }
            if (f.py < -500f) f.rest = true
        }
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
            if (dead) {
                stepFrags(dt)
                deadT += dt
                Cam.target[0] = x
                Cam.target[1] = y + 1.5f
                Cam.target[2] = z
                if (deadT >= RESPAWN_TIME) respawn()
            } else {
                stepPlayer(dt)
                touches()
            }
            for (p in Scene.parts) if (p.y < -500f) Scene.destroy(p)
            if (!dead && health <= 0f) die()
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
        // toque para mover: vai até o ponto tocado
        if (tapOn) {
            if (hypot(moveX, moveY) > 0.1f) {
                tapOn = false
            } else {
                val dx = tapX - x
                val dz = tapZ - z
                val dl = hypot(dx, dz)
                if (dl < 1.2f) {
                    tapOn = false
                } else {
                    mx = dx / dl
                    mz = dz / dl
                    val moved = hypot(x - lastPX, z - lastPZ)
                    if (moved < walkSpeed * dt * 0.25f) stuckT += dt else stuckT = 0f
                    if (stuckT > 0.25f && grounded) { vy = jumpPower; stuckT = 0f }
                }
            }
        }
        lastPX = x; lastPZ = z
        val len = hypot(mx, mz)
        if (len > 1f) { mx /= len; mz /= len }
        val power = if (len > 1f) 1f else len
        moving = power > 0.1f
        if (shiftLock) {
            // Shift Lock: o personagem sempre olha para onde a câmera aponta
            val target = Math.toDegrees(atan2(fx, fz).toDouble()).toFloat()
            var d = target - facing
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            facing += d * 0.5f
            if (moving) walkT += dt * power
        } else if (moving) {
            val target = Math.toDegrees(atan2(mx, mz).toDouble()).toFloat()
            var d = target - facing
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            facing += d * 0.35f
            walkT += dt * power
            // câmera "Seguir": gira devagar para ficar atrás do personagem
            if (Settings.camMode == Settings.CAM_FOLLOW && !lookHeld) {
                val want = Math.toRadians(facing.toDouble()).toFloat() + Math.PI.toFloat()
                var dy = want - Cam.yaw
                while (dy > Math.PI) dy -= (2 * Math.PI).toFloat()
                while (dy < -Math.PI) dy += (2 * Math.PI).toFloat()
                Cam.yaw += dy * minOf(1f, dt * 2.2f * power)
            }
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