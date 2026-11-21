package com.rblxstudio

import android.content.Context
import org.json.JSONObject

// Cores do corpo do avatar do Roblox (API pública). Roupas e acessórios 3D ainda não.
object Avatar {
    // cabeça, torso, braço esq, braço dir, perna esq, perna dir (0xRRGGBB)
    @Volatile var cols = intArrayOf(0xF5CD30, 0x0D69AC, 0xF5CD30, 0xF5CD30, 0x4B974B, 0x4B974B)

    private val bricks = mapOf(
        1 to 0xF2F3F3, 5 to 0xD7C59A, 9 to 0xE8BAC8, 11 to 0x80BBDC, 18 to 0xCC8E69, 21 to 0xC4281C,
        23 to 0x0D69AC, 24 to 0xF5CD30, 26 to 0x1B2A35, 28 to 0x287F47, 37 to 0x4B974B, 38 to 0xA05F35,
        45 to 0xB4D2E4, 101 to 0xDA867A, 102 to 0x6E99CA, 104 to 0x6B327C, 105 to 0xE29B40, 106 to 0xDA8541,
        119 to 0xA4BD47, 125 to 0xEAB892, 192 to 0x694028, 194 to 0xA3A2A5, 199 to 0x635F62, 208 to 0xE5E4DF,
        217 to 0x7C5C46, 226 to 0xFDEA8D, 1001 to 0xF8F8F8, 1002 to 0xCDCDCD, 1003 to 0x111111, 1004 to 0xFF0000
    )

    fun load(ctx: Context) {
        val id = RobloxAuth.userId(ctx)
        if (id.isEmpty()) return
        Thread {
            try {
                val o = JSONObject(Assets.httpGet("https://avatar.roblox.com/v1/users/" + id + "/avatar"))
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
            } catch (e: Exception) {
            }
        }.start()
    }
}