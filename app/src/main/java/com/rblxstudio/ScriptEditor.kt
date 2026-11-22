package com.rblxstudio

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.regex.Pattern

// Editor de scripts em tela cheia: abas, caminho do script, números de linha,
// cores de sintaxe do Lua, botão Check e uma fileira de símbolos para o teclado do celular.
class ScriptEditor(private val act: MainActivity) : FrameLayout(act) {
    private val d = act.resources.displayMetrics.density
    private val opened = ArrayList<Inst>()
    private var cur: Inst? = null
    private val tabRow = LinearLayout(act)
    private val crumb = TextView(act)
    private val count = TextView(act)
    private val gutter = TextView(act)
    private val et = EditText(act)
    private val status = TextView(act)
    private var guard = false
    private var pendingIndent = -1
    private var lastLines = 0

    private val cKw = 0xFFF86D7C.toInt()
    private val cStr = 0xFFADF195.toInt()
    private val cNum = 0xFFFFC600.toInt()
    private val cCom = 0xFF7F8A7F.toInt()
    private val cBuiltin = 0xFF84D6F7.toInt()

    private val keywords = setOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "if", "in", "local",
        "nil", "not", "or", "repeat", "return", "then", "true", "until", "while", "continue"
    )
    private val builtins = setOf(
        "game", "workspace", "script", "print", "warn", "wait", "task", "math", "string", "table", "Instance",
        "Vector3", "Vector2", "Color3", "CFrame", "UDim2", "Enum", "pairs", "ipairs", "tostring", "tonumber",
        "type", "typeof", "pcall", "require", "spawn", "delay", "error", "assert", "select", "next"
    )
    private val pat: Pattern = Pattern.compile(
        """(--\[\[[\s\S]*?\]\]|--[^\n]*)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|\[\[[\s\S]*?\]\])|(\b\d+(?:\.\d+)?\b)|(\b[A-Za-z_][A-Za-z0-9_]*\b)"""
    )

    private fun dp(v: Int) = (v * d).toInt()

    private fun round(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = r * d
        return g
    }

    init {
        isClickable = true
        visibility = View.GONE
        setBackgroundColor(0xFF1E1F22.toInt())

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL

        // abas
        val tabScroll = HorizontalScrollView(act)
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.setBackgroundColor(0xFF161719.toInt())
        tabRow.orientation = LinearLayout.HORIZONTAL
        tabScroll.addView(tabRow, FrameLayout.LayoutParams(-2, -1))
        col.addView(tabScroll, LinearLayout.LayoutParams(-1, dp(34)))

        // caminho + linhas + Check
        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setBackgroundColor(0xFF2A2B2E.toInt())
        head.setPadding(dp(10), dp(4), dp(8), dp(4))
        crumb.textSize = 11f
        crumb.setTextColor(0xFFC8C9CB.toInt())
        crumb.maxLines = 1
        crumb.ellipsize = android.text.TextUtils.TruncateAt.START
        head.addView(crumb, LinearLayout.LayoutParams(0, -2, 1f))
        count.textSize = 11f
        count.setTextColor(0xFF8A8C8E.toInt())
        count.setPadding(dp(8), 0, dp(10), 0)
        head.addView(count, LinearLayout.LayoutParams(-2, -2))
        val check = TextView(act)
        check.text = "Check"
        check.textSize = 12f
        check.typeface = Typeface.DEFAULT_BOLD
        check.setTextColor(Color.WHITE)
        check.gravity = Gravity.CENTER
        check.background = round(0xFF2E9E4A.toInt(), 6)
        check.setPadding(dp(14), dp(6), dp(14), dp(6))
        check.setOnClickListener { runCheck() }
        head.addView(check, LinearLayout.LayoutParams(-2, -2))
        col.addView(head, LinearLayout.LayoutParams(-1, -2))

        // código
        gutter.typeface = Typeface.MONOSPACE
        gutter.textSize = 13f
        gutter.setTextColor(0xFF6E7073.toInt())
        gutter.gravity = Gravity.END
        gutter.setPadding(dp(8), dp(8), dp(8), dp(8))
        gutter.setBackgroundColor(0xFF232427.toInt())

        et.typeface = Typeface.MONOSPACE
        et.textSize = 13f
        et.setTextColor(0xFFE6E6E6.toInt())
        et.setBackgroundColor(Color.TRANSPARENT)
        et.gravity = Gravity.TOP or Gravity.START
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        et.setHorizontallyScrolling(true)
        et.setPadding(dp(8), dp(8), dp(12), dp(8))
        et.minWidth = dp(420)
        et.minHeight = dp(260)
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, cnt: Int) {
                if (!guard && cnt == 1 && before == 0 && s != null && start < s.length && s[start] == '\n') {
                    pendingIndent = start + 1
                }
            }

            override fun afterTextChanged(e: Editable?) {
                if (e == null || guard) return
                if (pendingIndent >= 0) autoIndent(e)
                edited(e)
            }
        })

        val hsv = HorizontalScrollView(act)
        hsv.isHorizontalScrollBarEnabled = false
        hsv.addView(et, FrameLayout.LayoutParams(-2, -2))
        val codeRow = LinearLayout(act)
        codeRow.orientation = LinearLayout.HORIZONTAL
        codeRow.minimumHeight = dp(260)
        codeRow.addView(gutter, LinearLayout.LayoutParams(-2, -1))
        codeRow.addView(hsv, LinearLayout.LayoutParams(0, -2, 1f))
        val sv = ScrollView(act)
        sv.isFillViewport = true
        sv.addView(codeRow, FrameLayout.LayoutParams(-1, -2))
        col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        // resultado do Check
        status.textSize = 11f
        status.setPadding(dp(10), dp(4), dp(10), dp(4))
        status.visibility = View.GONE
        col.addView(status, LinearLayout.LayoutParams(-1, -2))

        // símbolos
        val keys = HorizontalScrollView(act)
        keys.isHorizontalScrollBarEnabled = false
        keys.setBackgroundColor(0xFF2A2B2E.toInt())
        val kr = LinearLayout(act)
        kr.orientation = LinearLayout.HORIZONTAL
        kr.setPadding(dp(4), dp(3), dp(4), dp(3))
        for (k in listOf("Tab", "(", ")", "{", "}", "[", "]", "\"", "'", "=", ".", ",", ":", "#", "+", "-", "*", "/", "<", ">", "~", "_", "and", "or", "not", "end", "local", "function")) {
            val b = TextView(act)
            b.text = k
            b.textSize = 13f
            b.typeface = Typeface.MONOSPACE
            b.setTextColor(Color.WHITE)
            b.gravity = Gravity.CENTER
            b.background = round(0xFF3A3B3F.toInt(), 6)
            b.setPadding(dp(11), dp(6), dp(11), dp(6))
            b.setOnClickListener { insertKey(k) }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(dp(2), 0, dp(2), 0)
            kr.addView(b, lp)
        }
        keys.addView(kr, FrameLayout.LayoutParams(-2, -2))
        col.addView(keys, LinearLayout.LayoutParams(-1, -2))

        addView(col, LayoutParams(-1, -1))
    }

    // ---------- abrir / fechar ----------
    private fun alive(i: Inst): Boolean {
        var x: Inst? = i
        while (x != null) {
            if (x === Scene.game) return true
            x = Scene.parentOf(x)
        }
        return false
    }

    fun open(s: Inst) {
        if (!opened.contains(s)) opened.add(s)
        cur = s
        load(s)
        buildTabs()
        visibility = View.VISIBLE
        bringToFront()
    }

    fun hide() {
        visibility = View.GONE
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(et.windowToken, 0)
        et.clearFocus()
    }

    private fun close(s: Inst) {
        opened.remove(s)
        if (cur === s) {
            cur = opened.lastOrNull()
            val c = cur
            if (c != null) load(c) else hide()
        }
        buildTabs()
    }

    private fun load(s: Inst) {
        guard = true
        et.setText(s.prop("Source"))
        guard = false
        val e = et.text
        highlight(e)
        updateGutter(e)
        crumb.text = pathOf(s)
        status.visibility = View.GONE
    }

    private fun pathOf(s: Inst): String {
        val parts = ArrayList<String>()
        var x: Inst? = s
        while (x != null && x !== Scene.game) {
            parts.add(0, x.name)
            x = Scene.parentOf(x)
        }
        return parts.joinToString(".")
    }

    private fun buildTabs() {
        opened.removeAll { !alive(it) }
        if (cur != null && !opened.contains(cur!!)) cur = opened.lastOrNull()
        tabRow.removeAllViews()

        val vp = LinearLayout(act)
        vp.orientation = LinearLayout.HORIZONTAL
        vp.gravity = Gravity.CENTER_VERTICAL
        vp.setPadding(dp(12), 0, dp(14), 0)
        val gi = ImageView(act)
        gi.setImageResource(R.drawable.ic_globe)
        gi.setColorFilter(0xFF4CD964.toInt())
        vp.addView(gi, LinearLayout.LayoutParams(dp(16), dp(16)))
        val gt = TextView(act)
        gt.text = "Viewport"
        gt.textSize = 12f
        gt.setTextColor(0xFF9A9C9E.toInt())
        gt.setPadding(dp(6), 0, 0, 0)
        vp.addView(gt, LinearLayout.LayoutParams(-2, -2))
        vp.setOnClickListener { hide() }
        tabRow.addView(vp, LinearLayout.LayoutParams(-2, -1))

        for (s in opened) {
            val on = s === cur
            val t = LinearLayout(act)
            t.orientation = LinearLayout.HORIZONTAL
            t.gravity = Gravity.CENTER_VERTICAL
            t.setPadding(dp(10), 0, dp(4), 0)
            t.setBackgroundColor(if (on) 0xFF1E1F22.toInt() else 0xFF161719.toInt())
            val ic = ImageView(act)
            ic.setImageResource(R.drawable.ic_script)
            ic.setColorFilter(classIcon(s.className).second)
            t.addView(ic, LinearLayout.LayoutParams(dp(16), dp(16)))
            val tx = TextView(act)
            tx.text = s.name
            tx.textSize = 12f
            tx.maxLines = 1
            tx.setTextColor(if (on) Color.WHITE else 0xFF9A9C9E.toInt())
            tx.setPadding(dp(6), 0, dp(6), 0)
            t.addView(tx, LinearLayout.LayoutParams(-2, -2))
            val x = TextView(act)
            x.text = "\u2715"
            x.textSize = 12f
            x.setTextColor(0xFF9A9C9E.toInt())
            x.gravity = Gravity.CENTER
            x.setPadding(dp(8), 0, dp(8), 0)
            x.setOnClickListener { close(s) }
            t.addView(x, LinearLayout.LayoutParams(-2, -1))
            t.setOnClickListener {
                if (cur !== s) {
                    cur = s
                    load(s)
                    buildTabs()
                }
            }
            tabRow.addView(t, LinearLayout.LayoutParams(-2, -1))
        }
    }

    // ---------- edição ----------
    private fun edited(e: Editable) {
        val s = cur
        if (s != null) s.props["Source"] = e.toString()
        highlight(e)
        updateGutter(e)
        status.visibility = View.GONE
    }

    private fun autoIndent(e: Editable) {
        val at = pendingIndent
        pendingIndent = -1
        if (at < 1 || at > e.length) return
        val text = e.toString()
        val nl = at - 1
        val lineStart = text.lastIndexOf('\n', nl - 1) + 1
        val prev = text.substring(lineStart, nl)
        val indent = prev.takeWhile { it == ' ' || it == '\t' }
        val t = prev.trimEnd()
        val deeper = t.endsWith("then") || t.endsWith("do") || t.endsWith("{") || t.endsWith("else") ||
            t.endsWith("repeat") || t.endsWith("(") || Regex("""(^|\s)function\b.*\)$""").containsMatchIn(t)
        val ins = indent + (if (deeper) "  " else "")
        if (ins.isEmpty()) return
        guard = true
        e.insert(at, ins)
        guard = false
        et.setSelection(at + ins.length)
    }

    private fun highlight(e: Editable) {
        for (sp in e.getSpans(0, e.length, ForegroundColorSpan::class.java)) e.removeSpan(sp)
        if (e.length > 30000) return
        val m = pat.matcher(e)
        while (m.find()) {
            var color = 0
            if (m.group(1) != null) {
                color = cCom
            } else if (m.group(2) != null) {
                color = cStr
            } else if (m.group(3) != null) {
                color = cNum
            } else {
                val w = m.group(4) ?: ""
                if (keywords.contains(w)) color = cKw else if (builtins.contains(w)) color = cBuiltin
            }
            if (color != 0) e.setSpan(ForegroundColorSpan(color), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun updateGutter(e: Editable) {
        var n = 1
        for (i in 0 until e.length) if (e[i] == '\n') n++
        count.text = n.toString() + " lines"
        if (n == lastLines) return
        lastLines = n
        val sb = StringBuilder()
        for (i in 1..n) {
            if (i > 1) sb.append('\n')
            sb.append(i)
        }
        gutter.text = sb.toString()
    }

    private fun runCheck() {
        val src = et.text.toString()
        val err = Lua.syntax(src)
        status.visibility = View.VISIBLE
        if (err == null) {
            status.text = "\u2713 Sem erros de sintaxe"
            status.setTextColor(0xFF5DD27A.toInt())
            status.setBackgroundColor(0xFF1F2E24.toInt())
        } else {
            status.text = err
            status.setTextColor(0xFFFF8A80.toInt())
            status.setBackgroundColor(0xFF33201F.toInt())
            Out.add(err, 2)
        }
    }

    private fun insertKey(k: String) {
        val text = et.text
        val a = minOf(et.selectionStart.coerceAtLeast(0), et.selectionEnd.coerceAtLeast(0))
        val b = maxOf(et.selectionStart.coerceAtLeast(0), et.selectionEnd.coerceAtLeast(0))
        val ins: String
        var back = 0
        when (k) {
            "Tab" -> ins = "  "
            "(" -> { ins = "()"; back = 1 }
            "{" -> { ins = "{}"; back = 1 }
            "[" -> { ins = "[]"; back = 1 }
            "\"" -> { ins = "\"\""; back = 1 }
            "'" -> { ins = "''"; back = 1 }
            "and", "or", "not", "end", "local", "function" -> ins = k + " "
            else -> ins = k
        }
        text.replace(a, b, ins)
        et.setSelection((a + ins.length - back).coerceIn(0, et.text.length))
        et.requestFocus()
    }
}
