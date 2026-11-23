package com.erkam.tvdisk

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import com.erkam.tvdisk.video.VideoAction
import com.erkam.tvdisk.video.VideoAnalyzer
import com.erkam.tvdisk.video.VideoConverter
import com.erkam.tvdisk.video.VideoInfo
import com.erkam.tvdisk.video.formatSize
import com.erkam.tvdisk.video.tvSafeName
import java.io.File
import java.io.IOException
import java.io.InputStream

class VideoActivity : Activity() {

    companion object {
        private const val REQ_DISK = 11
        private const val REQ_VIDEO = 12
        private const val PREF_TREE = "tree"
        private const val TARGET_DIR = "FILMLER"
    }

    private class Row(val view: View, var info: VideoInfo?, val uri: Uri) {
        var done = false
        val bar: ProgressBar get() = view.findViewById(R.id.bar)
        val status: TextView get() = view.findViewById(R.id.status)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val rows = mutableListOf<Row>()
    private var treeUri: Uri? = null
    private var running = false
    private var converter: VideoConverter? = null

    private lateinit var list: LinearLayout
    private lateinit var btnStart: TextView
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)
        list = findViewById(R.id.list)
        btnStart = findViewById(R.id.btnStart)
        summary = findViewById(R.id.summary)

        findViewById<View>(R.id.back).setOnClickListener { onBackPressed() }
        findViewById<View>(R.id.btnPickDisk).setOnClickListener { pickDisk() }
        findViewById<View>(R.id.btnPickVideo).setOnClickListener {
            if (running) return@setOnClickListener
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(i, REQ_VIDEO)
        }
        btnStart.setOnClickListener { startAll() }

