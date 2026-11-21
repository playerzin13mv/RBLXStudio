package com.rblxstudio

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// baixar texto / imagens do Roblox pelo ID
object Assets {
    private val cache = HashMap<String, Bitmap>()
    @Volatile var dir: java.io.File? = null   // imagens escolhidas da galeria (file:nome.png)
    private val main = Handler(Looper.getMainLooper())

    fun idOf(s: String): String = s.filter { it.isDigit() }

    fun httpGet(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 10000
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // aceita "123", "rbxassetid://123" ou um link http(s)
    fun image(src: String, cb: (Bitmap?) -> Unit) {
        val s = src.trim()
        if (s.isEmpty()) { cb(null); return }
        val cached = synchronized(cache) { cache[s] }
        if (cached != null) { cb(cached); return }
        Thread {
            var bmp: Bitmap? = null
            try {
                if (s.startsWith("file:")) {
                    val f = java.io.File(dir, s.removePrefix("file:").replace("/", "_"))
                    val o1 = BitmapFactory.Options()
                    o1.inJustDecodeBounds = true
                    BitmapFactory.decodeFile(f.absolutePath, o1)
                    var ss = 1
                    while (o1.outWidth / ss > 1024 || o1.outHeight / ss > 1024) ss *= 2
                    val o2 = BitmapFactory.Options()
                    o2.inSampleSize = ss
                    bmp = BitmapFactory.decodeFile(f.absolutePath, o2)
                }
                val url = if (s.startsWith("file:")) "" else if (s.startsWith("http")) s else {
                    val meta = httpGet("https://thumbnails.roblox.com/v1/assets?assetIds=" + idOf(s) +
                        "&returnPolicy=PlaceHolder&size=420x420&format=Png&isCircular=false")
                    JSONObject(meta).getJSONArray("data").getJSONObject(0).optString("imageUrl", "")
                }
                if (url.isNotEmpty()) {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 10000
                    c.readTimeout = 10000
                    bmp = c.inputStream.use { BitmapFactory.decodeStream(it) }
                }
            } catch (e: Exception) {
            }
            if (bmp != null) synchronized(cache) { cache[s] = bmp }
            main.post { cb(bmp) }
        }.start()
    }
}

// Sound: toca pelo SoundId (o Roblox pode bloquear áudios que não sejam públicos)
object Audio {
    private val players = HashMap<Inst, MediaPlayer>()
    private val main = Handler(Looper.getMainLooper())

    // converte o ID em link direto do áudio (a API nova devolve o link do CDN)
    private fun resolve(raw: String): String {
        if (raw.startsWith("http")) return raw
        val id = Assets.idOf(raw)
        try {
            val j = org.json.JSONObject(Assets.httpGet("https://assetdelivery.roblox.com/v2/assetId/" + id))
            val locs = j.optJSONArray("locations")
            if (locs != null && locs.length() > 0) {
                val loc = locs.getJSONObject(0).optString("location", "")
                if (loc.isNotEmpty()) return loc
            }
            val errs = j.optJSONArray("errors")
            if (errs != null && errs.length() > 0) {
                throw Exception(errs.getJSONObject(0).optString("message", "sem permissão"))
            }
        } catch (e: Exception) {
            if (e.message?.contains("HTTP") == false && e.message?.contains("Unable") == false) {
                Out.add("Sound " + id + ": " + (e.message ?: "erro") + " (o Roblox só libera áudios públicos)", 1)
            }
        }
        return "https://assetdelivery.roblox.com/v1/asset/?id=" + id
    }

