package com.hudur.app

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLDecoder

class AttServer(
    port: Int,
    private val ctx: Context,
    private val onChange: () -> Unit
) : NanoHTTPD(port) {

    private var cachedPage: String? = null

    private fun page(): String {
        val c = cachedPage
        if (c != null) return c
        val s = readAsset("student.html")
        cachedPage = s
        return s
    }

    private fun readAsset(name: String): String {
        try {
            val ins = ctx.assets.open(name)
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
            }
            ins.close()
            return String(bos.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            return "<!DOCTYPE html><html><body><h1>خطأ في تحميل الصفحة</h1></body></html>"
        }
    }

    private fun query(session: IHTTPSession, key: String): String {
        val qs = session.queryParameterString ?: return ""
        for (part in qs.split("&")) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            if (part.substring(0, i) == key) {
                return try {
                    URLDecoder.decode(part.substring(i + 1).replace("+", "%20"), "UTF-8")
                } catch (e: Exception) { "" }
            }
        }
        return ""
    }

    private fun html(s: String): Response {
        val r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", s)
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun json(s: String): Response {
        val r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", s)
        r.addHeader("Cache-Control", "no-store")
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    private fun redirectHome(): Response {
        val r = NanoHTTPD.newFixedLengthResponse(Response.Status.REDIRECT, "text/html", "")
        r.addHeader("Location", "/")
        return r
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        try {
            if (uri.startsWith("/checkin")) {
                val name = query(session, "name")
                val res = Store.checkIn(name)
                if (res.ok) onChange()
                val o = JSONObject()
                o.put("ok", res.ok)
                o.put("message", res.message)
                o.put("name", res.name)
                o.put("count", Store.countPresent())
                return json(o.toString())
            }
            if (uri == "/names") {
                val arr = JSONArray()
                for (n in Store.roster) arr.put(n)
                val o = JSONObject()
                o.put("names", arr)
                o.put("count", Store.countPresent())
                return json(o.toString())
            }
            if (uri == "/" || uri == "/index.html") {
                return html(page())
            }
            return redirectHome()
        } catch (e: Exception) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                "error"
            )
        }
    }
}
