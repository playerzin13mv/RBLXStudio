package com.rblxstudio

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// Menu do Roblox durante o Play (versão antiga): abas People, Settings, Report, Help e Record,
// e embaixo os botões Leave, Reset Character e Resume
class GameMenu(private val act: Activity) : FrameLayout(act) {
    private val d = act.resources.displayMetrics.density
    private val content = LinearLayout(act)
    private val tabCells = ArrayList<LinearLayout>()
    private val tabIcons = ArrayList<ImageView>()
    private val tabTexts = ArrayList<TextView>()
    private val tabBars = ArrayList<View>()
    private var tab = 0

    var onResume: (() -> Unit)? = null
    var onReset: (() -> Unit)? = null
    var onLeave: (() -> Unit)? = null
    var onChanged: (() -> Unit)? = null

    private fun dp(v: Int) = (v * d).toInt()

    private fun bg(color: Int, radius: Int, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        if (stroke != 0) g.setStroke(dp(1), stroke)
        return g
    }

    private fun icon(res: Int, size: Int, tint: Int): ImageView {
        val v = ImageView(act)
        v.setImageResource(res)
        v.setColorFilter(tint)
        v.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        return v
    }

    private fun toast(t: String) {
        Toast.makeText(act, t, Toast.LENGTH_SHORT).show()
    }

    init {
        setBackgroundColor(0x99000000.toInt())
        visibility = View.GONE
        isClickable = true
        setOnClickListener { hide() }

        val dm = act.resources.displayMetrics
        val cardW = minOf(dm.widthPixels - dp(32), dp(540))
        val cardH = minOf((dm.heightPixels * 0.62f).toInt(), dp(300))

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL

        val card = LinearLayout(act)
        card.orientation = LinearLayout.VERTICAL
        card.background = bg(0xF21E2022.toInt(), 10)
        card.isClickable = true

        // abas
        val tabs = LinearLayout(act)
        tabs.orientation = LinearLayout.HORIZONTAL
        val names = listOf("People", "Settings", "Report", "Help", "Record")
        val icons = listOf(R.drawable.ic_people, R.drawable.ic_settings, R.drawable.ic_flag, R.drawable.ic_help, R.drawable.ic_record)
        for (i in names.indices) {
            val cell = LinearLayout(act)
            cell.orientation = LinearLayout.VERTICAL
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER
            row.setPadding(dp(4), dp(12), dp(4), dp(10))
            val ic = icon(icons[i], 18, 0xFF9A9C9E.toInt())
            val tx = TextView(act)
            tx.text = names[i]
            tx.textSize = 12f
            tx.typeface = Typeface.DEFAULT_BOLD
            tx.setPadding(dp(5), 0, 0, 0)
            tx.maxLines = 1
            row.addView(ic)
            row.addView(tx, LinearLayout.LayoutParams(-2, -2))
            val bar = View(act)
            cell.addView(row, LinearLayout.LayoutParams(-1, -2))
            cell.addView(bar, LinearLayout.LayoutParams(-1, dp(2)))
            cell.setOnClickListener { setTab(i) }
            tabCells.add(cell)
            tabIcons.add(ic)
            tabTexts.add(tx)
            tabBars.add(bar)
            tabs.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        card.addView(tabs, LinearLayout.LayoutParams(-1, -2))
        val line = View(act)
        line.setBackgroundColor(0xFF3A3D40.toInt())
        card.addView(line, LinearLayout.LayoutParams(-1, dp(1)))

        val sv = ScrollView(act)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(12), dp(10), dp(12), dp(12))
        sv.addView(content, FrameLayout.LayoutParams(-1, -2))
        card.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        col.addView(card, LinearLayout.LayoutParams(cardW, cardH))

        // botões de baixo
        val bottom = LinearLayout(act)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER
        bottom.addView(bigBtn(R.drawable.ic_logout, "Leave", 0xFF8E9093.toInt()) { hide(); onLeave?.invoke() })
        bottom.addView(bigBtn(R.drawable.ic_resetchar, "Reset Character", 0xFF8E9093.toInt()) { hide(); onReset?.invoke() })
        bottom.addView(bigBtn(R.drawable.ic_play, "Resume", 0xFF2E9E4A.toInt()) { hide(); onResume?.invoke() })
        val blp = LinearLayout.LayoutParams(-2, -2)
        blp.topMargin = dp(10)
        col.addView(bottom, blp)

        addView(col, LayoutParams(-2, -2, Gravity.CENTER))
        setTab(0)
    }

    private fun bigBtn(res: Int, label: String, color: Int, click: () -> Unit): LinearLayout {
        val b = LinearLayout(act)
        b.orientation = LinearLayout.VERTICAL
        b.gravity = Gravity.CENTER_HORIZONTAL
        b.setPadding(dp(10), 0, dp(10), 0)
        val box = FrameLayout(act)
        box.background = bg(color, 8)
        val ic = ImageView(act)
        ic.setImageResource(res)
        ic.setColorFilter(Color.WHITE)
        box.addView(ic, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        b.addView(box, LinearLayout.LayoutParams(dp(46), dp(46)))
        val t = TextView(act)
        t.text = label
        t.textSize = 10f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(3), 0, 0)
        b.addView(t, LinearLayout.LayoutParams(-2, -2))
        b.setOnClickListener { click() }
        return b
    }

