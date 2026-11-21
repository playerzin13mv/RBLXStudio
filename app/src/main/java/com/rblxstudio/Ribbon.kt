package com.rblxstudio

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout

internal val palette = listOf(
    "Medium stone grey" to 0xA3A2A5, "Bright red" to 0xC4281C, "Bright blue" to 0x0D69AC,
    "Bright yellow" to 0xF5CD30, "Bright green" to 0x4B974B, "Bright orange" to 0xDA8541,
    "Hot pink" to 0xFF66CC, "Cyan" to 0x04AFEC, "Royal purple" to 0x6B327C,
    "Reddish brown" to 0x694028, "White" to 0xF2F3F3, "Really black" to 0x111111
)

internal fun MainActivity.rbtn(icon: Int, label: String, tint: Int? = C_TEXT, onClick: () -> Unit): LinearLayout {
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

internal fun MainActivity.toolBtn(t: Tool, icon: Int, label: String): View {
    val b = rbtn(icon, label) {
        Editor.tool = t
        refreshTools()
    }
    toolButtons[t] = b
    return b
}

internal fun MainActivity.refreshTools() {
    for ((t, v) in toolButtons) v.setBackgroundColor(if (t == Editor.tool) C_SEL else 0)
}

internal fun MainActivity.group(label: String, vararg items: View) {
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

internal fun MainActivity.check(text: String, init: Boolean, on: (Boolean) -> Unit): CheckBox {
    val c = CheckBox(this)
    c.text = text
    c.textSize = 11f
    c.setTextColor(C_TEXT)
    c.isChecked = init
    c.setOnCheckedChangeListener { _, v -> on(v) }
    return c
}

internal fun MainActivity.toggleView(v: View) {
    v.visibility = if (v.visibility == View.VISIBLE) View.GONE else View.VISIBLE
}

internal fun MainActivity.pickColor() {
    if (Scene.selected == null) {
        toast("Selecione uma peça")
        return
    }
    StyledDialog(this).setTitle("Color")
        .setItems(palette.map { it.first }.toTypedArray()) { _, i ->
            val c = palette[i].second
            Editor.setColor(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
        }.show()
}

internal fun MainActivity.buildRibbon(tab: String) {
    ribbon.removeAllViews()
    toolButtons.clear()
    val play = { rbtn(R.drawable.ic_play, "Play", C_GREEN) { startPlay(false) } }
    val playHere = { rbtn(R.drawable.ic_play, "Play Here", C_GREEN) { startPlay(true) } }
    val stop = { rbtn(R.drawable.ic_stop, "Stop", C_RED) { stopPlay() } }
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
                rbtn(R.drawable.ic_script, "Object") { insertMenu(Defaults.classes) },
                rbtn(R.drawable.ic_ui, "UI") { insertMenu(listOf("ScreenGui", "Frame", "TextLabel", "TextButton", "ImageLabel")) },
                rbtn(R.drawable.ic_import, "Import 3D") { importMesh() })
            group("Edit",
                rbtn(R.drawable.ic_color, "Color") { pickColor() },
                rbtn(R.drawable.ic_material, "Material") { soon() },
                rbtn(R.drawable.ic_group, "Group") { soon() },
                rbtn(R.drawable.ic_lock, "Lock Tool") { soon() },
                rbtn(R.drawable.ic_anchor, "Anchor") { Editor.toggleAnchor() },
                rbtn(R.drawable.ic_delete, "Delete") { deleteSel() })
            group("Test", play(), playHere(), stop())
            group("Settings", rbtn(R.drawable.ic_settings, "Game Settings") { soon() })
        }
        "TEST" -> group("Test", play(), playHere(), stop())
        "VIEW" -> group("Show",
            rbtn(R.drawable.ic_explorer, "Explorer") { toggleView(explorerPanel) },
            rbtn(R.drawable.ic_properties, "Properties") { toggleView(propsPanel) },
            rbtn(R.drawable.ic_script, "Output") {
                outText.text = Out.text(14)
                toggleView(outText)
            },
            rbtn(R.drawable.ic_focus, "Focus") { Editor.focus() })
        else -> {
            val t = tv("Em breve", 12f, C_DIM)
            t.setPadding(dp(16), dp(20), 0, 0)
            ribbon.addView(t)
        }
    }
    refreshTools()
}