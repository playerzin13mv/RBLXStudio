package com.rblxstudio

import android.content.Context
import android.graphics.Bitmap
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Tela inicial estilo Roblox Studio: Home (Oi, usuário + New + Recent), Recent, My Games, Group Games,
// Open File... e Open by Place ID.
class MainMenu(private val act: MainActivity) : FrameLayout(act) {
    private val body = FrameLayout(act)
    private val navItems = LinkedHashMap<String, TextView>()
    private val userChip = LinearLayout(act)
    private val closeBtn: TextView
    private var token = 0
    private var page = "Home"
    private val iconUrls = HashMap<Long, String>()
    private val pendingIcons = ArrayList<Pair<PlaceIcon, Long>>()
    private var ready = false

    private val cBg = 0xFF3E3E3E.toInt()
    private val cSide = 0xFF383838.toInt()
    private val cCard = 0xFF484848.toInt()

    private fun round(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = act.dp(r).toFloat()
        return g
    }

    init {
        isClickable = true
        setBackgroundColor(cBg)

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL

        // barra de cima: logo + título à esquerda, engrenagem e usuário à direita
        val top = LinearLayout(act)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(0xFF333333.toInt())
        top.setPadding(act.dp(12), act.dp(6), act.dp(10), act.dp(6))
        top.addView(act.iv(R.drawable.logo, 24, null))
        val title = act.tv("RBLXStudio", 15f, Color.WHITE, true)
        title.setPadding(act.dp(8), 0, 0, 0)
        top.addView(title, act.lp(0, WRAP, 1f))
        val gear = act.iv(R.drawable.ic_settings, 22, 0xFFE0E0E0.toInt())
        gear.setPadding(act.dp(2), act.dp(2), act.dp(2), act.dp(2))
        gear.setOnClickListener { act.openSettings() }
        top.addView(gear, act.lp(act.dp(30), act.dp(30)))
        userChip.orientation = LinearLayout.HORIZONTAL
        userChip.gravity = Gravity.CENTER_VERTICAL
        userChip.background = round(0xFF4A4A4A.toInt(), 16)
        userChip.setPadding(act.dp(4), act.dp(3), act.dp(12), act.dp(3))
        userChip.setOnClickListener { act.accountMenu() }
        val ulp = act.lp(WRAP, WRAP)
        ulp.leftMargin = act.dp(10)
        top.addView(userChip, ulp)
        col.addView(top, act.lp(MATCH, WRAP))

        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL

        val side = LinearLayout(act)
        side.orientation = LinearLayout.VERTICAL
        side.setBackgroundColor(cSide)
        side.setPadding(act.dp(8), act.dp(10), act.dp(8), 0)

        val items = listOf(
            Triple("Home", R.drawable.ic_globe, 0xFF4CD964.toInt()),
            Triple("Recent", R.drawable.ic_clock, 0xFFE0E0E0.toInt()),
            Triple("My Games", R.drawable.ic_person, 0xFF4DA3FF.toInt()),
            Triple("Group Games", R.drawable.ic_group, 0xFF4DA3FF.toInt())
        )
        for ((n, ic, tint) in items) {
            side.addView(navItem(n, ic, tint, true), act.lp(MATCH, WRAP))
        }
        val sep = View(act)
        sep.setBackgroundColor(0xFF555555.toInt())
        val slp = act.lp(MATCH, act.dp(1))
        slp.setMargins(act.dp(6), act.dp(8), act.dp(6), act.dp(8))
        side.addView(sep, slp)
        side.addView(navItem("Open File...", R.drawable.ic_open, 0xFFFFC857.toInt(), false), act.lp(MATCH, WRAP))
        side.addView(navItem("Open by Place ID", R.drawable.ic_import, 0xFFE0E0E0.toInt(), false), act.lp(MATCH, WRAP))

        row.addView(side, act.lp(act.dp(170), MATCH))
        row.addView(body, act.lp(0, MATCH, 1f))
        col.addView(row, act.lp(MATCH, 0, 1f))
        addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        closeBtn = act.tv("Fechar", 12f, C_TEXT, true)
        closeBtn.setPadding(act.dp(14), act.dp(7), act.dp(14), act.dp(7))
        closeBtn.background = round(0xFF555555.toInt(), 6)
        closeBtn.setOnClickListener { hide() }

        refreshUser()
        select("Home")
        ready = true
    }

