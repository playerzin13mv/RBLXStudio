package com.rblxstudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// Controles de câmera do Editor (parecido com o Studio do celular): joystick para voar,
// botões de subir / descer, foco na peça selecionada e velocidade x1 / x2 / x4.
class EditorPad(private val act: MainActivity) : FrameLayout(act) {
    private val d = act.resources.displayMetrics.density
    private var jx = 0f
    private var jy = 0f
    private var up = false
    private var down = false
    private var speed = 1
    private var last = 0L
    private var running = false

    private fun dp(v: Int) = (v * d).toInt()

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            step(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private inner class Joy(c0: Context) : View(c0) {
        private val base = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
        private var kx = 0f
        private var ky = 0f
        private var pid = -1

        init {
            base.color = 0x44FFFFFF
            ring.color = 0x88FFFFFF.toInt()
            ring.style = Paint.Style.STROKE
            ring.strokeWidth = 2f * d
            knob.color = 0xBBFFFFFF.toInt()
        }

        private fun radius() = minOf(width, height) / 2f - 4f * d

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = radius()
            c.drawCircle(cx, cy, r, base)
            c.drawCircle(cx, cy, r, ring)
            c.drawCircle(cx + kx, cy + ky, r * 0.42f, knob)
        }

        private fun upd(e: MotionEvent, i: Int) {
            val r = radius()
            var dx = e.getX(i) - width / 2f
            var dy = e.getY(i) - height / 2f
            val l = hypot(dx, dy)
            if (l > r) {
                dx = dx / l * r
                dy = dy / l * r
            }
            kx = dx
            ky = dy
            jx = dx / r
            jy = -dy / r
            invalidate()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pid = e.getPointerId(0)
                    upd(e, 0)
                }
                MotionEvent.ACTION_MOVE -> {
                    val i = e.findPointerIndex(pid)
                    if (i >= 0) upd(e, i)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pid = -1
                    kx = 0f
                    ky = 0f
                    jx = 0f
                    jy = 0f
                    invalidate()
                }
            }
            return true
        }
    }

    private fun circle(): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(0x66000000)
        g.setStroke(Math.round(2f * d), 0xCCFFFFFF.toInt())
        return g
    }

    private fun roundBtn(): FrameLayout {
        val f = FrameLayout(act)
        f.background = circle()
        val lp = LinearLayout.LayoutParams(dp(44), dp(44))
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        f.layoutParams = lp
        return f
    }

    private fun arrow(text: String, set: (Boolean) -> Unit): View {
        val f = roundBtn()
        val t = TextView(act)
        t.text = text
        t.textSize = 18f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        f.addView(t, LayoutParams(-1, -1))
        f.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> set(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> set(false)
            }
            true
        }
        return f
    }

    init {
        val joy = Joy(act)
        val jlp = LayoutParams(dp(130), dp(130), Gravity.BOTTOM or Gravity.START)
        jlp.setMargins(dp(14), 0, 0, dp(14))
        addView(joy, jlp)

        val cols = LinearLayout(act)
        cols.orientation = LinearLayout.HORIZONTAL
        val a = LinearLayout(act)
        a.orientation = LinearLayout.VERTICAL
        val b = LinearLayout(act)
        b.orientation = LinearLayout.VERTICAL

        a.addView(arrow("\u25B2") { up = it })
        a.addView(arrow("\u25BC") { down = it })

        val focus = roundBtn()
        val fi = ImageView(act)
        fi.setImageResource(R.drawable.ic_focus)
        fi.setColorFilter(Color.WHITE)
        focus.addView(fi, LayoutParams(dp(24), dp(24), Gravity.CENTER))
        focus.setOnClickListener { Editor.focus() }
        b.addView(focus)

        val sp = roundBtn()
        val st = TextView(act)
        st.text = "x1"
        st.textSize = 13f
        st.typeface = Typeface.DEFAULT_BOLD
        st.setTextColor(Color.WHITE)
        st.gravity = Gravity.CENTER
        sp.addView(st, LayoutParams(-1, -1))
        sp.setOnClickListener {
            speed = if (speed == 1) 2 else if (speed == 2) 4 else 1
            st.text = "x" + speed
        }
        b.addView(sp)

        cols.addView(b)
        cols.addView(a)
        val clp = LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
        clp.setMargins(0, 0, dp(10), dp(10))
        addView(cols, clp)
    }

    private fun step(t: Long) {
        val dt = if (last == 0L) 0f else ((t - last) / 1.0e9f).coerceAtMost(0.05f)
        last = t
        if (!isShown || Play.active) return
        val ud = (if (up) 1f else 0f) - (if (down) 1f else 0f)
        if (jx == 0f && jy == 0f && ud == 0f) return
        val sp = (24f + Cam.dist * 0.3f) * speed * dt
        val yaw = Cam.yaw
        val fx = -sin(yaw)
        val fz = -cos(yaw)
        val rx = cos(yaw)
        val rz = -sin(yaw)
        Cam.target[0] += (fx * jy + rx * jx) * sp
        Cam.target[2] += (fz * jy + rz * jx) * sp
        Cam.target[1] += ud * sp
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        last = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }
}
