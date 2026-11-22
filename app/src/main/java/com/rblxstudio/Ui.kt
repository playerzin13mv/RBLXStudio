package com.rblxstudio

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// ---------- ícones coloridos por tipo de objeto ----------
internal fun classIcon(cls: String): Pair<Int, Int> = when (cls) {
    "Script" -> Pair(R.drawable.ic_script, 0xFF4FC3F7.toInt())
    "LocalScript" -> Pair(R.drawable.ic_script, 0xFF26C6A0.toInt())
    "Sound" -> Pair(R.drawable.ic_volume, 0xFFAB47BC.toInt())
    "ScreenGui", "Frame", "TextLabel", "TextButton", "ImageLabel", "PlayerGui" -> Pair(R.drawable.ic_ui, 0xFF66BB6A.toInt())
    "Tool" -> Pair(R.drawable.ic_toolbox, 0xFFFF8A65.toInt())
    "Backpack" -> Pair(R.drawable.ic_box, 0xFFFFB74D.toInt())
    "Folder", "Model" -> Pair(R.drawable.ic_group, 0xFFFFCA28.toInt())
    "StringValue", "NumberValue", "BoolValue" -> Pair(R.drawable.ic_properties, 0xFFFF8A65.toInt())
    "MeshPart" -> Pair(R.drawable.ic_part, 0xFFFF7043.toInt())
    "Part" -> Pair(R.drawable.ic_part, 0xFFB0BEC5.toInt())
    "Humanoid", "Player" -> Pair(R.drawable.ic_player, 0xFFFFCA28.toInt())
    "StarterPlayerScripts" -> Pair(R.drawable.ic_script, 0xFF4FC3F7.toInt())
    "StarterCharacterScripts" -> Pair(R.drawable.ic_player, 0xFF4DB6AC.toInt())
    else -> Pair(R.drawable.ic_box, 0xFF90A4AE.toInt())
}

private val serviceColors = mapOf(
    "Workspace" to 0xFF42A5F5, "Players" to 0xFF66BB6A, "Lighting" to 0xFFFFEE58,
    "MaterialService" to 0xFFBA68C8, "ReplicatedFirst" to 0xFF4DD0E1, "ReplicatedStorage" to 0xFF4DD0E1,
    "ServerScriptService" to 0xFF4FC3F7, "ServerStorage" to 0xFFFFB74D, "StarterGui" to 0xFF66BB6A,
    "StarterPack" to 0xFFFF8A65, "StarterPlayer" to 0xFFFFCA28, "Teams" to 0xFFEF5350,
    "SoundService" to 0xFFAB47BC, "Chat" to 0xFF9575CD
)

internal fun iconColor(i: Inst): Int {
    if (i.parent === Scene.game) return (serviceColors[i.name] ?: 0xFF90A4AE).toInt()
    return classIcon(i.className).second
}

// ---------- caixa de diálogo no estilo do Editor ----------
class StyledDialog(private val act: Activity) {
    private val d = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private var title = ""
    private var message: String? = null
    private var content: View? = null
    private var items: Array<String>? = null
    private var icons: List<Pair<Int, Int>>? = null
    private var onItem: ((DialogInterface, Int) -> Unit)? = null
    private var posT: String? = null
    private var posL: ((DialogInterface, Int) -> Unit)? = null
    private var neuT: String? = null
    private var neuL: ((DialogInterface, Int) -> Unit)? = null
    private var negT: String? = null
    private var negL: ((DialogInterface, Int) -> Unit)? = null

    fun setTitle(t: CharSequence): StyledDialog { title = t.toString(); return this }
    fun setMessage(m: CharSequence): StyledDialog { message = m.toString(); return this }
    fun setView(v: View): StyledDialog { content = v; return this }
    fun setItems(a: Array<String>, l: (DialogInterface, Int) -> Unit): StyledDialog { items = a; onItem = l; return this }
    fun setIcons(l: List<Pair<Int, Int>>): StyledDialog { icons = l; return this }
    fun setPositiveButton(t: CharSequence, l: ((DialogInterface, Int) -> Unit)?): StyledDialog { posT = t.toString(); posL = l; return this }
    fun setNeutralButton(t: CharSequence, l: ((DialogInterface, Int) -> Unit)?): StyledDialog { neuT = t.toString(); neuL = l; return this }
    fun setNegativeButton(t: CharSequence, l: ((DialogInterface, Int) -> Unit)?): StyledDialog { negT = t.toString(); negL = l; return this }

    private fun round(color: Int, r: Int, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        if (stroke != 0) g.setStroke(dp(1), stroke)
        return g
    }