    fun play(i: Inst) {
        val raw = i.prop("SoundId").trim()
        if (raw.isEmpty()) return
        Thread {
            val src = resolve(raw)
            main.post {
                try {
                    stopNow(i)
                    val mp = MediaPlayer()
                    mp.setDataSource(src)
                    mp.isLooping = i.prop("Looped") == "true"
                    val v = (i.prop("Volume", "1").toFloatOrNull() ?: 1f).coerceIn(0f, 1f)
                    mp.setVolume(v, v)
                    mp.setOnPreparedListener { it.start() }
                    mp.setOnErrorListener { _, _, _ ->
                        Out.add("Sound: não consegui tocar o áudio " + raw + " (ID inválido ou áudio privado)", 1)
                        true
                    }
                    mp.prepareAsync()
                    players[i] = mp
                } catch (e: Exception) {
                    Out.add("Sound: " + (e.message ?: "erro"), 1)
                }
            }
        }.start()
    }

    private fun stopNow(i: Inst) {
        val mp = players.remove(i) ?: return
        try { mp.stop() } catch (e: Exception) { }
        mp.release()
    }

    fun stop(i: Inst) {
        main.post { stopNow(i) }
    }

    fun stopAll() {
        main.post {
            for (k in players.keys.toList()) stopNow(k)
        }
    }
}

// por cima do viewport durante o Play: UI do jogo (StarterGui), menu do Roblox, chat e lista de jogadores
class GameGui(private val act: Activity) : FrameLayout(act) {
    private val d = act.resources.displayMetrics.density
    private val hd = Handler(Looper.getMainLooper())
    private val userLayer = FrameLayout(act)
    private val views = HashMap<Inst, FrameLayout>()
    private val imgSrc = HashMap<Inst, String>()
    private val lastBg = HashMap<Inst, Int>()
    private val chatLines = ArrayList<String>()
    private val chatLog = TextView(act)
    private val chatScroll = ScrollView(act)
    private val chatRow = LinearLayout(act)
    private val chatInput = EditText(act)
    private val playersBox = LinearLayout(act)
    private var running = false
    private val bubble = TextView(act)
    private var bubbleUntil = 0L
    private var tick = 0
    var onLeave: (() -> Unit)? = null
    var onRespawn: (() -> Unit)? = null
    var onMenu: (() -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            syncAll()
            tick++
            if (tick % 20 == 0) refreshPlayers()
            hd.postDelayed(this, 100)
        }
    }

    private fun dp(v: Int) = (v * d).toInt()

    private fun roundBtn(res: Int, tint: Int?): ImageView {
        val v = ImageView(act)
        v.setImageResource(res)
        if (tint != null) v.setColorFilter(tint)
        val bg = GradientDrawable()
        bg.setColor(0x99000000.toInt())
        bg.cornerRadius = dp(10).toFloat()
        v.background = bg
        v.setPadding(dp(7), dp(7), dp(7), dp(7))
        return v
    }