    private fun navItem(name: String, icon: Int, tint: Int, page: Boolean): View {
        val r = LinearLayout(act)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(act.dp(10), act.dp(10), act.dp(8), act.dp(10))
        r.addView(act.iv(icon, 20, tint), act.lp(act.dp(20), act.dp(20)))
        val t = act.tv(name, 13f, C_DIM, true)
        t.setPadding(act.dp(10), 0, 0, 0)
        t.maxLines = 1
        r.addView(t, act.lp(WRAP, WRAP))
        if (page) {
            navItems[name] = t
            r.setOnClickListener { select(name) }
        } else if (name == "Open File...") {
            r.setOnClickListener { act.pickFile() }
        } else {
            r.setOnClickListener { act.openByPlaceId() }
        }
        r.tag = name
        return r
    }

    // chip do usuário (foto + nome) no canto de cima
    fun refreshUser() {
        userChip.removeAllViews()
        val logged = RobloxAuth.loggedIn(act)
        val img = ImageView(act)
        val av = RobloxAuth.avatar
        if (logged && av != null) {
            img.setImageBitmap(av)
        } else {
            img.setImageResource(R.drawable.ic_person)
            img.setColorFilter(0xFFBDBDBD.toInt())
            img.setPadding(act.dp(3), act.dp(3), act.dp(3), act.dp(3))
        }
        userChip.addView(img, act.lp(act.dp(26), act.dp(26)))
        val n = act.tv(if (logged) RobloxAuth.displayName(act) else "Entrar", 13f, Color.WHITE, true)
        n.maxLines = 1
        n.setPadding(act.dp(8), 0, 0, 0)
        userChip.addView(n, act.lp(WRAP, WRAP))
        if (ready && visibility == View.VISIBLE && (page == "Home" || page == "My Games" || page == "Group Games")) select(page)
    }

    fun show() {
        refreshUser()
        select("Home")
        visibility = View.VISIBLE
        bringToFront()
    }

    fun hide() {
        token++
        visibility = View.GONE
    }

    private fun select(n: String) {
        page = n
        token++
        for ((k, v) in navItems) {
            val on = k == n
            v.setTextColor(if (on) Color.WHITE else C_DIM)
            val p = v.parent as? View
            p?.background = if (on) round(C_SEL, 6) else null
        }
        body.removeAllViews()
        val v: View = when (n) {
            "Home" -> buildHome()
            "Recent" -> buildRecent()
            "My Games" -> buildMyGames()
            else -> buildGroupGames()
        }
        body.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
        lp.setMargins(0, act.dp(8), act.dp(12), 0)
        body.addView(closeBtn, lp)
    }

    // ---------- helpers de página ----------
    private fun scroller(col: LinearLayout): View {
        col.setPadding(act.dp(20), act.dp(14), act.dp(20), act.dp(20))
        val sv = ScrollView(act)
        sv.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        return sv
    }

    private fun column(): LinearLayout {
        val c = LinearLayout(act)
        c.orientation = LinearLayout.VERTICAL
        return c
    }

    private fun heading(text: String): TextView {
        val t = act.tv(text, 19f, Color.WHITE, true)
        t.setPadding(0, act.dp(6), 0, act.dp(10))
        return t
    }

    private fun message(text: String): TextView {
        val t = act.tv(text, 12f, C_DIM)
        t.setPadding(0, act.dp(8), 0, act.dp(8))
        return t
    }

    private fun button(text: String, click: () -> Unit): TextView {
        val b = act.tv(text, 12f, Color.WHITE, true)
        b.gravity = Gravity.CENTER
        b.background = round(C_SEL, 6)
        b.setPadding(act.dp(16), act.dp(8), act.dp(16), act.dp(8))
        b.setOnClickListener { click() }
        return b
    }

    // linha rolável de cartões
    private fun cardRow(cards: List<View>): View {
        val h = HorizontalScrollView(act)
        h.isHorizontalScrollBarEnabled = false
        val r = LinearLayout(act)
        r.orientation = LinearLayout.HORIZONTAL
        for (c in cards) {
            val lp = act.lp(act.dp(138), WRAP)
            lp.rightMargin = act.dp(10)
            r.addView(c, lp)
        }
        h.addView(r, FrameLayout.LayoutParams(WRAP, WRAP))
        return h
    }

