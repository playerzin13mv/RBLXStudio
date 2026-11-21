package com.rblxstudio

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

// saída dos scripts (print / warn / erros)
object Out {
    private val lines = ArrayList<String>()
    @Volatile var listener: ((Int) -> Unit)? = null

    fun add(s: String, kind: Int = 0) {
        val pre = when (kind) {
            1 -> "[aviso] "
            2 -> "[erro] "
            else -> ""
        }
        synchronized(lines) {
            lines.add(pre + s)
            if (lines.size > 200) lines.removeAt(0)
        }
        listener?.invoke(kind)
    }

    fun text(max: Int): String = synchronized(lines) { lines.takeLast(max).joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
        listener?.invoke(0)
    }
}

// Lua (LuaJ) com um pedaço da API do Roblox
object Lua {
    private var g: Globals? = null
    @Volatile private var live = false
    private var clock = 0f
    private var pendingJoin = false
    private val cache = HashMap<Inst, LuaValue>()
    private val conns = ConcurrentHashMap<String, CopyOnWriteArrayList<LuaValue>>()
    private val queue = ConcurrentLinkedQueue<() -> Unit>()
    private val meta = LuaTable()
    private val checker by lazy { JsePlatform.standardGlobals() }
    val touchSet: MutableSet<Inst> = ConcurrentHashMap.newKeySet<Inst>()

    private val signals = setOf(
        "Touched", "MouseButton1Click", "Activated", "Heartbeat", "RenderStepped", "Stepped",
        "PlayerAdded", "Chatted", "Died", "CharacterAdded"
    )
    private val strKeys = setOf("Text", "Image", "SoundId", "MeshId", "TextureID", "Source", "PlaceholderText")

    private const val PRELUDE = """
local unpack = table.unpack or unpack
Vector3 = {}
Vector3.__index = function(t, k)
  if k == "Magnitude" then return math.sqrt(t.X * t.X + t.Y * t.Y + t.Z * t.Z) end
  if k == "Unit" then
    local m = math.sqrt(t.X * t.X + t.Y * t.Y + t.Z * t.Z)
    if m == 0 then return Vector3.new(0, 0, 0) end
    return Vector3.new(t.X / m, t.Y / m, t.Z / m)
  end
  return rawget(Vector3, k)
end
function Vector3.new(x, y, z) return setmetatable({X = x or 0, Y = y or 0, Z = z or 0}, Vector3) end
Vector3.__add = function(a, b) return Vector3.new(a.X + b.X, a.Y + b.Y, a.Z + b.Z) end
Vector3.__sub = function(a, b) return Vector3.new(a.X - b.X, a.Y - b.Y, a.Z - b.Z) end
Vector3.__mul = function(a, b)
  if type(a) == "number" then return Vector3.new(a * b.X, a * b.Y, a * b.Z) end
  if type(b) == "number" then return Vector3.new(a.X * b, a.Y * b, a.Z * b) end
  return Vector3.new(a.X * b.X, a.Y * b.Y, a.Z * b.Z)
end
Vector3.__div = function(a, b)
  if type(b) == "number" then return Vector3.new(a.X / b, a.Y / b, a.Z / b) end
  return Vector3.new(a.X / b.X, a.Y / b.Y, a.Z / b.Z)
end
Vector3.__unm = function(a) return Vector3.new(-a.X, -a.Y, -a.Z) end
Vector3.__eq = function(a, b) return a.X == b.X and a.Y == b.Y and a.Z == b.Z end
Vector3.__tostring = function(a) return a.X .. ", " .. a.Y .. ", " .. a.Z end
Vector3.zero = Vector3.new(0, 0, 0)
Vector3.one = Vector3.new(1, 1, 1)
function Vector3.Dot(a, b) return a.X * b.X + a.Y * b.Y + a.Z * b.Z end
function Vector3.Cross(a, b)
  return Vector3.new(a.Y * b.Z - a.Z * b.Y, a.Z * b.X - a.X * b.Z, a.X * b.Y - a.Y * b.X)
end
function Vector3.Lerp(a, b, t) return a + (b - a) * t end

Vector2 = {}
Vector2.__index = Vector2
function Vector2.new(x, y) return setmetatable({X = x or 0, Y = y or 0}, Vector2) end

Color3 = {}
Color3.__index = Color3
function Color3.new(r, g, b) return setmetatable({R = r or 0, G = g or 0, B = b or 0}, Color3) end
function Color3.fromRGB(r, g, b) return Color3.new((r or 0) / 255, (g or 0) / 255, (b or 0) / 255) end
Color3.__tostring = function(c) return c.R .. ", " .. c.G .. ", " .. c.B end

UDim2 = {}
UDim2.__index = UDim2
function UDim2.new(xs, xo, ys, yo)
  return setmetatable({X = {Scale = xs or 0, Offset = xo or 0}, Y = {Scale = ys or 0, Offset = yo or 0}}, UDim2)
end
function UDim2.fromScale(xs, ys) return UDim2.new(xs, 0, ys, 0) end
function UDim2.fromOffset(xo, yo) return UDim2.new(0, xo, 0, yo) end

local threads = {}
local now = 0
function __spawn(f, ...)
  local args = {...}
  local n = select('#', ...)
  local co = coroutine.create(function() return f(unpack(args, 1, n)) end)
  threads[#threads + 1] = {co = co, t = 0}
end
function __step(dt)
  now = now + dt
  local i = 1
  while i <= #threads do
    local th = threads[i]
    if th.t <= now then
      local ok, res = coroutine.resume(th.co)
      if not ok then
        __err(tostring(res))
        table.remove(threads, i)
      elseif coroutine.status(th.co) == "dead" then
        table.remove(threads, i)
      else
        th.t = now + (tonumber(res) or 0.03)
        i = i + 1
      end
    else
      i = i + 1
    end
  end
end
function wait(t)
  t = t or 0.03
  coroutine.yield(t)
  return t, now
end
task = {
  wait = wait,
  spawn = function(f, ...) __spawn(f, ...) end,
  delay = function(t, f, ...)
    local a = {...}
    local n = select('#', ...)
    __spawn(function() wait(t) f(unpack(a, 1, n)) end)
  end
}
spawn = function(f) __spawn(f) end
delay = function(t, f) task.delay(t, f) end
function time() return __time() end
function tick() return __time() end
os = {time = function() return __time() end, clock = function() return __time() end}
function typeof(v)
  local m = getmetatable(v)
  if m == Vector3 then return "Vector3" end
  if m == Color3 then return "Color3" end
  if m == UDim2 then return "UDim2" end
  if m == Vector2 then return "Vector2" end
  if type(v) == "userdata" then return "Instance" end
  return type(v)
end
"""

