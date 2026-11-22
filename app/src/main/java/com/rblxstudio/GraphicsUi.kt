package com.rblxstudio

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

// Controle de qualidade gráfica (igual ao Roblox): Graphics Mode Automático/Manual + Graphics Quality 1 a 10.
// Usado no menu do jogo (Settings) e nas configurações do Studio.
object GraphicsUi {
    private fun bg(act: Activity, color: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radius * act.resources.displayMetrics.density
        return g
    }

    fun describe(level: Int): String = when {
        level <= 2 -> "Mínima: sem nuvens, sem névoa e sem sombras"
        level <= 4 -> "Baixa: céu com nuvens e sombra simples do personagem"
        level <= 6 -> "Média: névoa de distância e studs nas peças"
        level <= 8 -> "Alta: brilho do sol e reflexo nas peças"
        else -> "Máxima: nuvens detalhadas e todos os efeitos"
    }

    fun view(act: Activity, onChange: () -> Unit): View {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL

        fun label(t: String): TextView {
            val l = TextView(act)
            l.text = t
            l.textSize = 12f
            l.typeface = Typeface.DEFAULT_BOLD
            l.setTextColor(0xFFB8B9BB.toInt())
            l.setPadding(0, dp(8), 0, dp(4))
            return l
        }

        col.addView(label("Graphics Mode"), LinearLayout.LayoutParams(-1, -2))
        val modeRow = LinearLayout(act)
        modeRow.orientation = LinearLayout.HORIZONTAL
        val autoB = TextView(act)
        val manB = TextView(act)
        val seekBox = LinearLayout(act)
        seekBox.orientation = LinearLayout.VERTICAL
        val valueT = TextView(act)
        val descT = TextView(act)
        val seek = SeekBar(act)

        fun paintMode() {
            for ((v, on) in listOf(Pair(autoB, Settings.qualityAuto), Pair(manB, !Settings.qualityAuto))) {
                v.background = bg(act, if (on) 0xFF335FFF.toInt() else 0xFF2B2D30.toInt(), 8)
                v.setTextColor(if (on) Color.WHITE else 0xFFCCCCCC.toInt())
                v.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            seekBox.alpha = if (Settings.qualityAuto) 0.4f else 1f
            seek.isEnabled = !Settings.qualityAuto
            valueT.text = if (Settings.qualityAuto) "Auto" else Settings.quality.toString()
            descT.text = describe(Settings.level())
        }

        for ((v, name) in listOf(Pair(autoB, "Automatic"), Pair(manB, "Manual"))) {
            v.text = name
            v.textSize = 12f
            v.gravity = Gravity.CENTER
            v.setPadding(dp(4), dp(10), dp(4), dp(10))
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            if (v === autoB) lp.rightMargin = dp(6)
            modeRow.addView(v, lp)
        }
        autoB.setOnClickListener { Settings.qualityAuto = true; paintMode(); onChange() }
        manB.setOnClickListener { Settings.qualityAuto = false; paintMode(); onChange() }
        col.addView(modeRow, LinearLayout.LayoutParams(-1, -2))

        col.addView(label("Graphics Quality"), LinearLayout.LayoutParams(-1, -2))
        val line = LinearLayout(act)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        seek.max = 9
        seek.progress = Settings.quality.coerceIn(1, 10) - 1
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                Settings.quality = p + 1
                valueT.text = Settings.quality.toString()
                descT.text = describe(Settings.level())
                onChange()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        line.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        valueT.textSize = 14f
        valueT.typeface = Typeface.DEFAULT_BOLD
        valueT.setTextColor(Color.WHITE)
        valueT.gravity = Gravity.CENTER
        valueT.setPadding(dp(8), 0, 0, 0)
        line.addView(valueT, LinearLayout.LayoutParams(dp(48), -2))
        seekBox.addView(line, LinearLayout.LayoutParams(-1, -2))
        descT.textSize = 11f
        descT.setTextColor(0xFF8A8C8E.toInt())
        descT.setPadding(0, dp(4), 0, 0)
        seekBox.addView(descT, LinearLayout.LayoutParams(-1, -2))
        col.addView(seekBox, LinearLayout.LayoutParams(-1, -2))
        paintMode()
        return col
    }
}
