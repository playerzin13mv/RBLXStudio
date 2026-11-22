package com.rblxstudio

import java.text.Normalizer

// Filtro de palavrão do chat, parecido com o do Roblox: a palavra vira ####
// Funciona com acento, maiúscula, letras repetidas e troca por números (p0rr4, m3rd4)
object ChatFilter {
    @Volatile var enabled = true

    // palavras (já sem acento, minúsculas). Palavras curtas só são filtradas inteiras.
    private val words = listOf(
        // português
        "porra", "caralho", "merda", "bosta", "puta", "puto", "putaria", "foder", "foda", "fodido", "fodase", "fdp",
        "cu", "cuzao", "buceta", "bct", "pqp", "vsf", "vtnc", "tnc", "arrombado", "arrombada", "viado", "viadinho",
        "bicha", "corno", "otario", "otaria", "babaca", "idiota", "imbecil", "retardado", "desgraca", "desgracado",
        "vagabundo", "vagabunda", "cacete", "punheta", "xereca", "xoxota", "pau", "pica", "rola",
        "escroto", "filhodaputa", "vadia", "safado", "safada", "trouxa",
        // inglês
        "fuck", "fucking", "fucker", "fck", "fuk", "shit", "bullshit", "bitch", "bastard", "asshole", "ass", "dick",
        "cock", "pussy", "cunt", "slut", "whore", "nigger", "nigga", "faggot", "fag", "retard", "motherfucker",
        "wtf", "stfu", "damn", "dumbass", "jackass", "douche", "cum", "porn", "rape", "nazi"
    )

    // palavras curtas/ambíguas: só valem quando a palavra inteira é essa
    private val exactOnly = setOf("cu", "pau", "pica", "rola", "ass", "cum", "fag", "damn", "puto", "puta", "foda", "bct", "fdp", "tnc", "wtf", "rape", "cock", "dick", "bicha", "corno", "nazi", "porn")

    private val wordSet = words.toSet()

    private fun strip(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder()
        for (ch in n) {
            if (ch.category == CharCategory.NON_SPACING_MARK) continue
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun leet(c: Char): Char = when (c) {
        '0' -> 'o'
        '1' -> 'i'
        '3' -> 'e'
        '4' -> 'a'
        '5' -> 's'
        '7' -> 't'
        '@' -> 'a'
        '$' -> 's'
        '!' -> 'i'
        else -> c
    }

    // letras repetidas viram uma só: "meeeerda" -> "merda"
    private fun squeeze(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            if (sb.isNotEmpty() && sb[sb.length - 1] == ch) continue
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun bad(token: String): Boolean {
        val low = strip(token).lowercase()
        val norm = StringBuilder()
        for (ch in low) norm.append(leet(ch))
        val a = norm.toString().filter { it.isLetter() }
        if (a.length < 2) return false
        val b = squeeze(a)
        if (wordSet.contains(a) || wordSet.contains(b)) return true
        if (a.length < 4) return false
        for (w in words) {
            if (w.length < 4 || exactOnly.contains(w)) continue
            if (a.contains(w) || b.contains(w)) return true
        }
        return false
    }

    fun clean(text: String): String {
        if (!enabled || text.isEmpty()) return text
        val sb = StringBuilder()
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (ch.isWhitespace()) {
                sb.append(ch)
                i++
                continue
            }
            var j = i
            while (j < n && !text[j].isWhitespace()) j++
            val token = text.substring(i, j)
            if (bad(token)) {
                // mantém pontuação do começo e do fim; o resto vira #
                var s = 0
                var e = token.length
                while (s < e && !token[s].isLetterOrDigit()) s++
                while (e > s && !token[e - 1].isLetterOrDigit()) e--
                sb.append(token.substring(0, s))
                for (k in s until e) sb.append('#')
                sb.append(token.substring(e))
            } else {
                sb.append(token)
            }
            i = j
        }
        // palavras separadas por espaço/ponto: "p u t a", "m.e.r.d.a"
        return spaced(sb.toString())
    }

    private fun spaced(s: String): String {
        val m = Regex("(?:\\b[\\p{L}][ .\\-_*]){3,}[\\p{L}]\\b").findAll(s).toList()
        if (m.isEmpty()) return s
        var out = s
        for (x in m.reversed()) {
            val joined = x.value.filter { it.isLetter() }
            if (bad(joined)) {
                val rep = x.value.map { if (it.isLetter()) '#' else it }.joinToString("")
                out = out.substring(0, x.range.first) + rep + out.substring(x.range.last + 1)
            }
        }
        return out
    }
}
