package com.rblxstudio

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

private const val MATCH = -1
private const val WRAP = -2
private val C_BG = 0xFF2B2B2B.toInt()
private val C_BAR = 0xFF1B1B1B.toInt()
private val C_RIBBON = 0xFF2F2F2F.toInt()
private val C_PANEL = 0xFF2B2B2B.toInt()
private val C_HDR = 0xFF3A3A3A.toInt()
private val C_FIELD = 0xFF1E1E1E.toInt()
private val C_TEXT = 0xFFE6E6E6.toInt()
private val C_DIM = 0xFF9A9A9A.toInt()
private val C_SEL = 0xFF0F6FD8.toInt()
private val C_GREEN = 0xFF4CAF50.toInt()
private val C_RED = 0xFFE53935.toInt()

// joystick e botão de pulo desenhados por cima do viewport durante o Play
class PlayHud(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    var joyActive = false
    var joyCx = 0f
    var joyCy = 0f
    var knobX = 0f
    var knobY = 0f
    var jumpDown = false
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FFFFFF }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt() }
    private val pressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt() }
    private val icon = ctx.getDrawable(R.drawable.ic_jump)

    fun jumpX() = width - 80f * d
    fun jumpY() = height - 80f * d

    override fun onDraw(c: Canvas) {
        val bx = if (joyActive) joyCx else 100f * d
        val by = if (joyActive) joyCy else height - 90f * d
        c.drawCircle(bx, by, 52f * d, base)
        c.drawCircle(bx + knobX, by + knobY, 24f * d, knob)
        val jx = jumpX()
        val jy = jumpY()
        c.drawCircle(jx, jy, 36f * d, if (jumpDown) pressed else base)
        val s = (16f * d).toInt()
        icon?.setBounds(jx.toInt() - s, jy.toInt() - s, jx.toInt() + s, jy.toInt() + s)
        icon?.draw(c)
    }
}

class MainActivity : Activity() {
    private lateinit var gl: GLSurfaceView
    private val renderer = Renderer()
    private lateinit var ribbon: LinearLayout
    private lateinit var explorerList: LinearLayout
    private lateinit var propsList: LinearLayout
    private lateinit var explorerPanel: View
    private lateinit var propsPanel: View
    private lateinit var hud: PlayHud
    private lateinit var stopFab: ImageView
    private lateinit var accountIcon: ImageView
    private lateinit var accountLabel: TextView
    private val toolButtons = HashMap<Tool, View>()
    private val tabViews = LinkedHashMap<String, TextView>()
    private val refreshers = ArrayList<() -> Unit>()
    private var currentTab = "HOME"
    private var workspaceOpen = true
    private var explorerFilter = ""
    private var placeName: String? = null

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

    private val services = listOf(
        "Workspace" to R.drawable.ic_globe, "Players" to R.drawable.ic_people,
        "Lighting" to R.drawable.ic_bulb, "MaterialService" to R.drawable.ic_material,
        "ReplicatedFirst" to R.drawable.ic_box, "ReplicatedStorage" to R.drawable.ic_box,
        "ServerScriptService" to R.drawable.ic_script, "ServerStorage" to R.drawable.ic_storage,
        "StarterGui" to R.drawable.ic_ui, "StarterPack" to R.drawable.ic_toolbox,
        "StarterPlayer" to R.drawable.ic_player, "Teams" to R.drawable.ic_flag,
        "SoundService" to R.drawable.ic_volume
    )

    private val palette = listOf(
        "Medium stone grey" to 0xA3A2A5, "Bright red" to 0xC4281C, "Bright blue" to 0x0D69AC,
        "Bright yellow" to 0xF5CD30, "Bright green" to 0x4B974B, "Bright orange" to 0xDA8541,
        "Hot pink" to 0xFF66CC, "Cyan" to 0x04AFEC, "Royal purple" to 0x6B327C,
        "Reddish brown" to 0x694028, "White" to 0xF2F3F3, "Really black" to 0x111111
    )

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun soon() = toast("Em breve")
    private fun fmt(v: Float): String =
        String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.').let { if (it == "-0" || it.isEmpty()) "0" else it }

