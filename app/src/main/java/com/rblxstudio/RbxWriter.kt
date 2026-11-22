package com.rblxstudio

import java.io.ByteArrayOutputStream

// ---------- modelo intermediário ----------
// Tipos de propriedade que o exportador sabe escrever
enum class WT { STR, CONTENT, CODE, BOOL, INT, FLOAT, DOUBLE, ENUM, V2, V3, CF, C3, C3U8, UDIM2 }

// Valores: STR/CONTENT/CODE = String, BOOL = Boolean, INT/ENUM = Int, FLOAT = Float, DOUBLE = Double,
// V2 = [x,y], V3 = [x,y,z], CF = [x,y,z,r00,r01,r02,r10,r11,r12,r20,r21,r22],
// C3/C3U8 = [r,g,b] de 0 a 1, UDIM2 = [xScale,xOffset,yScale,yOffset]
class WProp(val name: String, val type: WT, val v: Any)

class WNode(val cls: String, val name: String, val service: Boolean = false) {
    val kids = ArrayList<WNode>()
    val props = LinkedHashMap<String, WProp>()
    var ref = 0

    fun p(n: String, t: WT, v: Any): WNode {
        props[n] = WProp(n, t, v)
        return this
    }
}

// ---------- escritor (.rbxl / .rbxm binário e .rbxlx / .rbxmx XML) ----------
object RbxWriter {
    fun count(roots: List<WNode>): Int {
        var n = 0
        fun go(x: WNode) {
            n++
            for (k in x.kids) go(k)
        }
        for (r in roots) go(r)
        return n
    }

    private fun flatten(roots: List<WNode>): ArrayList<WNode> {
        val all = ArrayList<WNode>()
        fun go(x: WNode) {
            x.ref = all.size
            all.add(x)
            for (k in x.kids) go(k)
        }
        for (r in roots) go(r)
        return all
    }

    fun write(roots: List<WNode>, binary: Boolean): ByteArray = if (binary) binary(roots) else xml(roots)

    // ================= binário =================
    private class Bw {
        val o = ByteArrayOutputStream()
        fun u8(v: Int) = o.write(v and 255)
        fun u32(v: Int) {
            o.write(v and 255)
            o.write((v ushr 8) and 255)
            o.write((v ushr 16) and 255)
            o.write((v ushr 24) and 255)
        }

        fun str(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            u32(b.size)
            o.write(b)
        }

        fun f32(f: Float) = u32(java.lang.Float.floatToRawIntBits(f))
        fun f64(d: Double) {
            val l = java.lang.Double.doubleToRawLongBits(d)
            u32(l.toInt())
            u32((l ushr 32).toInt())
        }

        // inteiros intercalados (byte mais alto primeiro)
        fun ints(v: IntArray) {
            val n = v.size
            val a = ByteArray(n * 4)
            for (i in 0 until n) {
                a[i] = (v[i] ushr 24).toByte()
                a[n + i] = (v[i] ushr 16).toByte()
                a[2 * n + i] = (v[i] ushr 8).toByte()
                a[3 * n + i] = v[i].toByte()
            }
            o.write(a)
        }

        fun zig(v: IntArray) = ints(IntArray(v.size) { (v[it] shl 1) xor (v[it] shr 31) })

        fun floats(v: FloatArray) = ints(IntArray(v.size) {
            val b = java.lang.Float.floatToRawIntBits(v[it])
            (b shl 1) or (b ushr 31)
        })

        fun refs(v: IntArray) {
            val d = IntArray(v.size)
            for (i in v.indices) d[i] = v[i] - (if (i > 0) v[i - 1] else 0)
            zig(d)
        }

        fun bytes(): ByteArray = o.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, tag: String, data: ByteArray) {
        val h = Bw()
        val t = tag.padEnd(4, '\u0000').toByteArray(Charsets.ISO_8859_1)
        h.o.write(t, 0, 4)
        h.u32(0)            // tamanho comprimido 0 = pedaço sem compressão
        h.u32(data.size)
        h.u32(0)
        out.write(h.bytes())
        out.write(data)
    }

    private fun typeByte(t: WT): Int = when (t) {
        WT.STR, WT.CONTENT, WT.CODE -> 0x01
        WT.BOOL -> 0x02
        WT.INT -> 0x03
        WT.FLOAT -> 0x04
        WT.DOUBLE -> 0x05
        WT.UDIM2 -> 0x07
        WT.C3 -> 0x0C
        WT.V2 -> 0x0D
        WT.V3 -> 0x0E
        WT.CF -> 0x10
        WT.ENUM -> 0x12
        WT.C3U8 -> 0x1A
    }