    // grade com 4 colunas
    private fun cardGrid(cards: List<View>): View {
        val g = LinearLayout(act)
        g.orientation = LinearLayout.VERTICAL
        var i = 0
        while (i < cards.size) {
            val r = LinearLayout(act)
            r.orientation = LinearLayout.HORIZONTAL
            for (k in 0 until 4) {
                val lp = act.lp(0, WRAP, 1f)
                lp.setMargins(0, 0, act.dp(10), act.dp(10))
                if (i + k < cards.size) r.addView(cards[i + k], lp) else r.addView(View(act), lp)
            }
            g.addView(r, act.lp(MATCH, WRAP))
            i += 4
        }
        return g
    }

    private fun cardBase(): LinearLayout {
        val c = LinearLayout(act)
        c.orientation = LinearLayout.VERTICAL
        c.background = round(cCard, 8)
        c.setPadding(act.dp(6), act.dp(6), act.dp(6), act.dp(8))
        return c
    }

    private fun label(c: LinearLayout, name: String, sub: String) {
        val t = act.tv(name, 12f, Color.WHITE, true)
        t.setPadding(act.dp(2), act.dp(7), 0, 0)
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        c.addView(t, act.lp(MATCH, WRAP))
        val d = act.tv(sub, 10f, C_DIM)
        d.setPadding(act.dp(2), act.dp(1), 0, 0)
        d.maxLines = 1
        d.ellipsize = android.text.TextUtils.TruncateAt.END
        c.addView(d, act.lp(MATCH, WRAP))
    }

    // cartão de template (aba New)
    private fun templateCard(name: String, desc: String, kind: Int): View {
        val c = cardBase()
        c.addView(Preview(act, kind), act.lp(MATCH, act.dp(120)))
        label(c, name, desc)
        c.setOnClickListener { act.openTemplate(name) }
        return c
    }

    // cartão de lugar: ícone do Place ou, se não tiver, o globo verde
    private fun placeCard(name: String, sub: String, universeId: Long, click: () -> Unit): View {
        val c = cardBase()
        val icon = PlaceIcon(act)
        c.addView(icon, act.lp(MATCH, act.dp(120)))
        label(c, name, sub)
        c.setOnClickListener { click() }
        if (universeId > 0L) pendingIcons.add(Pair(icon, universeId))
        return c
    }

    // busca os ícones dos lugares na internet e coloca nos cartões
    private fun loadIcons(my: Int) {
        val list = ArrayList(pendingIcons)
        pendingIcons.clear()
        if (list.isEmpty()) return
        Thread {
            try {
                val need = list.map { it.second }.filter { !iconUrls.containsKey(it) }
                if (need.isNotEmpty()) {
                    val got = RobloxApi.icons(need)
                    synchronized(iconUrls) { iconUrls.putAll(got) }
                }
            } catch (e: Exception) {
            }
            act.runOnUiThread {
                if (my == token) {
                    for ((icon, uid) in list) {
                        val url = synchronized(iconUrls) { iconUrls[uid] }
                        if (url != null) Assets.image(url) { b -> if (b != null && my == token) icon.setBitmap(b) }
                    }
                }
            }
        }.start()
    }

    private fun ago(ms: Long): String {
        if (ms <= 0L) return "arquivo no aparelho"
        val m = (System.currentTimeMillis() - ms) / 60000L
        return when {
            m < 1L -> "agora mesmo"
            m < 60L -> "há " + m + " min"
            m < 60L * 24L -> "há " + (m / 60L) + " h"
            else -> "há " + (m / (60L * 24L)) + " dia(s)"
        }
    }

    private fun recentCards(max: Int): List<View> {
        val out = ArrayList<View>()
        val names = Storage.list(act)
        for (n in names) {
            if (out.size >= max) break
            val cloud = Storage.cloud(act, n)
            val sub = (if (cloud != null) "Roblox place · " else "Arquivo · ") + ago(Storage.modified(act, n))
            out.add(placeCard(n, sub, if (cloud != null) cloud[1] else 0L) { act.openSaved(n) })
        }
        return out
    }

