package com.rblxstudio

import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

internal val serviceIcons = mapOf(
    "Workspace" to R.drawable.ic_globe, "Players" to R.drawable.ic_people,
    "Lighting" to R.drawable.ic_bulb, "MaterialService" to R.drawable.ic_material,
    "ReplicatedFirst" to R.drawable.ic_box, "ReplicatedStorage" to R.drawable.ic_box,
    "ServerScriptService" to R.drawable.ic_script, "ServerStorage" to R.drawable.ic_storage,
    "StarterGui" to R.drawable.ic_ui, "StarterPack" to R.drawable.ic_toolbox,
    "StarterPlayer" to R.drawable.ic_player, "Teams" to R.drawable.ic_flag,
    "SoundService" to R.drawable.ic_volume
)

// ---------- painéis ----------
internal fun MainActivity.makePanel(
    title: String, hint: String, onFilter: ((String) -> Unit)?, onAdd: (() -> Unit)?
): Pair<LinearLayout, LinearLayout> {
    val panel = LinearLayout(this)
    panel.orientation = LinearLayout.VERTICAL
    panel.setBackgroundColor(C_PANEL)

    val hdr = LinearLayout(this)
    hdr.orientation = LinearLayout.HORIZONTAL
    hdr.gravity = Gravity.CENTER_VERTICAL
    hdr.setBackgroundColor(C_HDR)
    val h = tv(title, 12f, C_TEXT, true)
    h.setPadding(dp(8), dp(4), dp(8), dp(4))
    hdr.addView(h, lp(0, WRAP, 1f))
    if (onAdd != null) {
        val plus = tv("+", 20f, C_TEXT, true)
        plus.setPadding(dp(14), 0, dp(14), 0)
        plus.setOnClickListener { onAdd() }
        hdr.addView(plus, lp(WRAP, WRAP))
    }
    panel.addView(hdr, lp(MATCH, WRAP))

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

internal fun iconFor(i: Inst): Int = when {
    i is PartData -> R.drawable.ic_part
    i.className == "Script" || i.className == "LocalScript" -> R.drawable.ic_script
    i.className == "Sound" -> R.drawable.ic_volume
    Defaults.isGui(i.className) || i.className == "PlayerGui" -> R.drawable.ic_ui
    i.className == "Player" || i.className == "Humanoid" -> R.drawable.ic_player
    i.parent === Scene.game -> serviceIcons[i.name] ?: R.drawable.ic_box
    else -> R.drawable.ic_box
}

private fun allDesc(i: Inst, out: MutableList<Inst>) {
    for (c in Scene.kids(i)) {
        out.add(c)
        allDesc(c, out)
    }
}

internal fun MainActivity.buildExplorer() {
    explorerList.removeAllViews()
    val q = explorerFilter.lowercase()

    fun row(
        indent: Int, chevron: Int?, icon: Int, tint: Int?, text: String, selected: Boolean,
        onChev: (() -> Unit)?, onClick: () -> Unit, onLong: (() -> Unit)?
    ) {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(4 + indent * 14), dp(4), dp(4), dp(4))
        if (selected) r.setBackgroundColor(C_SEL)
        if (chevron != null) {
            val c = iv(chevron, 14, C_DIM)
            if (onChev != null) c.setOnClickListener { onChev() }
            r.addView(c, lp(dp(14), dp(14)))
        } else {
            r.addView(View(this), lp(dp(14), dp(14)))
        }
        r.addView(iv(icon, 16, tint), lp(dp(16), dp(16)))
        val t = tv(text, 12f)
        t.setPadding(dp(6), 0, 0, 0)
        t.maxLines = 1
        r.addView(t, lp(WRAP, WRAP))
        r.setOnClickListener { onClick() }
        if (onLong != null) r.setOnLongClickListener { onLong(); true }
        explorerList.addView(r, lp(MATCH, WRAP))
    }

    fun tint(i: Inst): Int? = if (i is PartData) null else C_DIM
    fun isScript(i: Inst) = i.className == "Script" || i.className == "LocalScript"

    fun node(i: Inst, depth: Int) {
        val ks = Scene.kids(i)
        val isOpen = open.contains(i)
        val hasKids = ks.isNotEmpty() || i === Scene.workspace
        row(
            depth,
            if (hasKids) (if (isOpen) R.drawable.ic_expand else R.drawable.ic_chevron) else null,
            iconFor(i), tint(i), i.name, i === Scene.selObj,
            {
                if (isOpen) open.remove(i) else open.add(i)
                buildExplorer()
            },
            { pick(i) },
            if (isScript(i)) ({ editScript(i) }) else null
        )
        if (isOpen) {
            if (i === Scene.workspace) {
                row(depth + 1, null, R.drawable.ic_camera, C_DIM, "Camera", false, null, { }, null)
                row(depth + 1, null, R.drawable.ic_terrain, C_DIM, "Terrain", false, null, { }, null)
            }
            for (c in ks) node(c, depth + 1)
        }
    }

    if (q.isEmpty()) {
        for (s in Scene.services.values) node(s, 0)
    } else {
        for (s in Scene.services.values) {
            if (s.name.lowercase().contains(q)) {
                row(0, null, iconFor(s), C_DIM, s.name, s === Scene.selObj, null, { pick(s) }, null)
            }
            val all = ArrayList<Inst>()
            allDesc(s, all)
            for (c in all) {
                if (c.name.lowercase().contains(q) || c.className.lowercase().contains(q)) {
                    row(1, null, iconFor(c), tint(c), c.name, c === Scene.selObj, null, { pick(c) },
                        if (isScript(c)) ({ editScript(c) }) else null)
                }
            }
        }
    }
}

