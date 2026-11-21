package com.rblxstudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

// Editor visual de ScreenGui: aparece por cima do viewport quando um objeto de UI está selecionado.
// Arraste para mover, puxe o quadradinho do canto inferior direito para redimensionar.
class GuiEditor(private val act: MainActivity) : FrameLayout(act) {
    private val d = act.resources.displayMetrics.density
    private val layer = FrameLayout(act)
    private val views = HashMap<Inst, FrameLayout>()
    private val imgSrc = HashMap<Inst, String>()
    private val lastBg = HashMap<Inst, Int>()
    private val rects = LinkedHashMap<Inst, RectF>()
    private val box = SelBox(act)
    private var mode = 0 // 0 nada, 1 mover, 2 redimensionar
    private var lastX = 0f
    private var lastY = 0f

    init {
        setBackgroundColor(0x99101820.toInt())
        isClickable = true
        addView(layer, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(box, FrameLayout.LayoutParams(MATCH, MATCH))

        val bar = LinearLayout(act)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(0xCC000000.toInt())
        bar.addView(act.tv("UI", 11f, Color.WHITE, true), barLp(10))
        val adds = listOf("+Frame" to "Frame", "+Texto" to "TextLabel", "+Botão" to "TextButton", "+Imagem" to "ImageLabel")
        for ((label, cls) in adds) {
            val b = act.tv(label, 11f, 0xFF4DA3FF.toInt(), true)
            b.setOnClickListener { act.insertObject(cls) }
            bar.addView(b, barLp(10))
        }
        val del = act.tv("Apagar", 11f, 0xFFFF6B6B.toInt(), true)
        del.setOnClickListener { act.deleteSel() }
        bar.addView(del, barLp(10))
        val exit = act.tv("Sair", 11f, Color.WHITE, true)
        exit.setOnClickListener { act.pick(null) }
        bar.addView(exit, barLp(10))
        val blp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
        blp.setMargins(act.dp(8), act.dp(8), 0, 0)
        addView(bar, blp)

        visibility = View.GONE
    }

    private fun barLp(pad: Int): LinearLayout.LayoutParams {
        val l = LinearLayout.LayoutParams(WRAP, WRAP)
        l.setMargins(act.dp(pad), act.dp(6), act.dp(pad), act.dp(6))
        return l
    }

    private fun inStarterGui(o: Inst): Boolean {
        if (!Defaults.isGui(o.className)) return false
        val root = Scene.service("StarterGui")
        var p: Inst? = o
        while (p != null) {
            if (p === root) return true
            p = p.parent
        }
        return false
    }

    // chamado sempre que a seleção ou alguma propriedade muda
    fun refresh() {
        val o = Scene.selObj
        if (Play.active || o == null || !inStarterGui(o)) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        if (width > 0) sync() else post { sync() }
    }

    private fun alive(i: Inst, root: Inst): Boolean {
        var p: Inst? = i
        while (p != null) {
            if (p === root) return true
            p = p.parent
        }
        return false
    }

    private fun sync() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val root = Scene.service("StarterGui")
        rects.clear()
        syncKids(root, layer, w, h, 0f, 0f, true)
        val it = views.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (!alive(e.key, root)) {
                (e.value.parent as? FrameLayout)?.removeView(e.value)
                imgSrc.remove(e.key)
                lastBg.remove(e.key)
                it.remove()
            }
        }
        box.rect = Scene.selObj?.let { rects[it] }
        box.invalidate()
    }

    private fun syncKids(parent: Inst, container: FrameLayout, pw: Float, ph: Float, ox: Float, oy: Float, vis: Boolean) {
        for (c in parent.children) {
            if (!Defaults.isGui(c.className)) continue
            var v = views[c]
            if (v == null) {
                v = make(c)
                views[c] = v
                container.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            val r = applyProps(c, v, pw, ph)
            val shown = vis && v.visibility == View.VISIBLE
            if (c.className != "ScreenGui" && shown) {
                val ax = ox + r[2]
                val ay = oy + r[3]
                rects[c] = RectF(ax, ay, ax + r[0], ay + r[1])
                syncKids(c, v, r[0], r[1], ax, ay, shown)
            } else if (c.className == "ScreenGui") {
                syncKids(c, v, pw, ph, 0f, 0f, shown)
            } else {
                syncKids(c, v, r[0], r[1], ox, oy, false)
            }
        }
    }

    private fun make(c: Inst): FrameLayout {
        val fl = FrameLayout(act)
        fl.clipChildren = false
        if (c.className == "TextLabel" || c.className == "TextButton") {
            val t = TextView(act)
            t.gravity = Gravity.CENTER
            fl.addView(t, FrameLayout.LayoutParams(MATCH, MATCH))
            fl.tag = t
        } else if (c.className == "ImageLabel") {
            val iv = ImageView(act)
            iv.scaleType = ImageView.ScaleType.FIT_XY
            fl.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
            fl.tag = iv
        }
        return fl
    }

    private fun num(c: Inst, k: String, def: Float) = c.prop(k).toFloatOrNull() ?: def

    // devolve largura, altura, x e y (px) do objeto dentro do pai
    private fun applyProps(c: Inst, v: FrameLayout, pw: Float, ph: Float): FloatArray {
        if (c.className == "ScreenGui") {
            v.visibility = if (c.prop("Enabled", "true") == "true") View.VISIBLE else View.GONE
            return floatArrayOf(pw, ph, 0f, 0f)
        }
        v.visibility = if (c.prop("Visible", "true") == "true") View.VISIBLE else View.GONE
        val sz = parseNums(c.prop("Size", "0,100,0,100"))
        val ps = parseNums(c.prop("Position", "0,0,0,0"))
        val ap = parseNums(c.prop("AnchorPoint", "0,0"))
        val w = sz[0] * pw + sz[1] * d
        val h = sz[2] * ph + sz[3] * d
        val x = ps[0] * pw + ps[1] * d - ap[0] * w
        val y = ps[2] * ph + ps[3] * d - ap[1] * h
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w.toInt() || lp.height != h.toInt() || lp.leftMargin != x.toInt() || lp.topMargin != y.toInt()) {
            lp.width = w.toInt()
            lp.height = h.toInt()
            lp.leftMargin = x.toInt()
            lp.topMargin = y.toInt()
            lp.gravity = Gravity.TOP or Gravity.START
            v.layoutParams = lp
        }
        val bc = parseNums(c.prop("BackgroundColor3", "255,255,255"))
        val a = ((1f - num(c, "BackgroundTransparency", 0f)).coerceIn(0f, 1f) * 255f).toInt()
        val col = Color.argb(a, bc[0].toInt(), bc[1].toInt(), bc[2].toInt())
        if (lastBg[c] != col) {
            lastBg[c] = col
            v.setBackgroundColor(col)
        }
        val tag = v.tag
        if (tag is TextView) {
            val txt = c.prop("Text")
            if (tag.text.toString() != txt) tag.text = txt
            val tc = parseNums(c.prop("TextColor3", "0,0,0"))
            tag.setTextColor(Color.rgb(tc[0].toInt(), tc[1].toInt(), tc[2].toInt()))
            tag.setTextSize(TypedValue.COMPLEX_UNIT_DIP, num(c, "TextSize", 18f) * 0.9f)
        } else if (tag is ImageView) {
            val src = c.prop("Image")
            if (imgSrc[c] != src) {
                imgSrc[c] = src
                if (src.isEmpty()) {
                    tag.setImageDrawable(null)
                } else {
                    Assets.image(src) { bmp ->
                        if (imgSrc[c] == src && bmp != null) tag.setImageBitmap(bmp)
                    }
                }
            }
        }
        return floatArrayOf(w, h, x, y)
    }

    // ---------- toque: selecionar, mover, redimensionar ----------
    private fun topAt(x: Float, y: Float): Inst? {
        val list = rects.entries.toList()
        for (k in list.indices.reversed()) {
            if (list[k].value.contains(x, y)) return list[k].key
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                val cur = Scene.selObj
                val r = cur?.let { rects[it] }
                val hs = 18f * d
                if (r != null && e.x >= r.right - hs && e.x <= r.right + hs && e.y >= r.bottom - hs && e.y <= r.bottom + hs) {
                    mode = 2
                } else {
                    val hit = topAt(e.x, e.y)
                    if (hit != null) {
                        if (hit !== cur) act.pick(hit)
                        mode = 1
                    } else {
                        mode = 0
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val o = Scene.selObj
                if (o != null && mode != 0) {
                    val dx = (e.x - lastX) / d
                    val dy = (e.y - lastY) / d
                    lastX = e.x
                    lastY = e.y
                    if (mode == 1) moveBy(o, dx, dy) else resizeBy(o, dx, dy)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }

    private fun f(v: Float): String {
        val s = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
        return if (s == "-0" || s.isEmpty()) "0" else s
    }

    private fun join4(n: FloatArray) = f(n[0]) + "," + f(n[1]) + "," + f(n[2]) + "," + f(n[3])

    private fun moveBy(o: Inst, dx: Float, dy: Float) {
        if (o.className == "ScreenGui") return
        val ps = parseNums(o.prop("Position", "0,0,0,0"))
        ps[1] += dx
        ps[3] += dy
        o.props["Position"] = join4(ps)
        Editor.onChanged?.invoke(false)
    }

    private fun resizeBy(o: Inst, dx: Float, dy: Float) {
        if (o.className == "ScreenGui") return
        val sz = parseNums(o.prop("Size", "0,100,0,100"))
        val ps = parseNums(o.prop("Position", "0,0,0,0"))
        val ap = parseNums(o.prop("AnchorPoint", "0,0"))
        val nw = if (sz[1] + dx < 4f) 4f else sz[1] + dx
        val nh = if (sz[3] + dy < 4f) 4f else sz[3] + dy
        ps[1] += ap[0] * (nw - sz[1])
        ps[3] += ap[1] * (nh - sz[3])
        sz[1] = nw
        sz[3] = nh
        o.props["Size"] = join4(sz)
        o.props["Position"] = join4(ps)
        Editor.onChanged?.invoke(false)
    }

    // contorno azul + quadradinho de redimensionar
    private class SelBox(ctx: Context) : View(ctx) {
        var rect: RectF? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val r = rect ?: return
            val d = resources.displayMetrics.density
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f * d
            p.color = 0xFF0F9BFF.toInt()
            c.drawRect(r, p)
            val s = 6f * d
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            c.drawRect(r.right - s, r.bottom - s, r.right + s, r.bottom + s, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * d
            p.color = 0xFF0F9BFF.toInt()
            c.drawRect(r.right - s, r.bottom - s, r.right + s, r.bottom + s, p)
        }
    }
}
