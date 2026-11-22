package com.rblxstudio

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import kotlin.math.hypot

internal const val MATCH = -1
internal const val WRAP = -2
internal val C_BG = 0xFF2B2B2B.toInt()
internal val C_BAR = 0xFF1B1B1B.toInt()
internal val C_RIBBON = 0xFF2F2F2F.toInt()
internal val C_PANEL = 0xFF2B2B2B.toInt()
internal val C_HDR = 0xFF3A3A3A.toInt()
internal val C_FIELD = 0xFF1E1E1E.toInt()
internal val C_TEXT = 0xFFE6E6E6.toInt()
internal val C_DIM = 0xFF9A9A9A.toInt()
internal val C_SEL = 0xFF0F6FD8.toInt()
internal val C_GREEN = 0xFF4CAF50.toInt()
internal val C_RED = 0xFFE53935.toInt()

// controles desenhados por cima do viewport durante o Play: joystick / D-Pad, pulo e Shift Lock
class PlayHud(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    var joyActive = false
    var joyCx = 0f
    var joyCy = 0f
    var knobX = 0f
    var knobY = 0f
    var jumpDown = false
    var dpadH = 0
    var dpadV = 0
    var mode = Settings.MOVE_JOYSTICK
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FFFFFF }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt() }
    private val pressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt() }
    private val lockOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC0F6FD8.toInt() }
    private val icon = ctx.getDrawable(R.drawable.ic_jump)
    private val lockIcon = ctx.getDrawable(R.drawable.ic_lock)

    fun jumpX() = width - 80f * d
    fun jumpY() = height - 80f * d
    fun shiftX() = width - 80f * d
    fun shiftY() = height - 170f * d
    fun dpadX() = 110f * d
    fun dpadY() = height - 100f * d

    private fun glyph(c: Canvas, dr: android.graphics.drawable.Drawable?, x: Float, y: Float) {
        val s = (16f * d).toInt()
        dr?.setBounds(x.toInt() - s, y.toInt() - s, x.toInt() + s, y.toInt() + s)
        dr?.draw(c)
    }

    override fun onDraw(c: Canvas) {
        if (mode == Settings.MOVE_JOYSTICK) {
            val bx = if (joyActive) joyCx else 100f * d
            val by = if (joyActive) joyCy else height - 90f * d
            c.drawCircle(bx, by, 52f * d, base)
            c.drawCircle(bx + knobX, by + knobY, 24f * d, knob)
        } else if (mode == Settings.MOVE_DPAD) {
            val cx = dpadX()
            val cy = dpadY()
            val k = 58f * d
            val r = 30f * d
            c.drawCircle(cx, cy - k, r, if (dpadV > 0) pressed else base)
            c.drawCircle(cx, cy + k, r, if (dpadV < 0) pressed else base)
            c.drawCircle(cx - k, cy, r, if (dpadH < 0) pressed else base)
            c.drawCircle(cx + k, cy, r, if (dpadH > 0) pressed else base)
            c.drawCircle(cx, cy, 14f * d, base)
        }
        val jx = jumpX()
        val jy = jumpY()
        c.drawCircle(jx, jy, 36f * d, if (jumpDown) pressed else base)
        glyph(c, icon, jx, jy)
        if (Settings.shiftLockSwitch) {
            c.drawCircle(shiftX(), shiftY(), 28f * d, if (Play.shiftLock) lockOn else base)
            glyph(c, lockIcon, shiftX(), shiftY())
        }
    }
}

class MainActivity : Activity() {
    private lateinit var gl: GLSurfaceView
    internal lateinit var menu: MainMenu
    private lateinit var guiEditor: GuiEditor
    private var imgTarget: Inst? = null
    private var imgKey = ""
    private val renderer = Renderer()
    internal lateinit var ribbon: LinearLayout
    internal lateinit var explorerList: LinearLayout
    internal lateinit var propsList: LinearLayout
    internal lateinit var explorerPanel: View
    internal lateinit var propsPanel: View
    internal lateinit var outText: TextView
    private lateinit var hud: PlayHud
    private lateinit var gui: GameGui
    private lateinit var gameMenu: GameMenu
    private lateinit var stopFab: ImageView
    private lateinit var scriptEditor: ScriptEditor
    private lateinit var editorPad: EditorPad
    private lateinit var toolBar: ToolBar
    private lateinit var accountIcon: ImageView
    private lateinit var accountLabel: TextView
    internal val toolButtons = HashMap<Tool, View>()
    private val tabViews = LinkedHashMap<String, TextView>()
    internal val refreshers = ArrayList<() -> Unit>()
    internal val open = HashSet<Inst>()
    internal var explorerFilter = ""
    private var lastSel: PartData? = null
    private var currentTab = "HOME"
    private var placeName: String? = null
    private var exportModel = false
    private var exportBinary = true

    // toque no editor
    private var mode = 0
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastSpan = 1f
    private var moved = false

    // toque no Play
    private var joyId = -1
    private var lookId = -1
    private var jumpId = -1
    private var lookLX = 0f
    private var lookLY = 0f
    private var shiftId = -1
    private var pinchId = -1
    private var lastPinch = 0f
    private var lookDownT = 0L
    private var lookTravel = 0f

    // ---------- helpers ----------
    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    internal fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    internal fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    internal fun soon() = toast("Em breve")
    internal fun fmt(v: Float): String =
        String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.').let { if (it == "-0" || it.isEmpty()) "0" else it }

    internal fun tv(text: String, size: Float = 12f, color: Int = C_TEXT, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    internal fun iv(res: Int, sizeDp: Int, tint: Int? = C_TEXT): ImageView {
        val v = ImageView(this)
        v.setImageResource(res)
        if (tint != null) v.setColorFilter(tint)
        v.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return v
    }

    // seleciona qualquer objeto (peça ou não) no Explorer / Properties
    internal fun pick(o: Inst?) {
        val part = o as? PartData
        lastSel = part
        Scene.selObj = o
        Editor.select(part)
    }

    // ---------- ciclo de vida ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Assets.dir = File(filesDir, "images").also { it.mkdirs() }
        Settings.load(this)
        gl = GLSurfaceView(this)
        gl.setEGLContextClientVersion(2)
        gl.setRenderer(renderer)
        gl.setOnTouchListener { _, e -> glTouch(e) }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C_BG)
        root.addView(buildTabBar(), lp(MATCH, dp(34)))

