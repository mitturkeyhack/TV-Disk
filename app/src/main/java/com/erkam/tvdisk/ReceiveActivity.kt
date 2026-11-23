package com.erkam.tvdisk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.erkam.tvdisk.net.FileSink
import com.erkam.tvdisk.net.Proto
import com.erkam.tvdisk.net.ReceiveEvents
import com.erkam.tvdisk.net.ReceiverCore
import com.erkam.tvdisk.net.SinkTarget
import com.erkam.tvdisk.video.VideoAnalyzer
import com.erkam.tvdisk.video.formatSize
import com.erkam.tvdisk.video.tvSafeName
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket

fun deviceName(ctx: Context): String {
    val n = try { Settings.Global.getString(ctx.contentResolver, "device_name") } catch (_: Exception) { null }
    return n?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER.uppercase()} ${Build.MODEL}"
}

class ReceiveActivity : Activity() {

    companion object { private const val REQ_DISK = 21 }

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var alive = true
    private var server: ServerSocket? = null
    private var port = Proto.TCP_PORT
    private var wifiLock: WifiManager.WifiLock? = null

    // Disk durumu (işaret yayını için, birkaç saniyede bir yenilenir)
    @Volatile private var diskReady = false
    @Volatile private var diskFree = -1L
    @Volatile private var diskLabel = ""

