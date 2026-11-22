package com.rblxstudio

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Hotbar de Tools no Play (igual ao Roblox) + inventário (mochila) com todas as Tools.
// Toque numa Tool para pegar / guardar; toque na tela com a Tool na mão = Activated.
class ToolBar(private val act: MainActivity) : FrameLayout(act) {
    private val d = act.resources.displayMetrics.density
    private val bar = LinearLayout(act)
    private val gridBox = LinearLayout(act)
    private val gridRows = LinearLayout(act)
    private var gridOpen = false
    private var sig = ""

    private val ticker = object : Runnable {
        override fun run() {
            if (visibility == View.VISIBLE) {
                refresh()
                postDelayed(this, 400)
            }
        }
    }

    private fun dp(v: Int) = (v * d).toInt()

    private fun box(color: Int, radius: Int, stroke: Int, strokeColor: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radius * d
        if (stroke > 0) g.setStroke(stroke, strokeColor)
        return g
    }

    init {
        visibility = View.GONE
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER
        val blp = LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        blp.bottomMargin = dp(8)
        addView(bar, blp)

        gridBox.orientation = LinearLayout.VERTICAL
        gridBox.background = box(0xE61B1D20.toInt(), 10, dp(1), 0xFF3A3D40.toInt())
        gridBox.setPadding(dp(10), dp(8), dp(10), dp(8))
        gridBox.visibility = View.GONE
        gridBox.isClickable = true
        val title = TextView(act)
        title.text = "Inventory"
        title.textSize = 12f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(Color.WHITE)
        title.setPadding(0, 0, 0, dp(6))
        gridBox.addView(title, LinearLayout.LayoutParams(-2, -2))
        gridRows.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(act)
        sv.addView(gridRows, FrameLayout.LayoutParams(-2, -2))
        gridBox.addView(sv, LinearLayout.LayoutParams(-2, dp(150)))
        val glp = LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        glp.bottomMargin = dp(66)
        addView(gridBox, glp)
    }

    fun start() {
        gridOpen = false
        sig = ""
        visibility = View.VISIBLE
        refresh()
        removeCallbacks(ticker)
        postDelayed(ticker, 400)
    }

    fun stop() {
        removeCallbacks(ticker)
        visibility = View.GONE
        bar.removeAllViews()
        gridRows.removeAllViews()
        gridBox.visibility = View.GONE
        gridOpen = false
        sig = ""
    }

    fun refresh() {
        if (!Play.active) return
        val ts = Play.tools()
        val eq = Play.equipped
        var s = ""
        for (t in ts) s += System.identityHashCode(t).toString() + ","
        s += "#" + (if (eq != null) System.identityHashCode(eq) else 0) + "#" + gridOpen
        if (s == sig) return
        sig = s
        build(ts)
    }

    private fun slot(t: Inst, idx: Int, size: Int): View {
        val eq = Play.equipped === t
        val f = FrameLayout(act)
        f.background = box(if (eq) 0xE63A3F47.toInt() else 0xB31B1D20.toInt(), 8,
            Math.round((if (eq) 2f else 1f) * d), if (eq) Color.WHITE else 0xFF55585C.toInt())
        val tex = t.prop("TextureId")
        if (tex.isNotEmpty()) {
            val iv = ImageView(act)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setPadding(dp(4), dp(4), dp(4), dp(4))
            f.addView(iv, LayoutParams(-1, -1))
            Assets.image(tex) { b -> if (b != null) iv.setImageBitmap(b) }
        } else {
            val tx = TextView(act)
            tx.text = t.name
            tx.textSize = 9f
            tx.typeface = Typeface.DEFAULT_BOLD
            tx.setTextColor(Color.WHITE)
            tx.gravity = Gravity.CENTER
            tx.maxLines = 2
            tx.ellipsize = TextUtils.TruncateAt.END
            tx.setPadding(dp(3), dp(3), dp(3), dp(3))
            f.addView(tx, LayoutParams(-1, -1))
        }
        val n = TextView(act)
        n.text = idx.toString()
        n.textSize = 8f
        n.setTextColor(0xFFC8C9CB.toInt())
        n.setPadding(dp(4), dp(1), 0, 0)
        f.addView(n, LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        f.setOnClickListener {
            Play.toggleTool(t)
            if (gridOpen) {
                gridOpen = false
                sig = ""
                refresh()
            }
        }
        val lp = LinearLayout.LayoutParams(dp(size), dp(size))
        lp.setMargins(dp(3), 0, dp(3), 0)
        f.layoutParams = lp
        return f
    }

    private fun build(ts: List<Inst>) {
        bar.removeAllViews()
        gridRows.removeAllViews()
        if (ts.isEmpty()) {
            bar.visibility = View.GONE
            gridBox.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        val shown = minOf(ts.size, 5)
        for (i in 0 until shown) bar.addView(slot(ts[i], i + 1, 48))

        // botão da mochila
        val bag = FrameLayout(act)
        bag.background = box(if (gridOpen) 0xE63A3F47.toInt() else 0xB31B1D20.toInt(), 8,
            Math.round(d), if (gridOpen) Color.WHITE else 0xFF55585C.toInt())
        val ic = ImageView(act)
        ic.setImageResource(R.drawable.ic_toolbox)
        ic.setColorFilter(Color.WHITE)
        bag.addView(ic, LayoutParams(dp(24), dp(24), Gravity.CENTER))
        bag.setOnClickListener {
            gridOpen = !gridOpen
            sig = ""
            refresh()
        }
        val blp = LinearLayout.LayoutParams(dp(48), dp(48))
        blp.setMargins(dp(3), 0, dp(3), 0)
        bar.addView(bag, blp)

        if (gridOpen) {
            gridBox.visibility = View.VISIBLE
            var row: LinearLayout? = null
            for ((i, t) in ts.withIndex()) {
                if (i % 5 == 0) {
                    val r = LinearLayout(act)
                    r.orientation = LinearLayout.HORIZONTAL
                    gridRows.addView(r, LinearLayout.LayoutParams(-2, -2))
                    row = r
                }
                row?.addView(slot(t, i + 1, 54))
            }
        } else {
            gridBox.visibility = View.GONE
        }
    }
}