    // ---------- Home ----------
    private fun buildHome(): View {
        val my = token
        val col = column()

        // "Oi, nome" com a foto do lado
        val hi = LinearLayout(act)
        hi.orientation = LinearLayout.HORIZONTAL
        hi.gravity = Gravity.CENTER_VERTICAL
        hi.setPadding(0, 0, 0, act.dp(8))
        val logged = RobloxAuth.loggedIn(act)
        val img = ImageView(act)
        val av = RobloxAuth.avatar
        if (logged && av != null) {
            img.setImageBitmap(av)
        } else {
            img.setImageResource(R.drawable.ic_person)
            img.setColorFilter(0xFFBDBDBD.toInt())
            img.background = round(0xFF555555.toInt(), 28)
            img.setPadding(act.dp(10), act.dp(10), act.dp(10), act.dp(10))
        }
        hi.addView(img, act.lp(act.dp(52), act.dp(52)))
        val hiText = LinearLayout(act)
        hiText.orientation = LinearLayout.VERTICAL
        hiText.setPadding(act.dp(12), 0, 0, 0)
        if (logged) {
            hiText.addView(act.tv("Oi, " + RobloxAuth.displayName(act), 22f, Color.WHITE, true), act.lp(WRAP, WRAP))
            hiText.addView(act.tv("@" + RobloxAuth.username(act), 12f, C_DIM), act.lp(WRAP, WRAP))
        } else {
            hiText.addView(act.tv("Oi!", 22f, Color.WHITE, true), act.lp(WRAP, WRAP))
            hiText.addView(act.tv("Entre com sua conta do Roblox para ver seus lugares", 12f, C_DIM), act.lp(WRAP, WRAP))
        }
        hi.addView(hiText, act.lp(0, WRAP, 1f))
        col.addView(hi, act.lp(MATCH, WRAP))
        if (!logged) {
            val b = button("Entrar") { act.accountMenu() }
            val blp = act.lp(WRAP, WRAP)
            blp.bottomMargin = act.dp(6)
            col.addView(b, blp)
        }

        col.addView(heading("New"), act.lp(WRAP, WRAP))
        col.addView(cardRow(listOf(
            templateCard("Baseplate", "Chão grande e cinza", 0),
            templateCard("Classic Baseplate", "O chão clássico com studs", 1),
            templateCard("Empty Place", "Só o ponto de spawn", 2)
        )), act.lp(MATCH, WRAP))

        col.addView(heading("Recent"), act.lp(WRAP, WRAP))
        val rc = recentCards(8)
        if (rc.isEmpty()) col.addView(message("Nenhum lugar salvo ainda. Crie um projeto e salve em File > Salvar."), act.lp(WRAP, WRAP))
        else col.addView(cardRow(rc), act.lp(MATCH, WRAP))

        loadIcons(my)
        return scroller(col)
    }

    // ---------- Recent ----------
    private fun buildRecent(): View {
        val my = token
        val col = column()
        col.addView(heading("Recent"), act.lp(WRAP, WRAP))
        val rc = recentCards(60)
        if (rc.isEmpty()) col.addView(message("Nenhum lugar salvo ainda"), act.lp(WRAP, WRAP))
        else col.addView(cardGrid(rc), act.lp(MATCH, WRAP))
        loadIcons(my)
        return scroller(col)
    }

    // ---------- My Games ----------
    private fun buildMyGames(): View {
        val my = token
        val col = column()
        col.addView(heading("My Games"), act.lp(WRAP, WRAP))
        if (!RobloxAuth.loggedIn(act)) {
            col.addView(message("Entre com sua conta para ver os seus lugares."), act.lp(WRAP, WRAP))
            col.addView(button("Entrar") { act.accountMenu() }, act.lp(WRAP, WRAP))
            return scroller(col)
        }
        val holder = column()
        holder.addView(message("Carregando seus lugares..."), act.lp(WRAP, WRAP))
        col.addView(holder, act.lp(MATCH, WRAP))
        val uid = RobloxAuth.userId(act)
        val cookie = RobloxAuth.cookie
        Thread {
            var list: List<RobloxApi.PlaceInfo> = emptyList()
            var err = ""
            try {
                list = RobloxApi.userGames(uid, cookie)
            } catch (e: Exception) {
                err = e.message ?: "erro de rede"
            }
            act.runOnUiThread {
                if (my == token) {
                    holder.removeAllViews()
                    if (err.isNotEmpty()) {
                        holder.addView(message("Não consegui carregar os lugares (" + err + ")."), act.lp(WRAP, WRAP))
                        holder.addView(button("Tentar de novo") { select("My Games") }, act.lp(WRAP, WRAP))
                    } else if (list.isEmpty()) {
                        holder.addView(message("Você ainda não tem lugares públicos. Os privados só aparecem se você entrar pelo site (Login pelo site)."), act.lp(WRAP, WRAP))
                    } else {
                        holder.addView(cardGrid(list.map { cloudCard(it) }), act.lp(MATCH, WRAP))
                        loadIcons(my)
                    }
                }
            }
        }.start()
        return scroller(col)
    }

