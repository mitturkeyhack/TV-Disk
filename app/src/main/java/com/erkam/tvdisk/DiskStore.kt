package com.erkam.tvdisk

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/** Seçilen flash diski (SAF klasör izni) hatırlar; videolar FILMLER klasörüne konur. */
object DiskStore {
    const val TARGET_DIR = "FILMLER"
    private const val PREF_TREE = "tree"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tvdisk", Context.MODE_PRIVATE)

    fun treeUri(ctx: Context): Uri? = prefs(ctx).getString(PREF_TREE, null)?.let { Uri.parse(it) }

    fun root(ctx: Context): DocumentFile? {
        val u = treeUri(ctx) ?: return null
        return try {
            val d = DocumentFile.fromTreeUri(ctx, u)
            if (d != null && d.exists() && d.canWrite()) d else null
        } catch (_: Exception) { null }
    }

    /** FILMLER klasörünü bulur ya da oluşturur. */
    fun targetDir(ctx: Context): DocumentFile? {
        val r = root(ctx) ?: return null
        return try {
            if (r.name.equals(TARGET_DIR, true)) r
            else r.findFile(TARGET_DIR)?.takeIf { it.isDirectory } ?: r.createDirectory(TARGET_DIR)
        } catch (_: Exception) { null }
    }

    /** Flash diskteki boş alan; bilinemezse -1. */
    fun freeBytes(ctx: Context): Long {
        val u = treeUri(ctx) ?: return -1
        return try {
            val rootId = DocumentsContract.getTreeDocumentId(u).substringBefore(':')
            StatFs("/storage/$rootId").availableBytes
        } catch (_: Exception) { -1 }
    }

    fun showPicker(a: Activity, requestCode: Int) {
        AlertDialog.Builder(a)
            .setTitle("USB diski seç")
            .setMessage("Açılan pencerede sol üstteki ☰ menüden USB belleği seç (adı \"TV DISK\" olabilir).\n\n" +
                "Sonra alttaki \"İZİN VER\" ya da \"SEÇ\" düğmesine bas.\n\n" +
                "Disk görünmüyorsa: formatladıktan sonra diski çıkarıp tekrar taktığından emin ol.")
            .setPositiveButton("Tamam") { _, _ ->
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                i.putExtra("android.content.extra.SHOW_ADVANCED", true)
                @Suppress("DEPRECATION") a.startActivityForResult(i, requestCode)
            }
            .show()
    }

    /** Seçim sonucunu kaydeder. Telefonun kendi hafızası seçildiyse uyarır ve false döner. */
    fun handlePick(a: Activity, data: Intent?, requestCode: Int): Boolean {
        val uri = data?.data ?: return false
        try {
            a.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { }
        val docId = DocumentsContract.getTreeDocumentId(uri)
        if (docId.startsWith("primary") || docId.startsWith("home")) {
            AlertDialog.Builder(a)
                .setMessage("Seçtiğin yer telefonun kendi hafızası, flash disk değil.\nSol menüden USB belleği seç.")
                .setPositiveButton("Tekrar seç") { _, _ -> showPicker(a, requestCode) }
                .setNegativeButton("Vazgeç", null)
                .show()
            return false
        }
        prefs(a).edit().putString(PREF_TREE, uri.toString()).apply()
        return true
    }
}
