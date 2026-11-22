package com.rblxstudio

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

// Publicação pelo Open Cloud do Roblox.
// - Lugar: POST /universes/v1/{universeId}/places/{placeId}/versions (chave de API com "universe-places" Write)
// - Modelo: POST /assets/v1/assets (chave de API com "assets" Write)
object Publish {
    private fun prefs(c: Context) = c.getSharedPreferences("publish", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = prefs(c).getString("api_key", "") ?: ""
    fun placeId(c: Context): Long = prefs(c).getLong("place_id", 0L)

    fun universeId(c: Context): Long = prefs(c).getLong("universe_id", 0L)

    fun save(c: Context, key: String, placeId: Long, universeId: Long) {
        prefs(c).edit().putString("api_key", key.trim()).putLong("place_id", placeId).putLong("universe_id", universeId).apply()
    }

    fun saveKey(c: Context, key: String) {
        prefs(c).edit().putString("api_key", key.trim()).apply()
    }

    private class Resp(val code: Int, val body: String)

    private fun friendly(code: Int, body: String): String {
        val extra = body.take(200).ifEmpty { "sem detalhes" }
        return when (code) {
            400 -> "o Roblox recusou o arquivo ou os dados (400): $extra"
            401 -> "chave de API inválida ou expirada (401)"
            403 -> "a chave não tem permissão para isso (403). Confira a permissão e se o jogo está na lista da chave, e deixe o IP em 0.0.0.0/0"
            404 -> "Place ID ou Universe ID não encontrado (404)"
            409 -> "conflito (409): o lugar está aberto em Team Create ou já em atualização"
            429 -> "muitos pedidos (429): espere um minuto e tente de novo"
            else -> if (code >= 500) "o Roblox está com problema agora ($code)" else "erro $code: $extra"
        }
    }

    private fun send(
        url: String, method: String, key: String,
        contentType: String?, body: ByteArray?
    ): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20000
            c.readTimeout = 120000
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Resp(code, text)
        } finally {
            c.disconnect()
        }
    }

    // Place ID -> Universe ID (rota pública)
    fun universeOf(placeId: Long): Long {
        val s = RobloxApi.get("https://apis.roblox.com/universes/v1/places/" + placeId + "/universe")
        return JSONObject(s).optLong("universeId", 0L)
    }

    // Publica o arquivo de lugar (XML .rbxlx ou binário .rbxl). published = false guarda só como "Saved".
    // Devolve o número da versão. Lança Exception com texto em português se falhar.
    fun publishPlace(key: String, universeId: Long, placeId: Long, data: ByteArray, binary: Boolean, published: Boolean): Long {
        if (key.isBlank()) throw Exception("falta a chave de API")
        val url = "https://apis.roblox.com/universes/v1/" + universeId + "/places/" + placeId +
            "/versions?versionType=" + (if (published) "Published" else "Saved")
        val r = send(url, "POST", key.trim(), if (binary) "application/octet-stream" else "application/xml", data)
        if (r.code !in 200..299) throw Exception(friendly(r.code, r.body))
        return try {
            JSONObject(r.body).optLong("versionNumber", 0L)
        } catch (e: Exception) {
            0L
        }
    }

    private fun multipart(boundary: String, requestJson: String, fileName: String, fileType: String, file: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        fun w(s: String) = o.write(s.toByteArray(Charsets.UTF_8))
        w("--$boundary\r\n")
        w("Content-Disposition: form-data; name=\"request\"\r\n")
        w("Content-Type: application/json\r\n\r\n")
        w(requestJson)
        w("\r\n--$boundary\r\n")
        w("Content-Disposition: form-data; name=\"fileContent\"; filename=\"$fileName\"\r\n")
        w("Content-Type: $fileType\r\n\r\n")
        o.write(file)
        w("\r\n--$boundary--\r\n")
        return o.toByteArray()
    }

    // Envia um modelo (.rbxmx) como asset da conta. Devolve o Asset ID.
    fun uploadModel(key: String, userId: String, name: String, data: ByteArray): String {
        if (key.isBlank()) throw Exception("falta a chave de API")
        if (userId.isBlank()) throw Exception("entre com sua conta do Roblox primeiro")
        val req = JSONObject()
            .put("assetType", "Model")
            .put("displayName", name.take(50).ifEmpty { "Model" })
            .put("description", "Enviado pelo RBLXStudio")
            .put("creationContext", JSONObject().put("creator", JSONObject().put("userId", userId)))
        val boundary = "----rblxstudio" + System.currentTimeMillis()
        val body = multipart(boundary, req.toString(), "model.rbxmx", "model/x-rbxm", data)
        val r = send("https://apis.roblox.com/assets/v1/assets", "POST", key.trim(), "multipart/form-data; boundary=$boundary", body)
        if (r.code !in 200..299) throw Exception(friendly(r.code, r.body))
        var op = JSONObject(r.body)
        val path = op.optString("path", "")
        if (path.isEmpty()) throw Exception("resposta inesperada do Roblox")
        // o envio é assíncrono: consulta a operação até terminar (uns 60 s no máximo)
        var tries = 0
        while (!op.optBoolean("done", false)) {
            if (tries++ >= 30) throw Exception("o Roblox ainda está processando; veja em create.roblox.com > Creations")
            Thread.sleep(2000)
            val g = send("https://apis.roblox.com/assets/v1/" + path, "GET", key.trim(), null, null)
            if (g.code !in 200..299) throw Exception(friendly(g.code, g.body))
            op = JSONObject(g.body)
        }
        val err = op.optJSONObject("error")
        if (err != null) throw Exception(err.optString("message", "o Roblox recusou o modelo"))
        val id = op.optJSONObject("response")?.optString("assetId", "") ?: ""
        return if (id.isEmpty()) "(sem ID)" else id
    }
}