    private val rows = HashMap<String, View>()
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receive)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        list = findViewById(R.id.list)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.btnPickDisk).setOnClickListener { DiskStore.showPicker(this, REQ_DISK) }
        findViewById<TextView>(R.id.nameText).text = "Bu telefonun adı: ${deviceName(this)}"

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvdisk:al").apply { acquire() }
        } catch (_: Exception) { }

        Thread { serverLoop() }.start()
        Thread { beaconLoop() }.start()
        refreshDisk()
        pulse()
    }

    override fun onResume() {
        super.onResume()
        refreshDisk()
    }

    override fun onDestroy() {
        super.onDestroy()
        alive = false
        try { server?.close() } catch (_: Exception) { }
        try { wifiLock?.release() } catch (_: Exception) { }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DISK && resultCode == RESULT_OK) {
            DiskStore.handlePick(this, data, REQ_DISK)
            refreshDisk()
        }
    }

    // ---------- Disk ----------

    private fun refreshDisk() {
        Thread {
            val dir = DiskStore.targetDir(this)
            val root = DiskStore.root(this)
            val free = DiskStore.freeBytes(this)
            diskReady = dir != null
            diskFree = free
            diskLabel = root?.name ?: ""
            ui.post { showDisk() }
        }.start()
    }

    private fun showDisk() {
        val name = findViewById<TextView>(R.id.diskName)
        val free = findViewById<TextView>(R.id.diskFree)
        val btn = findViewById<TextView>(R.id.btnPickDisk)
        if (diskReady) {
            name.text = "✓  ${diskLabel.ifEmpty { "USB bellek" }} / ${DiskStore.TARGET_DIR}"
            name.setTextColor(color(R.color.ok))
            free.text = if (diskFree >= 0) "Boş alan: ${formatSize(diskFree)}" else "Videolar FILMLER klasörüne yazılacak"
            btn.text = "Başka disk seç"
        } else {
            name.text = "Flash disk seçilmedi"
            name.setTextColor(color(R.color.err))
            free.text = "Flash diski bu telefona tak ve seç. Seçmeden video alınamaz."
            btn.text = "USB diski seç"
        }
        val ip = Proto.localIPv4()
        findViewById<TextView>(R.id.ipText).text =
            if (ip != null) "Adres: $ip" else "Wi-Fi'ye bağlı değilsin! Evdeki Wi-Fi'ye ya da diğer telefonun hotspot'una bağlan."
    }

    private fun pulse() {
        val dot = findViewById<View>(R.id.pulse)
        val c = color(if (diskReady && Proto.localIPv4() != null) R.color.ok else R.color.warn)
        dot.backgroundTintList = ColorStateList.valueOf(c)
        dot.animate().alpha(if (dot.alpha > 0.6f) 0.25f else 1f).setDuration(700).withEndAction {
            if (alive) pulse()
        }.start()
    }

    // ---------- Ağ ----------

    private fun beaconLoop() {
        var sock: DatagramSocket? = null
        var tick = 0
        try {
            sock = DatagramSocket().apply { broadcast = true }
            while (alive) {
                if (tick++ % 4 == 0) {
                    // disk takılıp çıkarılmış olabilir
                    val dir = DiskStore.targetDir(this)
                    diskReady = dir != null
                    diskFree = DiskStore.freeBytes(this)
                }
                val msg = Proto.beacon(deviceName(this), port, diskReady, diskFree, diskLabel).toByteArray()
                for (addr in Proto.broadcastAddresses()) {
                    try { sock.send(DatagramPacket(msg, msg.size, addr, Proto.UDP_PORT)) } catch (_: Exception) { }
                }
                Thread.sleep(1000)
            }
        } catch (_: Exception) {
        } finally {
            sock?.close()
        }
    }

    private fun serverLoop() {
        val ss = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Proto.TCP_PORT)) }
        } catch (e: Exception) {
            ServerSocket(0)
        }
        server = ss
        port = ss.localPort
        while (alive) {
            val s = try { ss.accept() } catch (_: Exception) { break }
            try {
                s.use { ReceiverCore.handle(it, sink, events) }
            } catch (_: Exception) {
            }
            refreshDisk()
        }
    }

    private val sink = object : FileSink {
        override fun reject(name: String, size: Long): Pair<Int, String>? {
            if (DiskStore.targetDir(this@ReceiveActivity) == null) return Proto.NO_DISK to ""
            if (size > VideoAnalyzer.FAT32_MAX) return Proto.TOO_BIG to ""
            val free = DiskStore.freeBytes(this@ReceiveActivity)
            if (free in 0 until size + 8_000_000) return Proto.NO_SPACE to ""
            return null
        }

        override fun create(name: String): SinkTarget {
            val (folder, file) = DiskStore.splitName(name)
            val dir = DiskStore.dirNamed(this@ReceiveActivity, folder) ?: throw IOException("Disk yok")
            val f = dir.createFile("application/octet-stream", tvSafeName(file)) ?: throw IOException("Dosya oluşturulamadı")
            val os = contentResolver.openOutputStream(f.uri) ?: throw IOException("Diske yazılamadı")
            return object : SinkTarget {
                override val out: OutputStream = os
                override val displayName: String = f.name ?: name
                override fun delete() { try { f.delete() } catch (_: Exception) { } }
            }
        }
    }

    private val events = object : ReceiveEvents {
        override fun started(name: String, size: Long) = ui.post {
            setStatus("Alınıyor…", name)
            val v = LayoutInflater.from(this@ReceiveActivity).inflate(R.layout.item_video, list, false)
            v.findViewById<TextView>(R.id.name).text = name
            v.findViewById<TextView>(R.id.info).text = formatSize(size)
            v.findViewById<View>(R.id.remove).visibility = View.GONE
            v.findViewById<View>(R.id.verdict).visibility = View.GONE
            v.findViewById<View>(R.id.bar).visibility = View.VISIBLE
            v.findViewById<View>(R.id.status).visibility = View.VISIBLE
            v.findViewById<TextView>(R.id.status).text = "Bağlandı…"
            rows[name] = v
            list.addView(v, 0)
        }.let { }

        override fun progress(name: String, done: Long, total: Long, bytesPerSec: Double) = ui.post {
            val v = rows[name] ?: return@post
            val pct = if (total > 0) (done * 100 / total).toInt() else 0
            v.findViewById<ProgressBar>(R.id.bar).progress = pct
            val left = if (bytesPerSec > 0) ((total - done) / bytesPerSec).toLong() else -1
            v.findViewById<TextView>(R.id.status).text =
                "%$pct  ·  ${speed(bytesPerSec)}" + (if (left >= 0) "  ·  ${eta(left)} kaldı" else "")
        }.let { }

        override fun finished(name: String, ok: Boolean, message: String) = ui.post {
            setStatus("Gönderim bekleniyor", "Diğer telefonda TV Disk → Kablosuz Aktar → Gönder'i aç")
            val v = rows[name]
            if (v == null) {
                // başlamadan reddedildi
                setStatus("Alınamadı", message)
                return@post
            }
            val st = v.findViewById<TextView>(R.id.status)
            if (ok) {
                v.findViewById<ProgressBar>(R.id.bar).progress = 100
                st.text = "✓ $message"
                st.setTextColor(color(R.color.ok))
            } else {
                v.findViewById<View>(R.id.bar).visibility = View.GONE
                st.text = "✗ $message"
                st.setTextColor(color(R.color.err))
            }
        }.let { }
    }

    private fun setStatus(title: String, sub: String) {
        findViewById<TextView>(R.id.statusTitle).text = title
        findViewById<TextView>(R.id.statusSub).text = sub
    }
}

fun speed(bps: Double): String = String.format(java.util.Locale("tr"), "%.1f MB/sn", bps / 1e6)

fun eta(sec: Long): String = when {
    sec >= 3600 -> "${sec / 3600} sa ${(sec % 3600) / 60} dk"
    sec >= 60 -> "${sec / 60} dk ${sec % 60} sn"
    else -> "$sec sn"
}
