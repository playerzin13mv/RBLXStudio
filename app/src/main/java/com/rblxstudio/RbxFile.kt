package com.rblxstudio

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Leitor mínimo de arquivos do Roblox (.rbxm binário e XML antigo).
// Só pega o que o avatar precisa: nomes, texto (MeshId, TextureID...), Size/Scale/Offset e CFrame dos Attachments.
class RNode(val cls: String) {
    var name = ""
    var parent: RNode? = null
    val kids = ArrayList<RNode>()
    val str = HashMap<String, String>()
    val vec = HashMap<String, FloatArray>()   // Vector3: x y z
    val cf = HashMap<String, FloatArray>()    // CFrame: x y z r00 r01 r02 r10 r11 r12 r20 r21 r22
    val num = HashMap<String, Float>()        // int, float e enum (Shape, Material...)
    val bool = HashMap<String, Boolean>()     // Anchored, CanCollide...
    val col = HashMap<String, FloatArray>()   // Color3 / Color3uint8: r g b de 0 a 1

    fun find(pred: (RNode) -> Boolean): RNode? {
        if (pred(this)) return this
        for (k in kids) {
            val r = k.find(pred)
            if (r != null) return r
        }
        return null
    }

    fun all(out: ArrayList<RNode> = ArrayList()): ArrayList<RNode> {
        out.add(this)
        for (k in kids) k.all(out)
        return out
    }

    fun text(vararg keys: String): String {
        for (k in keys) {
            for ((pk, v) in str) {
                if (pk.equals(k, ignoreCase = true) && v.isNotEmpty()) return v
            }
        }
        return ""
    }
}

object RbxFile {
    private class Cls(val id: Int, val name: String, val refs: IntArray)

    // motivo da última falha de leitura (para mostrar ao usuário)
    @Volatile var lastError = ""