    fun show() {
        setTab(tab)
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
        Settings.save(act)
    }

    private fun setTab(i: Int) {
        tab = i
        for (k in tabCells.indices) {
            val on = k == i
            tabIcons[k].setColorFilter(if (on) Color.WHITE else 0xFF9A9C9E.toInt())
            tabTexts[k].setTextColor(if (on) Color.WHITE else 0xFF9A9C9E.toInt())
            tabBars[k].setBackgroundColor(if (on) Color.WHITE else 0)
        }
        content.removeAllViews()
        when (i) {
            0 -> buildPeople()
            1 -> buildConfig()
            2 -> buildReport()
            3 -> buildHelp()
            else -> buildRecord()
        }
    }

    // ---------- People ----------
    private fun roundIcon(res: Int, click: () -> Unit): ImageView {
        val v = ImageView(act)
        v.setImageResource(res)
        v.setColorFilter(Color.WHITE)
        v.background = bg(0xFF1A1B1D.toInt(), 6)
        v.setPadding(dp(6), dp(6), dp(6), dp(6))
        v.setOnClickListener { click() }
        return v
    }

    private fun buildPeople() {
        val inv = LinearLayout(act)
        inv.orientation = LinearLayout.HORIZONTAL
        inv.gravity = Gravity.CENTER_VERTICAL
        inv.background = bg(0xFF2B2D30.toInt(), 8)
        inv.setPadding(dp(12), dp(10), dp(12), dp(10))
        inv.addView(icon(R.drawable.ic_invite, 18, 0xFFE0E0E0.toInt()))
        val inviteTx = TextView(act)
        inviteTx.text = "Invite friends to join"
        inviteTx.textSize = 12f
        inviteTx.setTextColor(0xFFE8E8E8.toInt())
        inviteTx.setPadding(dp(8), 0, 0, 0)
        inv.addView(inviteTx, LinearLayout.LayoutParams(-2, -2))
        inv.setOnClickListener { toast("Convites só existem no Roblox de verdade") }
        val ilp = LinearLayout.LayoutParams(-1, -2)
        ilp.bottomMargin = dp(6)
        content.addView(inv, ilp)

        for (p in Scene.players.children) {
            if (p.className != "Player") continue
            val local = p === Scene.localPlayer
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = bg(0xFF2B2D30.toInt(), 8)
            row.setPadding(dp(10), dp(8), dp(10), dp(8))

            val img = ImageView(act)
            val av = RobloxAuth.avatar
            if (av != null && local) {
                img.setImageBitmap(av)
            } else {
                img.setImageResource(R.drawable.ic_person)
                img.setColorFilter(Color.WHITE)
            }
            row.addView(img, LinearLayout.LayoutParams(dp(34), dp(34)))

            val names = LinearLayout(act)
            names.orientation = LinearLayout.VERTICAL
            names.setPadding(dp(10), 0, 0, 0)
            val dn = TextView(act)
            dn.text = p.name
            dn.textSize = 13f
            dn.typeface = Typeface.DEFAULT_BOLD
            dn.setTextColor(Color.WHITE)
            dn.maxLines = 1
            names.addView(dn, LinearLayout.LayoutParams(-2, -2))
            val un = TextView(act)
            val uname = if (local) RobloxAuth.username(act) else ""
            un.text = "@" + (if (uname.isNotEmpty()) uname else p.name)
            un.textSize = 11f
            un.setTextColor(0xFF9A9C9E.toInt())
            un.maxLines = 1
            names.addView(un, LinearLayout.LayoutParams(-2, -2))
            row.addView(names, LinearLayout.LayoutParams(0, -2, 1f))

            val ib = LinearLayout.LayoutParams(dp(32), dp(32))
            ib.leftMargin = dp(6)
            row.addView(roundIcon(R.drawable.ic_search) { hide() }, ib)
            if (!local) {
                val ib2 = LinearLayout.LayoutParams(dp(32), dp(32))
                ib2.leftMargin = dp(6)
                row.addView(roundIcon(R.drawable.ic_flag) { toast("Denúncia não é enviada aqui") }, ib2)
                val ib3 = LinearLayout.LayoutParams(dp(32), dp(32))
                ib3.leftMargin = dp(6)
                row.addView(roundIcon(R.drawable.ic_block) { toast("Bloquear não existe no Studio") }, ib3)
                val add = TextView(act)
                add.text = "Add Friend"
                add.textSize = 11f
                add.setTextColor(Color.WHITE)
                add.typeface = Typeface.DEFAULT_BOLD
                add.gravity = Gravity.CENTER
                add.background = bg(0, 6, 0xFFFFFFFF.toInt())
                add.setPadding(dp(10), dp(7), dp(10), dp(7))
                add.setOnClickListener { toast("Pedido de amizade não existe no Studio") }
                val al = LinearLayout.LayoutParams(-2, -2)
                al.leftMargin = dp(6)
                row.addView(add, al)
            }
            val rl = LinearLayout.LayoutParams(-1, -2)
            rl.bottomMargin = dp(6)
            content.addView(row, rl)
        }
    }

