package com.hudur.app

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val port = 8080
    private val reqLocation = 101

    private var server: AttServer? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var running = false

    private lateinit var btnStart: Button
    private lateinit var btnRoster: Button
    private lateinit var btnExport: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvNet: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvPresent: TextView
    private lateinit var tvAbsent: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var boxQr: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var imgWifi: ImageView
    private lateinit var imgUrl: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Store.init(applicationContext)

        btnStart = findViewById(R.id.btnStart)
        btnRoster = findViewById(R.id.btnRoster)
        btnExport = findViewById(R.id.btnExport)
        tvStatus = findViewById(R.id.tvStatus)
        tvNet = findViewById(R.id.tvNet)
        tvDate = findViewById(R.id.tvDate)
        tvPresent = findViewById(R.id.tvPresent)
        tvAbsent = findViewById(R.id.tvAbsent)
        tvTotal = findViewById(R.id.tvTotal)
        tvEmpty = findViewById(R.id.tvEmpty)
        boxQr = findViewById(R.id.boxQr)
        listBox = findViewById(R.id.listBox)
        imgWifi = findViewById(R.id.imgWifi)
        imgUrl = findViewById(R.id.imgUrl)

        tvDate.text = SimpleDateFormat("EEEE d MMMM yyyy", Locale("ar")).format(Date())

        btnStart.setOnClickListener { if (running) stopSession() else startSession() }
        btnRoster.setOnClickListener { rosterDialog() }
        btnExport.setOnClickListener { exportCsv() }

        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServerOnly()
        releaseHotspot()
    }

    // ---------------- session ----------------

    private fun startSession() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), reqLocation
            )
            return
        }

        if (!startServer()) return

        running = true
        btnStart.text = "■  إنهاء الحصة"
        tvStatus.text = "جارٍ تشغيل الشبكة…"
        boxQr.visibility = View.GONE
        startHotspot()
    }

    private fun stopSession() {
        running = false
        btnStart.text = "▶  ابدأ الحصة"
        tvStatus.text = "توقّفت الحصة. السجل محفوظ."
        boxQr.visibility = View.GONE
        stopServerOnly()
        releaseHotspot()
    }

    private fun startServer(): Boolean {
        stopServerOnly()
        return try {
            val s = AttServer(port, applicationContext) {
                handler.post { render() }
            }
            s.start(NanoTimeout, false)
            server = s
            true
        } catch (e: Exception) {
            toast("تعذّر تشغيل الخادم: " + e.message)
            false
        }
    }

    private fun stopServerOnly() {
        try { server?.stop() } catch (e: Exception) { }
        server = null
    }

    // ---------------- hotspot ----------------

    @SuppressLint("MissingPermission")
    private fun startHotspot() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    var ssid = ""
                    var pass = ""
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val c = res.softApConfiguration
                            ssid = c.ssid ?: ""
                            pass = c.passphrase ?: ""
                        } else {
                            @Suppress("DEPRECATION")
                            val c = res.wifiConfiguration
                            ssid = c?.SSID ?: ""
                            pass = c?.preSharedKey ?: ""
                        }
                    } catch (e: Exception) { }
                    ssid = ssid.trim().trim('"')
                    pass = pass.trim().trim('"')
                    handler.postDelayed({ hotspotReady(ssid, pass) }, 1200)
                }

                override fun onFailed(reason: Int) {
                    handler.post { hotspotFailed() }
                }

                override fun onStopped() {
                    handler.post {
                        if (running) tvStatus.text = "انقطعت الشبكة. اضغط «ابدأ الحصة» من جديد."
                    }
                }
            }, handler)
        } catch (e: Exception) {
            hotspotFailed()
        }
    }

    private fun releaseHotspot() {
        try { reservation?.close() } catch (e: Exception) { }
        reservation = null
    }

    private fun hotspotReady(ssid: String, pass: String) {
        val ip = localIp()
        val url = "http://" + ip + ":" + port
        tvStatus.text = "الشبكة تعمل ✓  —  أبقِ التطبيق مفتوحًا"
        boxQr.visibility = View.VISIBLE
        showQr(imgWifi, wifiQrText(ssid, pass))
        showQr(imgUrl, url)
        tvNet.text = "الشبكة: " + ssid + "\nكلمة المرور: " + pass + "\nالرابط: " + url
        render()
    }

    private fun hotspotFailed() {
        val ip = localIp()
        val url = "http://" + ip + ":" + port
        tvStatus.text = "تعذّر تشغيل الشبكة تلقائيًا"
        boxQr.visibility = View.VISIBLE
        imgWifi.visibility = View.GONE
        showQr(imgUrl, url)
        tvNet.text = "شغّل «نقطة الاتصال» يدويًا من إعدادات الهاتف، ثم اطلب من الطلاب الاتصال بها ومسح الرمز.\nالرابط: " + url
        AlertDialog.Builder(this)
            .setTitle("تعذّر تشغيل الشبكة")
            .setMessage(
                "تأكد من تفعيل «الموقع» (GPS) ومن إيقاف نقطة اتصال أخرى، ثم أعد المحاولة.\n\n" +
                        "أو شغّل نقطة الاتصال يدويًا من الإعدادات — سيعمل التطبيق كذلك."
            )
            .setPositiveButton("فتح الإعدادات") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (e: Exception) { }
            }
            .setNegativeButton("حسنًا", null)
            .show()
    }

    private fun localIp(): String {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            var fallback = ""
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                if (ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a !is Inet4Address) continue
                    if (a.isLoopbackAddress) continue
                    val h = a.hostAddress ?: continue
                    if (h.startsWith("192.168.49.") || h.startsWith("192.168.43.")) return h
                    if (fallback.isEmpty()) fallback = h
                }
            }
            if (fallback.isNotEmpty()) return fallback
        } catch (e: Exception) { }
        return "192.168.49.1"
    }

    // ---------------- QR ----------------

    private fun escapeWifi(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    private fun wifiQrText(ssid: String, pass: String): String {
        if (pass.isEmpty()) return "WIFI:T:nopass;S:" + escapeWifi(ssid) + ";;"
        return "WIFI:T:WPA;S:" + escapeWifi(ssid) + ";P:" + escapeWifi(pass) + ";;"
    }

    private fun showQr(target: ImageView, text: String) {
        try {
            val size = 420
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = 1
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            val row = IntArray(size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    row[x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
                bmp.setPixels(row, 0, size, 0, y, size, 1)
            }
            target.setImageBitmap(bmp)
            target.visibility = View.VISIBLE
        } catch (e: Exception) {
            target.visibility = View.GONE
        }
    }

    // ---------------- roster ----------------

    private fun rosterDialog() {
        val input = EditText(this)
        input.setText(Store.roster.joinToString("\n"))
        input.hint = "اسم في كل سطر"
        input.setLines(10)
        input.gravity = Gravity.TOP or Gravity.START
        input.setPadding(32, 24, 32, 24)

        AlertDialog.Builder(this)
            .setTitle("قائمة الطلاب")
            .setMessage("الصق الأسماء، اسمًا في كل سطر. سيختار الطالب اسمه من القائمة بدل كتابته.")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val names = input.text.toString().split("\n")
                Store.setRoster(names)
                toast("حُفظ " + Store.roster.size + " اسمًا")
                render()
            }
            .setNeutralButton("مسح تفقّد اليوم") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("مسح كل التسجيلات لهذا اليوم؟")
                    .setPositiveButton("مسح") { _, _ -> Store.clearDay(); render() }
                    .setNegativeButton("تراجع", null)
                    .show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------------- export ----------------

    private fun exportCsv() {
        val snapE = Store.snapshot()
        if (snapE.isEmpty() && Store.roster.isEmpty()) {
            toast("لا توجد بيانات للتصدير")
            return
        }
        try {
            val sb = StringBuilder()
            sb.append('﻿')
            sb.append("الاسم,الحالة,الوقت\n")
            val tf = SimpleDateFormat("HH:mm", Locale.US)
            if (Store.roster.isEmpty()) {
                for (e in snapE) {
                    sb.append(csv(e.first)).append(",حاضر,").append(tf.format(Date(e.second))).append("\n")
                }
            } else {
                for (r in Store.roster) {
                    var t = ""
                    for (e in snapE) {
                        if (Store.norm(e.first) == Store.norm(r)) { t = tf.format(Date(e.second)); break }
                    }
                    sb.append(csv(r)).append(if (t.isEmpty()) ",غائب," else ",حاضر,").append(t).append("\n")
                }
                for (e in snapE) {
                    var known = false
                    for (r in Store.roster) if (Store.norm(e.first) == Store.norm(r)) { known = true; break }
                    if (!known) {
                        sb.append(csv(e.first)).append(",حاضر (خارج القائمة),")
                            .append(tf.format(Date(e.second))).append("\n")
                    }
                }
            }

            val dir = File(filesDir, "exports")
            dir.mkdirs()
            val f = File(dir, "attendance_" + Store.today() + ".csv")
            f.writeText(sb.toString(), Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
            val send = Intent(Intent.ACTION_SEND)
            send.type = "text/csv"
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.putExtra(Intent.EXTRA_SUBJECT, "تفقّد " + Store.today())
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "مشاركة ملف التفقّد"))
        } catch (e: Exception) {
            toast("تعذّر التصدير: " + e.message)
        }
    }

    private fun csv(v: String): String {
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\""
        }
        return v
    }

    // ---------------- render ----------------

    private fun render() {
        val snap = Store.snapshot()
        val p = snap.size
        val total = if (Store.roster.isEmpty()) p else Store.roster.size
        tvPresent.text = p.toString()
        tvAbsent.text = (if (total - p > 0) total - p else 0).toString()
        tvTotal.text = total.toString()

        listBox.removeAllViews()
        val tf = SimpleDateFormat("HH:mm", Locale.US)
        val entries = snap.sortedByDescending { it.second }
        tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        listBox.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE

        var i = entries.size
        for (e in entries) {
            val row = TextView(this)
            row.text = i.toString() + ".  " + e.first + "   —   " + tf.format(Date(e.second))
            row.textSize = 16f
            row.setTextColor(ContextCompat.getColor(this, R.color.ink))
            row.setPadding(20, 20, 20, 20)
            row.setBackgroundResource(R.drawable.row_bg)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = 10
            row.layoutParams = lp
            val nameForDelete = e.first
            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setMessage("حذف تسجيل «" + nameForDelete + "»؟")
                    .setPositiveButton("حذف") { _, _ -> Store.removeName(nameForDelete); render() }
                    .setNegativeButton("تراجع", null)
                    .show()
                true
            }
            listBox.addView(row)
            i--
        }
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqLocation) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSession()
            } else {
                toast("يحتاج التطبيق إذن الموقع لتشغيل شبكة Wi-Fi المحلية")
            }
        }
    }

    companion object {
        const val NanoTimeout = 10000
    }
}
