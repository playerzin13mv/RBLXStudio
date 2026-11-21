package com.rblxstudio

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
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

class MainActivity : Activity() {
    private lateinit var gl: GLSurfaceView
    private val renderer = Renderer()
    private lateinit var ribbon: LinearLayout
    private lateinit var explorerList: LinearLayout
    private lateinit var propsList: LinearLayout
    private lateinit var explorerPanel: View
    private lateinit var propsPanel: View
    private val toolButtons = HashMap<Tool, View>()
    private val tabViews = LinkedHashMap<String, TextView>()
    private val refreshers = ArrayList<() -> Unit>()
    private var currentTab = "HOME"
    private var workspaceOpen = true
    private var explorerFilter = ""

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

    private val services = listOf(
        "Workspace" to "🌐", "Players" to "👥", "Lighting" to "💡", "MaterialService" to "🧱",
        "ReplicatedFirst" to "📦", "ReplicatedStorage" to "📦", "ServerScriptService" to "📜",
        "ServerStorage" to "🗄", "StarterGui" to "🖥", "StarterPack" to "🎒",
        "StarterPlayer" to "🧍", "Teams" to "🚩", "SoundService" to "🔊"
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
        root.addView(buildTabBar(), lp(MATCH, dp(30)))

        val scroll = HorizontalScrollView(this)
        scroll.setBackgroundColor(C_RIBBON)
        scroll.isHorizontalScrollBarEnabled = false
        ribbon = LinearLayout(this)
        ribbon.orientation = LinearLayout.HORIZONTAL
        scroll.addView(ribbon)
        root.addView(scroll, lp(MATCH, dp(74)))

        val middle = LinearLayout(this)
        middle.orientation = LinearLayout.HORIZONTAL
        middle.addView(gl, lp(0, MATCH, 1f))

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

        val cmd = tv("  ›  Run a command", 11f, C_DIM)
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
    }

    override fun onResume() { super.onResume(); gl.onResume() }
    override fun onPause() { super.onPause(); gl.onPause() }

    // ---------- toque no viewport ----------
    private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    private fun glTouch(e: MotionEvent): Boolean {
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
        val file = tv("File", 12f, Color.WHITE, true)
        file.gravity = Gravity.CENTER
        file.setBackgroundColor(C_SEL)
        file.setPadding(dp(18), 0, dp(18), 0)
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
        return bar
    }

    private fun styleTabs() {
        for ((k, v) in tabViews) {
            v.setTextColor(if (k == currentTab) Color.WHITE else C_DIM)
            v.setBackgroundColor(if (k == currentTab) C_RIBBON else 0)
        }
    }

    private fun fileMenu() {
        AlertDialog.Builder(this).setTitle("File")
            .setItems(arrayOf("Novo lugar", "Salvar (em breve)", "Publicar no Roblox (em breve)")) { _, i ->
                if (i == 0) {
                    Scene.reset()
                    Editor.select(null)
                } else soon()
            }.show()
    }

    // ---------- ribbon ----------
    private fun rbtn(icon: String, label: String, onClick: () -> Unit): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        b.gravity = Gravity.CENTER
        b.setPadding(dp(8), dp(2), dp(8), dp(2))
        val i = tv(icon, 18f)
        i.gravity = Gravity.CENTER
        val l = tv(label, 10f)
        l.gravity = Gravity.CENTER
        b.addView(i, lp(WRAP, WRAP))
        b.addView(l, lp(WRAP, WRAP))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun toolBtn(t: Tool, icon: String, label: String): View {
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

    private fun buildRibbon(tab: String) {
        ribbon.removeAllViews()
        toolButtons.clear()
        when (tab) {
            "HOME" -> {
                group("Clipboard",
                    rbtn("📋", "Paste") { Editor.paste() },
                    rbtn("⧉", "Copy") { Editor.copy() },
                    rbtn("✂", "Cut") { Editor.cut() },
                    rbtn("❐", "Duplicate") { Editor.duplicate() })
                val snap = LinearLayout(this)
                snap.orientation = LinearLayout.VERTICAL
                snap.gravity = Gravity.CENTER_VERTICAL
                snap.addView(check("Rotate 15°", Editor.snapRot) { Editor.snapRot = it })
                snap.addView(check("Move 1 stud", Editor.snapMove) { Editor.snapMove = it })
                group("Tools",
                    toolBtn(Tool.SELECT, "↖", "Select"),
                    toolBtn(Tool.MOVE, "✥", "Move"),
                    toolBtn(Tool.SCALE, "⤢", "Scale"),
                    toolBtn(Tool.ROTATE, "⟳", "Rotate"),
                    snap)
                group("Terrain", rbtn("⛰", "Editor") { soon() })
                group("Insert",
                    rbtn("🧰", "Toolbox") { soon() },
                    rbtn("⬛", "Part") { Editor.addPart() },
                    rbtn("🖥", "UI") { soon() },
                    rbtn("🌐", "Import 3D") { soon() })
                group("Edit",
                    rbtn("🎨", "Color") { pickColor() },
                    rbtn("🧱", "Material") { soon() },
                    rbtn("▤", "Group") { soon() },
                    rbtn("🔒", "Lock Tool") { soon() },
                    rbtn("⚓", "Anchor") { Editor.toggleAnchor() },
                    rbtn("🗑", "Delete") { Editor.delete() })
                group("Test",
                    rbtn("▶", "Play") { toast("Play próprio: etapa 3") },
                    rbtn("■", "Stop") { })
                group("Settings", rbtn("⚙", "Game Settings") { soon() })
            }
            "TEST" -> group("Test",
                rbtn("▶", "Play") { toast("Play próprio: etapa 3") },
                rbtn("■", "Stop") { })
            "VIEW" -> group("Show",
                rbtn("🗂", "Explorer") { toggle(explorerPanel) },
                rbtn("📋", "Properties") { toggle(propsPanel) })
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
        fun row(indent: Int, text: String, selected: Boolean, onClick: () -> Unit) {
            val t = tv(text, 12f)
            t.setPadding(dp(6 + indent * 14), dp(4), dp(4), dp(4))
            if (selected) t.setBackgroundColor(C_SEL)
            t.setOnClickListener { onClick() }
            explorerList.addView(t, lp(MATCH, WRAP))
        }
        for ((name, icon) in services) {
            if (name == "Workspace") {
                row(0, (if (workspaceOpen) "▾ " else "▸ ") + icon + " Workspace", false) {
                    workspaceOpen = !workspaceOpen
                    buildExplorer()
                }
                if (workspaceOpen) {
                    row(1, "    🎥 Camera", false) { }
                    row(1, "    ⛰ Terrain", false) { }
                    for (p in Scene.parts) {
                        if (q.isEmpty() || p.name.lowercase().contains(q)) {
                            row(1, "    ▣ " + p.name, p === Scene.selected) { Editor.select(p) }
                        }
                    }
                }
            } else if (q.isEmpty() || name.lowercase().contains(q)) {
                row(0, "    " + icon + " " + name, false) { }
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
            if (v != null) {
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
        cb.setOnCheckedChangeListener { _, c -> p.anchored = c }
        refreshers.add { cb.isChecked = p.anchored }
        addRow("Anchored", cb)

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
