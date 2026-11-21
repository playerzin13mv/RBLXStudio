package com.rblxstudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// salvar/abrir lugares em JSON dentro da pasta privada do app
object Storage {
    private fun dir(ctx: Context): File = File(ctx.filesDir, "places").also { it.mkdirs() }

    private fun clean(name: String) = name.trim().replace(Regex("[^A-Za-z0-9 _.\\-]"), "_").ifEmpty { "Place" }

    fun list(ctx: Context): List<String> =
        (dir(ctx).listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".json") }
            .sortedByDescending { it.lastModified() }
            .map { it.name.removeSuffix(".json") }

    private fun propsJson(i: Inst): JSONObject {
        val o = JSONObject()
        for ((k, v) in i.props) o.put(k, v)
        return o
    }

    private fun readProps(i: Inst, o: JSONObject?) {
        if (o == null) return
        val ks = o.keys()
        while (ks.hasNext()) {
            val k = ks.next()
            i.props[k] = o.optString(k, "")
        }
    }

    private fun childrenJson(i: Inst): JSONArray {
        val a = JSONArray()
        for (c in i.children) a.put(instJson(c))
        return a
    }

    private fun partJson(p: PartData): JSONObject {
        val o = JSONObject()
        o.put("part", true)
        o.put("class", p.className)
        o.put("name", p.name)
        o.put("pos", JSONArray(listOf(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())))
        o.put("size", JSONArray(listOf(p.sx.toDouble(), p.sy.toDouble(), p.sz.toDouble())))
        o.put("color", JSONArray(listOf(p.r.toDouble(), p.g.toDouble(), p.b.toDouble())))
        o.put("rot", JSONArray(p.rot.map { it.toDouble() }))
        o.put("anchored", p.anchored)
        o.put("canCollide", p.canCollide)
        o.put("grid", p.grid)
        o.put("friction", p.friction.toDouble())
        o.put("elasticity", p.elasticity.toDouble())
        o.put("props", propsJson(p))
        o.put("children", childrenJson(p))
        return o
    }

    private fun instJson(i: Inst): JSONObject {
        if (i is PartData) return partJson(i)
        val o = JSONObject()
        o.put("class", i.className)
        o.put("name", i.name)
        o.put("props", propsJson(i))
        o.put("children", childrenJson(i))
        return o
    }

    private fun partFrom(o: JSONObject): PartData {
        val pos = o.getJSONArray("pos")
        val size = o.getJSONArray("size")
        val col = o.getJSONArray("color")
        val p = PartData(
            o.getString("name"),
            pos.getDouble(0).toFloat(), pos.getDouble(1).toFloat(), pos.getDouble(2).toFloat(),
            size.getDouble(0).toFloat(), size.getDouble(1).toFloat(), size.getDouble(2).toFloat(),
            col.getDouble(0).toFloat(), col.getDouble(1).toFloat(), col.getDouble(2).toFloat(),
            o.optBoolean("anchored", false)
        )
        p.className = o.optString("class", "Part")
        p.canCollide = o.optBoolean("canCollide", true)
        p.grid = o.optBoolean("grid", false)
        p.friction = o.optDouble("friction", 0.3).toFloat()
        p.elasticity = o.optDouble("elasticity", 0.5).toFloat()
        val rot = o.optJSONArray("rot")
        if (rot != null && rot.length() == 16) {
            for (k in 0 until 16) p.rot[k] = rot.getDouble(k).toFloat()
        }
        readProps(p, o.optJSONObject("props"))
        val ch = o.optJSONArray("children")
        if (ch != null) for (k in 0 until ch.length()) p.add(instFrom(ch.getJSONObject(k)))
        return p
    }

    private fun instFrom(o: JSONObject): Inst {
        if (o.optBoolean("part", false)) return partFrom(o)
        val i = Inst(o.optString("class", "Folder"), o.optString("name", "Instance"))
        readProps(i, o.optJSONObject("props"))
        val ch = o.optJSONArray("children")
        if (ch != null) for (k in 0 until ch.length()) i.add(instFrom(ch.getJSONObject(k)))
        return i
    }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (p in Scene.parts) arr.put(partJson(p))
        val sv = JSONObject()
        for ((n, s) in Scene.services) sv.put(n, childrenJson(s))
        return JSONObject().put("version", 2).put("parts", arr).put("services", sv)
    }

    fun fromJson(root: JSONObject) {
        Scene.clearAll()
        val arr = root.getJSONArray("parts")
        for (i in 0 until arr.length()) Scene.parts.add(partFrom(arr.getJSONObject(i)))
        val sv = root.optJSONObject("services")
        if (sv != null) {
            for ((n, s) in Scene.services) {
                val a = sv.optJSONArray(n) ?: continue
                for (k in 0 until a.length()) s.add(instFrom(a.getJSONObject(k)))
            }
        }
    }

    fun save(ctx: Context, name: String): String {
        val n = clean(name)
        File(dir(ctx), "$n.json").writeText(toJson().toString())
        return n
    }

    fun load(ctx: Context, name: String) {
        val f = File(dir(ctx), "$name.json")
        fromJson(JSONObject(f.readText()))
    }
}