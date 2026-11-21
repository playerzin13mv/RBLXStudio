package com.rblxstudio

import android.opengl.Matrix
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

fun v3(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
fun dot3(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
fun sub3(a: FloatArray, b: FloatArray) = v3(a[0] - b[0], a[1] - b[1], a[2] - b[2])
fun add3(a: FloatArray, b: FloatArray) = v3(a[0] + b[0], a[1] + b[1], a[2] + b[2])
fun mul3(a: FloatArray, s: Float) = v3(a[0] * s, a[1] * s, a[2] * s)
fun len3(a: FloatArray) = sqrt(dot3(a, a))
fun norm3(a: FloatArray) = mul3(a, 1f / len3(a))

// "a,b,c,d" -> 4 números
fun parseNums(s: String): FloatArray {
    val out = FloatArray(4)
    val t = s.split(",")
    for (i in 0 until minOf(4, t.size)) out[i] = t[i].trim().toFloatOrNull() ?: 0f
    return out
}

// qualquer objeto da árvore (Script, Sound, Frame, Folder...)
open class Inst(var className: String, var name: String) {
    var parent: Inst? = null
    val children = CopyOnWriteArrayList<Inst>()
    val props = ConcurrentHashMap<String, String>()

    fun add(c: Inst) {
        c.parent?.remove(c)
        c.parent = this
        children.add(c)
    }

    fun remove(c: Inst) {
        children.remove(c)
        if (c.parent === this) c.parent = null
    }

    fun prop(k: String, d: String = ""): String = props[k] ?: d

    open fun cloneTree(): Inst {
        val c = Inst(className, name)
        c.props.putAll(props)
        for (ch in children) c.add(ch.cloneTree())
        return c
    }
}

// partes do personagem (Humanoid, HumanoidRootPart, Head) - valores vêm do Play
class CharInst(cls: String, nm: String) : Inst(cls, nm)

class PartData(
    name: String,
    var x: Float, var y: Float, var z: Float,
    var sx: Float, var sy: Float, var sz: Float,
    var r: Float, var g: Float, var b: Float,
    var anchored: Boolean = false
) : Inst("Part", name) {
    var grid = false
    var canCollide = true
    var friction = 0.3f
    var elasticity = 0.5f
    val vel = FloatArray(3)      // velocidade linear (só no Play)
    val ang = FloatArray(3)      // velocidade angular (só no Play)
    var asleep = false
    var sleepT = 0f
    val rot = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    fun copyPart(): PartData {
        val p = PartData(name, x, y, z, sx, sy, sz, r, g, b, anchored)
        p.className = className
        p.grid = grid
        p.canCollide = canCollide
        p.friction = friction
        p.elasticity = elasticity
        System.arraycopy(rot, 0, p.rot, 0, 16)
        p.props.putAll(props)
        for (c in children) p.add(c.cloneTree())
        return p
    }

    override fun cloneTree(): Inst = copyPart()

    // meia-extensão da caixa no eixo k do mundo (considera a rotação)
    fun halfExtent(k: Int): Float =
        abs(rot[k]) * sx / 2 + abs(rot[4 + k]) * sy / 2 + abs(rot[8 + k]) * sz / 2

    fun size(i: Int) = when (i) {
        0 -> sx
        1 -> sy
        else -> sz
    }

    fun setSize(i: Int, v: Float) {
        when (i) {
            0 -> sx = v
            1 -> sy = v
            else -> sz = v
        }
    }

    // matriz modelo = T * R * S(size/2)
    fun model(out: FloatArray, mul: Float = 1f) {
        val t = FloatArray(16)
        Matrix.setIdentityM(t, 0)
        Matrix.translateM(t, 0, x, y, z)
        Matrix.multiplyMM(out, 0, t, 0, rot, 0)
        Matrix.scaleM(out, 0, sx / 2 * mul, sy / 2 * mul, sz / 2 * mul)
    }

    // orientação em graus (ordem YXZ, igual ao Roblox)
    fun orientation(): FloatArray {
        fun e(r: Int, c: Int) = rot[c * 4 + r]
        val rx = asin((-e(1, 2)).coerceIn(-1f, 1f))
        val ry = atan2(e(0, 2), e(2, 2))
        val rz = atan2(e(1, 0), e(1, 1))
        val k = (180.0 / Math.PI).toFloat()
        return floatArrayOf(rx * k, ry * k, rz * k)
    }

    fun setOrientation(rx: Float, ry: Float, rz: Float) {
        Matrix.setIdentityM(rot, 0)
        Matrix.rotateM(rot, 0, ry, 0f, 1f, 0f)
        Matrix.rotateM(rot, 0, rx, 1f, 0f, 0f)
        Matrix.rotateM(rot, 0, rz, 0f, 0f, 1f)
    }
}

// classes que dá para inserir e as propriedades editáveis de cada uma
object Defaults {
    val classes = listOf("Script", "LocalScript", "Sound", "ScreenGui", "Frame", "TextLabel", "TextButton", "ImageLabel", "Folder")
    private val guiClasses = setOf("ScreenGui", "Frame", "TextLabel", "TextButton", "ImageLabel")
    val boolKeys = setOf("Visible", "Enabled", "Looped", "Playing")
    private val base = listOf("Position", "Size", "AnchorPoint", "BackgroundColor3", "BackgroundTransparency", "Visible")

    fun isGui(c: String) = c in guiClasses

    fun keys(c: String): List<String> = when (c) {
        "Script", "LocalScript" -> listOf("Source")
        "Sound" -> listOf("SoundId", "Volume", "Looped", "Playing")
        "ScreenGui" -> listOf("Enabled")
        "Frame" -> base
        "TextLabel", "TextButton" -> base + listOf("Text", "TextColor3", "TextSize")
        "ImageLabel" -> base + listOf("Image")
        "MeshPart" -> listOf("MeshId", "TextureID")
        else -> emptyList()
    }

    fun make(cls: String): Inst {
        val i = Inst(cls, cls)
        when (cls) {
            "Script", "LocalScript" -> i.props["Source"] = "print(\"Hello world!\")"
            "Sound" -> {
                i.props["SoundId"] = ""
                i.props["Volume"] = "1"
                i.props["Looped"] = "false"
                i.props["Playing"] = "false"
            }
            "ScreenGui" -> i.props["Enabled"] = "true"
        }
        if (cls == "Frame" || cls == "TextLabel" || cls == "TextButton" || cls == "ImageLabel") {
            i.props["Position"] = "0,20,0,20"
            i.props["Size"] = "0,200,0,100"
            i.props["AnchorPoint"] = "0,0"
            i.props["BackgroundColor3"] = if (cls == "TextButton") "163,162,165" else "255,255,255"
            i.props["BackgroundTransparency"] = "0"
            i.props["Visible"] = "true"
        }
        if (cls == "TextLabel" || cls == "TextButton") {
            i.props["Text"] = cls
            i.props["TextColor3"] = "0,0,0"
            i.props["TextSize"] = "18"
        }
        if (cls == "ImageLabel") i.props["Image"] = ""
        return i
    }
}

object Scene {
    val parts = CopyOnWriteArrayList<PartData>()
    @Volatile var selected: PartData? = null
    @Volatile var selObj: Inst? = null
    var clipboard: PartData? = null

    val game = Inst("DataModel", "Game")
    val workspace = Inst("Workspace", "Workspace")
    val services = LinkedHashMap<String, Inst>()
    private val hidden = HashMap<String, Inst>()

    // criados só durante o Play
    @Volatile var localPlayer: Inst? = null
    @Volatile var character: Inst? = null
    @Volatile var playerGui: Inst? = null

    val players: Inst get() = services["Players"]!!

    init {
        val names = listOf(
            "Workspace", "Players", "Lighting", "MaterialService", "ReplicatedFirst", "ReplicatedStorage",
            "ServerScriptService", "ServerStorage", "StarterGui", "StarterPack", "StarterPlayer", "Teams", "SoundService"
        )
        for (n in names) {
            val s = if (n == "Workspace") workspace else Inst(n, n)
            services[n] = s
            game.add(s)
        }
        reset()
    }

    fun service(name: String): Inst =
        services[name] ?: hidden.getOrPut(name) { Inst(name, name).also { game.add(it) } }

    fun clearAll() {
        parts.clear()
        for (s in services.values) s.children.clear()
        selected = null
        selObj = null
    }

    fun reset() {
        clearAll()
        val bp = PartData("Baseplate", 0f, -10f, 0f, 512f, 20f, 512f, 0.39f, 0.37f, 0.38f, true)
        bp.grid = true
        parts.add(bp)
        parts.add(PartData("SpawnLocation", 0f, 0.5f, 0f, 6f, 1f, 6f, 0.64f, 0.635f, 0.647f, true))
    }

    // filhos de um objeto (o Workspace mostra as partes + os outros objetos dele)
    fun kids(i: Inst): List<Inst> {
        if (i !== workspace) return i.children
        val l = ArrayList<Inst>()
        l.addAll(parts)
        l.addAll(i.children)
        return l
    }

    fun parentOf(i: Inst): Inst? =
        if (i is PartData && i.parent == null && parts.contains(i)) workspace else i.parent

    fun setParent(i: Inst, np: Inst?) {
        if (i is PartData) {
            if (np === workspace) {
                i.parent?.remove(i)
                i.parent = null
                if (!parts.contains(i)) parts.add(i)
                return
            }
            parts.remove(i)
            if (selected === i) selected = null
            i.parent?.remove(i)
            i.parent = null
            if (np != null) np.add(i) // fora do Workspace fica guardada, sem aparecer nem cair
            return
        }
        i.parent?.remove(i)
        i.parent = null
        if (np != null) np.add(i)
    }

    fun destroy(i: Inst) {
        if (i is PartData) {
            parts.remove(i)
            if (selected === i) selected = null
            Physics.wakeAll()
        }
        if (selObj === i) selObj = null
        i.parent?.remove(i)
        i.parent = null
    }

    fun snapshot(): Map<String, List<Inst>> =
        services.mapValues { e -> e.value.children.map { it.cloneTree() } }

    fun restore(m: Map<String, List<Inst>>) {
        for ((n, s) in services) {
            s.children.clear()
            for (c in m[n] ?: emptyList()) s.add(c.cloneTree())
        }
    }
}

object Cam {
    @Volatile var yaw = 0.6f
    @Volatile var pitch = 0.5f
    @Volatile var dist = 40f
    val target = floatArrayOf(0f, 3f, 0f)
    @Volatile var w = 1
    @Volatile var h = 1
    const val FOV = 60f

    // frente, direita, cima
    fun basis(): Array<FloatArray> {
        val cp = cos(pitch)
        val sp = sin(pitch)
        val sy = sin(yaw)
        val cy = cos(yaw)
        return arrayOf(v3(-sy * cp, -sp, -cy * cp), v3(cy, 0f, -sy), v3(-sy * sp, cp, -cy * sp))
    }

    fun eye(): FloatArray {
        val f = basis()[0]
        return v3(target[0] - f[0] * dist, target[1] - f[1] * dist, target[2] - f[2] * dist)
    }

    fun ray(px: Float, py: Float): Array<FloatArray> {
        val b = basis()
        val nx = 2f * px / w - 1f
        val ny = 1f - 2f * py / h
        val th = tan(Math.toRadians((FOV / 2f).toDouble())).toFloat()
        val asp = w.toFloat() / h
        val d = norm3(add3(add3(b[0], mul3(b[1], nx * th * asp)), mul3(b[2], ny * th)))
        return arrayOf(eye(), d)
    }

    fun pan(dx: Float, dy: Float) {
        val b = basis()
        val k = 2f * dist * tan(Math.toRadians((FOV / 2f).toDouble())).toFloat() / h
        for (i in 0..2) target[i] += -b[1][i] * dx * k + b[2][i] * dy * k
    }
}

// raio contra caixa orientada; devolve t ou -1
fun rayObb(o: FloatArray, d: FloatArray, c: FloatArray, ori: FloatArray?, half: FloatArray): Float {
    val w0 = sub3(o, c)
    val lo = FloatArray(3)
    val ld = FloatArray(3)
    for (i in 0..2) {
        if (ori == null) {
            lo[i] = w0[i]
            ld[i] = d[i]
        } else {
            lo[i] = ori[i * 4] * w0[0] + ori[i * 4 + 1] * w0[1] + ori[i * 4 + 2] * w0[2]
            ld[i] = ori[i * 4] * d[0] + ori[i * 4 + 1] * d[1] + ori[i * 4 + 2] * d[2]
        }
    }
    var tmin = 0f
    var tmax = Float.MAX_VALUE
    for (i in 0..2) {
        if (abs(ld[i]) < 1e-6f) {
            if (abs(lo[i]) > half[i]) return -1f
        } else {
            var t1 = (-half[i] - lo[i]) / ld[i]
            var t2 = (half[i] - lo[i]) / ld[i]
            if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
            tmin = max(tmin, t1)
            tmax = min(tmax, t2)
            if (tmin > tmax) return -1f
        }
    }
    return tmin
}