        val scroll = HorizontalScrollView(this)
        scroll.setBackgroundColor(C_RIBBON)
        scroll.isHorizontalScrollBarEnabled = false
        ribbon = LinearLayout(this)
        ribbon.orientation = LinearLayout.HORIZONTAL
        scroll.addView(ribbon)
        root.addView(scroll, lp(MATCH, dp(78)))

        val middle = LinearLayout(this)
        middle.orientation = LinearLayout.HORIZONTAL

        val glFrame = FrameLayout(this)
        glFrame.addView(gl, FrameLayout.LayoutParams(MATCH, MATCH))
        editorPad = EditorPad(this)
        glFrame.addView(editorPad, FrameLayout.LayoutParams(MATCH, MATCH))
        hud = PlayHud(this)
        hud.visibility = View.GONE
        glFrame.addView(hud, FrameLayout.LayoutParams(MATCH, MATCH))

        gui = GameGui(this)
        gui.visibility = View.GONE
        gui.onLeave = { stopPlay() }
        gui.onRespawn = { Play.respawnNow() }
        gui.onMenu = { gameMenu.show() }
        glFrame.addView(gui, FrameLayout.LayoutParams(MATCH, MATCH))

        toolBar = ToolBar(this)
        glFrame.addView(toolBar, FrameLayout.LayoutParams(MATCH, MATCH))
        Play.onTools = { runOnUiThread { toolBar.refresh() } }

        gameMenu = GameMenu(this)
        gameMenu.onLeave = { stopPlay() }
        gameMenu.onReset = { Play.respawnNow() }
        gameMenu.onChanged = { applyControls() }
        glFrame.addView(gameMenu, FrameLayout.LayoutParams(MATCH, MATCH))

        guiEditor = GuiEditor(this)
        glFrame.addView(guiEditor, FrameLayout.LayoutParams(MATCH, MATCH))
        scriptEditor = ScriptEditor(this)
        glFrame.addView(scriptEditor, FrameLayout.LayoutParams(MATCH, MATCH))

        outText = tv("", 10f, Color.WHITE)
        outText.typeface = Typeface.MONOSPACE
        outText.setBackgroundColor(0xAA000000.toInt())
        outText.setPadding(dp(6), dp(4), dp(6), dp(4))
        outText.visibility = View.GONE
        outText.setOnClickListener { outText.visibility = View.GONE }
        val outLp = FrameLayout.LayoutParams(dp(340), WRAP, Gravity.BOTTOM or Gravity.START)
        outLp.setMargins(dp(8), 0, 0, dp(8))
        glFrame.addView(outText, outLp)