    private fun tv(text: String, size: Float = 12f, color: Int = C_TEXT, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun iv(res: Int, sizeDp: Int, tint: Int? = C_TEXT): ImageView {
        val v = ImageView(this)
        v.setImageResource(res)
        if (tint != null) v.setColorFilter(tint)
        v.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return v
    }

    // ---------- ciclo de vida ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        hud = PlayHud(this)
        hud.visibility = View.GONE
        glFrame.addView(hud, FrameLayout.LayoutParams(MATCH, MATCH))
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
        val ex = makePanel("Explorer", "Filter workspace (name or class)") { q ->
            explorerFilter = q
            buildExplorer()
        }
        explorerPanel = ex.first
        explorerList = ex.second
        val pr = makePanel("Properties", "Filter Properties", null)
        propsPanel = pr.first
        propsList = pr.second
        dock.addView(explorerPanel, lp(MATCH, 0, 1f))
        dock.addView(propsPanel, lp(MATCH, 0, 1f))
        middle.addView(dock, lp(dp(200), MATCH))
        root.addView(middle, lp(MATCH, 0, 1f))

        val cmd = tv("  >  Run a command", 11f, C_DIM)
        cmd.gravity = Gravity.CENTER_VERTICAL
        cmd.setBackgroundColor(C_FIELD)
        root.addView(cmd, lp(MATCH, dp(22)))
        setContentView(root)

        Editor.onChanged = { structural ->
            if (structural) {
                buildExplorer()
                buildProps()
            } else {
                updateProps()
            }
        }
        buildRibbon("HOME")
        buildExplorer()
        buildProps()
        refreshAccount()
        if (RobloxAuth.loggedIn(this)) RobloxAuth.loadAvatar(this) { refreshAccount() }
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
                if (ok) RobloxAuth.loadAvatar(this) { refreshAccount() }
            }
        }
    }

    // ---------- Play / Stop ----------
    private fun startPlay() {
        if (Play.active) return
        Play.start()
        hud.visibility = View.VISIBLE
        stopFab.visibility = View.VISIBLE
        Editor.onChanged?.invoke(true)
        toast("Esquerda: andar | Direita: olhar | Botão: pular")
    }

    private fun stopPlay() {
        if (!Play.active) return
        Play.stop()
        joyId = -1; lookId = -1; jumpId = -1
        hud.joyActive = false
        hud.knobX = 0f; hud.knobY = 0f
        hud.jumpDown = false
        hud.visibility = View.GONE
        stopFab.visibility = View.GONE
        Editor.onChanged?.invoke(true)
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
                } else if (x < gl.width * 0.45f && joyId == -1) {
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
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val x = e.getX(i)
                    val y = e.getY(i)
                    if (id == joyId) {
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
                    } else if (id == lookId) {
                        Cam.yaw -= (x - lookLX) * 0.008f
                        Cam.pitch = (Cam.pitch + (y - lookLY) * 0.008f).coerceIn(0.05f, 1.5f)
                        lookLX = x
                        lookLY = y
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> releasePlayPointer(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                releasePlayPointer(joyId)
                releasePlayPointer(lookId)
                releasePlayPointer(jumpId)
            }
        }
        hud.invalidate()
        return true
    }

    private fun releasePlayPointer(id: Int) {
        if (id == -1) return
        if (id == joyId) {
            joyId = -1
            hud.joyActive = false
            hud.knobX = 0f
            hud.knobY = 0f
            Play.moveX = 0f
            Play.moveY = 0f
        }
        if (id == lookId) lookId = -1
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

    private fun accountMenu() {
        if (RobloxAuth.loggedIn(this)) {
            AlertDialog.Builder(this)
                .setTitle(RobloxAuth.displayName(this))
                .setMessage("@" + RobloxAuth.username(this) + "\nID: " + RobloxAuth.userId(this))
                .setPositiveButton("Sair") { _, _ ->
                    RobloxAuth.logout(this)
                    refreshAccount()
                    toast("Você saiu da conta")
                }
                .setNegativeButton("Fechar", null)
                .show()
        } else {
            loginDialog()
        }
    }

    private fun styledEdit(hint: String, init: String, secret: Boolean): EditText {
        val et = EditText(this)
        et.hint = hint
        et.setText(init)
        et.textSize = 12f
        et.setTextColor(C_TEXT)
        et.setHintTextColor(C_DIM)
        et.setSingleLine()
        if (secret) et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        return et
    }

    private fun loginDialog() {
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
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this).setTitle("File")
            .setItems(arrayOf("Novo lugar", "Abrir...", "Salvar", "Salvar como...", "Publicar no Roblox")) { _, i ->
                when (i) {
                    0 -> newPlace()
                    1 -> openPlace()
                    2 -> savePlace(false)
                    3 -> savePlace(true)
                    else -> publish()
                }
            }.show()
    }

    private fun needStop(): Boolean {
        if (Play.active) {
            toast("Pare o Play primeiro")
            return true
        }
        return false
    }

    private fun newPlace() {
        if (needStop()) return
        Scene.reset()
        History.clear()
        placeName = null
        Editor.select(null)
    }

    private fun openPlace() {
        if (needStop()) return
        val names = Storage.list(this)
        if (names.isEmpty()) {
            toast("Nenhum lugar salvo ainda")
            return
        }
        AlertDialog.Builder(this).setTitle("Abrir lugar")
            .setItems(names.toTypedArray()) { _, i ->
                try {
                    Storage.load(this, names[i])
                    History.clear()
                    placeName = names[i]
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
        AlertDialog.Builder(this).setTitle("Salvar como")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ -> doSave(et.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun publish() {
        if (!RobloxAuth.loggedIn(this)) {
            toast("Entre com sua conta do Roblox primeiro")
            loginDialog()
        } else {
            toast("Publicar no Roblox: próxima etapa")
        }
    }

    // ---------- ribbon ----------
    private fun rbtn(icon: Int, label: String, tint: Int? = C_TEXT, onClick: () -> Unit): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        b.gravity = Gravity.CENTER
        b.setPadding(dp(8), dp(2), dp(8), dp(2))
        val i = iv(icon, 24, tint)
        val l = tv(label, 10f)
        l.gravity = Gravity.CENTER
        b.addView(i, lp(dp(24), dp(24)))
        b.addView(l, lp(WRAP, WRAP))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun toolBtn(t: Tool, icon: Int, label: String): View {
        val b = rbtn(icon, label) {
            Editor.tool = t
            refreshTools()
        }
        toolButtons[t] = b
        return b
    }

    private fun refreshTools() {
        for ((t, v) in toolButtons) v.setBackgroundColor(if (t == Editor.tool) C_SEL else 0)
    }

    private fun group(label: String, vararg items: View) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(4), dp(2), dp(4), 0)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        for (v in items) row.addView(v, lp(WRAP, WRAP))
        col.addView(row, lp(WRAP, 0, 1f))
        val l = tv(label, 9f, C_DIM)
        l.gravity = Gravity.CENTER
        col.addView(l, lp(MATCH, WRAP))
        ribbon.addView(col, lp(WRAP, MATCH))
        val sep = View(this)
        sep.setBackgroundColor(0xFF4A4A4A.toInt())
        ribbon.addView(sep, lp(dp(1), MATCH))
    }

    private fun check(text: String, init: Boolean, on: (Boolean) -> Unit): CheckBox {
        val c = CheckBox(this)
        c.text = text
        c.textSize = 11f
        c.setTextColor(C_TEXT)
        c.isChecked = init
        c.setOnCheckedChangeListener { _, v -> on(v) }
        return c
    }

    private fun playBtn() = rbtn(R.drawable.ic_play, "Play", C_GREEN) { startPlay() }
    private fun stopBtn() = rbtn(R.drawable.ic_stop, "Stop", C_RED) { stopPlay() }

    private fun buildRibbon(tab: String) {
        ribbon.removeAllViews()
        toolButtons.clear()
        when (tab) {
            "HOME" -> {
                group("History",
                    rbtn(R.drawable.ic_undo, "Undo") { Editor.undo() },
                    rbtn(R.drawable.ic_redo, "Redo") { Editor.redo() })
                group("Clipboard",
                    rbtn(R.drawable.ic_paste, "Paste") { Editor.paste() },
                    rbtn(R.drawable.ic_copy, "Copy") { Editor.copy() },
                    rbtn(R.drawable.ic_cut, "Cut") { Editor.cut() },
                    rbtn(R.drawable.ic_duplicate, "Duplicate") { Editor.duplicate() })
                val snap = LinearLayout(this)
                snap.orientation = LinearLayout.VERTICAL
                snap.gravity = Gravity.CENTER_VERTICAL
                snap.addView(check("Rotate 15°", Editor.snapRot) { Editor.snapRot = it })
                snap.addView(check("Move 1 stud", Editor.snapMove) { Editor.snapMove = it })
                group("Tools",
                    toolBtn(Tool.SELECT, R.drawable.ic_select, "Select"),
                    toolBtn(Tool.MOVE, R.drawable.ic_move, "Move"),
                    toolBtn(Tool.SCALE, R.drawable.ic_scale, "Scale"),
                    toolBtn(Tool.ROTATE, R.drawable.ic_rotate, "Rotate"),
                    snap)
                group("Terrain", rbtn(R.drawable.ic_terrain, "Editor") { soon() })
                group("Insert",
                    rbtn(R.drawable.ic_toolbox, "Toolbox") { soon() },
                    rbtn(R.drawable.ic_part, "Part", null) { Editor.addPart() },
                    rbtn(R.drawable.ic_ui, "UI") { soon() },
                    rbtn(R.drawable.ic_import, "Import 3D") { soon() })
                group("Edit",
                    rbtn(R.drawable.ic_color, "Color") { pickColor() },
                    rbtn(R.drawable.ic_material, "Material") { soon() },
                    rbtn(R.drawable.ic_group, "Group") { soon() },
                    rbtn(R.drawable.ic_lock, "Lock Tool") { soon() },
                    rbtn(R.drawable.ic_anchor, "Anchor") { Editor.toggleAnchor() },
                    rbtn(R.drawable.ic_delete, "Delete") { Editor.delete() })
                group("Test", playBtn(), stopBtn())
                group("Settings", rbtn(R.drawable.ic_settings, "Game Settings") { soon() })
            }
            "TEST" -> group("Test", playBtn(), stopBtn())
            "VIEW" -> group("Show",
                rbtn(R.drawable.ic_explorer, "Explorer") { toggle(explorerPanel) },
                rbtn(R.drawable.ic_properties, "Properties") { toggle(propsPanel) },
                rbtn(R.drawable.ic_focus, "Focus") { Editor.focus() })
            else -> {
                val t = tv("Em breve", 12f, C_DIM)
                t.setPadding(dp(16), dp(20), 0, 0)
                ribbon.addView(t)
            }
        }
        refreshTools()
    }

    private fun toggle(v: View) {
        v.visibility = if (v.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun pickColor() {
        if (Scene.selected == null) {
            toast("Selecione uma peça")
            return
        }
        AlertDialog.Builder(this).setTitle("Color")
            .setItems(palette.map { it.first }.toTypedArray()) { _, i ->
                val c = palette[i].second
                Editor.setColor(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
            }.show()
    }

    // ---------- painéis ----------
    private fun makePanel(title: String, hint: String, onFilter: ((String) -> Unit)?): Pair<LinearLayout, LinearLayout> {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(C_PANEL)
        val h = tv(title, 12f, C_TEXT, true)
        h.setBackgroundColor(C_HDR)
        h.setPadding(dp(8), dp(4), dp(8), dp(4))
        panel.addView(h, lp(MATCH, WRAP))
        val f = EditText(this)
        f.hint = hint
        f.textSize = 11f
        f.setTextColor(C_TEXT)
        f.setHintTextColor(C_DIM)
        f.setSingleLine()
        f.setBackgroundColor(C_FIELD)
        f.setPadding(dp(8), dp(2), dp(8), dp(2))
        f.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        if (onFilter != null) {
            f.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { onFilter(s?.toString() ?: "") }
            })
        }
        panel.addView(f, lp(MATCH, dp(28)))
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(this)
        sv.addView(list)
        panel.addView(sv, lp(MATCH, 0, 1f))
        return Pair(panel, list)
    }

    private fun buildExplorer() {
        explorerList.removeAllViews()
        val q = explorerFilter.lowercase()
        fun row(indent: Int, chevron: Int?, icon: Int, tint: Int?, text: String, selected: Boolean, onClick: () -> Unit) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(4 + indent * 14), dp(4), dp(4), dp(4))
            if (selected) r.setBackgroundColor(C_SEL)
            if (chevron != null) {
                r.addView(iv(chevron, 14, C_DIM), lp(dp(14), dp(14)))
            } else {
                r.addView(View(this), lp(dp(14), dp(14)))
            }
            val ic = iv(icon, 16, tint)
            r.addView(ic, lp(dp(16), dp(16)))
            val t = tv(text, 12f)
            t.setPadding(dp(6), 0, 0, 0)
            t.maxLines = 1
            r.addView(t, lp(WRAP, WRAP))
            r.setOnClickListener { onClick() }
            explorerList.addView(r, lp(MATCH, WRAP))
        }
        for ((name, icon) in services) {
            if (name == "Workspace") {
                row(0, if (workspaceOpen) R.drawable.ic_expand else R.drawable.ic_chevron, icon, C_TEXT, "Workspace", false) {
                    workspaceOpen = !workspaceOpen
                    buildExplorer()
                }
                if (workspaceOpen) {
                    row(1, null, R.drawable.ic_camera, C_DIM, "Camera", false) { }
                    row(1, null, R.drawable.ic_terrain, C_DIM, "Terrain", false) { }
                    for (p in Scene.parts) {
                        if (q.isEmpty() || p.name.lowercase().contains(q)) {
                            row(1, null, R.drawable.ic_part, null, p.name, p === Scene.selected) { Editor.select(p) }
                        }
                    }
                }
            } else if (q.isEmpty() || name.lowercase().contains(q)) {
                row(0, null, icon, C_DIM, name, false) { }
            }
        }
    }

    private fun updateProps() {
        for (r in refreshers) r()
    }

    private fun section(title: String) {
        val t = tv(title, 11f, C_TEXT, true)
        t.setBackgroundColor(C_HDR)
        t.setPadding(dp(8), dp(2), dp(8), dp(2))
        propsList.addView(t, lp(MATCH, WRAP))
    }

    private fun addRow(label: String, v: View) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(8), dp(1), dp(6), dp(1))
        row.addView(tv(label, 11f), lp(0, WRAP, 1f))
        row.addView(v, lp(dp(84), dp(26)))
        propsList.addView(row, lp(MATCH, dp(30)))
    }

    private fun field(init: String, numeric: Boolean, onCommit: (String) -> Unit): EditText {
        val et = EditText(this)
        et.setText(init)
        et.textSize = 11f
        et.setTextColor(C_TEXT)
        et.setSingleLine()
        if (numeric) {
            et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        et.setBackgroundColor(C_FIELD)
        et.setPadding(dp(4), dp(2), dp(4), dp(2))
        et.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        et.setOnEditorActionListener { v, _, _ ->
            onCommit(v.text.toString())
            v.clearFocus()
            false
        }
        et.setOnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) onCommit((v as EditText).text.toString())
        }
        return et
    }

    private fun numRow(label: String, get: () -> Float, set: (Float) -> Unit) {
        val et = field(fmt(get()), true) { s ->
            val v = s.toFloatOrNull()
            if (v != null && abs(v - get()) > 1e-4f) {
                History.checkpoint()
                set(v)
                Editor.onChanged?.invoke(false)
            }
        }
        refreshers.add { if (!et.hasFocus()) et.setText(fmt(get())) }
        addRow(label, et)
    }

    private fun buildProps() {
        propsList.removeAllViews()
        refreshers.clear()
        val p = Scene.selected
        if (p == null) {
            val t = tv("Nada selecionado", 11f, C_DIM)
            t.setPadding(dp(8), dp(8), 0, 0)
            propsList.addView(t)
            return
        }
        section("Data")
        val nameEt = field(p.name, false) { s ->
            if (s.isNotEmpty() && s != p.name) {
                History.checkpoint()
                p.name = s
                buildExplorer()
            }
        }
        refreshers.add { if (!nameEt.hasFocus()) nameEt.setText(p.name) }
        addRow("Name", nameEt)

        section("Appearance")
        val sw = View(this)
        fun paint() {
            sw.setBackgroundColor(Color.rgb((p.r * 255).toInt(), (p.g * 255).toInt(), (p.b * 255).toInt()))
        }
        paint()
        sw.setOnClickListener { pickColor() }
        refreshers.add { paint() }
        addRow("Color", sw)

        section("Behavior")
        val cb = CheckBox(this)
        cb.isChecked = p.anchored
        cb.setOnCheckedChangeListener { _, c ->
            if (p.anchored != c) {
                History.checkpoint()
                p.anchored = c
            }
        }
        refreshers.add { cb.isChecked = p.anchored }
        addRow("Anchored", cb)
        val cc = CheckBox(this)
        cc.isChecked = p.canCollide
        cc.setOnCheckedChangeListener { _, c ->
            if (p.canCollide != c) {
                History.checkpoint()
                p.canCollide = c
            }
        }
        refreshers.add { cc.isChecked = p.canCollide }
        addRow("CanCollide", cc)

        section("Part")
        numRow("Position X", { p.x }) { p.x = it }
        numRow("Position Y", { p.y }) { p.y = it }
        numRow("Position Z", { p.z }) { p.z = it }
        for (i in 0..2) {
            val ax = arrayOf("X", "Y", "Z")[i]
            numRow("Orientation " + ax, { p.orientation()[i] }) { v ->
                val o = p.orientation()
                o[i] = v
                p.setOrientation(o[0], o[1], o[2])
            }
        }
        for (i in 0..2) {
            val ax = arrayOf("X", "Y", "Z")[i]
            numRow("Size " + ax, { p.size(i) }) { v -> p.setSize(i, if (v < 0.05f) 0.05f else v) }
        }
    }
}
