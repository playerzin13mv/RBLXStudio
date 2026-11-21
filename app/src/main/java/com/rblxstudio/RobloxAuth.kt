package com.rblxstudio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

// Login com conta do Roblox (OAuth 2.0 authorization code + PKCE)
// Precisa de um app registrado em create.roblox.com/dashboard/credentials (aba OAuth 2.0 Apps)
object RobloxAuth {
    const val REDIRECT_URI = "rblxstudio://oauth"
    private const val AUTH_URL = "https://apis.roblox.com/oauth/v1/authorize"
    private const val TOKEN_URL = "https://apis.roblox.com/oauth/v1/token"
    private const val REVOKE_URL = "https://apis.roblox.com/oauth/v1/token/revoke"
    private const val USERINFO_URL = "https://apis.roblox.com/oauth/v1/userinfo"
    private const val PREFS = "roblox_auth"

    private val main = Handler(Looper.getMainLooper())
    @Volatile var avatar: Bitmap? = null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // se você registrar o app UMA vez, cole o Client ID aqui e ninguém mais precisa configurar nada
    const val DEFAULT_CLIENT_ID = ""

    fun clientId(c: Context): String {
        val v = prefs(c).getString("client_id", "") ?: ""
        return if (v.isNotEmpty()) v else DEFAULT_CLIENT_ID
    }
    fun clientSecret(c: Context): String = prefs(c).getString("client_secret", "") ?: ""

    fun saveClient(c: Context, id: String, secret: String) {
        prefs(c).edit().putString("client_id", id.trim()).putString("client_secret", secret.trim()).apply()
    }

    fun loggedIn(c: Context) = prefs(c).getString("sub", null) != null
    fun userId(c: Context): String = prefs(c).getString("sub", "") ?: ""
    fun displayName(c: Context): String = prefs(c).getString("nickname", "") ?: ""
    fun username(c: Context): String = prefs(c).getString("username", "") ?: ""
    fun accessToken(c: Context): String = prefs(c).getString("access_token", "") ?: ""

