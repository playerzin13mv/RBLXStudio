package com.rblxstudio

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Menu de pausa do Play (estilo Roblox): aba Menu e aba Configurações
class GameMenu(private val act: Activity) : FrameLayout(act) {
    private val d = act.resources.displayMetrics.density
    private val content = LinearLayout(act)
    private val tabMenu = TextView(act)
    private val tabCfg = TextView(act)
    private var tab = 0

    var onResume: (() -> Unit)? = null
    var onReset: (() -> Unit)? = null
    var onLeave: (() -> Unit)? = null
    var onChanged: (() -> Unit)? = null

    private fun dp(v: Int) = (v * d).toInt()

    private fun bg(color: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        return g
    }

    init {
        setBackgroundColor(0xAA000000.toInt())
        visibility = View.GONE
        isClickable = true
        setOnClickListener { hide() }

        val card = LinearLayout(act)
        card.orientation = LinearLayout.VERTICAL
        card.background = bg(0xF2232527.toInt(), 12)
        card.isClickable = true

        val tabs = LinearLayout(act)
        tabs.orientation = LinearLayout.HORIZONTAL
        for ((i, t) in listOf(tabMenu, tabCfg).withIndex()) {
            t.text = if (i == 0) "Menu" else "Configurações"
            t.textSize = 13f
            t.gravity = Gravity.CENTER
            t.typeface = Typeface.DEFAULT_BOLD
            t.setPadding(dp(10), dp(10), dp(10), dp(10))
            t.setOnClickListener { setTab(i) }
            tabs.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        card.addView(tabs, LinearLayout.LayoutParams(-1, -2))

        val sv = ScrollView(act)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(14), dp(10), dp(14), dp(14))
        sv.addView(content)
        card.addView(sv, LinearLayout.LayoutParams(-1, -2))

        val lp = LayoutParams(dp(360), -2, Gravity.CENTER)
        lp.topMargin = dp(8)
        lp.bottomMargin = dp(8)
        addView(card, lp)
        setTab(0)
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
        tabMenu.setTextColor(if (i == 0) Color.WHITE else 0xFF9A9A9A.toInt())
        tabCfg.setTextColor(if (i == 1) Color.WHITE else 0xFF9A9A9A.toInt())
        tabMenu.setBackgroundColor(if (i == 0) 0xFF3A3D40.toInt() else 0)
        tabCfg.setBackgroundColor(if (i == 1) 0xFF3A3D40.toInt() else 0)
        content.removeAllViews()
        if (i == 0) buildMenu() else buildConfig()
    }

    private fun button(text: String, color: Int, click: () -> Unit): TextView {
        val b = TextView(act)
        b.text = text
        b.textSize = 14f
        b.setTextColor(Color.WHITE)
        b.typeface = Typeface.DEFAULT_BOLD
        b.gravity = Gravity.CENTER
        b.background = bg(color, 8)
        b.setPadding(dp(10), dp(12), dp(10), dp(12))
        b.setOnClickListener { click() }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(8)
        content.addView(b, lp)
        return b
    }

    private fun buildMenu() {
        button("Retomar", 0xFF2E7D32.toInt()) { hide(); onResume?.invoke() }
        button("Reiniciar personagem", 0xFF3A3D40.toInt()) { hide(); onReset?.invoke() }
        button("Sair do jogo", 0xFFC62828.toInt()) { hide(); onLeave?.invoke() }
    }

    private fun label(text: String) {
        val t = TextView(act)
        t.text = text
        t.textSize = 12f
        t.setTextColor(0xFFB0B0B0.toInt())
        t.setPadding(0, dp(6), 0, dp(4))
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
        t.background = bg(if (on) 0xFF0F6FD8.toInt() else 0xFF3A3D40.toInt(), 8)
        t.setTextColor(if (on) Color.WHITE else 0xFFCCCCCC.toInt())
        t.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun buildConfig() {
        label("Shift Lock")
        choice(listOf("Ligado", "Desligado"), if (Settings.shiftLockSwitch) 0 else 1) {
            Settings.shiftLockSwitch = it == 0
            if (!Settings.shiftLockSwitch) Play.applyShiftLock(false)
        }
        label("Modo da câmera")
        choice(listOf("Seguir", "Clássica"), Settings.camMode) { Settings.camMode = it }
        label("Movimento")
        choice(listOf("Joystick", "D-Pad", "Tocar p/ mover"), Settings.moveMode) { Settings.moveMode = it }
        val tip = TextView(act)
        tip.text = "Seguir: a câmera gira atrás do personagem. Clássica: você gira a câmera com o dedo. " +
            "Shift Lock mostra um botão de cadeado ao lado do pulo."
        tip.textSize = 11f
        tip.setTextColor(0xFF8A8A8A.toInt())
        tip.setPadding(0, dp(10), 0, 0)
        content.addView(tip, LinearLayout.LayoutParams(-1, -2))
    }
}
