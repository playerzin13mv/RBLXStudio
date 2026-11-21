package com.rblxstudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Tela inicial estilo Roblox Studio: aba New (templates) e aba Recent (lugares salvos)
class MainMenu(private val act: MainActivity) : FrameLayout(act) {
    private val body = FrameLayout(act)
    private val navItems = LinkedHashMap<String, TextView>()
    private val closeBtn: TextView

    init {
        isClickable = true
        setBackgroundColor(0xFF232527.toInt())

        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL

        val side = LinearLayout(act)
        side.orientation = LinearLayout.VERTICAL
        side.setBackgroundColor(C_BAR)
        side.setPadding(0, act.dp(14), 0, 0)

        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(act.dp(14), 0, act.dp(8), act.dp(14))
        head.addView(act.iv(R.drawable.logo, 24, null))
        val title = act.tv("Studio", 14f, Color.WHITE, true)
        title.setPadding(act.dp(8), 0, 0, 0)
        head.addView(title, act.lp(WRAP, WRAP))
        side.addView(head, act.lp(MATCH, WRAP))

        for (n in listOf("New", "Recent")) {
            val t = act.tv(n, 13f, C_DIM, true)
            t.setPadding(act.dp(18), act.dp(12), act.dp(8), act.dp(12))
            t.setOnClickListener { select(n) }
            navItems[n] = t
            side.addView(t, act.lp(MATCH, WRAP))
        }
        row.addView(side, act.lp(act.dp(140), MATCH))
        row.addView(body, act.lp(0, MATCH, 1f))
        addView(row, FrameLayout.LayoutParams(MATCH, MATCH))

        closeBtn = act.tv("Fechar", 12f, C_TEXT, true)
        closeBtn.setPadding(act.dp(14), act.dp(8), act.dp(14), act.dp(8))
        val cb = GradientDrawable()
        cb.setColor(C_HDR)
        cb.cornerRadius = act.dp(4).toFloat()
        closeBtn.background = cb
        closeBtn.setOnClickListener { hide() }

        select("New")
    }

    fun show() {
        select(navItems.keys.first())
        visibility = View.VISIBLE
        bringToFront()
    }

    fun hide() {
        visibility = View.GONE
    }

    private fun select(n: String) {
        for ((k, v) in navItems) {
            val on = k == n
            v.setTextColor(if (on) Color.WHITE else C_DIM)
            v.setBackgroundColor(if (on) C_SEL else Color.TRANSPARENT)
        }
        body.removeAllViews()
        body.addView(if (n == "New") buildNew() else buildRecent(), FrameLayout.LayoutParams(MATCH, MATCH))
        val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
        lp.setMargins(0, act.dp(10), act.dp(12), 0)
        body.addView(closeBtn, lp)
    }

    private fun page(titleText: String): LinearLayout {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(act.dp(20), act.dp(14), act.dp(20), act.dp(14))
        col.addView(act.tv(titleText, 20f, Color.WHITE, true), act.lp(WRAP, WRAP))
        return col
    }

    // ---------- aba New ----------
    private fun buildNew(): View {
        val col = page("New")
        val tab = act.tv("All Templates", 12f, Color.WHITE, true)
        tab.setPadding(0, act.dp(10), 0, act.dp(4))
        col.addView(tab, act.lp(WRAP, WRAP))
        val line = View(act)
        line.setBackgroundColor(C_SEL)
        col.addView(line, act.lp(act.dp(86), act.dp(2)))

        val cards = LinearLayout(act)
        cards.orientation = LinearLayout.HORIZONTAL
        cards.setPadding(0, act.dp(14), 0, 0)
        cards.addView(card("Baseplate", "Chão com grade e um SpawnLocation", 0), cardLp())
        cards.addView(card("Classic Baseplate", "Chão cinza clássico e um SpawnLocation", 1), cardLp())
        cards.addView(card("Projeto Vazio", "Só o SpawnLocation, sem chão", 2), cardLp())
        col.addView(cards, act.lp(MATCH, WRAP))

        val sv = ScrollView(act)
        sv.addView(col)
        return sv
    }

    private fun cardLp(): LinearLayout.LayoutParams {
        val l = act.lp(0, WRAP, 1f)
        l.setMargins(act.dp(5), 0, act.dp(5), 0)
        return l
    }