    private fun defaultOf(t: WT): Any = when (t) {
        WT.STR, WT.CONTENT, WT.CODE -> ""
        WT.BOOL -> false
        WT.INT, WT.ENUM -> 0
        WT.FLOAT -> 0f
        WT.DOUBLE -> 0.0
        WT.V2 -> FloatArray(2)
        WT.V3 -> FloatArray(3)
        WT.CF -> floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        WT.C3, WT.C3U8 -> FloatArray(3)
        WT.UDIM2 -> FloatArray(4)
    }

    private fun values(b: Bw, t: WT, vs: List<Any>) {
        val n = vs.size
        when (t) {
            WT.STR, WT.CONTENT, WT.CODE -> for (v in vs) b.str(v as String)
            WT.BOOL -> for (v in vs) b.u8(if (v as Boolean) 1 else 0)
            WT.INT -> b.zig(IntArray(n) { vs[it] as Int })
            WT.ENUM -> b.ints(IntArray(n) { vs[it] as Int })
            WT.FLOAT -> b.floats(FloatArray(n) { vs[it] as Float })
            WT.DOUBLE -> for (v in vs) b.f64(v as Double)
            WT.V2 -> for (k in 0..1) b.floats(FloatArray(n) { (vs[it] as FloatArray)[k] })
            WT.V3 -> for (k in 0..2) b.floats(FloatArray(n) { (vs[it] as FloatArray)[k] })
            WT.C3 -> for (k in 0..2) b.floats(FloatArray(n) { (vs[it] as FloatArray)[k] })
            WT.C3U8 -> for (k in 0..2) {
                for (v in vs) b.u8(((v as FloatArray)[k].coerceIn(0f, 1f) * 255f + 0.5f).toInt())
            }
            WT.UDIM2 -> {
                b.floats(FloatArray(n) { (vs[it] as FloatArray)[0] })   // escala X
                b.floats(FloatArray(n) { (vs[it] as FloatArray)[2] })   // escala Y
                b.zig(IntArray(n) { (vs[it] as FloatArray)[1].toInt() }) // deslocamento X
                b.zig(IntArray(n) { (vs[it] as FloatArray)[3].toInt() }) // deslocamento Y
            }
            WT.CF -> {
                for (v in vs) {
                    val c = v as FloatArray
                    b.u8(0)  // rotação completa (9 números)
                    for (q in 3..11) b.f32(c[q])
                }
                for (k in 0..2) b.floats(FloatArray(n) { (vs[it] as FloatArray)[k] })
            }
        }
    }