    init {
        addView(userLayer, FrameLayout.LayoutParams(-1, -1))

        val tl = LinearLayout(act)
        tl.orientation = LinearLayout.VERTICAL

        val btns = LinearLayout(act)
        btns.orientation = LinearLayout.HORIZONTAL
        val menu = roundBtn(R.drawable.logo, null)
        menu.setOnClickListener { onMenu?.invoke() }
        btns.addView(menu, LinearLayout.LayoutParams(dp(40), dp(40)))
        val chatBtn = roundBtn(R.drawable.ic_chat, Color.WHITE)
        chatBtn.setOnClickListener {
            if (chatRow.visibility == View.VISIBLE) {
                chatRow.visibility = View.GONE
            } else {
                chatRow.visibility = View.VISIBLE
                chatInput.requestFocus()
                (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(chatInput, 0)
            }
        }
        val cl = LinearLayout.LayoutParams(dp(40), dp(40))
        cl.leftMargin = dp(8)
        btns.addView(chatBtn, cl)
        tl.addView(btns, LinearLayout.LayoutParams(-2, -2))

        chatLog.textSize = 12f
        chatLog.setTextColor(Color.WHITE)
        chatLog.setPadding(dp(6), dp(4), dp(6), dp(4))
        chatScroll.setBackgroundColor(0x55000000)
        chatScroll.addView(chatLog)
        val sl = LinearLayout.LayoutParams(-1, dp(110))
        sl.topMargin = dp(6)
        tl.addView(chatScroll, sl)

        chatRow.orientation = LinearLayout.HORIZONTAL
        chatRow.setBackgroundColor(0x99000000.toInt())
        chatRow.visibility = View.GONE
        chatInput.hint = "Toque para conversar"
        chatInput.textSize = 12f
        chatInput.setTextColor(Color.WHITE)
        chatInput.setHintTextColor(0xFFAAAAAA.toInt())
        chatInput.setSingleLine()
        chatInput.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        chatInput.setOnEditorActionListener { _, _, _ ->
            send()
            true
        }
        chatRow.addView(chatInput, LinearLayout.LayoutParams(0, -2, 1f))
        val sendBtn = TextView(act)
        sendBtn.text = "Enviar"
        sendBtn.textSize = 12f
        sendBtn.setTextColor(0xFF4DA3FF.toInt())
        sendBtn.typeface = Typeface.DEFAULT_BOLD
        sendBtn.setPadding(dp(10), dp(8), dp(10), dp(8))
        sendBtn.setOnClickListener { send() }
        chatRow.addView(sendBtn, LinearLayout.LayoutParams(-2, -2))
        tl.addView(chatRow, LinearLayout.LayoutParams(-1, -2))

        val tlp = FrameLayout.LayoutParams(dp(290), -2, Gravity.TOP or Gravity.START)
        tlp.setMargins(dp(8), dp(8), 0, 0)
        addView(tl, tlp)

        playersBox.orientation = LinearLayout.VERTICAL
        val plp = FrameLayout.LayoutParams(dp(150), -2, Gravity.TOP or Gravity.END)
        plp.setMargins(0, dp(62), dp(8), 0)
        addView(playersBox, plp)

        // bolha de chat em cima da cabeça do personagem
        bubble.textSize = 12f
        bubble.setTextColor(0xFF1B1B1B.toInt())
        bubble.maxWidth = dp(220)
        bubble.setPadding(dp(10), dp(6), dp(10), dp(6))
        val bb = GradientDrawable()
        bb.setColor(0xF2FFFFFF.toInt())
        bb.cornerRadius = dp(12).toFloat()
        bubble.background = bb
        bubble.visibility = View.GONE
        addView(bubble, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
    }

    private val bubbleTicker = object : Runnable {
        override fun run() {
            if (!running) return
            updateBubble()
            hd.postDelayed(this, 33)
        }
    }

    fun say(msg: String) {
        bubble.text = msg
        bubble.alpha = 0f
        bubble.visibility = View.VISIBLE
        bubbleUntil = android.os.SystemClock.uptimeMillis() + 4000L + 60L * msg.length.coerceAtMost(100)
    }

    private fun updateBubble() {
        if (bubble.visibility != View.VISIBLE) return
        val left = bubbleUntil - android.os.SystemClock.uptimeMillis()
        if (left <= 0L) {
            bubble.visibility = View.GONE
            return
        }
        val s = Renderer.project(Play.x, Play.y + 3.1f, Play.z, width, height)
        if (s == null) {
            bubble.alpha = 0f
            return
        }
        bubble.alpha = if (left < 500L) left / 500f else 1f
        bubble.translationX = s[0] - bubble.width / 2f
        bubble.translationY = s[1] - bubble.height - dp(4)
    }

    private fun send() {
        val m = chatInput.text.toString().trim()
        if (m.isEmpty()) return
        chatInput.setText("")
        addChat(Play.playerName, m)
        say(m)
        Lua.post { Lua.chatted(m) }
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    }

    fun addChat(name: String, msg: String) {
        post {
            chatLines.add("[" + name + "]: " + msg)
            chatLog.text = chatLines.takeLast(30).joinToString("\n")
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun refreshPlayers() {
        playersBox.removeAllViews()
        val head = TextView(act)
        head.text = "Players"
        head.textSize = 11f
        head.typeface = Typeface.DEFAULT_BOLD
        head.setTextColor(Color.WHITE)
        head.setBackgroundColor(0x99000000.toInt())
        head.setPadding(dp(8), dp(3), dp(8), dp(3))
        playersBox.addView(head, LinearLayout.LayoutParams(-1, -2))
        for (p in Scene.players.children) {
            if (p.className != "Player") continue
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setBackgroundColor(0x66000000)
            row.setPadding(dp(6), dp(3), dp(6), dp(3))
            val img = ImageView(act)
            val av = RobloxAuth.avatar
            if (av != null && p === Scene.localPlayer) {
                img.setImageBitmap(av)
            } else {
                img.setImageResource(R.drawable.ic_person)
                img.setColorFilter(Color.WHITE)
            }
            row.addView(img, LinearLayout.LayoutParams(dp(22), dp(22)))
            val t = TextView(act)
            t.text = p.name
            t.textSize = 12f
            t.setTextColor(Color.WHITE)
            t.maxLines = 1
            t.setPadding(dp(6), 0, 0, 0)
            row.addView(t, LinearLayout.LayoutParams(-2, -2))
            playersBox.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    fun start() {
        visibility = View.VISIBLE
        running = true
        tick = 0
        chatLines.clear()
        chatLog.text = ""
        chatRow.visibility = View.GONE
        refreshPlayers()
        hd.post(ticker)
        hd.post(bubbleTicker)
    }

    fun stop() {
        running = false
        hd.removeCallbacks(ticker)
        hd.removeCallbacks(bubbleTicker)
        bubble.visibility = View.GONE
        userLayer.removeAllViews()
        views.clear()
        imgSrc.clear()
        lastBg.clear()
        visibility = View.GONE
    }

    // ---------- UI do jogo (ScreenGui, Frame, TextLabel, TextButton, ImageLabel) ----------
    private fun alive(i: Inst, root: Inst): Boolean {
        var p: Inst? = i
        while (p != null) {
            if (p === root) return true
            p = p.parent
        }
        return false
    }

    private fun syncAll() {
        val root = Scene.playerGui ?: return
        syncKids(root, userLayer, width.toFloat(), height.toFloat())
        val it = views.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (!alive(e.key, root)) {
                (e.value.parent as? FrameLayout)?.removeView(e.value)
                it.remove()
            }
        }
    }

    private fun syncKids(parent: Inst, container: FrameLayout, pw: Float, ph: Float) {
        for (c in parent.children) {
            if (!Defaults.isGui(c.className)) continue
            var v = views[c]
            if (v == null) {
                v = make(c)
                views[c] = v
                container.addView(v, FrameLayout.LayoutParams(-1, -1))
            }
            val r = applyProps(c, v, pw, ph)
            syncKids(c, v, r[0], r[1])
        }
    }

    private fun make(c: Inst): FrameLayout {
        val fl = FrameLayout(act)
        if (c.className == "TextLabel" || c.className == "TextButton") {
            val t = TextView(act)
            t.gravity = Gravity.CENTER
            fl.addView(t, FrameLayout.LayoutParams(-1, -1))
            fl.tag = t
            if (c.className == "TextButton") {
                fl.isClickable = true
                fl.setOnClickListener { Lua.post { Lua.fire(c, "MouseButton1Click"); Lua.fire(c, "Activated") } }
            }
        } else if (c.className == "ImageLabel") {
            val iv = ImageView(act)
            iv.scaleType = ImageView.ScaleType.FIT_XY
            fl.addView(iv, FrameLayout.LayoutParams(-1, -1))
            fl.tag = iv
        }
        return fl
    }

    private fun num(c: Inst, k: String, def: Float) = c.prop(k).toFloatOrNull() ?: def

    private fun applyProps(c: Inst, v: FrameLayout, pw: Float, ph: Float): FloatArray {
        if (c.className == "ScreenGui") {
            v.visibility = if (c.prop("Enabled", "true") == "true") View.VISIBLE else View.GONE
            return floatArrayOf(pw, ph)
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
        return floatArrayOf(w, h)
    }
}