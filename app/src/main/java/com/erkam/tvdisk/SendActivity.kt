package com.erkam.tvdisk

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.erkam.tvdisk.net.Peer
import com.erkam.tvdisk.net.Proto
import com.erkam.tvdisk.net.SenderCore
import com.erkam.tvdisk.video.VideoAction
import com.erkam.tvdisk.video.VideoAnalyzer
import com.erkam.tvdisk.video.VideoConverter
import com.erkam.tvdisk.video.VideoInfo
import com.erkam.tvdisk.video.formatSize
import java.io.File
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class SendActivity : Activity() {

    companion object { private const val REQ_VIDEO = 31 }

    private class Row(val view: View, val uri: Uri) {
        var info: VideoInfo? = null
        var done = false
        var ok = false
        val bar: ProgressBar get() = view.findViewById(R.id.bar)
        val status: TextView get() = view.findViewById(R.id.status)
    }

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var alive = true
    @Volatile private var cancelled = false
    private var running = false
    private var converter: VideoConverter? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var discovery: DatagramSocket? = null

    private val peers = LinkedHashMap<String, Peer>()
    private var selected: String? = null
    private val rows = mutableListOf<Row>()

    private lateinit var peersBox: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var btnStart: TextView
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send)
        peersBox = findViewById(R.id.peers)
        list = findViewById(R.id.list)
        btnStart = findViewById(R.id.btnStart)
        summary = findViewById(R.id.summary)

        findViewById<View>(R.id.back).setOnClickListener { onBackPressed() }
        findViewById<View>(R.id.btnManual).setOnClickListener { manualDialog() }
        findViewById<View>(R.id.btnPickVideo).setOnClickListener {
            if (running) return@setOnClickListener
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_VIDEO)
        }
        btnStart.setOnClickListener { startAll() }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("tvdisk:bul").apply { setReferenceCounted(false); acquire() }
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvdisk:gonder").apply { acquire() }
        } catch (_: Exception) { }

        Thread { discoveryLoop() }.start()
        prunePeers()
        updateSummary()
    }

    override fun onDestroy() {
        super.onDestroy()
        alive = false
        cancelled = true
        converter?.cancel()
        try { discovery?.close() } catch (_: Exception) { }
        try { multicastLock?.release() } catch (_: Exception) { }
        try { wifiLock?.release() } catch (_: Exception) { }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (running) {
            AlertDialog.Builder(this)
                .setMessage("Gönderim sürüyor. Durdurup çıkmak istiyor musun?")
                .setPositiveButton("Durdur") { _, _ -> cancelled = true; converter?.cancel(); finish() }
                .setNegativeButton("Devam et", null)
                .show()
            return
        }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------- Alıcı bulma ----------

    private fun discoveryLoop() {
        try {
            val s = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1000
                bind(InetSocketAddress(Proto.UDP_PORT))
            }
            discovery = s
            val buf = ByteArray(1024)
            while (alive) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                val host = p.address?.hostAddress ?: continue
                val peer = Proto.parseBeacon(String(p.data, 0, p.length, Charsets.UTF_8), host) ?: continue
                ui.post { onPeer(peer) }
            }
        } catch (_: Exception) {
            ui.post {
                findViewById<TextView>(R.id.searchText).text =
                    "Otomatik arama başlatılamadı. \"Adresle bağlan\"ı kullan."
            }
        }
    }

    private fun onPeer(p: Peer) {
        val key = "${p.host}:${p.port}"
        peers[key] = p
        if (selected == null) selected = key
        renderPeers()
    }

    private fun prunePeers() {
        val now = System.currentTimeMillis()
        val gone = peers.filter { it.value.port > 0 && it.value.lastSeen > 0 && now - it.value.lastSeen > 6000 }.keys
        if (gone.isNotEmpty()) {
            gone.forEach { peers.remove(it) }
            if (selected in gone) selected = peers.keys.firstOrNull()
            renderPeers()
        }
        if (alive) ui.postDelayed({ prunePeers() }, 2000)
    }

    private fun renderPeers() {
        peersBox.removeAllViews()
        findViewById<View>(R.id.searchBox).visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
        for ((key, p) in peers) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_peer, peersBox, false)
            v.findViewById<TextView>(R.id.peerName).text = p.name
            val info = v.findViewById<TextView>(R.id.peerInfo)
            val dot = v.findViewById<View>(R.id.dot)
            when {
                p.lastSeen == 0L -> {
                    info.text = "Adresle eklendi · ${p.host}"
                    dot.backgroundTintList = ColorStateList.valueOf(color(R.color.sub))
                }
                p.diskReady -> {
                    info.text = "Bağlı · ${p.diskName.ifEmpty { "Flash disk" }} takılı" +
                        (if (p.free >= 0) " · ${formatSize(p.free)} boş" else "")
                    dot.backgroundTintList = ColorStateList.valueOf(color(R.color.ok))
                }
                else -> {
                    info.text = "Bağlı · ama o telefonda flash disk seçilmemiş"
                    dot.backgroundTintList = ColorStateList.valueOf(color(R.color.warn))
                }
            }
            val sel = key == selected
            v.setBackgroundResource(if (sel) R.drawable.bg_option_sel else R.drawable.bg_option)
            v.findViewById<View>(R.id.check).visibility = if (sel) View.VISIBLE else View.INVISIBLE
            v.setOnClickListener { if (!running) { selected = key; renderPeers() } }
            peersBox.addView(v)
        }
        updateSummary()
    }

    private fun manualDialog() {
        val et = EditText(this).apply {
            hint = "192.168.1.5"
            inputType = InputType.TYPE_CLASS_PHONE
            setTextColor(color(R.color.text))
        }
        AlertDialog.Builder(this)
            .setTitle("Alıcının adresi")
            .setMessage("Diğer telefonun \"Kablosuz al\" ekranında yazan adresi gir.")
            .setView(et)
            .setPositiveButton("Bağlan") { _, _ ->
                val ip = et.text.toString().trim()
                if (ip.isNotEmpty()) {
                    val key = "$ip:${Proto.TCP_PORT}"
                    peers[key] = Peer("Elle eklenen telefon", ip, Proto.TCP_PORT, true, -1, "", 0L)
                    selected = key
                    renderPeers()
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ---------- Videolar ----------

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VIDEO || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        uris.forEach { addVideo(it) }
    }

    private fun addVideo(uri: Uri) {
        if (rows.any { it.uri == uri }) return
        val v = LayoutInflater.from(this).inflate(R.layout.item_video, list, false)
        val row = Row(v, uri)
        rows += row
        list.addView(v)
        findViewById<View>(R.id.empty).visibility = View.GONE
        v.findViewById<TextView>(R.id.name).text = "Kontrol ediliyor…"
        v.findViewById<TextView>(R.id.remove).setOnClickListener {
            if (running) return@setOnClickListener
            rows.remove(row); list.removeView(v)
            if (rows.isEmpty()) findViewById<View>(R.id.empty).visibility = View.VISIBLE
            updateSummary()
        }
        updateSummary()
        Thread {
            val info = try { VideoAnalyzer.analyze(this, uri) } catch (_: Exception) { null }
            ui.post { row.info = info; bindRow(row); updateSummary() }
        }.start()
    }

    private fun bindRow(r: Row) {
        val v = r.view
        val info = r.info
        val verdict = v.findViewById<TextView>(R.id.verdict)
        if (info == null) {
            v.findViewById<TextView>(R.id.name).text = "Video okunamadı"
            verdict.text = "✗ Bu dosya açılamadı"
            verdict.setTextColor(color(R.color.err))
            return
        }
        v.findViewById<TextView>(R.id.name).text = info.name
        v.findViewById<TextView>(R.id.info).text = info.description
        val (text, c) = when (info.action) {
            VideoAction.COPY -> "✓ Olduğu gibi gönderilecek (kalite aynen korunur)" to R.color.ok
            VideoAction.REMUX -> "⟳ MP4'e çevrilip gönderilecek (hızlı, kalite aynı)" to R.color.warn
            VideoAction.CONVERT -> "⟳ ${info.reason.substringBefore(';')}; 1080p'ye dönüştürülüp gönderilecek" to R.color.warn
        }
        verdict.text = text
        verdict.setTextColor(color(c))
    }

    private fun updateSummary() {
        val ready = rows.filter { it.info != null && !it.done }
        val conv = ready.count { it.info!!.action != VideoAction.COPY }
        val peer = selected?.let { peers[it] }
        summary.text = when {
            peer == null -> "Önce alıcı telefonu bul"
            ready.isEmpty() -> "Gönderilecek video seç"
            conv > 0 -> "${ready.size} video · $conv tanesi önce bu telefonda dönüştürülecek"
            else -> "${ready.size} video · ${formatSize(ready.sumOf { it.info!!.size })}"
        }
        btnStart.isEnabled = !running && ready.isNotEmpty() && peer != null
        btnStart.text = when {
            running -> "Gönderiliyor…"
            peer != null && ready.isNotEmpty() -> "${peer.name} cihazına gönder (${ready.size})"
            else -> "Gönder"
        }
    }

    // ---------- Gönderim ----------

    private fun startAll() {
        if (running) return
        val peer = selected?.let { peers[it] } ?: return
        if (peer.lastSeen > 0 && !peer.diskReady) {
            AlertDialog.Builder(this)
                .setMessage("${peer.name} telefonunda flash disk seçili değil. O telefonda \"USB diski seç\"e bas, sonra tekrar dene.")
                .setPositiveButton("Tamam", null).show()
            return
        }
        running = true
        cancelled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateSummary()
        renderPeers()
        processNext(peer.host, peer.port)
    }

    private fun processNext(host: String, port: Int) {
        if (!running || cancelled) return
        val r = rows.firstOrNull { it.info != null && !it.done }
        if (r == null) { finishAll(); return }
        val info = r.info!!
        r.bar.visibility = View.VISIBLE
        r.status.visibility = View.VISIBLE
        r.status.setTextColor(color(R.color.sub))
        r.bar.progress = 0

        if (info.action == VideoAction.COPY) {
            val size = if (info.size > 0) info.size else fileLength(info.uri)
            sendInBackground(r, host, port, info.name, size, 0, { contentResolver.openInputStream(info.uri) }, null)
            return
        }

        val tmp = File(cacheDir, "gonder.mp4")
        val free = StatFs(cacheDir.absolutePath).availableBytes
        val estimate = if (info.durationUs > 0) info.durationUs / 1_000_000L * 800_000L else info.size
        if (free < estimate + 300_000_000L) {
            fail(r, "Bu telefonda yer yok (gerekli ≈ ${formatSize(estimate)})"); processNext(host, port); return
        }
        val label = if (info.action == VideoAction.REMUX) "MP4'e çevriliyor" else "Dönüştürülüyor (1080p)"
        r.status.text = "$label…"
        val conv = VideoConverter(this)
        converter = conv
        val outName = info.name.substringBeforeLast('.') + ".mp4"
        conv.start(info, tmp, maxShortSide = 1080,
            onProgress = { p -> r.bar.progress = p / 2; r.status.text = "$label…  %$p" },
            onDone = {
                converter = null
                if (tmp.length() > VideoAnalyzer.FAT32_MAX) {
                    tmp.delete(); fail(r, "Dönüşen dosya 4 GB'tan büyük oldu"); processNext(host, port)
                } else {
                    sendInBackground(r, host, port, outName, tmp.length(), 50, { tmp.inputStream() }, tmp)
                }
            },
            onError = { msg -> converter = null; fail(r, msg); processNext(host, port) })
    }

    private fun sendInBackground(r: Row, host: String, port: Int, name: String, size: Long, base: Int,
                                 open: () -> InputStream?, tmp: File?) {
        r.status.text = "Bağlanıyor…"
        Thread {
            try {
                if (size <= 0) throw java.io.IOException("Dosya boyutu okunamadı")
                val input = open() ?: throw java.io.IOException("Video açılamadı")
                val finalName = SenderCore.send(host, port, name, size, input, { cancelled }) { done, total, bps ->
                    val pct = (done * 100 / total).toInt()
                    val left = if (bps > 0) ((total - done) / bps).toLong() else -1
                    ui.post {
                        r.bar.progress = base + pct * (100 - base) / 100
                        r.status.text = "Gönderiliyor…  %$pct  ·  ${speed(bps)}" + (if (left >= 0) "  ·  ${eta(left)} kaldı" else "")
                    }
                }
                tmp?.delete()
                ui.post {
                    r.done = true; r.ok = true
                    r.bar.progress = 100
                    r.status.text = "✓ Flash diske yazıldı: ${DiskStore.TARGET_DIR}/$finalName"
                    r.status.setTextColor(color(R.color.ok))
                    processNext(host, port)
                }
            } catch (e: Exception) {
                tmp?.delete()
                val msg = when (e) {
                    is java.net.ConnectException, is java.net.SocketTimeoutException ->
                        "Alıcıya bağlanılamadı. İki telefon aynı Wi-Fi'de mi, alıcıda \"Kablosuz al\" açık mı?"
                    else -> e.message ?: "Gönderilemedi"
                }
                ui.post { fail(r, msg); processNext(host, port) }
            }
        }.start()
    }

    private fun fileLength(uri: Uri): Long = try {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
    } catch (_: Exception) { -1 }

    private fun fail(r: Row, msg: String) {
        r.done = true
        r.bar.visibility = View.GONE
        r.status.visibility = View.VISIBLE
        r.status.text = "✗ $msg"
        r.status.setTextColor(color(R.color.err))
    }

    private fun finishAll() {
        running = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateSummary()
        renderPeers()
        val ok = rows.count { it.ok }
        AlertDialog.Builder(this)
            .setTitle(if (ok > 0) "Bitti!" else "Gönderilemedi")
            .setMessage(if (ok > 0)
                "$ok video flash diske yazıldı.\n\nAlıcı telefonda bildirimden USB belleği \"ÇIKAR\" de, sonra TV'ye tak."
            else "Hiçbir video gönderilemedi. Hata mesajlarına bak.")
            .setPositiveButton("Tamam", null)
            .show()
    }
}