    init {
        meta.set("__index", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                val i = asInst(a) ?: return LuaValue.NIL
                return get(i, b.tojstring())
            }
        })
        meta.set("__newindex", object : ThreeArgFunction() {
            override fun call(a: LuaValue, b: LuaValue, c: LuaValue): LuaValue {
                val i = asInst(a)
                if (i != null) set(i, b.tojstring(), c)
                return LuaValue.NONE
            }
        })
        meta.set("__tostring", object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue = sv(asInst(a)?.name ?: "nil")
        })
    }

    // ---------- helpers ----------
    private fun nv(v: Float): LuaValue = LuaValue.valueOf(v.toDouble())
    private fun sv(s: String): LuaValue = LuaValue.valueOf(s)
    private fun bv(b: Boolean): LuaValue = LuaValue.valueOf(b)

    private fun fn(body: (Varargs) -> Varargs): LuaValue = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs = body(args)
    }

    private fun asInst(v: LuaValue): Inst? = if (v.isuserdata()) (v.touserdata() as? Inst) else null

    fun wrap(i: Inst?): LuaValue {
        if (i == null) return LuaValue.NIL
        return cache.getOrPut(i) { LuaValue.userdataOf(i, meta) }
    }

    private fun vec3(x: Float, y: Float, z: Float): LuaValue =
        g!!.get("Vector3").get("new").call(nv(x), nv(y), nv(z))

    private fun color3(r: Float, gg: Float, b: Float): LuaValue =
        g!!.get("Color3").get("new").call(nv(r), nv(gg), nv(b))

    private fun f3(v: LuaValue): FloatArray {
        if (!v.istable()) return FloatArray(3)
        return floatArrayOf(v.get("X").tofloat(), v.get("Y").tofloat(), v.get("Z").tofloat())
    }

    private fun key(i: Inst, k: String) = System.identityHashCode(i).toString() + ":" + k

    private fun find(i: Inst, nm: String, rec: Boolean): Inst? {
        for (c in Scene.kids(i)) if (c.name == nm) return c
        if (rec) for (c in Scene.kids(i)) {
            val r = find(c, nm, true)
            if (r != null) return r
        }
        return null
    }

    private fun desc(i: Inst, out: MutableList<Inst>) {
        for (c in Scene.kids(i)) {
            out.add(c)
            desc(c, out)
        }
    }

    private fun list(l: List<Inst>): LuaValue {
        val t = LuaTable()
        for ((n, c) in l.withIndex()) t.set(n + 1, wrap(c))
        return t
    }

    // ---------- eventos ----------
    private fun signal(i: Inst, k: String): LuaValue {
        val t = LuaTable()
        val c = fn { a ->
            val f = a.arg(2)
            conns.getOrPut(key(i, k)) { CopyOnWriteArrayList() }.add(f)
            if (k == "Touched") touchSet.add(i)
            val con = LuaTable()
            con.set("Disconnect", fn { conns[key(i, k)]?.remove(f); LuaValue.NONE })
            con.set("Connected", LuaValue.TRUE)
            con
        }
        t.set("Connect", c)
        t.set("connect", c)
        t.set("Once", c)
        return t
    }

    fun fire(i: Inst, name: String, vararg args: LuaValue) {
        val l = conns[key(i, name)] ?: return
        val gl = g ?: return
        val direct = name == "Heartbeat" || name == "RenderStepped" || name == "Stepped"
        for (f in l) {
            try {
                if (direct) {
                    f.invoke(LuaValue.varargsOf(args as Array<LuaValue>))
                } else {
                    val all = arrayOf<LuaValue>(f, *args)
                    gl.get("__spawn").invoke(LuaValue.varargsOf(all))
                }
            } catch (e: LuaError) {
                Out.add(e.message ?: "erro", 2)
            }
        }
    }

    fun touched(part: Inst, other: Inst?) {
        if (live) fire(part, "Touched", wrap(other))
    }

    fun chatted(msg: String) {
        val p = Scene.localPlayer ?: return
        fire(p, "Chatted", sv(msg))
    }

    fun died() {
        val h = Scene.character?.children?.firstOrNull { it.className == "Humanoid" } ?: return
        fire(h, "Died")
    }

    fun post(r: () -> Unit) {
        queue.add(r)
    }

    // ---------- leitura de propriedades ----------
    private fun decode(k: String, s: String): LuaValue {
        if (k in strKeys) return sv(s)
        if (k.endsWith("Color3")) {
            val n = parseNums(s)
            return color3(n[0] / 255f, n[1] / 255f, n[2] / 255f)
        }
        if (k == "Position" || k == "Size") {
            val n = parseNums(s)
            return g!!.get("UDim2").get("new").call(nv(n[0]), nv(n[1]), nv(n[2]), nv(n[3]))
        }
        if (k == "AnchorPoint") {
            val n = parseNums(s)
            return g!!.get("Vector2").get("new").call(nv(n[0]), nv(n[1]))
        }
        if (s == "true") return LuaValue.TRUE
        if (s == "false") return LuaValue.FALSE
        val d = s.toDoubleOrNull()
        if (d != null) return LuaValue.valueOf(d)
        return sv(s)
    }

    private fun get(i: Inst, k: String): LuaValue {
        when (k) {
            "Name" -> return sv(i.name)
            "ClassName" -> return sv(i.className)
            "Parent" -> return wrap(Scene.parentOf(i))
            "GetChildren" -> return fn { list(Scene.kids(i)) }
            "GetDescendants" -> return fn {
                val o = ArrayList<Inst>()
                desc(i, o)
                list(o)
            }
            "FindFirstChild", "WaitForChild" -> return fn { a -> wrap(find(i, a.arg(2).tojstring(), a.arg(3).toboolean())) }
            "FindFirstChildOfClass" -> return fn { a ->
                val c = a.arg(2).tojstring()
                wrap(Scene.kids(i).firstOrNull { it.className == c })
            }
            "IsA" -> return fn { a ->
                val c = a.arg(2).tojstring()
                bv(c == i.className || c == "Instance" || (c == "BasePart" && i is PartData))
            }
            "Destroy", "Remove" -> return fn { Scene.destroy(i); LuaValue.NONE }
            "Clone" -> return fn { wrap(if (i is PartData) i.copyPart() else i.cloneTree()) }
            "GetService" -> return fn { a -> wrap(Scene.service(a.arg(2).tojstring())) }
            "GetPlayers" -> return fn { list(Scene.players.children.filter { it.className == "Player" }) }
            "Play" -> return fn { Audio.play(i); LuaValue.NONE }
            "Stop", "Pause" -> return fn { Audio.stop(i); LuaValue.NONE }
            "TakeDamage" -> return fn { a -> Play.health -= a.arg(2).tofloat(); LuaValue.NONE }
        }
        if (k in signals) return signal(i, k)

        if (i is PartData) {
            when (k) {
                "Position" -> return vec3(i.x, i.y, i.z)
                "Size" -> return vec3(i.sx, i.sy, i.sz)
                "Color" -> return color3(i.r, i.g, i.b)
                "Anchored" -> return bv(i.anchored)
                "CanCollide" -> return bv(i.canCollide)
                "Orientation", "Rotation" -> {
                    val o = i.orientation()
                    return vec3(o[0], o[1], o[2])
                }
                "Velocity", "AssemblyLinearVelocity" -> return vec3(i.vel[0], i.vel[1], i.vel[2])
            }
        }
        if (i is CharInst && k == "Position") {
            return vec3(Play.x, Play.y + (if (i.name == "Head") 2f else 0f), Play.z)
        }
        if (i.className == "Humanoid") {
            when (k) {
                "Health" -> return nv(Play.health)
                "MaxHealth" -> return nv(100f)
                "WalkSpeed" -> return nv(Play.walkSpeed)
                "JumpPower" -> return nv(Play.jumpPower)
            }
        }
        if (i === Scene.players && k == "LocalPlayer") return wrap(Scene.localPlayer)
        if (i === Scene.localPlayer) {
            when (k) {
                "UserId" -> return LuaValue.valueOf(Play.userId.toDouble())
                "Character" -> return wrap(Scene.character)
                "DisplayName" -> return sv(i.name)
            }
        }
        if (i === Scene.workspace && k == "Gravity") return nv(196.2f)

        val p = i.props[k]
        if (p != null) return decode(k, p)
        for (c in Scene.kids(i)) if (c.name == k) return wrap(c)
        return LuaValue.NIL
    }

    // ---------- escrita de propriedades ----------
    private fun encode(k: String, v: LuaValue): String {
        if (v.istable()) {
            if (!v.get("R").isnil()) {
                val r = Math.round(v.get("R").tofloat() * 255f)
                val gg = Math.round(v.get("G").tofloat() * 255f)
                val b = Math.round(v.get("B").tofloat() * 255f)
                return "$r,$gg,$b"
            }
            val x = v.get("X")
            if (x.istable()) {
                val y = v.get("Y")
                return x.get("Scale").tojstring() + "," + x.get("Offset").tojstring() + "," +
                    y.get("Scale").tojstring() + "," + y.get("Offset").tojstring()
            }
            if (!x.isnil()) return x.tojstring() + "," + v.get("Y").tojstring()
        }
        return v.tojstring()
    }

    private fun set(i: Inst, k: String, v: LuaValue) {
        if (k == "Name") { i.name = v.tojstring(); return }
        if (k == "Parent") { Scene.setParent(i, asInst(v)); return }
        if (i is PartData) {
            when (k) {
                "Position" -> {
                    val a = f3(v)
                    i.x = a[0]; i.y = a[1]; i.z = a[2]
                    i.asleep = false
                    return
                }
                "Size" -> {
                    val a = f3(v)
                    i.sx = a[0]; i.sy = a[1]; i.sz = a[2]
                    i.asleep = false
                    return
                }
                "Color" -> {
                    val c = if (v.istable()) floatArrayOf(v.get("R").tofloat(), v.get("G").tofloat(), v.get("B").tofloat()) else FloatArray(3)
                    i.r = c[0]; i.g = c[1]; i.b = c[2]
                    return
                }
                "Anchored" -> {
                    i.anchored = v.toboolean()
                    i.asleep = false
                    for (n in 0..2) { i.vel[n] = 0f; i.ang[n] = 0f }
                    return
                }
                "CanCollide" -> { i.canCollide = v.toboolean(); Physics.wakeAll(); return }
                "Orientation", "Rotation" -> {
                    val a = f3(v)
                    i.setOrientation(a[0], a[1], a[2])
                    i.asleep = false
                    return
                }
                "Velocity", "AssemblyLinearVelocity" -> {
                    val a = f3(v)
                    i.vel[0] = a[0]; i.vel[1] = a[1]; i.vel[2] = a[2]
                    i.asleep = false
                    return
                }
            }
        }
        if (i.className == "Humanoid") {
            when (k) {
                "Health" -> { Play.health = v.tofloat(); return }
                "WalkSpeed" -> { Play.walkSpeed = v.tofloat(); return }
                "JumpPower" -> { Play.jumpPower = v.tofloat(); return }
            }
        }
        if (v.isnil()) {
            i.props.remove(k)
            return
        }
        i.props[k] = encode(k, v)
        if (i.className == "Sound" && k == "Playing") {
            if (v.toboolean()) Audio.play(i) else Audio.stop(i)
        }
    }

    // ---------- execução ----------
    private fun printFn(kind: Int): LuaValue = fn { a ->
        val ts = g!!.get("tostring")
        val sb = StringBuilder()
        for (n in 1..a.narg()) {
            if (n > 1) sb.append('\t')
            sb.append(ts.call(a.arg(n)).tojstring())
        }
        Out.add(sb.toString(), kind)
        LuaValue.NONE
    }

    private fun newInst(cls: String, parent: Inst?): LuaValue {
        val i: Inst = when (cls) {
            "Part" -> PartData("Part", 0f, 0.5f, 0f, 4f, 1f, 2f, 0.64f, 0.635f, 0.647f, false)
            "MeshPart" -> PartData("MeshPart", 0f, 2f, 0f, 4f, 4f, 4f, 0.64f, 0.635f, 0.647f, false).also { it.className = "MeshPart" }
            else -> Defaults.make(cls)
        }
        if (parent != null) Scene.setParent(i, parent)
        return wrap(i)
    }

    private fun build(): Globals {
        val gl = JsePlatform.standardGlobals()
        g = gl
        for (n in listOf("io", "os", "luajava", "dofile", "loadfile")) gl.set(n, LuaValue.NIL)
        gl.set("print", printFn(0))
        gl.set("warn", printFn(1))
        gl.set("__err", fn { a -> Out.add(a.arg(1).tojstring(), 2); LuaValue.NONE })
        gl.set("__time", fn { nv(clock) })
        gl.set("game", wrap(Scene.game))
        gl.set("workspace", wrap(Scene.workspace))
        val instT = LuaTable()
        instT.set("new", fn { a -> newInst(a.arg(1).tojstring(), asInst(a.arg(2))) })
        gl.set("Instance", instT)
        gl.load(PRELUDE, "=prelude").call()
        return gl
    }

    private fun scriptsIn(i: Inst, out: MutableList<Inst>) {
        for (c in Scene.kids(i)) {
            if ((c.className == "Script" || c.className == "LocalScript") && c.prop("Disabled") != "true") out.add(c)
            scriptsIn(c, out)
        }
    }

    private fun runScript(gl: Globals, s: Inst) {
        try {
            // "local script = ..." fica na mesma linha, então as linhas dos erros batem
            val f = gl.load("local script = ...; " + s.prop("Source"), "=" + s.name)
            gl.get("__spawn").call(f, wrap(s))
        } catch (e: LuaError) {
            Out.add(e.message ?: "erro", 2)
        }
    }

    fun start() {
        stop()
        clock = 0f
        live = true
        val gl = build()
        val all = ArrayList<Inst>()
        scriptsIn(Scene.workspace, all)
        scriptsIn(Scene.service("ServerScriptService"), all)
        scriptsIn(Scene.service("StarterPlayer"), all)
        Scene.playerGui?.let { scriptsIn(it, all) }
        for (s in all) runScript(gl, s)
        pendingJoin = true
        Out.add("Play iniciado (" + all.size + " script(s))")
    }

    fun stop() {
        live = false
        g = null
        cache.clear()
        conns.clear()
        touchSet.clear()
        queue.clear()
        pendingJoin = false
    }

    fun step(dt: Float) {
        val gl = g ?: return
        if (!live) return
        clock += dt
        while (true) {
            val q = queue.poll() ?: break
            try { q() } catch (e: Throwable) { Out.add(e.message ?: "erro", 2) }
        }
        val rs = Scene.service("RunService")
        fire(rs, "Heartbeat", nv(dt))
        fire(rs, "RenderStepped", nv(dt))
        fire(rs, "Stepped", nv(clock), nv(dt))
        try {
            gl.get("__step").call(nv(dt))
        } catch (e: LuaError) {
            Out.add(e.message ?: "erro", 2)
        }
        if (pendingJoin) {
            pendingJoin = false
            val p = Scene.localPlayer
            if (p != null) {
                fire(Scene.players, "PlayerAdded", wrap(p))
                fire(p, "CharacterAdded", wrap(Scene.character))
            }
        }
    }

    // barra "Run a command": no Play roda dentro do jogo; fora do Play roda direto na cena
    fun command(src: String) {
        if (live) {
            post {
                val gl = g
                if (gl != null) {
                    try {
                        gl.get("__spawn").call(gl.load(src, "=command"))
                    } catch (e: LuaError) {
                        Out.add(e.message ?: "erro", 2)
                    }
                }
            }
        } else {
            try {
                val gl = g ?: build()
                gl.load(src, "=command").call()
            } catch (e: LuaError) {
                Out.add(e.message ?: "erro", 2)
            }
        }
    }

    // devolve a mensagem de erro de sintaxe, ou null se estiver ok
    fun syntax(src: String): String? = try {
        checker.load(src, "=script")
        null
    } catch (e: LuaError) {
        e.message
    }
}