    // as 24 rotações "especiais" do formato binário: direita = N[(id-1)/6], cima = N[(id-1)%6], trás = direita x cima
    private val NORMALS = arrayOf(
        floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f),
        floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, -1f)
    )

    private fun specialRot(id: Int): FloatArray {
        val a = id - 1
        val rt = NORMALS[(a / 6).coerceIn(0, 5)]
        val up = NORMALS[(a % 6).coerceIn(0, 5)]
        val bk = floatArrayOf(
            rt[1] * up[2] - rt[2] * up[1],
            rt[2] * up[0] - rt[0] * up[2],
            rt[0] * up[1] - rt[1] * up[0]
        )
        // linhas da matriz: colunas são direita, cima, trás
        return floatArrayOf(rt[0], up[0], bk[0], rt[1], up[1], bk[1], rt[2], up[2], bk[2])
    }

    fun parse(raw: ByteArray): List<RNode>? {
        lastError = ""
        return try {
            if (raw.size > 32 && String(raw, 0, 8, Charsets.ISO_8859_1) == "<roblox!") parseBin(raw)
            else if (raw.size > 20 && String(raw, 0, 10, Charsets.ISO_8859_1).trimStart().startsWith("<roblox")) parseXml(raw)
            else {
                lastError = "não é um arquivo do Roblox (.rbxl, .rbxm, .rbxlx ou .rbxmx)"
                null
            }
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    // ---------- binário ----------
    private class Rd(val b: ByteBuffer) {
        fun u32(): Int = b.int
        fun str(): String {
            val n = b.int
            if (n < 0 || n > b.remaining()) throw Exception("texto")
            val a = ByteArray(n)
            b.get(a)
            return String(a, Charsets.UTF_8)
        }

        // inteiros intercalados (byte mais alto primeiro)
        fun ints(n: Int): IntArray {
            val a = ByteArray(n * 4)
            b.get(a)
            val o = IntArray(n)
            for (i in 0 until n) {
                o[i] = ((a[i].toInt() and 255) shl 24) or ((a[n + i].toInt() and 255) shl 16) or
                    ((a[2 * n + i].toInt() and 255) shl 8) or (a[3 * n + i].toInt() and 255)
            }
            return o
        }

        fun zig(n: Int): IntArray {
            val v = ints(n)
            for (i in 0 until n) v[i] = (v[i] ushr 1) xor -(v[i] and 1)
            return v
        }

        fun refs(n: Int): IntArray {
            val v = zig(n)
            for (i in 1 until n) v[i] += v[i - 1]
            return v
        }

        fun floats(n: Int): FloatArray {
            val v = ints(n)
            val o = FloatArray(n)
            for (i in 0 until n) o[i] = java.lang.Float.intBitsToFloat((v[i] ushr 1) or (v[i] shl 31))
            return o
        }
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
                    val x = src[s++].toInt() and 255
                    lit += x
                    if (x != 255) break
                }
            }
            for (k in 0 until lit) dst[d++] = src[s++]
            if (s >= end) break
            val back = (src[s].toInt() and 255) or ((src[s + 1].toInt() and 255) shl 8)
            s += 2
            var ml = token and 15
            if (ml == 15) {
                while (true) {
                    val x = src[s++].toInt() and 255
                    ml += x
                    if (x != 255) break
                }
            }
            ml += 4
            var from = d - back
            for (k in 0 until ml) dst[d++] = dst[from++]
        }
        return dst
    }

    private fun parseBin(raw: ByteArray): List<RNode> {
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        var p = 32
        val classes = HashMap<Int, Cls>()
        val nodes = HashMap<Int, RNode>()
        val parents = ArrayList<IntArray>()
        var zstd = false
        while (p + 16 <= raw.size) {
            val tag = String(raw, p, 4, Charsets.ISO_8859_1)
            val comp = bb.getInt(p + 4)
            val uncomp = bb.getInt(p + 8)
            p += 16
            if (uncomp < 0 || comp < 0) break
            val len = if (comp == 0) uncomp else comp
            if (len < 0 || p + len > raw.size) break
            if (comp != 0 && len >= 4 && raw[p] == 0x28.toByte() && raw[p + 1] == 0xB5.toByte() && raw[p + 2] == 0x2F.toByte() && raw[p + 3] == 0xFD.toByte()) {
                // bloco comprimido com ZSTD (arquivos mais novos): ainda não sei ler
                zstd = true
                p += len
                continue
            }
            val data = if (comp == 0) raw.copyOfRange(p, p + len) else lz4(raw, p, len, uncomp)
            p += len
            if (tag.startsWith("END")) break
            val r = Rd(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN))
            try {
                when {
                    tag.startsWith("INST") -> {
                        val id = r.u32()
                        val name = r.str()
                        r.b.get()  // é serviço?
                        val n = r.u32()
                        val refs = r.refs(n)
                        classes[id] = Cls(id, name, refs)
                        for (x in refs) nodes[x] = RNode(name)
                    }
                    tag.startsWith("PROP") -> readProp(r, classes, nodes)
                    tag.startsWith("PRNT") -> {
                        r.b.get()  // versão
                        val n = r.u32()
                        val child = r.refs(n)
                        val par = r.refs(n)
                        parents.add(child)
                        parents.add(par)
                    }
                }
            } catch (e: Throwable) {
                // pedaço que não entendi: ignora
            }
        }
        var i = 0
        while (i + 1 < parents.size) {
            val ch = parents[i]
            val pa = parents[i + 1]
            for (k in ch.indices) {
                val c = nodes[ch[k]] ?: continue
                val pn = nodes[pa[k]] ?: continue
                c.parent = pn
                pn.kids.add(c)
            }
            i += 2
        }
        if (zstd && nodes.isEmpty()) lastError = "arquivo comprimido com ZSTD (ainda não suportado)"
        else if (zstd) lastError = "parte do arquivo usa ZSTD e foi ignorada"
        return nodes.values.filter { it.parent == null }
    }

    private fun readProp(r: Rd, classes: HashMap<Int, Cls>, nodes: HashMap<Int, RNode>) {
        val cid = r.u32()
        val cls = classes[cid] ?: return
        val prop = r.str()
        val type = r.b.get().toInt() and 255
        val n = cls.refs.size
        when (type) {
            0x01 -> {
                for (k in 0 until n) {
                    val s = r.str()
                    val nd = nodes[cls.refs[k]] ?: continue
                    if (prop == "Name") nd.name = s
                    nd.str[prop] = s
                }
            }
            0x02 -> {
                for (k in 0 until n) {
                    val v = r.b.get().toInt() != 0
                    nodes[cls.refs[k]]?.bool?.put(prop, v)
                }
            }
            0x03 -> {
                val v = r.zig(n)
                for (k in 0 until n) nodes[cls.refs[k]]?.num?.put(prop, v[k].toFloat())
            }
            0x04 -> {
                val v = r.floats(n)
                for (k in 0 until n) nodes[cls.refs[k]]?.num?.put(prop, v[k])
            }
            0x12 -> {
                val v = r.ints(n)
                for (k in 0 until n) nodes[cls.refs[k]]?.num?.put(prop, v[k].toFloat())
            }
            0x0C -> {
                val cr = r.floats(n)
                val cg = r.floats(n)
                val cb = r.floats(n)
                for (k in 0 until n) nodes[cls.refs[k]]?.col?.put(prop, floatArrayOf(cr[k], cg[k], cb[k]))
            }
            0x1A -> {
                val br = ByteArray(n)
                val bg = ByteArray(n)
                val bb = ByteArray(n)
                r.b.get(br)
                r.b.get(bg)
                r.b.get(bb)
                for (k in 0 until n) {
                    nodes[cls.refs[k]]?.col?.put(prop, floatArrayOf(
                        (br[k].toInt() and 255) / 255f, (bg[k].toInt() and 255) / 255f, (bb[k].toInt() and 255) / 255f))
                }
            }
            0x0E -> {
                val xs = r.floats(n)
                val ys = r.floats(n)
                val zs = r.floats(n)
                for (k in 0 until n) nodes[cls.refs[k]]?.vec?.put(prop, floatArrayOf(xs[k], ys[k], zs[k]))
            }
            0x10 -> {
                val rots = ArrayList<FloatArray>()
                for (k in 0 until n) {
                    val id = r.b.get().toInt() and 255
                    if (id == 0) {
                        val m = FloatArray(9)
                        for (q in 0 until 9) m[q] = r.b.float
                        rots.add(m)
                    } else {
                        // rotação "especial" (alinhada aos eixos): id 2 = sem rotação
                        rots.add(specialRot(id))
                    }
                }
                val xs = r.floats(n)
                val ys = r.floats(n)
                val zs = r.floats(n)
                for (k in 0 until n) {
                    val m = rots[k]
                    nodes[cls.refs[k]]?.cf?.put(prop, floatArrayOf(xs[k], ys[k], zs[k], m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8]))
                }
            }
        }
    }

    // ---------- XML antigo ----------
    private fun parseXml(raw: ByteArray): List<RNode> {
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        val doc = f.newDocumentBuilder().parse(java.io.ByteArrayInputStream(raw))
        val out = ArrayList<RNode>()
        val kids = doc.documentElement.childNodes
        for (i in 0 until kids.length) {
            val e = kids.item(i) as? org.w3c.dom.Element ?: continue
            if (e.tagName == "Item") out.add(xmlItem(e, null))
        }
        return out
    }

    private fun num(e: org.w3c.dom.Element, tag: String): Float {
        val l = e.getElementsByTagName(tag)
        if (l.length == 0) return 0f
        return l.item(0).textContent.trim().toFloatOrNull() ?: 0f
    }

    private fun xmlItem(e: org.w3c.dom.Element, parent: RNode?): RNode {
        val n = RNode(e.getAttribute("class"))
        n.parent = parent
        parent?.kids?.add(n)
        val ch = e.childNodes
        for (i in 0 until ch.length) {
            val c = ch.item(i) as? org.w3c.dom.Element ?: continue
            if (c.tagName == "Item") {
                xmlItem(c, n)
            } else if (c.tagName == "Properties") {
                val ps = c.childNodes
                for (k in 0 until ps.length) {
                    val pe = ps.item(k) as? org.w3c.dom.Element ?: continue
                    val pn = pe.getAttribute("name")
                    when (pe.tagName) {
                        "string", "ProtectedString" -> {
                            val s = pe.textContent
                            if (pn == "Name") n.name = s
                            n.str[pn] = s
                        }
                        "Content" -> {
                            val u = pe.getElementsByTagName("url")
                            if (u.length > 0) n.str[pn] = u.item(0).textContent.trim()
                        }
                        "bool" -> n.bool[pn] = pe.textContent.trim().equals("true", true)
                        "int", "float", "double", "token", "int64" -> {
                            val fv = pe.textContent.trim().toFloatOrNull()
                            if (fv != null) n.num[pn] = fv
                        }
                        "Color3uint8", "Color3" -> {
                            if (pe.getElementsByTagName("R").length > 0) {
                                n.col[pn] = floatArrayOf(num(pe, "R"), num(pe, "G"), num(pe, "B"))
                            } else {
                                val pk = pe.textContent.trim().toLongOrNull()
                                if (pk != null) n.col[pn] = floatArrayOf(((pk shr 16) and 255L) / 255f, ((pk shr 8) and 255L) / 255f, (pk and 255L) / 255f)
                            }
                        }
                        "Vector3" -> n.vec[pn] = floatArrayOf(num(pe, "X"), num(pe, "Y"), num(pe, "Z"))
                        "CoordinateFrame" -> n.cf[pn] = floatArrayOf(
                            num(pe, "X"), num(pe, "Y"), num(pe, "Z"),
                            num(pe, "R00"), num(pe, "R01"), num(pe, "R02"),
                            num(pe, "R10"), num(pe, "R11"), num(pe, "R12"),
                            num(pe, "R20"), num(pe, "R21"), num(pe, "R22")
                        )
                    }
                }
            }
        }
        return n
    }
}
