package com.hudur.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

object Store {

    val roster = ArrayList<String>()
    val present = LinkedHashMap<String, Long>()
    private var dir: File? = null

    fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun init(ctx: Context) {
        dir = ctx.filesDir
        loadRoster()
        loadDay()
    }

    fun norm(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ى' -> sb.append('ي')
                'ئ' -> sb.append('ي')
                'ة' -> sb.append('ه')
                'ؤ' -> sb.append('و')
                'ً', 'ٌ', 'ٍ', 'َ', 'ُ',
                'ِ', 'ّ', 'ْ', 'ـ' -> { }
                else -> sb.append(ch)
            }
        }
        var out = sb.toString().trim()
        while (out.contains("  ")) out = out.replace("  ", " ")
        return out.lowercase(Locale.ROOT)
    }

    @Synchronized
    fun checkIn(raw: String): CheckResult {
        var name = raw.trim()
        while (name.contains("  ")) name = name.replace("  ", " ")
        if (name.length < 2) return CheckResult(false, "اكتب اسمك كاملًا", "")
        if (name.length > 60) return CheckResult(false, "الاسم طويل جدًا", "")

        val key = norm(name)
        var finalName = name
        for (r in roster) {
            if (norm(r) == key) { finalName = r; break }
        }

        for (p in present.keys) {
            if (norm(p) == norm(finalName)) {
                return CheckResult(true, "أنت مسجّل بالفعل", p)
            }
        }

        present[finalName] = System.currentTimeMillis()
        saveDay()
        return CheckResult(true, "تم تسجيل حضورك", finalName)
    }

    @Synchronized
    fun snapshot(): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        for (e in present.entries) out.add(Pair(e.key, e.value))
        return out
    }

    @Synchronized
    fun countPresent(): Int = present.size

    @Synchronized
    fun removeName(name: String) {
        present.remove(name)
        saveDay()
    }

    @Synchronized
    fun clearDay() {
        present.clear()
        saveDay()
    }

    @Synchronized
    fun setRoster(names: List<String>) {
        roster.clear()
        for (n in names) {
            val t = n.trim()
            if (t.isNotEmpty() && !roster.contains(t)) roster.add(t)
        }
        saveRoster()
    }

    fun absentees(): List<String> {
        val out = ArrayList<String>()
        for (r in roster) {
            var found = false
            for (p in present.keys) if (norm(p) == norm(r)) { found = true; break }
            if (!found) out.add(r)
        }
        return out
    }

    @Synchronized
    fun listDays(): List<String> {
        val d = dir ?: return ArrayList()
        val out = ArrayList<String>()
        val fs = d.listFiles()
        if (fs != null) {
            for (f in fs) {
                val n = f.name
                if (n.startsWith("att_") && n.endsWith(".json")) {
                    out.add(n.substring(4, n.length - 5))
                }
            }
        }
        out.sort()
        return out
    }

    @Synchronized
    fun dayRecord(day: String): LinkedHashMap<String, Long> {
        if (day == today()) return LinkedHashMap(present)
        val out = LinkedHashMap<String, Long>()
        try {
            val f = file("att_" + day + ".json") ?: return out
            if (!f.exists()) return out
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = o.optLong(k, 0L)
            }
        } catch (e: Exception) { }
        return out
    }

    @Synchronized
    fun allNames(): List<String> {
        val seen = LinkedHashMap<String, String>()
        for (r in roster) seen[norm(r)] = r
        for (d in listDays()) {
            for (k in dayRecord(d).keys) {
                if (!seen.containsKey(norm(k))) seen[norm(k)] = k
            }
        }
        return ArrayList(seen.values)
    }

    // ---------- persistence ----------

    private fun file(name: String): File? {
        val d = dir ?: return null
        return File(d, name)
    }

    private fun saveRoster() {
        try {
            val f = file("roster.json") ?: return
            val arr = JSONArray()
            for (r in roster) arr.put(r)
            f.writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) { }
    }

    private fun loadRoster() {
        try {
            val f = file("roster.json") ?: return
            if (!f.exists()) return
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            roster.clear()
            for (i in 0 until arr.length()) roster.add(arr.getString(i))
        } catch (e: Exception) { }
    }

    private fun saveDay() {
        try {
            val f = file("att_" + today() + ".json") ?: return
            val o = JSONObject()
            for (e in present.entries) o.put(e.key, e.value)
            f.writeText(o.toString(), Charsets.UTF_8)
        } catch (e: Exception) { }
    }

    private fun loadDay() {
        try {
            val f = file("att_" + today() + ".json") ?: return
            if (!f.exists()) return
            val o = JSONObject(f.readText(Charsets.UTF_8))
            present.clear()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                present[k] = o.optLong(k, System.currentTimeMillis())
            }
        } catch (e: Exception) { }
    }
}

data class CheckResult(val ok: Boolean, val message: String, val name: String)