        prefs().getString(PREF_TREE, null)?.let { treeUri = Uri.parse(it) }
        updateDisk()
        updateSummary()
    }

    override fun onResume() {
        super.onResume()
        if (!running) updateDisk()
    }

    override fun onDestroy() {
        super.onDestroy()
        converter?.cancel()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (running) {
            AlertDialog.Builder(this)
                .setMessage("Aktarım sürüyor. Durdurup çıkmak istiyor musun?")
                .setPositiveButton("Durdur") { _, _ -> converter?.cancel(); running = false; finish() }
                .setNegativeButton("Devam et", null)
                .show()
            return
        }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun prefs() = getSharedPreferences("tvdisk", Context.MODE_PRIVATE)

    // ---------- Disk seçimi ----------

    private fun pickDisk() {
        if (running) return
        AlertDialog.Builder(this)
            .setTitle("USB diski seç")
            .setMessage("Açılan pencerede sol üstteki ☰ menüden USB belleği seç (adı \"TV DISK\" olabilir).\n\n" +
                "Sonra alttaki \"İZİN VER\" ya da \"SEÇ\" düğmesine bas.\n\n" +
                "Disk görünmüyorsa: formatladıktan sonra diski çıkarıp tekrar taktığından emin ol.")
            .setPositiveButton("Tamam") { _, _ ->
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                i.putExtra("android.content.extra.SHOW_ADVANCED", true)
                startActivityForResult(i, REQ_DISK)
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_DISK -> {
                val uri = data.data ?: return
                try {
                    contentResolver.takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (_: Exception) { }
                val docId = DocumentsContract.getTreeDocumentId(uri)
                if (docId.startsWith("primary") || docId.startsWith("home")) {
                    AlertDialog.Builder(this)
                        .setMessage("Seçtiğin yer telefonun kendi hafızası, flash disk değil.\nSol menüden USB belleği seç.")
                        .setPositiveButton("Tekrar seç") { _, _ -> pickDisk() }
                        .setNegativeButton("Vazgeç", null)
                        .show()
                    return
                }
                treeUri = uri
                prefs().edit().putString(PREF_TREE, uri.toString()).apply()
                updateDisk()
            }
            REQ_VIDEO -> {
                val uris = mutableListOf<Uri>()
                data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
                if (uris.isEmpty()) data.data?.let { uris += it }
                uris.forEach { addVideo(it) }
            }
        }
    }

    private fun diskRoot(): DocumentFile? {
        val u = treeUri ?: return null
        return try {
            val d = DocumentFile.fromTreeUri(this, u)
            if (d != null && d.exists() && d.canWrite()) d else null
        } catch (_: Exception) { null }
    }

    private fun updateDisk() {
        val name = findViewById<TextView>(R.id.diskName)
        val hint = findViewById<TextView>(R.id.diskHint)
        val btn = findViewById<TextView>(R.id.btnPickDisk)
        val root = diskRoot()
        if (root != null) {
            name.text = "✓  ${root.name ?: "USB bellek"}"
            name.setTextColor(color(R.color.ok))
            hint.text = "Videolar bu diskteki $TARGET_DIR klasörüne konacak."
            btn.text = "Başka disk seç"
        } else {
            name.text = if (treeUri == null) "Seçilmedi" else "Disk bulunamadı"
            name.setTextColor(color(R.color.err))
            hint.text = if (treeUri == null) "Videoların gideceği flash diski seç."
            else "Disk takılı değil ya da yeniden formatlandı. Diski tak ve tekrar seç."
            btn.text = "USB diski seç"
        }
        updateSummary()
    }

    // ---------- Video listesi ----------

    private fun addVideo(uri: Uri) {
        if (rows.any { it.uri == uri }) return
        val v = LayoutInflater.from(this).inflate(R.layout.item_video, list, false)
        val row = Row(v, null, uri)
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
            val info = try { VideoAnalyzer.analyze(this, uri) } catch (e: Exception) { null }
            ui.post {
                row.info = info
                bindRow(row)
                updateSummary()
            }
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
        when (info.action) {
            VideoAction.COPY -> { verdict.text = "✓ ${info.reason}"; verdict.setTextColor(color(R.color.ok)) }
            VideoAction.REMUX -> { verdict.text = "⟳ ${info.reason}"; verdict.setTextColor(color(R.color.warn)) }
            VideoAction.CONVERT -> { verdict.text = "⟳ ${info.reason}"; verdict.setTextColor(color(R.color.warn)) }
        }
    }

    private fun updateSummary() {
        val ready = rows.filter { it.info != null && !it.done }
        val conv = ready.count { it.info!!.action == VideoAction.CONVERT }
        summary.text = when {
            rows.isEmpty() -> "Önce disk, sonra video seç"
            ready.isEmpty() -> "Aktarılacak video yok"
            conv > 0 -> "${ready.size} video · $conv tanesi dönüştürülecek (uzun sürebilir)"
            else -> "${ready.size} video aktarılmaya hazır"
        }
        btnStart.isEnabled = !running && ready.isNotEmpty() && diskRoot() != null
        btnStart.text = if (running) "Aktarılıyor…" else "Diske aktar"
    }

    // ---------- Aktarım ----------

    private fun startAll() {
        if (running) return
        val root = diskRoot() ?: run { updateDisk(); return }
        val dir = try {
            if (root.name.equals(TARGET_DIR, true)) root
            else root.findFile(TARGET_DIR)?.takeIf { it.isDirectory } ?: root.createDirectory(TARGET_DIR)
        } catch (e: Exception) { null }
        if (dir == null) {
            AlertDialog.Builder(this).setMessage("Diskte klasör oluşturulamadı. Diski çıkarıp takıp tekrar seç.")
                .setPositiveButton("Tamam", null).show()
            return
        }
        running = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateSummary()
        processNext(dir)
    }

    private fun processNext(dir: DocumentFile) {
        if (!running) return
        val r = rows.firstOrNull { it.info != null && !it.done }
        if (r == null) { finishAll(); return }
        val info = r.info!!
        r.bar.visibility = View.VISIBLE
        r.status.visibility = View.VISIBLE
        r.bar.progress = 0

        if (info.action == VideoAction.COPY) {
            r.status.text = "Diske kopyalanıyor…"
            val name = tvSafeName(info.name)
            copyInBackground(r, dir, name, info.size, { contentResolver.openInputStream(info.uri) }, null)
            return
        }

        // Dönüştür → geçici dosya → diske kopyala
        val tmp = File(cacheDir, "donusum.mp4")
        val estimate = if (info.durationUs > 0) info.durationUs / 1_000_000L * 400_000L else info.size
        val free = StatFs(cacheDir.absolutePath).availableBytes
        if (free < estimate + 200_000_000L) {
            fail(r, "Telefonda yer yok (gerekli ≈ ${formatSize(estimate)}, boş ${formatSize(free)})")
            processNext(dir); return
        }
        r.status.text = if (info.action == VideoAction.REMUX) "MP4'e çevriliyor…" else "Dönüştürülüyor… (telefon ısınabilir)"
        val conv = VideoConverter(this)
        converter = conv
        val baseName = tvSafeName(info.name.substringBeforeLast('.')) + ".mp4"
        conv.start(info, tmp,
            onProgress = { p ->
                r.bar.progress = p * 85 / 100
                r.status.text = (if (info.action == VideoAction.REMUX) "MP4'e çevriliyor" else "Dönüştürülüyor") + "…  %$p"
            },
            onDone = {
                converter = null
                if (tmp.length() > VideoAnalyzer.FAT32_MAX) {
                    tmp.delete(); fail(r, "Dönüşen dosya 4 GB'tan büyük oldu, FAT32'ye sığmıyor"); processNext(dir)
                } else {
                    r.status.text = "Diske kopyalanıyor…"
                    copyInBackground(r, dir, baseName, tmp.length(), { tmp.inputStream() }, tmp)
                }
            },
            onError = { msg ->
                converter = null
                fail(r, msg)
                processNext(dir)
            })
    }

    private fun copyInBackground(r: Row, dir: DocumentFile, name: String, total: Long,
                                 open: () -> InputStream?, tmp: File?) {
        val base = if (tmp != null) 85 else 0
        Thread {
            var target: DocumentFile? = null
            try {
                target = dir.createFile("application/octet-stream", name)
                    ?: throw IOException("Diskte dosya oluşturulamadı")
                val input = open() ?: throw IOException("Video açılamadı")
                val out = contentResolver.openOutputStream(target.uri) ?: throw IOException("Diske yazılamadı")
                input.use { inp ->
                    out.use { o ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt().coerceAtMost(100) else 0
                            if (pct != last) {
                                last = pct
                                ui.post {
                                    r.bar.progress = base + pct * (100 - base) / 100
                                    r.status.text = "Diske kopyalanıyor…  %$pct"
                                }
                            }
                        }
                        o.flush()
                    }
                }
                tmp?.delete()
                ui.post {
                    r.done = true
                    r.bar.progress = 100
                    r.status.text = "✓ Diske aktarıldı: $TARGET_DIR/${target.name}"
                    r.status.setTextColor(color(R.color.ok))
                    processNext(dir)
                }
            } catch (e: Exception) {
                tmp?.delete()
                try { target?.delete() } catch (_: Exception) { }
                val msg = if (e is IOException && (e.message ?: "").contains("ENOSPC", true))
                    "Diskte yer kalmadı" else "Kopyalanamadı: ${e.message ?: "bilinmeyen hata"} (diskte yer kalmamış olabilir)"
                ui.post { fail(r, msg); processNext(dir) }
            }
        }.start()
    }

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
        val ok = rows.count { it.done && it.status.text.startsWith("✓") }
        AlertDialog.Builder(this)
            .setTitle(if (ok > 0) "Bitti!" else "Aktarılamadı")
            .setMessage(if (ok > 0)
                "$ok video diske aktarıldı.\n\nDiski çıkarmadan önce bildirim panelinden USB belleğe dokunup \"ÇIKAR\" de. " +
                    "Sonra TV'ye tak, kumandadan Kaynak/Source → USB'yi seç ve $TARGET_DIR klasörüne gir."
            else "Hiçbir video aktarılamadı. Hata mesajlarına bak.")
            .setPositiveButton("Tamam", null)
            .show()
    }
}