    private fun b64url(b: ByteArray) =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // abre o navegador na tela de autorização do Roblox
    fun begin(a: Activity) {
        val rnd = SecureRandom()
        val vb = ByteArray(48)
        rnd.nextBytes(vb)
        val verifier = b64url(vb)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val sb = ByteArray(16)
        rnd.nextBytes(sb)
        val state = b64url(sb)
        prefs(a).edit().putString("verifier", verifier).putString("state", state).apply()
        val url = AUTH_URL +
            "?client_id=" + enc(clientId(a)) +
            "&redirect_uri=" + enc(REDIRECT_URI) +
            "&scope=" + enc("openid profile") +
            "&response_type=code" +
            "&code_challenge=" + enc(challenge) +
            "&code_challenge_method=S256" +
            "&state=" + enc(state)
        a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun post(url: String, params: Map<String, String>): Pair<Int, String> {
        val body = params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String, bearer: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "Bearer " + bearer)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return Pair(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }


    // ---------- login rápido: só o nome de usuário (ou ID), sem criar app ----------
    private fun postJson(url: String, json: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(json.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return Pair(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun plainGet(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return Pair(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    // aceita "NomeDeUsuario", "@NomeDeUsuario" ou o ID numérico
    fun quickLogin(ctx: Context, input: String, done: (Boolean, String) -> Unit) {
        val q = input.trim().removePrefix("@")
        if (q.isEmpty()) { done(false, "Digite seu usuário do Roblox"); return }
        val app = ctx.applicationContext
        Thread {
            var ok = false
            var msg = ""
            try {
                var id = ""
                var name = ""
                var display = ""
                if (q.all { it.isDigit() }) {
                    val (c, t) = plainGet("https://users.roblox.com/v1/users/" + q)
                    if (c in 200..299) {
                        val u = JSONObject(t)
                        id = u.optLong("id", 0L).toString()
                        name = u.optString("name", "")
                        display = u.optString("displayName", name)
                    }
                }
                if (id.isEmpty() || id == "0") {
                    val body = JSONObject()
                    body.put("usernames", org.json.JSONArray().put(q))
                    body.put("excludeBannedUsers", true)
                    val (c, t) = postJson("https://users.roblox.com/v1/usernames/users", body.toString())
                    if (c in 200..299) {
                        val arr = JSONObject(t).optJSONArray("data")
                        if (arr != null && arr.length() > 0) {
                            val u = arr.getJSONObject(0)
                            id = u.optLong("id", 0L).toString()
                            name = u.optString("name", q)
                            display = u.optString("displayName", name)
                        }
                    } else {
                        msg = "Roblox não respondeu (" + c + ")"
                    }
                }
                if (id.isEmpty() || id == "0") {
                    if (msg.isEmpty()) msg = "Usuário não encontrado"
                } else {
                    prefs(app).edit()
                        .putString("sub", id)
                        .putString("nickname", display.ifEmpty { name })
                        .putString("username", name)
                        .putString("picture", "")
                        .apply()
                    ok = true
                    msg = "Logado como " + display.ifEmpty { name }
                }
            } catch (e: Exception) {
                msg = "Erro de rede: " + (e.message ?: e.javaClass.simpleName)
            }
            main.post { done(ok, msg) }
        }.start()
    }

    // foto de perfil pública (rosto do avatar) pelo ID do usuário
    private fun headshotUrl(ctx: Context): String {
        val saved = prefs(ctx).getString("picture", "") ?: ""
        if (saved.isNotEmpty()) return saved
        val id = userId(ctx)
        if (id.isEmpty()) return ""
        val (c, t) = plainGet("https://thumbnails.roblox.com/v1/users/avatar-headshot?userIds=" + id +
            "&size=150x150&format=Png&isCircular=false")
        if (c !in 200..299) return ""
        val arr = JSONObject(t).optJSONArray("data") ?: return ""
        return if (arr.length() > 0) arr.getJSONObject(0).optString("imageUrl", "") else ""
    }

    // chamado quando o navegador volta para rblxstudio://oauth?code=...&state=...
    fun finish(ctx: Context, data: Uri, done: (Boolean, String) -> Unit) {
        val err = data.getQueryParameter("error")
        if (err != null) {
            done(false, "Login cancelado: " + (data.getQueryParameter("error_description") ?: err))
            return
        }
        val code = data.getQueryParameter("code")
        val state = data.getQueryParameter("state")
        val p = prefs(ctx)
        if (code == null || state == null || state != p.getString("state", null)) {
            done(false, "Resposta de login inválida")
            return
        }
        val verifier = p.getString("verifier", "") ?: ""
        val app = ctx.applicationContext
        Thread {
            var ok = false
            var msg = ""
            try {
                val params = HashMap<String, String>()
                params["grant_type"] = "authorization_code"
                params["code"] = code
                params["code_verifier"] = verifier
                params["client_id"] = clientId(app)
                params["redirect_uri"] = REDIRECT_URI
                val secret = clientSecret(app)
                if (secret.isNotEmpty()) params["client_secret"] = secret
                val (c1, t1) = post(TOKEN_URL, params)
                if (c1 !in 200..299) {
                    msg = "Roblox recusou o login (" + c1 + "): " + t1.take(160)
                } else {
                    val tok = JSONObject(t1)
                    val access = tok.getString("access_token")
                    val (c2, t2) = get(USERINFO_URL, access)
                    if (c2 !in 200..299) {
                        msg = "Não consegui ler o perfil (" + c2 + ")"
                    } else {
                        val u = JSONObject(t2)
                        val nick = u.optString("nickname", u.optString("name", ""))
                        val uname = u.optString("preferred_username", nick)
                        p.edit()
                            .putString("access_token", access)
                            .putString("refresh_token", tok.optString("refresh_token", ""))
                            .putString("sub", u.getString("sub"))
                            .putString("nickname", nick)
                            .putString("username", uname)
                            .putString("picture", u.optString("picture", ""))
                            .remove("verifier").remove("state")
                            .apply()
                        ok = true
                        msg = "Logado como " + nick
                    }
                }
            } catch (e: Exception) {
                msg = "Erro de rede: " + (e.message ?: e.javaClass.simpleName)
            }
            main.post { done(ok, msg) }
        }.start()
    }

    // baixa a foto do perfil (em segundo plano) e deixa redonda
    fun loadAvatar(ctx: Context, onReady: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread {
            try {
                val url = headshotUrl(app)
                if (url.isEmpty()) return@Thread
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    avatar = circle(bmp)
                    if (onReady != null) main.post { onReady() }
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun circle(src: Bitmap): Bitmap {
        val s = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawCircle(s / 2f, s / 2f, s / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, (s - src.width) / 2f, (s - src.height) / 2f, paint)
        return out
    }

    fun logout(ctx: Context) {
        val app = ctx.applicationContext
        val p = prefs(app)
        val refresh = p.getString("refresh_token", "") ?: ""
        val id = clientId(app)
        val secret = clientSecret(app)
        if (refresh.isNotEmpty()) {
            Thread {
                try {
                    val m = HashMap<String, String>()
                    m["token"] = refresh
                    m["client_id"] = id
                    if (secret.isNotEmpty()) m["client_secret"] = secret
                    post(REVOKE_URL, m)
                } catch (e: Exception) {
                }
            }.start()
        }
        p.edit().remove("access_token").remove("refresh_token").remove("sub").remove("nickname")
            .remove("username").remove("picture").apply()
        avatar = null
    }
}
