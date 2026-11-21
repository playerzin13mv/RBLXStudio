package com.rblxstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Acessório 3D já convertido: vértices intercalados (x y z nx ny nz u v), triângulos soltos
class Acc(
    val name: String,
    val type: String,
    val data: FloatArray,
    val min: FloatArray,
    val max: FloatArray,
    var tex: Bitmap?
)

// Avatar do jogador logado: cores do corpo, roupas 2D clássicas e acessórios 3D
object Avatar {
    // cabeça, torso, braço esq, braço dir, perna esq, perna dir (0xRRGGBB)
    @Volatile var cols = intArrayOf(0xF5CD30, 0x0D69AC, 0xF5CD30, 0xF5CD30, 0x4B974B, 0x4B974B)

    // templates das roupas (585x559): camisa, calça, camiseta (imagem solta)
    @Volatile var shirt: Bitmap? = null
    @Volatile var pants: Bitmap? = null
    @Volatile var tshirt: Bitmap? = null
    @Volatile var face: Bitmap? = null
    @Volatile var layered = 0
    @Volatile var accs: List<Acc> = emptyList()

    // sobe a cada mudança; o Renderer reenvia as texturas quando muda
    @Volatile var version = 0

    // resumo da última carga, para mostrar na tela
    @Volatile var status = ""
    private val errors = ArrayList<String>()
    private val ui = Handler(Looper.getMainLooper())

    private val bricks = mapOf(
        1 to 0xF2F3F3, 5 to 0xD7C59A, 9 to 0xE8BAC8, 11 to 0x80BBDC, 18 to 0xCC8E69, 21 to 0xC4281C,
        23 to 0x0D69AC, 24 to 0xF5CD30, 26 to 0x1B2A35, 28 to 0x287F47, 37 to 0x4B974B, 38 to 0xA05F35,
        45 to 0xB4D2E4, 101 to 0xDA867A, 102 to 0x6E99CA, 104 to 0x6B327C, 105 to 0xE29B40, 106 to 0xDA8541,
        119 to 0xA4BD47, 125 to 0xEAB892, 192 to 0x694028, 194 to 0xA3A2A5, 199 to 0x635F62, 208 to 0xE5E4DF,
        217 to 0x7C5C46, 226 to 0xFDEA8D, 1001 to 0xF8F8F8, 1002 to 0xCDCDCD, 1003 to 0x111111, 1004 to 0xFF0000
    )

    // tipos de asset (assetType.id da API do Roblox)
    private const val T_TSHIRT = 2
    private const val T_SHIRT = 11
    private const val T_PANTS = 12
    private const val T_FACE = 18
    private val ACC_TYPES = mapOf(
        8 to "Hat", 17 to "Head", 41 to "Hair", 42 to "Face", 43 to "Neck",
        44 to "Shoulder", 45 to "Front", 46 to "Back", 47 to "Waist"
    )
    private const val MAX_ACCS = 14
    private const val MAX_TRIS = 40000

    fun clear() {
        shirt = null; pants = null; tshirt = null; face = null; layered = 0; accs = emptyList()
        cols = intArrayOf(0xF5CD30, 0x0D69AC, 0xF5CD30, 0xF5CD30, 0x4B974B, 0x4B974B)
        version++
    }

    private class Item(val id: Long, val type: Int, val name: String)

    private val typeByName = mapOf(
        "TShirt" to 2, "Hat" to 8, "Shirt" to 11, "Pants" to 12, "Head" to 17, "Face" to 18,
        "HairAccessory" to 41, "FaceAccessory" to 42, "NeckAccessory" to 43, "ShoulderAccessory" to 44,
        "FrontAccessory" to 45, "BackAccessory" to 46, "WaistAccessory" to 47
    )