internal fun MainActivity.updateProps() {
    for (r in refreshers) r()
}

internal fun MainActivity.section(title: String) {
    val t = tv(title, 11f, C_TEXT, true)
    t.setBackgroundColor(C_HDR)
    t.setPadding(dp(8), dp(2), dp(8), dp(2))
    propsList.addView(t, lp(MATCH, WRAP))
}

internal fun MainActivity.addRow(label: String, v: View) {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(dp(8), dp(1), dp(6), dp(1))
    row.addView(tv(label, 11f), lp(0, WRAP, 1f))
    row.addView(v, lp(dp(84), dp(26)))
    propsList.addView(row, lp(MATCH, dp(30)))
}

internal fun MainActivity.field(init: String, numeric: Boolean, onCommit: (String) -> Unit): EditText {
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

internal fun MainActivity.numRow(label: String, get: () -> Float, set: (Float) -> Unit) {
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

// campo de texto simples ligado a uma propriedade (props) de qualquer objeto
internal val imageKeys = setOf("Texture", "TextureID", "Image")

internal fun MainActivity.propRow(o: Inst, key: String) {
    val et = field(o.prop(key), false) { s ->
        if (o.prop(key) != s) {
            o.props[key] = s
            Editor.onChanged?.invoke(false)
        }
    }
    refreshers.add {
        if (!et.hasFocus()) {
            val v = o.prop(key)
            if (et.text.toString() != v) et.setText(v)
        }
    }
    addRow(key, et)
    if (key in imageKeys) {
        val b = tv("Galeria", 11f, C_SEL, true)
        b.setOnClickListener { pickImage(o, key) }
        addRow("   escolher do celular", b)
    }
}

internal fun MainActivity.buildProps() {
    propsList.removeAllViews()
    refreshers.clear()
    val o = Scene.selObj
    if (o == null) {
        val t = tv("Nada selecionado", 11f, C_DIM)
        t.setPadding(dp(8), dp(8), 0, 0)
        propsList.addView(t)
        return
    }
    if (o !is PartData) {
        buildObjProps(o)
        return
    }
    val p: PartData = o
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
            val ori = p.orientation()
            ori[i] = v
            p.setOrientation(ori[0], ori[1], ori[2])
        }
    }
    for (i in 0..2) {
        val ax = arrayOf("X", "Y", "Z")[i]
        numRow("Size " + ax, { p.size(i) }) { v -> p.setSize(i, if (v < 0.05f) 0.05f else v) }
    }

    section("Texture")
    propRow(p, "Texture")
    propRow(p, "StudsPerTile")
    val hint = tv("Texture: ID, link ou imagem da galeria. StudsPerTile vazio = estica na face; 4 = repete a cada 4 studs.", 10f, C_DIM)
    hint.setPadding(dp(8), dp(2), dp(8), dp(4))
    propsList.addView(hint, lp(MATCH, WRAP))

    section("Physics")
    numRow("Friction", { p.friction }) { p.friction = it.coerceIn(0f, 2f) }
    numRow("Elasticity", { p.elasticity }) { p.elasticity = it.coerceIn(0f, 1f) }

    if (p.className == "MeshPart") {
        section("Mesh")
        propRow(p, "MeshId")
        propRow(p, "TextureID")
    }
}

// propriedades de objetos que não são peças (Script, Sound, Frame, TextLabel...)
internal fun MainActivity.buildObjProps(o: Inst) {
    section("Data")
    if (o.parent === Scene.game) {
        addRow("Name", tv(o.name, 11f, C_DIM))
    } else {
        addRow("Name", field(o.name, false) { s ->
            if (s.isNotEmpty() && s != o.name) {
                o.name = s
                buildExplorer()
            }
        })
    }
    addRow("ClassName", tv(o.className, 11f, C_DIM))

    val keys = Defaults.keys(o.className)
    if (keys.isEmpty()) return
    section(o.className)
    for (k in keys) {
        if (k == "Source") {
            val b = tv("Editar", 11f, C_SEL, true)
            b.setOnClickListener { editScript(o) }
            addRow("Source", b)
        } else if (k in Defaults.boolKeys) {
            val cb = CheckBox(this)
            cb.isChecked = o.prop(k, "false") == "true"
            cb.setOnCheckedChangeListener { _, c -> o.props[k] = if (c) "true" else "false" }
            addRow(k, cb)
        } else {
            propRow(o, k)
        }
    }
}