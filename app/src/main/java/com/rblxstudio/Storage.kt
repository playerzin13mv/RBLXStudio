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

    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (p in Scene.parts) {
            val o = JSONObject()
            o.put("name", p.name)
            o.put("pos", JSONArray(listOf(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())))
            o.put("size", JSONArray(listOf(p.sx.toDouble(), p.sy.toDouble(), p.sz.toDouble())))
            o.put("color", JSONArray(listOf(p.r.toDouble(), p.g.toDouble(), p.b.toDouble())))
            o.put("rot", JSONArray(p.rot.map { it.toDouble() }))
            o.put("anchored", p.anchored)
            o.put("canCollide", p.canCollide)
            o.put("grid", p.grid)
            arr.put(o)
        }
        return JSONObject().put("version", 1).put("parts", arr)
    }

    fun fromJson(root: JSONObject) {
        val arr = root.getJSONArray("parts")
        Scene.parts.clear()
        Scene.selected = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
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
            p.canCollide = o.optBoolean("canCollide", true)
            p.grid = o.optBoolean("grid", false)
            val rot = o.optJSONArray("rot")
            if (rot != null && rot.length() == 16) {
                for (k in 0 until 16) p.rot[k] = rot.getDouble(k).toFloat()
            }
            Scene.parts.add(p)
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