    private fun card(name: String, desc: String, kind: Int): View {
        val c = LinearLayout(act)
        c.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(0xFF2F3135.toInt())
        bg.cornerRadius = act.dp(6).toFloat()
        c.background = bg
        c.setPadding(act.dp(6), act.dp(6), act.dp(6), act.dp(10))
        c.addView(Preview(act, kind), act.lp(MATCH, act.dp(96)))
        val t = act.tv(name, 12f, Color.WHITE, true)
        t.setPadding(act.dp(2), act.dp(8), 0, 0)
        c.addView(t, act.lp(MATCH, WRAP))
        val d = act.tv(desc, 10f, C_DIM)
        d.setPadding(act.dp(2), act.dp(2), 0, 0)
        c.addView(d, act.lp(MATCH, WRAP))
        c.setOnClickListener { act.openTemplate(name) }
        return c
    }

    // ---------- aba Recent ----------
    private fun buildRecent(): View {
        val col = page("Recent")
        col.setPadding(act.dp(20), act.dp(14), act.dp(20), act.dp(14))
        val names = Storage.list(act)
        if (names.isEmpty()) {
            val e = act.tv("Nenhum lugar salvo ainda", 12f, C_DIM)
            e.setPadding(0, act.dp(14), 0, 0)
            col.addView(e, act.lp(WRAP, WRAP))
        }
        for (n in names) {
            val r = act.tv(n, 13f, C_TEXT, true)
            r.setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(12))
            val bg = GradientDrawable()
            bg.setColor(0xFF2F3135.toInt())
            bg.cornerRadius = act.dp(4).toFloat()
            r.background = bg
            r.setOnClickListener { act.openSaved(n) }
            val l = act.lp(MATCH, WRAP)
            l.setMargins(0, act.dp(8), 0, 0)
            col.addView(r, l)
        }
        val sv = ScrollView(act)
        sv.addView(col)
        return sv
    }

    // miniatura desenhada à mão de cada template (0 = Baseplate, 1 = Classic, 2 = Vazio)
    private class Preview(ctx: Context, private val kind: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val w = width.toFloat()
            val h = height.toFloat()
            val hz = h * 0.48f
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, 0f, 0f, hz, 0xFF4F9BE0.toInt(), 0xFFD6EAF8.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, hz, p)
            p.shader = null
            p.color = 0xFFBFD9EE.toInt()
            c.drawRect(0f, hz, w, h, p)

            val lx = w * 0.22f
            val rx = w * 0.78f
            val lb = -w * 0.1f
            val rb = w * 1.1f
            if (kind != 2) {
                p.color = if (kind == 0) 0xFF5A5D62.toInt() else 0xFFA3A2A5.toInt()
                val path = Path()
                path.moveTo(lx, hz)
                path.lineTo(rx, hz)
                path.lineTo(rb, h)
                path.lineTo(lb, h)
                path.close()
                c.drawPath(path, p)

                if (kind == 0) {
                    p.color = 0x44FFFFFF
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 1.2f * d
                    for (i in 1..5) {
                        val t = i / 6f
                        c.drawLine(lx + (rx - lx) * t, hz, lb + (rb - lb) * t, h, p)
                    }
                    for (j in 1..4) {
                        val t = (j / 5f) * (j / 5f)
                        val y = hz + (h - hz) * t
                        val l = lx + (lb - lx) * t
                        val r = rx + (rb - rx) * t
                        c.drawLine(l, y, r, y, p)
                    }
                    p.style = Paint.Style.FILL
                } else {
                    p.color = 0xFFBEBDC0.toInt()
                    for (j in 1..6) {
                        val t = Math.pow((j / 7f).toDouble(), 1.8).toFloat()
                        val y = hz + (h - hz) * t
                        val l = lx + (lb - lx) * t
                        val r = rx + (rb - rx) * t
                        for (i in 0..8) {
                            val x = l + (r - l) * (i / 8f)
                            c.drawCircle(x, y, (0.8f + 1.6f * t) * d, p)
                        }
                    }
                }
            }

            // SpawnLocation
            p.color = if (kind == 0) 0xFFB4B4B9.toInt() else 0xFF8C8C90.toInt()
            c.drawRoundRect(RectF(w * 0.40f, h * 0.70f, w * 0.60f, h * 0.80f), 3f * d, 3f * d, p)
        }
    }
}