    private fun binary(roots: List<WNode>): ByteArray {
        val all = flatten(roots)
        // classes na ordem em que aparecem
        val classes = LinkedHashMap<String, ArrayList<WNode>>()
        for (x in all) classes.getOrPut(x.cls) { ArrayList() }.add(x)

        val out = ByteArrayOutputStream()
        val h = Bw()
        h.o.write("<roblox!".toByteArray(Charsets.ISO_8859_1))
        h.o.write(byteArrayOf(0x89.toByte(), 0xFF.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        h.o.write(0)
        h.o.write(0)
        h.u32(classes.size)
        h.u32(all.size)
        h.u32(0)
        h.u32(0)
        out.write(h.bytes())

        val meta = Bw()
        meta.u32(1)
        meta.str("ExplicitAutoJoints")
        meta.str("true")
        chunk(out, "META", meta.bytes())

        val sstr = Bw()
        sstr.u32(0)
        sstr.u32(0)
        chunk(out, "SSTR", sstr.bytes())

        var cid = 0
        for ((cls, list) in classes) {
            val ib = Bw()
            ib.u32(cid)
            ib.str(cls)
            val svc = list[0].service
            ib.u8(if (svc) 1 else 0)
            ib.u32(list.size)
            ib.refs(IntArray(list.size) { list[it].ref })
            if (svc) for (k in list.indices) ib.u8(1)
            chunk(out, "INST", ib.bytes())

            // todas as instâncias da classe precisam ter a mesma lista de propriedades
            val order = LinkedHashMap<String, WT>()
            order["Name"] = WT.STR
            for (x in list) for ((pn, pp) in x.props) if (!order.containsKey(pn)) order[pn] = pp.type
            for ((pn, pt) in order) {
                val vs = ArrayList<Any>(list.size)
                for (x in list) {
                    vs.add(if (pn == "Name") x.name else (x.props[pn]?.v ?: defaultOf(pt)))
                }
                val pb = Bw()
                pb.u32(cid)
                pb.str(pn)
                pb.u8(typeByte(pt))
                values(pb, pt, vs)
                chunk(out, "PROP", pb.bytes())
            }
            cid++
        }

        // pais
        val child = IntArray(all.size) { all[it].ref }
        val parent = IntArray(all.size) { -1 }
        for (x in all) for (k in x.kids) parent[k.ref] = x.ref
        val pr = Bw()
        pr.u8(0)
        pr.u32(all.size)
        pr.refs(child)
        pr.refs(parent)
        chunk(out, "PRNT", pr.bytes())

        chunk(out, "END", "</roblox>".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    // ================= XML =================
    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\'' -> sb.append("&apos;")
                ch.code < 32 && ch != '\n' && ch != '\r' && ch != '\t' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun cdata(s: String): String {
        val clean = StringBuilder(s.length)
        for (ch in s) if (!(ch.code < 32 && ch != '\n' && ch != '\r' && ch != '\t')) clean.append(ch)
        return "<![CDATA[" + clean.toString().replace("]]>", "]]]]><![CDATA[>") + "]]>"
    }

    private fun f(x: Float): String = if (x.isNaN() || x.isInfinite()) "0" else x.toString()

    private fun xmlProp(sb: StringBuilder, p: WProp) {
        val n = esc(p.name)
        when (p.type) {
            WT.STR -> sb.append("<string name=\"").append(n).append("\">").append(esc(p.v as String)).append("</string>\n")
            WT.CODE -> sb.append("<ProtectedString name=\"").append(n).append("\">").append(cdata(p.v as String)).append("</ProtectedString>\n")
            WT.CONTENT -> {
                val u = p.v as String
                if (u.isEmpty()) sb.append("<Content name=\"").append(n).append("\"><null></null></Content>\n")
                else sb.append("<Content name=\"").append(n).append("\"><url>").append(esc(u)).append("</url></Content>\n")
            }
            WT.BOOL -> sb.append("<bool name=\"").append(n).append("\">").append(if (p.v as Boolean) "true" else "false").append("</bool>\n")
            WT.INT -> sb.append("<int name=\"").append(n).append("\">").append(p.v as Int).append("</int>\n")
            WT.ENUM -> sb.append("<token name=\"").append(n).append("\">").append(p.v as Int).append("</token>\n")
            WT.FLOAT -> sb.append("<float name=\"").append(n).append("\">").append(f(p.v as Float)).append("</float>\n")
            WT.DOUBLE -> sb.append("<double name=\"").append(n).append("\">").append(p.v as Double).append("</double>\n")
            WT.V2 -> {
                val a = p.v as FloatArray
                sb.append("<Vector2 name=\"").append(n).append("\"><X>").append(f(a[0])).append("</X><Y>").append(f(a[1])).append("</Y></Vector2>\n")
            }
            WT.V3 -> {
                val a = p.v as FloatArray
                sb.append("<Vector3 name=\"").append(n).append("\"><X>").append(f(a[0])).append("</X><Y>").append(f(a[1]))
                    .append("</Y><Z>").append(f(a[2])).append("</Z></Vector3>\n")
            }
            WT.C3 -> {
                val a = p.v as FloatArray
                sb.append("<Color3 name=\"").append(n).append("\"><R>").append(f(a[0])).append("</R><G>").append(f(a[1]))
                    .append("</G><B>").append(f(a[2])).append("</B></Color3>\n")
            }
            WT.C3U8 -> {
                val a = p.v as FloatArray
                val r = (a[0].coerceIn(0f, 1f) * 255f + 0.5f).toLong()
                val g = (a[1].coerceIn(0f, 1f) * 255f + 0.5f).toLong()
                val b = (a[2].coerceIn(0f, 1f) * 255f + 0.5f).toLong()
                val packed = 0xFF000000L or (r shl 16) or (g shl 8) or b
                sb.append("<Color3uint8 name=\"").append(n).append("\">").append(packed).append("</Color3uint8>\n")
            }
            WT.UDIM2 -> {
                val a = p.v as FloatArray
                sb.append("<UDim2 name=\"").append(n).append("\"><XS>").append(f(a[0])).append("</XS><XO>").append(a[1].toInt())
                    .append("</XO><YS>").append(f(a[2])).append("</YS><YO>").append(a[3].toInt()).append("</YO></UDim2>\n")
            }
            WT.CF -> {
                val a = p.v as FloatArray
                val tags = arrayOf("X", "Y", "Z", "R00", "R01", "R02", "R10", "R11", "R12", "R20", "R21", "R22")
                sb.append("<CoordinateFrame name=\"").append(n).append("\">")
                for (k in 0 until 12) sb.append('<').append(tags[k]).append('>').append(f(a[k])).append("</").append(tags[k]).append('>')
                sb.append("</CoordinateFrame>\n")
            }
        }
    }

    private fun xmlNode(sb: StringBuilder, x: WNode) {
        sb.append("<Item class=\"").append(esc(x.cls)).append("\" referent=\"RBX").append(x.ref).append("\">\n<Properties>\n")
        sb.append("<string name=\"Name\">").append(esc(x.name)).append("</string>\n")
        for ((_, p) in x.props) if (p.name != "Name") xmlProp(sb, p)
        sb.append("</Properties>\n")
        for (k in x.kids) xmlNode(sb, k)
        sb.append("</Item>\n")
    }

    private fun xml(roots: List<WNode>): ByteArray {
        flatten(roots)
        val sb = StringBuilder(1 shl 16)
        sb.append("<roblox xmlns:xmime=\"http://www.w3.org/2005/05/xmlmime\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
        sb.append("xsi:noNamespaceSchemaLocation=\"http://www.roblox.com/roblox.xsd\" version=\"4\">\n")
        sb.append("<Meta name=\"ExplicitAutoJoints\">true</Meta>\n")
        for (r in roots) xmlNode(sb, r)
        sb.append("</roblox>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}

// ---------- converte a cena do app em nós para o escritor ----------
object RbxExport {
    // serviços que nunca vão no arquivo (só existem durante o Play ou o Roblox cria sozinho)
    private val SKIP_SERVICES = setOf("Players", "Teams", "Chat", "MaterialService")
    private val PLAIN = setOf("Folder", "Model", "Actor", "WorldModel", "StarterPlayerScripts", "StarterCharacterScripts")

    @Volatile var skipped = 0

    private fun cframe(p: PartData): FloatArray {
        val r = p.rot  // coluna-principal: R[linha][coluna] = rot[coluna * 4 + linha]
        return floatArrayOf(p.x, p.y, p.z, r[0], r[4], r[8], r[1], r[5], r[9], r[2], r[6], r[10])
    }

    private fun part(p: PartData): WNode {
        val cls = if (p.className == "MeshPart" || p.className == "SpawnLocation") p.className else "Part"
        val n = WNode(cls, p.name)
        n.p("CFrame", WT.CF, cframe(p))
        n.p("size", WT.V3, floatArrayOf(p.sx, p.sy, p.sz))
        n.p("Color3uint8", WT.C3U8, floatArrayOf(p.r, p.g, p.b))
        n.p("Anchored", WT.BOOL, p.anchored)
        n.p("CanCollide", WT.BOOL, p.canCollide)
        n.p("Transparency", WT.FLOAT, (p.props["Transparency"]?.toFloatOrNull() ?: 0f).coerceIn(0f, 1f))
        if (cls == "MeshPart") {
            n.p("MeshId", WT.CONTENT, p.props["MeshId"] ?: "")
            n.p("TextureID", WT.CONTENT, p.props["TextureID"] ?: "")
        }
        for (c in p.children) {
            val k = inst(c)
            if (k != null) n.kids.add(k)
        }
        return n
    }

    private fun rgb(s: String): FloatArray {
        val a = parseNums(s)
        return floatArrayOf(a[0] / 255f, a[1] / 255f, a[2] / 255f)
    }

    private fun gui(i: Inst, c: String): WNode {
        val n = WNode(c, i.name)
        n.p("Position", WT.UDIM2, parseNums(i.prop("Position", "0,0,0,0")))
        n.p("Size", WT.UDIM2, parseNums(i.prop("Size", "0,100,0,100")))
        val ap = parseNums(i.prop("AnchorPoint", "0,0"))
        n.p("AnchorPoint", WT.V2, floatArrayOf(ap[0], ap[1]))
        n.p("BackgroundColor3", WT.C3, rgb(i.prop("BackgroundColor3", "255,255,255")))
        n.p("BackgroundTransparency", WT.FLOAT, i.prop("BackgroundTransparency", "0").toFloatOrNull() ?: 0f)
        n.p("Visible", WT.BOOL, i.prop("Visible", "true") == "true")
        if (c == "TextLabel" || c == "TextButton") {
            n.p("Text", WT.STR, i.prop("Text", ""))
            n.p("TextColor3", WT.C3, rgb(i.prop("TextColor3", "0,0,0")))
            n.p("TextSize", WT.INT, (i.prop("TextSize", "18").toFloatOrNull() ?: 18f).toInt())
        }
        if (c == "ImageLabel") n.p("Image", WT.CONTENT, i.prop("Image", ""))
        return n
    }

    private fun inst(i: Inst): WNode? {
        if (i is PartData) return part(i)
        if (i is CharInst) return null
        val c = i.className
        val n: WNode = when (c) {
            "Script", "LocalScript", "ModuleScript" -> WNode(c, i.name).p("Source", WT.CODE, i.prop("Source", ""))
            "Sound" -> WNode(c, i.name)
                .p("SoundId", WT.CONTENT, i.prop("SoundId", ""))
                .p("Volume", WT.FLOAT, i.prop("Volume", "1").toFloatOrNull() ?: 1f)
                .p("Looped", WT.BOOL, i.prop("Looped", "false") == "true")
                .p("Playing", WT.BOOL, i.prop("Playing", "false") == "true")
            "ScreenGui" -> WNode(c, i.name).p("Enabled", WT.BOOL, i.prop("Enabled", "true") == "true")
            "Frame", "TextLabel", "TextButton", "ImageLabel" -> gui(i, c)
            "Tool" -> WNode(c, i.name)
                .p("ToolTip", WT.STR, i.prop("ToolTip", ""))
                .p("TextureId", WT.CONTENT, i.prop("TextureId", ""))
                .p("CanBeDropped", WT.BOOL, i.prop("CanBeDropped", "true") == "true")
                .p("RequiresHandle", WT.BOOL, i.prop("RequiresHandle", "true") == "true")
            "StringValue" -> WNode(c, i.name).p("Value", WT.STR, i.prop("Value", ""))
            "NumberValue" -> WNode(c, i.name).p("Value", WT.DOUBLE, i.prop("Value", "0").toDoubleOrNull() ?: 0.0)
            "BoolValue" -> WNode(c, i.name).p("Value", WT.BOOL, i.prop("Value", "false") == "true")
            in PLAIN -> WNode(c, i.name)
            else -> {
                skipped++
                return null
            }
        }
        for (k in i.children) {
            val kn = inst(k)
            if (kn != null) n.kids.add(kn)
        }
        return n
    }

    // lugar inteiro (.rbxl / .rbxlx): serviços na raiz
    fun place(): List<WNode> {
        skipped = 0
        val out = ArrayList<WNode>()
        val ws = WNode("Workspace", "Workspace", true)
        for (p in Scene.parts) ws.kids.add(part(p))
        for (c in Scene.workspace.children) {
            val k = inst(c)
            if (k != null) ws.kids.add(k)
        }
        out.add(ws)
        for ((name, s) in Scene.services) {
            if (name == "Workspace" || name in SKIP_SERVICES) continue
            val kids = ArrayList<WNode>()
            for (c in s.children) {
                val k = inst(c)
                if (k != null) kids.add(k)
            }
            if (kids.isEmpty()) continue
            val sn = WNode(name, name, true)
            sn.kids.addAll(kids)
            out.add(sn)
        }
        return out
    }

    // modelo (.rbxm / .rbxmx): o objeto selecionado, ou tudo do Workspace dentro de um Model
    fun model(name: String): List<WNode> {
        skipped = 0
        val sel: Inst? = Scene.selObj ?: Scene.selected
        if (sel != null && sel !== Scene.workspace && !Scene.services.containsValue(sel)) {
            val n = inst(sel)
            if (n != null) return listOf(n)
        }
        val m = WNode("Model", name.ifEmpty { "Model" })
        for (p in Scene.parts) m.kids.add(part(p))
        for (c in Scene.workspace.children) {
            val k = inst(c)
            if (k != null) m.kids.add(k)
        }
        return listOf(m)
    }

    fun usedSelection(): Boolean {
        val sel: Inst? = Scene.selObj ?: Scene.selected
        return sel != null && sel !== Scene.workspace && !Scene.services.containsValue(sel)
    }
}
