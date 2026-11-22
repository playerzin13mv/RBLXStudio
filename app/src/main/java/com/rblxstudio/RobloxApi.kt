package com.rblxstudio

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Consultas públicas ao Roblox: lugares (games) do usuário, de grupos, ícones e detalhes de um Place ID
object RobloxApi {
    class PlaceInfo(val placeId: Long, val universeId: Long, val name: String, val desc: String)
    class GroupInfo(val id: Long, val name: String)

    // GET simples; o cookie da sessão só é enviado para hosts do roblox.com
    fun get(url: String, cookie: String = ""): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12000
            c.readTimeout = 12000
            c.setRequestProperty("Accept", "application/json")
            val host = c.url.host ?: ""
            if (cookie.isNotEmpty() && (host == "roblox.com" || host.endsWith(".roblox.com"))) {
                c.setRequestProperty("Cookie", ".ROBLOSECURITY=" + cookie)
            }
            val code = c.responseCode
            if (code !in 200..299) throw Exception("HTTP " + code)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun parseGames(json: String): List<PlaceInfo> {
        val out = ArrayList<PlaceInfo>()
        val arr: JSONArray = JSONObject(json).optJSONArray("data") ?: return out
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            val root = g.optJSONObject("rootPlace")
            val pid = root?.optLong("id", 0L) ?: 0L
            if (pid == 0L) continue
            out.add(PlaceInfo(pid, g.optLong("id", 0L), g.optString("name", "Place"), g.optString("description", "")))
        }
        return out
    }

    // lugares do usuário (com a sessão do login pelo site, tenta trazer também os privados)
    fun userGames(userId: String, cookie: String): List<PlaceInfo> {
        val base = "https://games.roblox.com/v2/users/" + userId + "/games?limit=50&sortOrder=Asc"
        if (cookie.isNotEmpty()) {
            try {
                return parseGames(get(base + "&accessFilter=All", cookie))
            } catch (e: Exception) {
            }
        }
        return parseGames(get(base))
    }

    fun groups(userId: String): List<GroupInfo> {
        val out = ArrayList<GroupInfo>()
        val arr = JSONObject(get("https://groups.roblox.com/v1/users/" + userId + "/groups/roles")).optJSONArray("data") ?: return out
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i).optJSONObject("group") ?: continue
            out.add(GroupInfo(g.optLong("id", 0L), g.optString("name", "Group")))
        }
        return out
    }

    fun groupGames(groupId: Long): List<PlaceInfo> =
        parseGames(get("https://games.roblox.com/v2/groups/" + groupId + "/games?limit=50&sortOrder=Asc"))

    // universeId -> link da imagem do ícone
    fun icons(universeIds: List<Long>): Map<Long, String> {
        val out = HashMap<Long, String>()
        val ids = universeIds.filter { it > 0L }.distinct().take(50)
        if (ids.isEmpty()) return out
        val arr = JSONObject(get("https://thumbnails.roblox.com/v1/games/icons?universeIds=" + ids.joinToString(",") +
            "&size=150x150&format=Png&isCircular=false")).optJSONArray("data") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.optString("imageUrl", "")
            if (url.isNotEmpty()) out[o.optLong("targetId", 0L)] = url
        }
        return out
    }

    // "Open by Place ID": descobre o universo e o nome do lugar
    fun placeInfo(placeId: String): PlaceInfo? {
        val pid = placeId.trim().toLongOrNull() ?: return null
        val uni = JSONObject(get("https://apis.roblox.com/universes/v1/places/" + pid + "/universe")).optLong("universeId", 0L)
        if (uni == 0L) return null
        val arr = JSONObject(get("https://games.roblox.com/v1/games?universeIds=" + uni)).optJSONArray("data")
        if (arr == null || arr.length() == 0) return PlaceInfo(pid, uni, "Place " + pid, "")
        val g = arr.getJSONObject(0)
        return PlaceInfo(pid, uni, g.optString("name", "Place " + pid), g.optString("description", ""))
    }
}
