package com.erkam.tvdisk

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import com.erkam.tvdisk.disk.DiskInspector
import com.erkam.tvdisk.disk.Fat32Formatter
import com.erkam.tvdisk.usb.UsbMassStorage

class FormatActivity : Activity() {

    companion object {
        private const val ACTION_PERM = "com.erkam.tvdisk.USB_PERMISSION"
        private const val SAFE_LIMIT = 32_100_000_000L
    }

    private lateinit var usb: UsbManager
    private var disk: UsbMassStorage? = null
    private var device: UsbDevice? = null
    private var safeMode = true
    private var busy = false
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var waitBox: View
    private lateinit var waitText: TextView
    private lateinit var spinner: View
    private lateinit var btnRetry: View
    private lateinit var infoCard: View
    private lateinit var optionsBox: View
    private lateinit var progressCard: View
    private lateinit var progText: TextView
    private lateinit var progBar: ProgressBar
    private lateinit var holdBar: ProgressBar

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != ACTION_PERM) return
            val d = device ?: return
            if (usb.hasPermission(d)) openDisk(d)
            else showError("USB izni verilmedi. \"Tekrar dene\"ye basıp izin penceresinde \"Tamam\"a dokun.")
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (busy) showError("Disk çıkarıldı! Formatlama yarıda kaldı. Diski tekrar takıp baştan formatla.")
            else if (disk != null) { closeDisk(); showError("Disk çıkarıldı. Tekrar takıp \"Tekrar dene\"ye bas.") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_format)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        waitBox = findViewById(R.id.waitBox)
        waitText = findViewById(R.id.waitText)
        spinner = findViewById(R.id.spinner)
        btnRetry = findViewById(R.id.btnRetry)
        infoCard = findViewById(R.id.infoCard)
        optionsBox = findViewById(R.id.optionsBox)
        progressCard = findViewById(R.id.progressCard)
        progText = findViewById(R.id.progText)
        progBar = findViewById(R.id.progBar)
        holdBar = findViewById(R.id.holdBar)

        findViewById<View>(R.id.back).setOnClickListener { onBackPressed() }
        btnRetry.setOnClickListener { start() }
        findViewById<View>(R.id.optSafe).setOnClickListener { selectMode(true) }
        findViewById<View>(R.id.optFull).setOnClickListener { selectMode(false) }
        findViewById<View>(R.id.btnToVideo).setOnClickListener {
            startActivity(Intent(this, VideoActivity::class.java)); finish()
        }
        findViewById<View>(R.id.btnHome).setOnClickListener { finish() }
        setupHold()

        registerReceiverCompat(permReceiver, IntentFilter(ACTION_PERM))
        registerReceiverCompat(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        start()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(permReceiver) } catch (_: Exception) { }
        try { unregisterReceiver(detachReceiver) } catch (_: Exception) { }
        if (!busy) closeDisk()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (busy) return // formatlama sırasında çıkılmasın
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------- Akış ----------

    private fun start() {
        closeDisk()
        showWait("Flash disk aranıyor…")
        val d = UsbMassStorage.find(usb)
        if (d == null) {
            showError("Flash disk bulunamadı.\nDiski telefona takıp \"Tekrar dene\"ye bas.")
            return
        }
        device = d
        if (usb.hasPermission(d)) openDisk(d) else {
            showWait("İzin bekleniyor… Açılan pencerede \"Tamam\"a dokun.")
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), flags)
            usb.requestPermission(d, pi)
        }
    }

    private fun openDisk(d: UsbDevice) {
        showWait("Disk okunuyor…")
        Thread {
            try {
                val m = UsbMassStorage.open(usb, d)
                val info = DiskInspector.inspect(m)
                ui.post {
                    if (isFinishing) { m.close(); return@post }
                    disk = m
                    showInfo(m, info)
                }
            } catch (e: Exception) {
                ui.post { showError("Disk okunamadı: ${e.message}\nDiski çıkarıp tekrar tak.") }
            }
        }.start()
    }

    private fun showInfo(m: UsbMassStorage, info: DiskInspector.Info) {
        waitBox.visibility = View.GONE
        btnRetry.visibility = View.GONE
        infoCard.visibility = View.VISIBLE
        optionsBox.visibility = View.VISIBLE

        val total = m.blockCount * 512
        val name = listOf(m.vendor, m.product).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { "USB bellek" }
        findViewById<TextView>(R.id.infoName).text = name
        findViewById<TextView>(R.id.infoCap).text = "Kapasite: ${gb(total)}"
        findViewById<TextView>(R.id.infoFormat).text = "Şu anki biçim: ${info.fs}  ·  ${info.scheme}"
        val note = findViewById<TextView>(R.id.infoNote)
        note.text = if (info.tvReady) "✓ ${info.note}" else "✗ ${info.note}"
        note.setTextColor(color(if (info.tvReady) R.color.ok else R.color.err))

        val safeT = findViewById<TextView>(R.id.optSafeTitle)
        val safeD = findViewById<TextView>(R.id.optSafeDesc)
        val optFull = findViewById<View>(R.id.optFull)
        if (total <= SAFE_LIMIT) {
            safeT.text = "Tüm disk (${gb(total)})"
            safeD.text = "Eski TV'lerin okuduğu FAT32 biçimi."
            optFull.visibility = View.GONE
        } else {
            safeT.text = "Güvenli — 32 GB  (önerilen)"
            safeD.text = "Diskin 32 GB'ı kullanılır, kalanı boş kalır. Eski TV'lerin neredeyse hepsi okur."
            findViewById<TextView>(R.id.optFullTitle).text = "Tam kapasite — ${gb(total)}"
            findViewById<TextView>(R.id.optFullDesc).text =
                "Diskin tamamı kullanılır. Bazı eski TV'ler 32 GB üstünü okumaz; okumazsa \"Güvenli\" ile tekrar formatla."
            optFull.visibility = View.VISIBLE
        }
        selectMode(true)
    }

    private fun selectMode(safe: Boolean) {
        safeMode = safe
        findViewById<View>(R.id.optSafe).setBackgroundResource(if (safe) R.drawable.bg_option_sel else R.drawable.bg_option)
        findViewById<View>(R.id.optFull).setBackgroundResource(if (!safe) R.drawable.bg_option_sel else R.drawable.bg_option)
    }

    // ---------- Basılı tutarak onay ----------

    private val holdTick = object : Runnable {
        override fun run() {
            holdBar.progress = holdBar.progress + 2
            if (holdBar.progress >= 100) { holdBar.progress = 0; startFormat() }
            else ui.postDelayed(this, 30)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHold() {
        val btn = findViewById<TextView>(R.id.btnHold)
        btn.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!busy && disk != null) { holdBar.progress = 0; ui.post(holdTick) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(holdTick)
                    if (holdBar.progress in 1..99) holdBar.progress = 0
                    true
                }
                else -> true
            }
        }
    }

    // ---------- Formatlama ----------

    private fun startFormat() {
        val m = disk ?: return
        busy = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        infoCard.visibility = View.GONE
        optionsBox.visibility = View.GONE
        progressCard.visibility = View.VISIBLE
        progText.text = "Başlıyor…"
        progBar.progress = 0
        val safe = safeMode
        Thread {
            try {
                val plan = Fat32Formatter.format(m, safe) { pct, msg ->
                    ui.post { progBar.progress = pct; progText.text = "$msg…  %$pct" }
                }
                m.close()
                ui.post {
                    disk = null
                    busy = false
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    showDone(plan.partBytes)
                }
            } catch (e: Exception) {
                m.close()
                ui.post {
                    disk = null
                    busy = false
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    progressCard.visibility = View.GONE
                    showError("Formatlama yarıda kaldı: ${e.message}\nDiski çıkarıp takıp tekrar dene.")
                }
            }
        }.start()
    }

    private fun showDone(bytes: Long) {
        progressCard.visibility = View.GONE
        findViewById<View>(R.id.doneCard).visibility = View.VISIBLE
        findViewById<View>(R.id.btnToVideo).visibility = View.VISIBLE
        findViewById<View>(R.id.btnHome).visibility = View.VISIBLE
        findViewById<TextView>(R.id.doneText).text =
            "Disk artık FAT32 (${gb(bytes)}). İçinde FILMLER, MUZIK ve FOTOLAR klasörleri var.\n\n" +
                "Şimdi diski telefondan ÇIKARIP TEKRAR TAK. Sonra \"Video Ekle\" ile videolarını aktar."
    }

    // ---------- Yardımcılar ----------

    private fun showWait(msg: String) {
        waitBox.visibility = View.VISIBLE
        spinner.visibility = View.VISIBLE
        waitText.text = msg
        waitText.setTextColor(color(R.color.text))
        btnRetry.visibility = View.GONE
        infoCard.visibility = View.GONE
        optionsBox.visibility = View.GONE
    }

    private fun showError(msg: String) {
        waitBox.visibility = View.VISIBLE
        spinner.visibility = View.GONE
        waitText.text = msg
        waitText.setTextColor(color(R.color.err))
        btnRetry.visibility = View.VISIBLE
        infoCard.visibility = View.GONE
        optionsBox.visibility = View.GONE
    }

    private fun closeDisk() {
        disk?.close()
        disk = null
    }
}