    private fun parseItems(arr: org.json.JSONArray?): List<Item> {
        val out = ArrayList<Item>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val aid = a.optLong("id", 0L)
            if (aid == 0L) continue
            val t = a.optJSONObject("assetType")
            var tid = t?.optInt("id", -1) ?: -1
            if (tid <= 0) tid = typeByName[t?.optString("name", "") ?: ""] ?: -1
            out.add(Item(aid, tid, a.optString("name", "Item")))
        }
        return out
    }

    // plano C: lista de IDs vestidos + tipo de cada um pela API da economia
    private fun wearingFallback(id: String): List<Item> {
        val out = ArrayList<Item>()
        val j = JSONObject(Assets.httpGet("https://avatar.roblox.com/v1/users/" + id + "/currently-wearing"))
        val ids = j.optJSONArray("assetIds") ?: return out
        for (i in 0 until minOf(ids.length(), 40)) {
            val aid = ids.getLong(i)
            try {
                val d = JSONObject(Assets.httpGet("https://economy.roblox.com/v2/assets/" + aid + "/details"))
                out.add(Item(aid, d.optInt("AssetTypeId", -1), d.optString("Name", "Item")))
            } catch (e: Exception) {
                errors.add("tipo do item " + aid + ": " + (e.message ?: e.javaClass.simpleName))
            }
        }
        return out
    }

    fun load(ctx: Context, onDone: ((String) -> Unit)? = null) {
        val id = RobloxAuth.userId(ctx)
        if (id.isEmpty()) return
        Thread {
            errors.clear()
            var got = 0
            try {
                val o = JSONObject(Assets.httpGet("https://avatar.roblox.com/v1/users/" + id + "/avatar"))
                loadColors(o)
                var items = parseItems(o.optJSONArray("assets"))
                if (items.isEmpty()) {
                    try {
                        val o2 = JSONObject(Assets.httpGet("https://avatar.roblox.com/v2/avatar/users/" + id + "/avatar"))
                        items = parseItems(o2.optJSONArray("assets"))
                    } catch (e: Exception) {
                        errors.add("avatar v2: " + (e.message ?: e.javaClass.simpleName))
                    }
                }
                if (items.isEmpty()) {
                    try { items = wearingFallback(id) } catch (e: Exception) {
                        errors.add("currently-wearing: " + (e.message ?: e.javaClass.simpleName))
                    }
                }
                got = items.size
                loadWearing(items)
            } catch (e: Exception) {
                errors.add("avatar: " + (e.message ?: e.javaClass.simpleName))
            }
            var s = "Avatar (" + got + " item(ns)): " + (if (shirt != null) "camisa OK" else "sem camisa") + ", " +
                (if (pants != null) "calça OK" else "sem calça") + ", " +
                (if (tshirt != null) "camiseta OK" else "sem camiseta") + ", " +
                (if (face != null) "rosto OK, " else "") + accs.size + " acessório(s)"
            if (layered > 0) s += ", " + layered + " roupa(s) em camadas (ainda não suportada)"
            if (errors.isNotEmpty()) s += "\nFalhou: " + errors.first().take(120) + " (" + errors.size + " erro(s))"
            status = s
            Out.add(s, if (errors.isEmpty()) 0 else 1)
            if (onDone != null) ui.post { onDone(s) }
        }.start()
    }

    private fun loadColors(o: JSONObject) {
        val out = cols.copyOf()
        val c3 = o.optJSONObject("bodyColor3s")
        if (c3 != null) {
            val ks = arrayOf("headColor3", "torsoColor3", "leftArmColor3", "rightArmColor3", "leftLegColor3", "rightLegColor3")
            for (n in 0..5) {
                val hex = c3.optString(ks[n], "").removePrefix("#")
                val v = hex.toIntOrNull(16)
                if (v != null) out[n] = v
            }
        } else {
            val bc = o.optJSONObject("bodyColors")
            if (bc != null) {
                val ks = arrayOf("headColorId", "torsoColorId", "leftArmColorId", "rightArmColorId", "leftLegColorId", "rightLegColorId")
                for (n in 0..5) {
                    val v = bricks[bc.optInt(ks[n], -1)]
                    if (v != null) out[n] = v
                }
            }
        }
        cols = out
        version++
    }

    private fun loadWearing(items: List<Item>) {
        val accList = ArrayList<Acc>()
        layered = 0
        for (item in items) {
            try {
                when (item.type) {
                    T_SHIRT -> { val b = clothingTemplate(item.id); if (b != null) { shirt = b; version++ } }
                    T_PANTS -> { val b = clothingTemplate(item.id); if (b != null) { pants = b; version++ } }
                    T_TSHIRT -> { val b = clothingTemplate(item.id); if (b != null) { tshirt = b; version++ } }
                    T_FACE -> { val b = clothingTemplate(item.id); if (b != null) { face = b; version++ } }
                    in 64..72 -> layered++
                    else -> {
                        val kind = ACC_TYPES[item.type]
                        if (kind != null && accList.size < MAX_ACCS) {
                            val acc = loadAccessory(item.id, item.name, kind)
                            if (acc != null) {
                                accList.add(acc)
                                accs = ArrayList(accList)
                                version++
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                errors.add(item.name + " (" + item.id + "): " + (e.message ?: e.javaClass.simpleName))
            }
        }
        accs = ArrayList(accList)
        version++
    }

    // ---------- download ----------
    private fun bytes(u: String): ByteArray {
        var url = u
        for (hop in 0..4) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 12000
            c.readTimeout = 20000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) RBLXStudio/0.8")
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw Exception("redirect sem destino")
                    url = loc
                    continue
                }
                if (code !in 200..299) throw Exception("HTTP " + code)
                return c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        }
        throw Exception("redirects demais")
    }

    // tenta a API nova (v2 devolve o link do CDN) e cai para a antiga (v1)
    private fun asset(id: Long): ByteArray {
        var err: Exception? = null
        try {
            val c = URL("https://assetdelivery.roblox.com/v2/assetId/" + id).openConnection() as HttpURLConnection
            c.connectTimeout = 12000
            c.readTimeout = 20000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) RBLXStudio/0.8")
            try {
                if (c.responseCode in 200..299) {
                    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val locs = j.optJSONArray("locations")
                    if (locs != null && locs.length() > 0) {
                        val loc = locs.getJSONObject(0).optString("location", "")
                        if (loc.isNotEmpty()) return bytes(loc)
                    }
                } else {
                    err = Exception("v2 HTTP " + c.responseCode)
                }
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            err = e
        }
        try {
            return bytes("https://assetdelivery.roblox.com/v1/asset/?id=" + id)
        } catch (e: Exception) {
            throw Exception((err?.message ?: "sem resposta") + " / " + (e.message ?: e.javaClass.simpleName))
        }
    }

    // Procura IDs de imagem/malha dentro do arquivo do item (XML ou rbxm binário)
    private fun refs(raw: ByteArray): List<Long> {
        val text = rbxText(raw)
        val out = ArrayList<Long>()
        val re = Regex("(?:asset/\\?id=|rbxassetid://|assetId=)(\\d{3,})")
        for (m in re.findAll(text)) {
            val v = m.groupValues[1].toLongOrNull() ?: continue
            if (!out.contains(v)) out.add(v)
        }
        return out
    }

    private fun decodeBmp(b: ByteArray): Bitmap? {
        val o1 = BitmapFactory.Options()
        o1.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(b, 0, b.size, o1)
        var ss = 1
        while (o1.outWidth / ss > 1024 || o1.outHeight / ss > 1024) ss *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = ss
        return BitmapFactory.decodeByteArray(b, 0, b.size, o2)
    }

    // item de roupa -> imagem do template (o item aponta para outro asset, que é a imagem)
    private fun clothingTemplate(itemId: Long): Bitmap? {
        val raw = asset(itemId)
        val direct = decodeBmp(raw)
        if (direct != null) return direct
        for (r in refs(raw)) {
            val bmp = decodeBmp(asset(r))
            if (bmp != null) return bmp
        }
        errors.add("roupa " + itemId + ": imagem não encontrada")
        return null
    }

    // item de acessório -> malha + textura
    private fun loadAccessory(itemId: Long, name: String, kind: String): Acc? {
        val raw = asset(itemId)
        val ids = refs(raw)
        var mesh: Mesh? = null
        var tex: Bitmap? = null
        var meshVer = ""
        for (r in ids) {
            val b = try { asset(r) } catch (e: Exception) { continue }
            if (mesh == null && b.size > 12 && String(b, 0, 8, Charsets.ISO_8859_1).startsWith("version ")) {
                mesh = parseMesh(b)
                if (mesh == null) meshVer = String(b, 0, 12, Charsets.ISO_8859_1).trim()
                continue
            }
            if (tex == null) {
                val bmp = decodeBmp(b)
                if (bmp != null) tex = bmp
            }
        }
        val m = mesh ?: run {
            errors.add(name + ": malha " + (if (meshVer.isEmpty()) "não encontrada" else meshVer + " não suportada"))
            return null
        }
        return Acc(name, kind, m.data, m.min, m.max, tex)
    }

    // ---------- rbxm: descompacta os blocos (LZ4) para achar os textos ----------
    private fun rbxText(raw: ByteArray): String {
        if (raw.size < 32 || String(raw, 0, 8, Charsets.ISO_8859_1) != "<roblox!") {
            return String(raw, Charsets.ISO_8859_1)
        }
        val sb = StringBuilder()
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        var p = 32
        while (p + 16 <= raw.size) {
            val comp = bb.getInt(p + 4)
            val uncomp = bb.getInt(p + 8)
            p += 16
            if (uncomp < 0 || comp < 0) break
            val len = if (comp == 0) uncomp else comp
            if (len < 0 || p + len > raw.size) break
            try {
                if (comp == 0) sb.append(String(raw, p, len, Charsets.ISO_8859_1))
                else sb.append(String(lz4(raw, p, len, uncomp), Charsets.ISO_8859_1))
            } catch (e: Exception) {
            }
            sb.append('\n')
            p += len
        }
        return sb.toString()
    }

    private fun lz4(src: ByteArray, off: Int, len: Int, outLen: Int): ByteArray {
        val dst = ByteArray(outLen)
        var s = off
        val end = off + len
        var d = 0
        while (s < end) {
            val token = src[s++].toInt() and 255
            var lit = token shr 4
            if (lit == 15) {
                while (true) {
                    val b = src[s++].toInt() and 255
                    lit += b
                    if (b != 255) break
                }
            }
            for (k in 0 until lit) dst[d++] = src[s++]
            if (s >= end) break
            val back = (src[s].toInt() and 255) or ((src[s + 1].toInt() and 255) shl 8)
            s += 2
            var ml = token and 15
            if (ml == 15) {
                while (true) {
                    val b = src[s++].toInt() and 255
                    ml += b
                    if (b != 255) break
                }
            }
            ml += 4
            var from = d - back
            for (k in 0 until ml) dst[d++] = dst[from++]
        }
        return dst
    }

    // ---------- malha .mesh (versões 1.xx texto, 2.00 e 3.xx binárias) ----------
    class Mesh(val data: FloatArray, val min: FloatArray, val max: FloatArray)

    private fun finish(d: FloatArray): Mesh {
        val mn = floatArrayOf(1e9f, 1e9f, 1e9f)
        val mx = floatArrayOf(-1e9f, -1e9f, -1e9f)
        var i = 0
        while (i < d.size) {
            for (k in 0..2) {
                if (d[i + k] < mn[k]) mn[k] = d[i + k]
                if (d[i + k] > mx[k]) mx[k] = d[i + k]
            }
            i += 8
        }
        return Mesh(d, mn, mx)
    }

    private fun parseMesh(b: ByteArray): Mesh? {
        val head = String(b, 0, 12, Charsets.ISO_8859_1)
        return try {
            when {
                head.startsWith("version 1.") -> parseMeshV1(b)
                head.startsWith("version 2.") || head.startsWith("version 3.") || head.startsWith("version 4.") ->
                    parseMeshBin(b, head.substring(8, 9).toInt())
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseMeshBin(b: ByteArray, ver: Int): Mesh? {
        val v3 = ver == 3
        val v4 = ver == 4
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var p = 13 // "version X.XX\n"
        val hdrSize = bb.getShort(p).toInt() and 0xffff
        val vSize: Int
        val fSize: Int
        var nVerts: Int
        var nFaces: Int
        var lodCount = 0
        if (v4) {
            vSize = 40
            fSize = 12
            nVerts = bb.getInt(p + 4)
            nFaces = bb.getInt(p + 8)
            lodCount = bb.getShort(p + 12).toInt() and 0xffff
        } else if (v3) {
            vSize = bb.get(p + 2).toInt() and 255
            fSize = bb.get(p + 3).toInt() and 255
            lodCount = bb.getShort(p + 6).toInt() and 0xffff
            nVerts = bb.getInt(p + 8)
            nFaces = bb.getInt(p + 12)
        } else {
            vSize = bb.get(p + 2).toInt() and 255
            fSize = bb.get(p + 3).toInt() and 255
            nVerts = bb.getInt(p + 4)
            nFaces = bb.getInt(p + 8)
        }
        p += hdrSize
        if (nVerts <= 0 || nFaces <= 0 || vSize < 32 || fSize < 12) return null
        val vOff = p
        val fOff = vOff + nVerts * vSize
        // v3: só o nível de detalhe 0
        if ((v3 || v4) && lodCount >= 2) {
            val lodOff = fOff + nFaces * fSize
            val end = bb.getInt(lodOff + 4)
            if (end in 1..nFaces) nFaces = end
        }
        if (nFaces > MAX_TRIS) return null
        val out = FloatArray(nFaces * 3 * 8)
        var w = 0
        for (f in 0 until nFaces) {
            for (k in 0..2) {
                val idx = bb.getInt(fOff + f * fSize + k * 4)
                if (idx < 0 || idx >= nVerts) return null
                val q = vOff + idx * vSize
                out[w++] = bb.getFloat(q); out[w++] = bb.getFloat(q + 4); out[w++] = bb.getFloat(q + 8)
                out[w++] = bb.getFloat(q + 12); out[w++] = bb.getFloat(q + 16); out[w++] = bb.getFloat(q + 20)
                out[w++] = bb.getFloat(q + 24); out[w++] = bb.getFloat(q + 28)  // v já vem de cima para baixo
            }
        }
        return finish(out)
    }

    private fun parseMeshV1(b: ByteArray): Mesh? {
        val text = String(b, Charsets.ISO_8859_1)
        val nl = text.indexOf('\n')
        val scale = if (text.startsWith("version 1.00")) 0.5f else 1f
        val nl2 = text.indexOf('\n', nl + 1)
        val nums = Regex("-?[0-9.]+(?:[eE][-+]?[0-9]+)?").findAll(text.substring(nl2 + 1)).map { it.value.toFloat() }.toList()
        val nTri = nums.size / 27
        if (nTri <= 0 || nTri > MAX_TRIS) return null
        val out = FloatArray(nTri * 3 * 8)
        var w = 0
        for (t in 0 until nTri * 3) {
            val q = t * 9
            out[w++] = nums[q] * scale; out[w++] = nums[q + 1] * scale; out[w++] = nums[q + 2] * scale
            out[w++] = nums[q + 3]; out[w++] = nums[q + 4]; out[w++] = nums[q + 5]
            out[w++] = nums[q + 6]; out[w++] = 1f - nums[q + 7]  // v1: origem embaixo
        }
        return finish(out)
    }
}