        stopFab = ImageView(this)
        stopFab.setImageResource(R.drawable.ic_stop)
        stopFab.setColorFilter(Color.WHITE)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0xCCE53935.toInt())
        stopFab.background = bg
        stopFab.setPadding(dp(8), dp(8), dp(8), dp(8))
        stopFab.visibility = View.GONE
        stopFab.setOnClickListener { stopPlay() }
        val fabLp = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END)
        fabLp.setMargins(0, dp(10), dp(10), 0)
        glFrame.addView(stopFab, fabLp)
        middle.addView(glFrame, lp(0, MATCH, 1f))

        val dock = LinearLayout(this)
        dock.orientation = LinearLayout.VERTICAL
        val ex = makePanel("Explorer", "Filter workspace (name or class)", { q ->
            explorerFilter = q
            buildExplorer()
        }, { insertMenu(Defaults.classes) })
        explorerPanel = ex.first
        explorerList = ex.second
        val pr = makePanel("Properties", "Filter Properties", null, null)
        propsPanel = pr.first
        propsList = pr.second
        dock.addView(explorerPanel, lp(MATCH, 0, 1f))
        dock.addView(propsPanel, lp(MATCH, 0, 1f))
        middle.addView(dock, lp(dp(200), MATCH))
        root.addView(middle, lp(MATCH, 0, 1f))

        // barra de comandos (Lua)
        val cmd = EditText(this)
        cmd.hint = ">  Run a command"
        cmd.textSize = 11f
        cmd.setTextColor(C_TEXT)
        cmd.setHintTextColor(C_DIM)
        cmd.setSingleLine()
        cmd.setBackgroundColor(C_FIELD)
        cmd.setPadding(dp(8), 0, dp(8), 0)
        cmd.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        cmd.setOnEditorActionListener { v, _, _ ->
            val s = v.text.toString().trim()
            if (s.isNotEmpty()) {
                Lua.command(s)
                v.text = ""
                outText.text = Out.text(14)
                outText.visibility = View.VISIBLE
            }
            false
        }
        root.addView(cmd, lp(MATCH, dp(26)))
        val holder = FrameLayout(this)
        holder.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        menu = MainMenu(this)
        holder.addView(menu, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(holder)

        Out.listener = { kind ->
            runOnUiThread {
                outText.text = Out.text(14)
                if (kind == 2) outText.visibility = View.VISIBLE
            }
        }

        Editor.onChanged = { structural ->
            if (structural) {
                if (Scene.selected !== lastSel) {
                    lastSel = Scene.selected
                    Scene.selObj = Scene.selected
                }
                buildExplorer()
                buildProps()
                guiEditor.refresh()
            } else {
                updateProps()
                guiEditor.refresh()
            }
        }
        open.add(Scene.workspace)
        buildRibbon("HOME")
        buildExplorer()
        buildProps()
        refreshAccount()
        if (RobloxAuth.loggedIn(this)) {
            RobloxAuth.loadAvatar(this) { refreshAccount() }
            Avatar.load(this) { toast(it.lines().first()) }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() { super.onResume(); gl.onResume(); refreshAccount() }
    override fun onPause() { super.onPause(); gl.onPause() }

    // volta do navegador depois do login do Roblox (rblxstudio://oauth?code=...)
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val d = i.data ?: return
        if (d.scheme == "rblxstudio" && d.host == "oauth") {
            i.data = null
            toast("Entrando com o Roblox...")
            RobloxAuth.finish(this, d) { ok, msg ->
                toast(msg)
                refreshAccount()
                if (ok) {
                    RobloxAuth.loadAvatar(this) { refreshAccount() }
                    Avatar.load(this) { avatarReport(it) }
                }
            }
        }
    }

    // ---------- Play / Stop ----------
    // here = true: "Play Here" (o personagem nasce onde a câmera está olhando)
    internal fun startPlay(here: Boolean) {
        if (Play.active) return
        Play.playerName = if (RobloxAuth.loggedIn(this)) RobloxAuth.displayName(this).ifEmpty { "Player" } else "Player"
        Play.userId = RobloxAuth.userId(this).toLongOrNull() ?: 1L
        Out.clear()
        Play.start(here)
        applyControls()
        hud.visibility = View.VISIBLE
        stopFab.visibility = View.VISIBLE
        editorPad.visibility = View.GONE
        scriptEditor.hide()
        toolBar.start()
        gui.start()
        Editor.onChanged?.invoke(true)
        toast(
            when (Settings.moveMode) {
                Settings.MOVE_DPAD -> "D-Pad: andar | Direita: olhar | Botão: pular"
                Settings.MOVE_TAP -> "Toque no chão para andar | Arraste: olhar"
                else -> "Esquerda: andar | Direita: olhar | Botão: pular"
            }
        )
    }

    // aplica as configurações do menu (movimento, Shift Lock)
    private fun applyControls() {
        hud.mode = Settings.moveMode
        if (!Settings.shiftLockSwitch && Play.shiftLock) Play.applyShiftLock(false)
        if (joyId != -1) {
            Play.moveX = 0f; Play.moveY = 0f
            joyId = -1
            hud.joyActive = false
            hud.knobX = 0f; hud.knobY = 0f
            hud.dpadH = 0; hud.dpadV = 0
        }
        hud.invalidate()
    }

    internal fun stopPlay() {
        if (!Play.active) return
        Play.stop()
        Audio.stopAll()
        gui.stop()
        gameMenu.visibility = View.GONE
        joyId = -1; lookId = -1; jumpId = -1; shiftId = -1; pinchId = -1
        hud.dpadH = 0; hud.dpadV = 0
        hud.joyActive = false
        hud.knobX = 0f; hud.knobY = 0f
        hud.jumpDown = false
        hud.visibility = View.GONE
        stopFab.visibility = View.GONE
        toolBar.stop()
        editorPad.visibility = View.VISIBLE
        lastSel = null
        Editor.onChanged?.invoke(true)
    }

    private fun dpadUpdate(x: Float, y: Float) {
        val d = resources.displayMetrics.density
        val dead = 22f * d
        val dx = x - hud.dpadX()
        val dy = y - hud.dpadY()
        val h = if (dx > dead) 1 else if (dx < -dead) -1 else 0
        val v = if (dy < -dead) 1 else if (dy > dead) -1 else 0
        hud.dpadH = h
        hud.dpadV = v
        Play.moveX = h.toFloat()
        Play.moveY = v.toFloat()
    }

    // toque para mover: acha o ponto do chão tocado e manda o personagem andar até lá
    private fun tapMove(x: Float, y: Float) {
        val ray = Cam.ray(x, y)
        var bt = Float.MAX_VALUE
        for (p in Scene.parts) {
            val t = rayObb(ray[0], ray[1], v3(p.x, p.y, p.z), p.rot, floatArrayOf(p.sx / 2, p.sy / 2, p.sz / 2))
            if (t >= 0f && t < bt) bt = t
        }
        if (bt == Float.MAX_VALUE) {
            if (ray[1][1] < -0.01f) bt = (Play.y - Play.HY - ray[0][1]) / ray[1][1] else return
        }
        Play.walkTo(ray[0][0] + ray[1][0] * bt, ray[0][2] + ray[1][2] * bt)
    }

    private fun playTouch(e: MotionEvent): Boolean {
        val d = resources.displayMetrics.density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                if (hypot(x - hud.jumpX(), y - hud.jumpY()) < 48f * d) {
                    jumpId = id
                    Play.jump = true
                    hud.jumpDown = true
                } else if (Settings.shiftLockSwitch && hypot(x - hud.shiftX(), y - hud.shiftY()) < 38f * d) {
                    shiftId = id
                    Play.applyShiftLock(!Play.shiftLock)
                } else if (Settings.moveMode == Settings.MOVE_DPAD && joyId == -1 &&
                    hypot(x - hud.dpadX(), y - hud.dpadY()) < 110f * d) {
                    joyId = id
                    dpadUpdate(x, y)
                } else if (Settings.moveMode == Settings.MOVE_JOYSTICK && x < gl.width * 0.45f && joyId == -1) {
                    joyId = id
                    hud.joyActive = true
                    hud.joyCx = x
                    hud.joyCy = y
                    hud.knobX = 0f
                    hud.knobY = 0f
                } else if (lookId == -1) {
                    lookId = id
                    lookLX = x
                    lookLY = y
                    lookDownT = e.eventTime
                    lookTravel = 0f
                    Play.lookHeld = true
                } else if (pinchId == -1) {
                    // segundo dedo na câmera: zoom com pinça
                    pinchId = id
                    val a = e.findPointerIndex(lookId)
                    lastPinch = if (a >= 0) hypot(e.getX(a) - x, e.getY(a) - y) else 0f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val x = e.getX(i)
                    val y = e.getY(i)
                    if (id == joyId) {
                        if (Settings.moveMode == Settings.MOVE_DPAD) {
                            dpadUpdate(x, y)
                        } else {
                            var dx = x - hud.joyCx
                            var dy = y - hud.joyCy
                            val r = 52f * d
                            val l = hypot(dx, dy)
                            if (l > r) {
                                dx = dx / l * r
                                dy = dy / l * r
                            }
                            hud.knobX = dx
                            hud.knobY = dy
                            Play.moveX = dx / r
                            Play.moveY = -dy / r
                        }
                    } else if (id == lookId) {
                        lookTravel += hypot(x - lookLX, y - lookLY)
                        if (pinchId == -1) {
                            Cam.yaw -= (x - lookLX) * 0.008f
                            Cam.pitch = (Cam.pitch + (y - lookLY) * 0.008f).coerceIn(-1.3f, 1.5f)
                        }
                        lookLX = x
                        lookLY = y
                    }
                }
                if (pinchId != -1 && lookId != -1) {
                    val a = e.findPointerIndex(lookId)
                    val b = e.findPointerIndex(pinchId)
                    if (a >= 0 && b >= 0) {
                        val s = hypot(e.getX(a) - e.getX(b), e.getY(a) - e.getY(b))
                        if (s > 10f && lastPinch > 10f) Cam.dist = (Cam.dist * lastPinch / s).coerceIn(0.5f, 80f)
                        lastPinch = s
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> releasePlayPointer(e.getPointerId(e.actionIndex), e.eventTime)
            MotionEvent.ACTION_UP -> {
                releasePlayPointer(joyId, e.eventTime)
                releasePlayPointer(lookId, e.eventTime)
                releasePlayPointer(jumpId, e.eventTime)
                releasePlayPointer(shiftId, e.eventTime)
                releasePlayPointer(pinchId, e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> {
                lookTravel = 1e9f
                releasePlayPointer(joyId, e.eventTime)
                releasePlayPointer(lookId, e.eventTime)
                releasePlayPointer(jumpId, e.eventTime)
                releasePlayPointer(shiftId, e.eventTime)
                releasePlayPointer(pinchId, e.eventTime)
            }
        }
        hud.invalidate()
        return true
    }

    private fun releasePlayPointer(id: Int, t: Long) {
        if (id == -1) return
        if (id == joyId) {
            joyId = -1
            hud.joyActive = false
            hud.knobX = 0f
            hud.knobY = 0f
            hud.dpadH = 0
            hud.dpadV = 0
            Play.moveX = 0f
            Play.moveY = 0f
        }
        if (id == lookId) {
            lookId = -1
            Play.lookHeld = false
            val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
            if (pinchId == -1 && lookTravel < slop * 1.5f && t - lookDownT < 300L) {
                if (Settings.moveMode == Settings.MOVE_TAP) tapMove(lookLX, lookLY)
                val eqTool = Play.equipped
                if (eqTool != null) Lua.activated(eqTool)
                Lua.mouseTap(lookLX, lookLY)
            }
        }
        if (id == pinchId) pinchId = -1
        if (id == shiftId) shiftId = -1
        if (id == jumpId) {
            jumpId = -1
            Play.jump = false
            hud.jumpDown = false
        }
    }

    // ---------- toque no viewport ----------
    private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    private fun glTouch(e: MotionEvent): Boolean {
        if (Play.active) return playTouch(e)
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentFocus?.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(gl.windowToken, 0)
                downX = e.x; downY = e.y; downT = e.eventTime
                lastX = e.x; lastY = e.y
                moved = false
                mode = if (Editor.grab(e.x, e.y)) 1 else 2
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == 1) Editor.release()
                mode = 3
                moved = true
                if (e.pointerCount >= 2) {
                    lastSpan = span(e)
                    lastMidX = (e.getX(0) + e.getX(1)) / 2
                    lastMidY = (e.getY(0) + e.getY(1)) / 2
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 1) {
                    Editor.drag(e.x, e.y)
                } else if (mode == 2) {
                    if (hypot(e.x - downX, e.y - downY) > slop) moved = true
                    if (moved) {
                        Cam.yaw -= (e.x - lastX) * 0.008f
                        Cam.pitch = (Cam.pitch + (e.y - lastY) * 0.008f).coerceIn(0.05f, 1.5f)
                    }
                    lastX = e.x; lastY = e.y
                } else if (mode == 3 && e.pointerCount >= 2) {
                    val s = span(e)
                    val mx = (e.getX(0) + e.getX(1)) / 2
                    val my = (e.getY(0) + e.getY(1)) / 2
                    if (s > 10f && lastSpan > 10f) Cam.dist = (Cam.dist * lastSpan / s).coerceIn(4f, 400f)
                    Cam.pan(mx - lastMidX, my - lastMidY)
                    lastSpan = s; lastMidX = mx; lastMidY = my
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount == 2) {
                    val i = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(i); lastY = e.getY(i)
                    mode = 2
                    moved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mode == 1) Editor.release()
                else if (mode == 2 && !moved && e.eventTime - downT < 400) Editor.tap(e.x, e.y)
                mode = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                Editor.release()
                mode = 0
            }
        }
        return true
    }

    // ---------- barra de abas ----------
    private fun buildTabBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(C_BAR)

        val file = LinearLayout(this)
        file.orientation = LinearLayout.HORIZONTAL
        file.gravity = Gravity.CENTER
        file.setBackgroundColor(C_SEL)
        file.setPadding(dp(12), 0, dp(16), 0)
        file.addView(iv(R.drawable.logo, 22, null), lp(dp(22), dp(22)))
        val ft = tv("File", 12f, Color.WHITE, true)
        ft.setPadding(dp(8), 0, 0, 0)
        file.addView(ft, lp(WRAP, WRAP))
        file.setOnClickListener { fileMenu() }
        bar.addView(file, lp(WRAP, MATCH))

        for (t in listOf("HOME", "MODEL", "AVATAR", "TEST", "VIEW", "PLUGINS")) {
            val v = tv(t, 11f, C_DIM, true)
            v.gravity = Gravity.CENTER
            v.setPadding(dp(14), 0, dp(14), 0)
            v.setOnClickListener {
                currentTab = t
                buildRibbon(t)
                styleTabs()
            }
            tabViews[t] = v
            bar.addView(v, lp(WRAP, MATCH))
        }
        styleTabs()

        bar.addView(View(this), lp(0, MATCH, 1f))

        val acc = LinearLayout(this)
        acc.orientation = LinearLayout.HORIZONTAL
        acc.gravity = Gravity.CENTER_VERTICAL
        acc.setPadding(dp(12), 0, dp(12), 0)
        accountIcon = ImageView(this)
        acc.addView(accountIcon, lp(dp(22), dp(22)))
        accountLabel = tv("", 11f, C_TEXT, true)
        accountLabel.setPadding(dp(6), 0, 0, 0)
        accountLabel.maxLines = 1
        acc.addView(accountLabel, lp(WRAP, WRAP))
        acc.setOnClickListener { accountMenu() }
        bar.addView(acc, lp(WRAP, MATCH))
        return bar
    }

    private fun styleTabs() {
        for ((k, v) in tabViews) {
            v.setTextColor(if (k == currentTab) Color.WHITE else C_DIM)
            v.setBackgroundColor(if (k == currentTab) C_RIBBON else 0)
        }
    }

    // ---------- conta do Roblox ----------
    private fun refreshAccount() {
        if (::menu.isInitialized) menu.refreshUser()
        if (RobloxAuth.loggedIn(this)) {
            accountLabel.text = RobloxAuth.displayName(this)
            val av = RobloxAuth.avatar
            if (av != null) {
                accountIcon.clearColorFilter()
                accountIcon.setImageBitmap(av)
            } else {
                accountIcon.setImageResource(R.drawable.ic_person)
                accountIcon.setColorFilter(C_TEXT)
            }
        } else {
            accountLabel.text = "Entrar"
            accountIcon.setImageResource(R.drawable.ic_person)
            accountIcon.setColorFilter(C_DIM)
        }
    }

    internal fun accountMenu() {
        if (RobloxAuth.loggedIn(this)) {
            StyledDialog(this)
                .setTitle(RobloxAuth.displayName(this))
                .setMessage("@" + RobloxAuth.username(this) + "\nID: " + RobloxAuth.userId(this))
                .setPositiveButton("Sair") { _, _ ->
                    RobloxAuth.logout(this)
                    Avatar.clear()
                    refreshAccount()
                    toast("Você saiu da conta")
                }
                .setNeutralButton("Ver avatar") { _, _ ->
                    avatarReport(Avatar.status.ifEmpty { "O avatar ainda está carregando..." })
                }
                .setNegativeButton("Fechar", null)
                .show()
        } else {
            loginDialog()
        }
    }

    internal fun styledEdit(hint: String, init: String, secret: Boolean): EditText {
        val et = EditText(this)
        et.hint = hint
        et.setText(init)
        et.textSize = 14f
        et.setTextColor(C_TEXT)
        et.setHintTextColor(C_DIM)
        et.setSingleLine()
        et.setPadding(dp(12), dp(10), dp(12), dp(10))
        val g = android.graphics.drawable.GradientDrawable()
        g.setColor(C_FIELD)
        g.cornerRadius = dp(8).toFloat()
        g.setStroke(dp(1), 0xFF4A4A4A.toInt())
        et.background = g
        if (secret) et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        return et
    }

    // resultado do carregamento do avatar (o que veio e o que falhou)
    internal fun avatarReport(text: String) {
        StyledDialog(this).setTitle("Avatar carregado").setMessage(text)
            .setPositiveButton("OK", null).show()
    }

    private fun loginDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo)
        box.addView(logo, lp(dp(56), dp(56)))
        val head = tv("Entre com seu Roblox", 16f, C_TEXT, true)
        head.setPadding(0, dp(8), 0, dp(4))
        box.addView(head, lp(WRAP, WRAP))
        val info = tv("Digite seu usuário (ou o ID) para entrar rápido, sem senha. Para baixar roupas e acessórios com tudo, use Login pelo site: você entra na página oficial do Roblox.", 12f, C_DIM)
        info.gravity = Gravity.CENTER
        info.setPadding(0, 0, 0, dp(10))
        box.addView(info, lp(MATCH, WRAP))
        val et = styledEdit("Usuário do Roblox", RobloxAuth.username(this), false)
        box.addView(et, lp(MATCH, WRAP))
        StyledDialog(this)
            .setTitle("Entrar")
            .setView(box)
            .setPositiveButton("Entrar") { _, _ ->
                toast("Procurando usuário...")
                RobloxAuth.quickLogin(this, et.text.toString()) { ok, msg ->
                    toast(msg)
                    refreshAccount()
                    if (ok) {
                        RobloxAuth.loadAvatar(this) { refreshAccount() }
                        Avatar.load(this) { avatarReport(it) }
                    }
                }
            }
            .setNeutralButton("Login pelo site") { _, _ ->
                WebLogin(this) { ok, msg ->
                    toast(msg)
                    refreshAccount()
                    if (ok) {
                        RobloxAuth.loadAvatar(this) { refreshAccount() }
                        Avatar.load(this) { avatarReport(it) }
                    }
                }.show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun oauthDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val info = tv(
            "Para entrar com sua conta do Roblox, crie um app em create.roblox.com/dashboard/credentials " +
                "(aba OAuth 2.0 Apps), marque os escopos openid e profile e cadastre este Redirect URL:", 12f, C_TEXT)
        box.addView(info, lp(MATCH, WRAP))
        val redirect = tv(RobloxAuth.REDIRECT_URI, 14f, C_SEL, true)
        redirect.setTextIsSelectable(true)
        redirect.setPadding(0, dp(6), 0, dp(6))
        box.addView(redirect, lp(MATCH, WRAP))
        val idEt = styledEdit("Client ID", RobloxAuth.clientId(this), false)
        val secEt = styledEdit("Client Secret (só se o app tiver)", RobloxAuth.clientSecret(this), true)
        box.addView(idEt, lp(MATCH, WRAP))
        box.addView(secEt, lp(MATCH, WRAP))
        val sv = ScrollView(this)
        sv.addView(box)
        StyledDialog(this)
            .setTitle("Entrar com Roblox")
            .setView(sv)
            .setPositiveButton("Entrar") { _, _ ->
                val id = idEt.text.toString().trim()
                if (id.isEmpty()) {
                    toast("Cole o Client ID do seu app")
                } else {
                    RobloxAuth.saveClient(this, id, secEt.text.toString())
                    try {
                        RobloxAuth.begin(this)
                    } catch (e: Exception) {
                        toast("Não consegui abrir o navegador")
                    }
                }
            }
            .setNeutralButton("Abrir site do Roblox") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://create.roblox.com/dashboard/credentials?activeTab=OAuthTab")))
                } catch (e: Exception) {
                    toast("Não consegui abrir o navegador")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- menu File ----------
    private fun fileMenu() {
        StyledDialog(this).setTitle("File")
            .setItems(arrayOf(
                "Novo lugar", "Abrir...", "Salvar", "Salvar como...",
                "Exportar lugar...", "Exportar modelo...",
                "Publicar lugar no Roblox", "Enviar modelo ao Roblox"
            )) { _, i ->
                when (i) {
                    0 -> newPlace()
                    1 -> openPlace()
                    2 -> savePlace(false)
                    3 -> savePlace(true)
                    4 -> exportMenu(false)
                    5 -> exportMenu(true)
                    6 -> publish()
                    else -> uploadModelDialog()
                }
            }.show()
    }

    // ---------- exportar .rbxl / .rbxm ----------
    private fun exportMenu(model: Boolean) {
        if (needStop()) return
        val opts = if (model) arrayOf(".rbxm (binário)", ".rbxmx (XML)") else arrayOf(".rbxl (binário)", ".rbxlx (XML)")
        val msg = if (!model) "Exporta o lugar inteiro (Workspace e serviços)."
        else if (RbxExport.usedSelection()) "Exporta o objeto selecionado."
        else "Nada selecionado: exporta tudo do Workspace dentro de um Model."
        StyledDialog(this).setTitle(if (model) "Exportar modelo" else "Exportar lugar")
            .setMessage(msg)
            .setItems(opts) { _, i -> startExport(model, i == 0) }
            .show()
    }

    private fun startExport(model: Boolean, binary: Boolean) {
        exportModel = model
        exportBinary = binary
        val base = Storage.cleanName(placeName ?: (if (model) "Model" else "Place"))
        val ext = if (model) (if (binary) ".rbxm" else ".rbxmx") else (if (binary) ".rbxl" else ".rbxlx")
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/octet-stream"
        i.putExtra(Intent.EXTRA_TITLE, base + ext)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 79)
        } catch (e: Exception) {
            toast("Não achei o seletor de arquivos")
        }
    }

    private fun finishExport(u: Uri) {
        val roots = if (exportModel) RbxExport.model(placeName ?: "Model") else RbxExport.place()
        val skippedN = RbxExport.skipped
        val binary = exportBinary
        val total = RbxWriter.count(roots)
        toast("Exportando...")
        Thread {
            var msg = ""
            try {
                val bytes = RbxWriter.write(roots, binary)
                val os = contentResolver.openOutputStream(u, "wt") ?: throw Exception("não consegui abrir o arquivo")
                os.use { it.write(bytes) }
                msg = "Exportado: " + total + " objeto(s), " + (bytes.size / 1024) + " KB" +
                    (if (skippedN > 0) " (" + skippedN + " item(ns) sem suporte ficaram de fora)" else "")
            } catch (e: Throwable) {
                msg = "Falha ao exportar: " + (e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread { toast(msg) }
        }.start()
    }

    internal fun needStop(): Boolean {
        if (Play.active) {
            toast("Pare o Play primeiro")
            return true
        }
        return false
    }

    private fun newPlace() {
        if (needStop()) return
        menu.show()
    }

    // escolhido na tela inicial (aba New)
    internal fun openTemplate(name: String) {
        if (needStop()) return
        Scene.applyTemplate(name)
        History.clear()
        placeName = null
        lastSel = null
        Editor.select(null)
        menu.hide()
        toast("Novo: " + name)
    }

    // escolhido na tela inicial (aba Recent)
    internal fun openSaved(name: String) {
        if (needStop()) return
        try {
            Storage.load(this, name)
            History.clear()
            placeName = name
            lastSel = null
            Editor.select(null)
            menu.hide()
            toast("Aberto: " + name)
        } catch (e: Exception) {
            toast("Não consegui abrir esse arquivo")
        }
    }

    // escolher imagem da galeria para uma propriedade (Texture, Image...)
    internal fun pickImage(o: Inst, key: String) {
        imgTarget = o
        imgKey = key
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        i.addCategory(Intent.CATEGORY_OPENABLE)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(i, "Escolher imagem"), 77)
        } catch (e: Exception) {
            toast("Não achei a galeria")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 78 && resultCode == RESULT_OK) {
            val u = data?.data
            if (u != null) openFileUri(u)
            return
        }
        if (requestCode == 79) {
            val u = data?.data
            if (resultCode == RESULT_OK && u != null) finishExport(u)
            return
        }
        if (requestCode != 77 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val target = imgTarget ?: return
        val key = imgKey
        Thread {
            var ok = false
            var name = ""
            try {
                val dir = File(filesDir, "images")
                dir.mkdirs()
                val o1 = BitmapFactory.Options()
                o1.inJustDecodeBounds = true
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o1) }
                var ss = 1
                while (o1.outWidth / ss > 1024 || o1.outHeight / ss > 1024) ss *= 2
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = ss
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
                if (bmp != null) {
                    name = "img" + System.currentTimeMillis() + ".png"
                    File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    ok = true
                }
            } catch (e: Exception) {
                ok = false
            }
            runOnUiThread {
                if (ok) {
                    target.props[key] = "file:" + name
                    buildProps()
                    Editor.onChanged?.invoke(false)
                    toast("Imagem aplicada")
                } else {
                    toast("Não consegui abrir a imagem")
                }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (Play.active) {
            if (gameMenu.visibility == View.VISIBLE) gameMenu.hide() else gameMenu.show()
        } else if (scriptEditor.visibility == View.VISIBLE) {
            scriptEditor.hide()
        } else if (menu.visibility == View.VISIBLE) menu.hide() else super.onBackPressed()
    }

    private fun openPlace() {
        if (needStop()) return
        val names = Storage.list(this)
        if (names.isEmpty()) {
            toast("Nenhum lugar salvo ainda")
            return
        }
        StyledDialog(this).setTitle("Abrir lugar")
            .setItems(names.toTypedArray()) { _, i ->
                try {
                    Storage.load(this, names[i])
                    History.clear()
                    placeName = names[i]
                    lastSel = null
                    Editor.select(null)
                    toast("Aberto: " + names[i])
                } catch (e: Exception) {
                    toast("Não consegui abrir esse arquivo")
                }
            }.show()
    }

    private fun doSave(name: String) {
        try {
            placeName = Storage.save(this, name)
            toast("Salvo: " + placeName)
        } catch (e: Exception) {
            toast("Erro ao salvar")
        }
    }

    private fun savePlace(asNew: Boolean) {
        if (needStop()) return
        val cur = placeName
        if (!asNew && cur != null) {
            doSave(cur)
            return
        }
        val et = styledEdit("Nome do lugar", cur ?: "MeuLugar", false)
        val box = LinearLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(et, lp(MATCH, WRAP))
        StyledDialog(this).setTitle("Salvar como")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ -> doSave(et.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun fieldBox(vararg fields: EditText): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        for ((idx, f) in fields.withIndex()) {
            val l = lp(MATCH, WRAP)
            if (idx > 0) l.topMargin = dp(8)
            box.addView(f, l)
        }
        return box
    }

    private fun showResult(title: String, msg: String) {
        runOnUiThread { StyledDialog(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show() }
    }

    private fun publish() {
        if (needStop()) return
        val cloud = placeName?.let { Storage.cloud(this, Storage.cleanName(it)) }
        val pid = cloud?.get(0) ?: Publish.placeId(this)
        val uid = cloud?.get(1) ?: Publish.universeId(this)
        val etKey = styledEdit("Chave de API (Open Cloud)", Publish.apiKey(this), true)
        val etPlace = styledEdit("Place ID do lugar que vai receber", if (pid > 0L) pid.toString() else "", false)
        val etUni = styledEdit("Universe ID (vazio = achar sozinho)", if (uid > 0L) uid.toString() else "", false)
        etPlace.inputType = InputType.TYPE_CLASS_NUMBER
        etUni.inputType = InputType.TYPE_CLASS_NUMBER
        StyledDialog(this).setTitle("Publicar no Roblox")
            .setMessage("Atualiza um lugar que já existe na sua conta (a API do Roblox não cria jogos novos). " +
                "Crie a chave em create.roblox.com > Credentials com a permissão universe-places (Write), o jogo na lista e o IP 0.0.0.0/0.")
            .setView(fieldBox(etKey, etPlace, etUni))
            .setPositiveButton("Publicar") { _, _ ->
                doPublish(etKey.text.toString(), etPlace.text.toString(), etUni.text.toString(), true)
            }
            .setNeutralButton("Só salvar versão") { _, _ ->
                doPublish(etKey.text.toString(), etPlace.text.toString(), etUni.text.toString(), false)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun doPublish(key: String, placeText: String, uniText: String, published: Boolean) {
        val pid = placeText.trim().toLongOrNull()
        if (key.isBlank() || pid == null) {
            toast("Preencha a chave de API e o Place ID")
            return
        }
        val uniIn = uniText.trim().toLongOrNull() ?: 0L
        Publish.save(this, key, pid, uniIn)
        val roots = RbxExport.place()
        val total = RbxWriter.count(roots)
        toast("Publicando...")
        Thread {
            var title = "Publicado"
            var msg = ""
            try {
                val uni = if (uniIn > 0L) uniIn else Publish.universeOf(pid)
                if (uni == 0L) throw Exception("não achei o jogo desse Place ID; preencha o Universe ID")
                val bytes = RbxWriter.write(roots, false)
                val v = Publish.publishPlace(key, uni, pid, bytes, false, published)
                msg = (if (published) "Publicado" else "Versão salva") + (if (v > 0L) " (versão " + v + ")" else "") +
                    ": " + total + " objeto(s), " + (bytes.size / 1024) + " KB."
            } catch (e: Throwable) {
                title = "Não publicou"
                msg = e.message ?: e.javaClass.simpleName
            }
            showResult(title, msg)
        }.start()
    }

    private fun uploadModelDialog() {
        if (needStop()) return
        if (!RobloxAuth.loggedIn(this)) {
            toast("Entre com sua conta do Roblox primeiro")
            loginDialog()
            return
        }
        val etKey = styledEdit("Chave de API (Open Cloud)", Publish.apiKey(this), true)
        val etName = styledEdit("Nome do modelo", placeName ?: "MeuModelo", false)
        val what = if (RbxExport.usedSelection()) "Vai enviar o objeto selecionado." else "Nada selecionado: vai enviar tudo do Workspace."
        StyledDialog(this).setTitle("Enviar modelo ao Roblox")
            .setMessage("Envia como Model para a sua conta (a chave precisa da permissão assets Write). " + what +
                " O Roblox pode recusar arquivos feitos fora do Studio e o modelo passa por moderação.")
            .setView(fieldBox(etKey, etName))
            .setPositiveButton("Enviar") { _, _ -> doUploadModel(etKey.text.toString(), etName.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun doUploadModel(key: String, name: String) {
        if (key.isBlank()) {
            toast("Preencha a chave de API")
            return
        }
        Publish.saveKey(this, key)
        val nm = name.trim().ifEmpty { "MeuModelo" }
        val roots = RbxExport.model(nm)
        val total = RbxWriter.count(roots)
        val user = RobloxAuth.userId(this)
        toast("Enviando...")
        Thread {
            var title = "Modelo enviado"
            var msg = ""
            try {
                val bytes = RbxWriter.write(roots, false)
                val id = Publish.uploadModel(key, user, nm, bytes)
                msg = "Asset ID: " + id + " (" + total + " objeto(s)). Veja em create.roblox.com > Creations > Models."
            } catch (e: Throwable) {
                title = "Não enviou"
                msg = e.message ?: e.javaClass.simpleName
            }
            showResult(title, msg)
        }.start()
    }

    // ---------- inserir objetos ----------
    private fun canHold(i: Inst) =
        i is PartData || i.className == "Folder" || i.className == "Model" || i.className == "Tool" || i.className.startsWith("Starter") || i.parent === Scene.game

    internal fun insertObject(cls: String) {
        if (needStop()) return
        val sel = Scene.selObj
        val parent: Inst = when {
            cls == "ScreenGui" -> Scene.service("StarterGui")
            cls == "Tool" -> Scene.service("StarterPack")
            Defaults.isGui(cls) -> {
                if (sel != null && (sel.className == "ScreenGui" || sel.className == "Frame")) sel
                else Scene.service("StarterGui").children.firstOrNull { it.className == "ScreenGui" }
                    ?: Defaults.make("ScreenGui").also { Scene.service("StarterGui").add(it) }
            }
            sel != null && canHold(sel) -> sel
            cls == "Script" -> Scene.service("ServerScriptService")
            cls == "LocalScript" -> Scene.service("StarterPlayer")
            else -> Scene.workspace
        }
        val i = Defaults.make(cls)
        Scene.setParent(i, parent)
        open.add(parent)
        pick(i)
        if (cls == "Script" || cls == "LocalScript") toast("Segure o script no Explorer ou use Editar para escrever o código")
    }

    internal fun insertMenu(list: List<String>) {
        if (needStop()) return
        StyledDialog(this).setTitle("Inserir objeto")
            .setIcons(list.map { classIcon(it) })
            .setItems(list.toTypedArray()) { _, i -> insertObject(list[i]) }
            .show()
    }

    // Import 3D: cria um MeshPart; o ID vai na propriedade MeshId
    internal fun importMesh() {
        if (needStop()) return
        Editor.addPart()
        val p = Scene.selected ?: return
        p.className = "MeshPart"
        p.name = "MeshPart"
        p.sx = 4f; p.sy = 4f; p.sz = 4f
        p.y = 2f
        p.props["MeshId"] = ""
        p.props["TextureID"] = ""
        pick(p)
        Editor.onChanged?.invoke(true)
        toast("MeshPart criado: coloque o ID em MeshId")
    }

    internal fun deleteSel() {
        val o = Scene.selObj
        if (o == null || o is PartData) {
            Editor.delete()
            return
        }
        if (needStop()) return
        Scene.destroy(o)
        pick(null)
    }

    internal fun editScript(s: Inst) {
        if (needStop()) return
        scriptEditor.open(s)
    }

    // ---------- tela inicial: configurações, arquivo, Place ID, lugares do Roblox ----------
    internal fun openSettings() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.addView(GraphicsUi.rigRow(this) { Settings.save(this) }, lp(MATCH, WRAP))
        box.addView(GraphicsUi.view(this) { Settings.save(this) }, lp(MATCH, WRAP))
        StyledDialog(this).setTitle("Settings")
            .setView(box)
            .setPositiveButton("OK") { _, _ -> Settings.save(this) }
            .show()
    }

    internal fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.type = "*/*"
        i.addCategory(Intent.CATEGORY_OPENABLE)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 78)
        } catch (e: Exception) {
            toast("Não achei o seletor de arquivos")
        }
    }

    private fun openFileUri(u: Uri) {
        if (needStop()) return
        toast("Abrindo arquivo...")
        Thread {
            var name = "Arquivo"
            var data: ByteArray? = null
            try {
                contentResolver.query(u, null, null, null, null)?.use { c ->
                    val ix = c.getColumnIndex("_display_name")
                    if (ix >= 0 && c.moveToFirst()) name = c.getString(ix) ?: name
                }
                data = contentResolver.openInputStream(u)?.use { it.readBytes() }
            } catch (e: Exception) {
                data = null
            }
            val raw = data
            if (raw == null || raw.isEmpty()) {
                runOnUiThread { toast("Não consegui ler esse arquivo") }
                return@Thread
            }
            // lugar salvo por este app (.json)
            val first = raw.firstOrNull { it.toInt() > 32 }?.toInt()?.toChar() ?: ' '
            if (name.endsWith(".json", true) || first == '{') {
                try {
                    val js = org.json.JSONObject(String(raw, Charsets.UTF_8))
                    runOnUiThread {
                        try {
                            Storage.fromJson(js)
                            afterOpen(name, null)
                        } catch (e: Exception) {
                            toast("Esse arquivo não é um lugar válido")
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { toast("Esse arquivo não é um lugar válido") }
                }
                return@Thread
            }
            // arquivos do Roblox: .rbxl / .rbxlx (lugar), .rbxm / .rbxmx (modelo); também aceita .rblx / .rblxm
            val tree = RbxFile.parse(raw)
            if (tree == null || tree.isEmpty()) {
                val why = RbxFile.lastError.ifEmpty { "formato desconhecido" }
                runOnUiThread { toast("Não consegui abrir " + name + ": " + why) }
                return@Thread
            }
            val place = PlaceImport.isPlace(tree)
            runOnUiThread {
                try {
                    val res = PlaceImport.apply(tree, place)
                    afterOpen(name, (if (place) "Lugar aberto: " else "Modelo inserido: ") + res.text())
                } catch (e: Throwable) {
                    toast("Erro ao importar: " + (e.message ?: e.javaClass.simpleName))
                }
            }
        }.start()
    }

    // depois de abrir/importar algo
    private fun afterOpen(name: String, msg: String?) {
        History.clear()
        placeName = null
        lastSel = null
        Editor.select(null)
        menu.hide()
        val m = msg ?: ("Aberto: " + name)
        toast(m)
        Out.add(m, 0)
    }

    internal fun openByPlaceId() {
        val et = styledEdit("Place ID (só números)", "", false)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        val box = LinearLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(et, lp(MATCH, WRAP))
        StyledDialog(this).setTitle("Open by Place ID")
            .setView(box)
            .setPositiveButton("Abrir") { _, _ ->
                val id = et.text.toString().trim()
                toast("Procurando lugar...")
                Thread {
                    var info: RobloxApi.PlaceInfo? = null
                    var err = ""
                    try {
                        info = RobloxApi.placeInfo(id)
                        if (info == null) err = "Lugar não encontrado"
                    } catch (e: Exception) {
                        err = "Não consegui achar esse lugar (" + (e.message ?: "erro") + ")"
                    }
                    val found = info
                    runOnUiThread {
                        if (found != null) openCloudPlace(found) else toast(err)
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // lugar da conta do Roblox: baixa o mapa de verdade (só funciona para lugares seus ou "copy unlocked");
    // se não der, abre um projeto novo (Baseplate) com o nome e o ícone do lugar
    internal fun openCloudPlace(p: RobloxApi.PlaceInfo) {
        if (needStop()) return
        val nm = Storage.cleanName(p.name)
        if (Storage.exists(this, nm)) {
            openSaved(nm)
            return
        }
        toast("Baixando " + p.name + "...")
        Thread {
            var tree: List<RNode>? = null
            var err = ""
            try {
                RobloxAuth.restoreSession(this)
                val raw = Avatar.fetchAsset(p.placeId)
                tree = RbxFile.parse(raw)
                if (tree == null || tree.isEmpty()) err = RbxFile.lastError.ifEmpty { "arquivo vazio" }
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            val got = tree
            runOnUiThread {
                var note = ""
                if (got != null && got.isNotEmpty()) {
                    try {
                        val res = PlaceImport.apply(got, true)
                        note = "Mapa carregado: " + res.text()
                    } catch (e: Throwable) {
                        err = e.message ?: e.javaClass.simpleName
                    }
                }
                if (note.isEmpty()) {
                    Scene.applyTemplate("Baseplate")
                    note = "Não consegui baixar o mapa (" + err + "). Abri um Baseplate; use Open File... com o .rbxl"
                }
                History.clear()
                lastSel = null
                Editor.select(null)
                try {
                    placeName = Storage.save(this, nm)
                    Storage.setCloud(this, nm, p.placeId, p.universeId)
                } catch (e: Exception) {
                    placeName = null
                }
                menu.hide()
                toast(note)
                Out.add(note, 0)
            }
        }.start()
    }
}
