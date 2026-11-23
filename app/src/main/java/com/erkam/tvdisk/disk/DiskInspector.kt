package com.erkam.tvdisk.disk

/** Diskin şu anki bölüm tablosunu ve dosya sistemini tanır. */
object DiskInspector {

    data class Info(
        val scheme: String,      // "MBR", "GPT", "Bölümsüz", "Boş"
        val fs: String,          // "FAT32", "exFAT", "NTFS", "FAT16", "Bilinmiyor"
        val partBytes: Long,
        val tvReady: Boolean,
        val note: String,
    )

    private const val SAFE_LIMIT_BYTES = 32_100_000_000L

    fun inspect(dev: BlockDevice): Info {
        val s0 = ByteArray(512)
        dev.read(0, 1, s0)
        val sig = (s0[510].toInt() and 0xFF) == 0x55 && (s0[511].toInt() and 0xFF) == 0xAA

        // Bölüm tablosu olmadan doğrudan dosya sistemiyle formatlanmış disk
        val direct = fsName(s0)
        val first = s0[0].toInt() and 0xFF
        if (direct != null && (first == 0xEB || first == 0xE9)) {
            return Info("Bölümsüz", direct, dev.blockCount * 512, false,
                "Disk bölüm tablosu olmadan formatlanmış. Eski TV'ler genelde bunu okumaz.")
        }
        if (!sig) {
            return Info("Boş", "Bilinmiyor", 0, false, "Diskte tanınan bir biçim yok.")
        }

        val type = s0[0x1C2].toInt() and 0xFF
        if (type == 0xEE) {
            // GPT: ilk bölümün başlangıcını bul
            var fs = "Bilinmiyor"
            var size = 0L
            try {
                val hdr = ByteArray(512); dev.read(1, 1, hdr)
                if (String(hdr, 0, 8, Charsets.US_ASCII) == "EFI PART") {
                    val entLba = le64(hdr, 72)
                    val ent = ByteArray(512); dev.read(entLba, 1, ent)
                    val start = le64(ent, 32); val end = le64(ent, 40)
                    size = (end - start + 1) * 512
                    if (start in 1 until dev.blockCount) {
                        val b = ByteArray(512); dev.read(start, 1, b)
                        fs = fsName(b) ?: "Bilinmiyor"
                    }
                }
            } catch (_: Exception) { }
            return Info("GPT", fs, size, false,
                "Disk GPT bölüm tablosunda. Eski TV'ler GPT'yi tanımaz.")
        }

        // MBR: ilk dolu bölüm
        for (i in 0 until 4) {
            val e = 0x1BE + i * 16
            val t = s0[e + 4].toInt() and 0xFF
            if (t == 0) continue
            val start = le32(s0, e + 8)
            val count = le32(s0, e + 12)
            var fs = "Bilinmiyor"
            if (start in 1 until dev.blockCount) {
                val b = ByteArray(512); dev.read(start, 1, b)
                fs = fsName(b) ?: "Bilinmiyor"
            }
            val bytes = count * 512
            val ready = fs == "FAT32" && bytes <= SAFE_LIMIT_BYTES
            val note = when {
                ready -> "Disk zaten TV'ye uygun görünüyor."
                fs == "FAT32" -> "FAT32 ama 32 GB'tan büyük. Bazı eski TV'ler okumayabilir."
                fs == "exFAT" -> "exFAT biçiminde. Eski TV'ler genelde exFAT okumaz."
                fs == "NTFS" -> "NTFS biçiminde. Eski TV'ler çoğunlukla NTFS okumaz."
                else -> "Dosya sistemi tanınmadı."
            }
            return Info("MBR", fs, bytes, ready, note)
        }
        return Info("MBR", "Bilinmiyor", 0, false, "Bölüm tablosu boş.")
    }

    fun fsName(b: ByteArray): String? {
        val oem = String(b, 3, 8, Charsets.US_ASCII)
        if (oem == "EXFAT   ") return "exFAT"
        if (oem == "NTFS    ") return "NTFS"
        if (String(b, 82, 5, Charsets.US_ASCII) == "FAT32") return "FAT32"
        val t16 = String(b, 54, 5, Charsets.US_ASCII)
        if (t16 == "FAT16" || t16 == "FAT12" || t16.startsWith("FAT")) return "FAT16"
        return null
    }

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun le64(b: ByteArray, o: Int): Long = le32(b, o) or (le32(b, o + 4) shl 32)
}