    // ---------- Settings ----------
    private fun label(text: String) {
        val t = TextView(act)
        t.text = text
        t.textSize = 12f
        t.setTextColor(0xFFB8B9BB.toInt())
        t.typeface = Typeface.DEFAULT_BOLD
        t.setPadding(0, dp(8), 0, dp(4))
        content.addView(t, LinearLayout.LayoutParams(-1, -2))
    }

    // linha de opções (só uma fica marcada)
    private fun choice(options: List<String>, selected: Int, pick: (Int) -> Unit) {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        val views = ArrayList<TextView>()
        for ((i, o) in options.withIndex()) {
            val t = TextView(act)
            t.text = o
            t.textSize = 12f
            t.gravity = Gravity.CENTER
            t.setPadding(dp(4), dp(10), dp(4), dp(10))
            t.setOnClickListener {
                pick(i)
                for ((k, v) in views.withIndex()) paint(v, k == i)
                onChanged?.invoke()
            }
            views.add(t)
            paint(t, i == selected)
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.rightMargin = if (i < options.size - 1) dp(6) else 0
            row.addView(t, lp)
        }
        content.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun paint(t: TextView, on: Boolean) {
        t.background = bg(if (on) 0xFF335FFF.toInt() else 0xFF2B2D30.toInt(), 8)
        t.setTextColor(if (on) Color.WHITE else 0xFFCCCCCC.toInt())
        t.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun buildConfig() {
        label("Shift Lock Switch")
        choice(listOf("On", "Off"), if (Settings.shiftLockSwitch) 0 else 1) {
            Settings.shiftLockSwitch = it == 0
            if (!Settings.shiftLockSwitch) Play.applyShiftLock(false)
        }
        label("Camera Mode")
        choice(listOf("Follow", "Classic"), Settings.camMode) { Settings.camMode = it }
        label("Camera View")
        choice(listOf("Third person", "First person"), if (Settings.firstPerson) 1 else 0) { Settings.firstPerson = it == 1 }
        label("Movement Mode")
        choice(listOf("Joystick", "D-Pad", "Tap to move"), Settings.moveMode) { Settings.moveMode = it }
        note("Follow: a câmera gira atrás do personagem. Classic: você gira a câmera com o dedo. " +
            "Pinça com dois dedos para zoom: bem perto vira primeira pessoa.")
    }

    private fun note(text: String) {
        val tip = TextView(act)
        tip.text = text
        tip.textSize = 11f
        tip.setTextColor(0xFF8A8C8E.toInt())
        tip.setPadding(0, dp(10), 0, 0)
        content.addView(tip, LinearLayout.LayoutParams(-1, -2))
    }

    // ---------- Report / Help / Record ----------
    private fun title(text: String) {
        val t = TextView(act)
        t.text = text
        t.textSize = 14f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(Color.WHITE)
        t.setPadding(0, dp(2), 0, dp(6))
        content.addView(t, LinearLayout.LayoutParams(-1, -2))
    }

    private fun buildReport() {
        title("Report Abuse")
        note("Aqui não existe denúncia de verdade: é só o Studio testando o seu jogo. " +
            "O chat já esconde palavrões trocando por #.")
    }

    private fun buildHelp() {
        title("Controls")
        helpRow("Move", "Joystick à esquerda (ou D-Pad / tocar na tela, em Settings)")
        helpRow("Jump", "Botão com a seta para cima")
        helpRow("Camera", "Arraste o dedo no lado direito; pinça para zoom")
        helpRow("Shift Lock", "Botão do cadeado, ao lado do pulo")
        helpRow("Chat", "Botão do balão no canto superior esquerdo")
    }

    private fun helpRow(a: String, b: String) {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.background = bg(0xFF2B2D30.toInt(), 8)
        row.setPadding(dp(10), dp(9), dp(10), dp(9))
        val ta = TextView(act)
        ta.text = a
        ta.textSize = 12f
        ta.typeface = Typeface.DEFAULT_BOLD
        ta.setTextColor(Color.WHITE)
        row.addView(ta, LinearLayout.LayoutParams(dp(80), -2))
        val tb = TextView(act)
        tb.text = b
        tb.textSize = 12f
        tb.setTextColor(0xFFC8C9CB.toInt())
        row.addView(tb, LinearLayout.LayoutParams(0, -2, 1f))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(6)
        content.addView(row, lp)
    }

    private fun buildRecord() {
        title("Record")
        note("Gravar vídeo e tirar print ainda não foram feitos neste Studio. " +
            "Use o gravador de tela do próprio celular.")
    }
}