    private fun cloudCard(p: RobloxApi.PlaceInfo): View =
        placeCard(p.name, "Place ID " + p.placeId, p.universeId) { act.openCloudPlace(p) }

    // ---------- Group Games ----------
    private fun buildGroupGames(): View {
        val my = token
        val col = column()
        col.addView(heading("Group Games"), act.lp(WRAP, WRAP))
        if (!RobloxAuth.loggedIn(act)) {
            col.addView(message("Entre com sua conta para ver os lugares dos seus grupos."), act.lp(WRAP, WRAP))
            col.addView(button("Entrar") { act.accountMenu() }, act.lp(WRAP, WRAP))
            return scroller(col)
        }
        val holder = column()
        holder.addView(message("Carregando seus grupos..."), act.lp(WRAP, WRAP))
        col.addView(holder, act.lp(MATCH, WRAP))
        val uid = RobloxAuth.userId(act)
        Thread {
            val result = ArrayList<Pair<String, List<RobloxApi.PlaceInfo>>>()
            var err = ""
            try {
                for (g in RobloxApi.groups(uid).take(10)) {
                    try {
                        val gg = RobloxApi.groupGames(g.id)
                        if (gg.isNotEmpty()) result.add(Pair(g.name, gg))
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
                err = e.message ?: "erro de rede"
            }
            act.runOnUiThread {
                if (my == token) {
                    holder.removeAllViews()
                    if (err.isNotEmpty()) {
                        holder.addView(message("Não consegui carregar os grupos (" + err + ")."), act.lp(WRAP, WRAP))
                        holder.addView(button("Tentar de novo") { select("Group Games") }, act.lp(WRAP, WRAP))
                    } else if (result.isEmpty()) {
                        holder.addView(message("Nenhum dos seus grupos tem lugares públicos."), act.lp(WRAP, WRAP))
                    } else {
                        for ((gname, games) in result) {
                            val t = act.tv(gname, 14f, Color.WHITE, true)
                            t.setPadding(0, act.dp(8), 0, act.dp(8))
                            holder.addView(t, act.lp(WRAP, WRAP))
                            holder.addView(cardRow(games.map { cloudCard(it) }), act.lp(MATCH, WRAP))
                        }
                        loadIcons(my)
                    }
                }
            }
        }.start()
        return scroller(col)
    }

    // ---------- ícone do lugar: imagem do Roblox ou o globo verde do Studio ----------
    class PlaceIcon(ctx: Context) : View(ctx) {
        private var bmp: Bitmap? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        fun setBitmap(b: Bitmap) {
            bmp = b
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val w = width.toFloat()
            val h = height.toFloat()
            val r = 6f * d
            val rect = RectF(0f, 0f, w, h)
            val clip = Path()
            clip.addRoundRect(rect, r, r, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            val b = bmp
            if (b != null) {
                val s = maxOf(w / b.width, h / b.height)
                val dw = b.width * s
                val dh = b.height * s
                c.drawBitmap(b, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), p)
            } else {
                p.style = Paint.Style.FILL
                p.color = 0xFF3A3A3A.toInt()
                c.drawRect(rect, p)
                // globo verde (ícone padrão dos lugares sem imagem)
                val cx = w / 2f
                val cy = h / 2f
                val rad = minOf(w, h) * 0.34f
                p.color = 0xFF3DDC6E.toInt()
                c.drawCircle(cx, cy, rad, p)
                p.color = 0xFF3A3A3A.toInt()
                val bite = Path()
                bite.moveTo(cx - rad * 0.95f, cy - rad * 0.35f)
                bite.lineTo(cx - rad * 0.45f, cy - rad * 0.05f)
                bite.lineTo(cx - rad * 0.30f, cy + rad * 0.45f)
                bite.lineTo(cx - rad * 0.05f, cy + rad * 0.70f)
                bite.lineTo(cx - rad * 0.20f, cy + rad * 1.05f)
                bite.lineTo(cx - rad * 1.05f, cy + rad * 0.60f)
                bite.close()
                c.drawPath(bite, p)
                c.drawRect(cx - rad * 0.10f, cy - rad * 0.40f, cx + rad * 0.40f, cy - rad * 0.12f, p)
            }
            c.restore()
        }
    }

    // miniatura desenhada à mão de cada template (0 = Baseplate, 1 = Classic, 2 = Vazio)
    private class Preview(ctx: Context, private val kind: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val w = width.toFloat()
            val h = height.toFloat()
            if (kind == 2) {
                // Empty Place: globo verde em fundo cinza, igual ao Studio
                p.style = Paint.Style.FILL
                p.color = 0xFF7A7A7A.toInt()
                c.drawRoundRect(RectF(0f, 0f, w, h), 6f * d, 6f * d, p)
                val cx = w / 2f
                val cy = h / 2f
                val rad = minOf(w, h) * 0.34f
                p.color = 0xFF3DDC6E.toInt()
                c.drawCircle(cx, cy, rad, p)
                p.color = 0xFF7A7A7A.toInt()
                val bite = Path()
                bite.moveTo(cx - rad * 0.95f, cy - rad * 0.35f)
                bite.lineTo(cx - rad * 0.45f, cy - rad * 0.05f)
                bite.lineTo(cx - rad * 0.30f, cy + rad * 0.45f)
                bite.lineTo(cx - rad * 0.05f, cy + rad * 0.70f)
                bite.lineTo(cx - rad * 0.20f, cy + rad * 1.05f)
                bite.lineTo(cx - rad * 1.05f, cy + rad * 0.60f)
                bite.close()
                c.drawPath(bite, p)
                c.drawRect(cx - rad * 0.10f, cy - rad * 0.40f, cx + rad * 0.40f, cy - rad * 0.12f, p)
                return
            }
            // Baseplate / Classic: cubo branco sobre fundo colorido, como no Studio
            p.style = Paint.Style.FILL
            p.color = if (kind == 0) 0xFF3F7FE0.toInt() else 0xFF5F9B4B.toInt()
            c.drawRoundRect(RectF(0f, 0f, w, h), 6f * d, 6f * d, p)
            val cx = w / 2f
            val cy = h / 2f
            val s = minOf(w, h) * 0.30f
            p.color = 0xFFEDEDED.toInt()
            val top = Path()
            top.moveTo(cx, cy - s * 1.05f)
            top.lineTo(cx + s, cy - s * 0.5f)
            top.lineTo(cx, cy + 0.02f * s)
            top.lineTo(cx - s, cy - s * 0.5f)
            top.close()
            c.drawPath(top, p)
            p.color = 0xFFF8F8F8.toInt()
            val left = Path()
            left.moveTo(cx - s, cy - s * 0.5f)
            left.lineTo(cx, cy + 0.02f * s)
            left.lineTo(cx, cy + s * 1.05f)
            left.lineTo(cx - s, cy + s * 0.5f)
            left.close()
            c.drawPath(left, p)
            p.color = 0xFFD9D9D9.toInt()
            val right = Path()
            right.moveTo(cx + s, cy - s * 0.5f)
            right.lineTo(cx, cy + 0.02f * s)
            right.lineTo(cx, cy + s * 1.05f)
            right.lineTo(cx + s, cy + s * 0.5f)
            right.close()
            c.drawPath(right, p)
            p.color = if (kind == 0) 0xFF3F7FE0.toInt() else 0xFF5F9B4B.toInt()
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f * d
            c.drawLine(cx, cy + 0.02f * s, cx, cy + s * 1.05f, p)
            c.drawLine(cx, cy + 0.02f * s, cx + s, cy - s * 0.5f, p)
            c.drawLine(cx, cy + 0.02f * s, cx - s, cy - s * 0.5f, p)
        }
    }
}