    private fun button(dlg: Dialog, text: String, filled: Boolean, l: ((DialogInterface, Int) -> Unit)?): TextView {
        val b = TextView(act)
        b.text = text
        b.textSize = 13f
        b.typeface = Typeface.DEFAULT_BOLD
        b.gravity = Gravity.CENTER
        b.setTextColor(if (filled) Color.WHITE else C_TEXT)
        b.background = round(if (filled) C_SEL else 0xFF3C3C3C.toInt(), 6)
        b.setPadding(dp(16), dp(9), dp(16), dp(9))
        b.setOnClickListener {
            dlg.dismiss()
            l?.invoke(dlg, 0)
        }
        return b
    }

    fun show(): Dialog {
        val dlg = Dialog(act)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val screen = act.resources.displayMetrics
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.background = round(C_PANEL, 10, 0xFF4A4A4A.toInt())
        root.clipToOutline = true

        // cabeçalho (igual aos painéis do Editor)
        val hdr = LinearLayout(act)
        hdr.orientation = LinearLayout.HORIZONTAL
        hdr.gravity = Gravity.CENTER_VERTICAL
        hdr.setBackgroundColor(C_HDR)
        val bar = View(act)
        bar.setBackgroundColor(C_SEL)
        hdr.addView(bar, LinearLayout.LayoutParams(dp(4), dp(24)).also { it.leftMargin = dp(12) })
        val t = TextView(act)
        t.text = title
        t.textSize = 15f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(C_TEXT)
        t.setPadding(dp(10), dp(12), dp(14), dp(12))
        hdr.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(hdr, LinearLayout.LayoutParams(-1, -2))

        // corpo rolável
        val body = LinearLayout(act)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(14), dp(12), dp(14), dp(4))
        val msg = message
        if (msg != null) {
            val m = TextView(act)
            m.text = msg
            m.textSize = 13f
            m.setTextColor(C_TEXT)
            m.setPadding(0, 0, 0, dp(8))
            body.addView(m, LinearLayout.LayoutParams(-1, -2))
        }
        val cv = content
        if (cv != null) {
            (cv.parent as? ViewGroup)?.removeView(cv)
            body.addView(cv, LinearLayout.LayoutParams(-1, -2))
        }
        val its = items
        if (its != null) {
            for ((idx, name) in its.withIndex()) {
                val row = LinearLayout(act)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.background = round(0xFF333333.toInt(), 6)
                row.setPadding(dp(10), dp(9), dp(10), dp(9))
                val ic = icons?.getOrNull(idx)
                if (ic != null) {
                    val box = FrameLayout(act)
                    box.background = round((ic.second and 0x00FFFFFF) or 0x33000000, 6)
                    val iv = ImageView(act)
                    iv.setImageResource(ic.first)
                    iv.setColorFilter(ic.second)
                    box.addView(iv, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                    row.addView(box, LinearLayout.LayoutParams(dp(32), dp(32)))
                }
                val tx = TextView(act)
                tx.text = name
                tx.textSize = 14f
                tx.setTextColor(C_TEXT)
                tx.setPadding(dp(if (ic != null) 12 else 4), 0, 0, 0)
                row.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
                row.setOnClickListener {
                    dlg.dismiss()
                    onItem?.invoke(dlg, idx)
                }
                val lp = LinearLayout.LayoutParams(-1, -2)
                lp.bottomMargin = dp(6)
                body.addView(row, lp)
            }
        }
        val sv = ScrollView(act)
        sv.addView(body)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        // botões
        if (posT != null || neuT != null || negT != null) {
            val foot = LinearLayout(act)
            foot.orientation = LinearLayout.HORIZONTAL
            foot.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            foot.setPadding(dp(12), dp(6), dp(12), dp(12))
            val nt = neuT
            if (nt != null) {
                foot.addView(button(dlg, nt, false, neuL), LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = dp(8) })
                foot.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            }
            val ng = negT
            if (ng != null) foot.addView(button(dlg, ng, false, negL), LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = dp(8) })
            val ps = posT
            if (ps != null) foot.addView(button(dlg, ps, true, posL), LinearLayout.LayoutParams(-2, -2))
            root.addView(foot, LinearLayout.LayoutParams(-1, -2))
        } else {
            root.addView(View(act), LinearLayout.LayoutParams(-1, dp(8)))
        }

        val w = minOf((screen.widthPixels * 0.9f).toInt(), dp(440))
        val maxH = (screen.heightPixels * 0.88f).toInt()
        dlg.setContentView(root, ViewGroup.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.window?.setDimAmount(0.55f)
        dlg.show()
        root.post {
            if (root.height > maxH) {
                val p = root.layoutParams
                p.height = maxH
                root.layoutParams = p
            }
        }
        return dlg
    }
}
