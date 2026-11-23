package com.erkam.tvdisk.disk

import java.io.IOException
import java.util.Calendar
import kotlin.random.Random

/**
 * Diski eski TV'lerin okuduğu biçime getirir:
 * MBR bölüm tablosu + tek birincil bölüm (tip 0x0C) + FAT32.
 */
object Fat32Formatter {

    const val PART_START = 2048L                 // 1 MiB hizalı başlangıç
    const val SAFE_MAX_SECTORS = 62_500_000L     // ≈ 32 GB (32.000.000.000 bayt)
    val FOLDERS = listOf("FILMLER", "MUZIK", "FOTOLAR")

    data class Plan(
        val partStart: Long,
        val partSectors: Long,
        val sectorsPerCluster: Int,
        val reserved: Int,
        val fatSize: Long,
        val clusters: Long,
    ) {
        val dataStartRel: Long get() = reserved + 2 * fatSize
        val partBytes: Long get() = partSectors * 512
        fun clusterLba(c: Long): Long = partStart + dataStartRel + (c - 2) * sectorsPerCluster
    }

    fun plan(totalSectors: Long, safeMode: Boolean): Plan {
        val avail = totalSectors - PART_START
        if (avail < 600_000) throw IOException("Disk çok küçük")
        var part = if (safeMode) minOf(avail, SAFE_MAX_SECTORS) else avail
        if (part > 0xFFFFFFFFL) part = 0xFFFFFFFFL
        part -= part % 2048 // 1 MiB'a yuvarla

        val spc = when {
            part <= 16_777_216L -> 8      // ≤ 8 GB  → 4 KB küme
            part <= 33_554_432L -> 16     // ≤ 16 GB → 8 KB
            part <= 67_108_864L -> 32     // ≤ 32 GB → 16 KB
            else -> 64                    // büyük   → 32 KB
        }
        val baseReserved = 32
        // Microsoft FAT32 formülü
        val tmp1 = part - baseReserved
        val tmp2 = (256L * spc + 2) / 2
        val fatSize = (tmp1 + tmp2 - 1) / tmp2
        // Veri alanını 1 MiB'a hizala (flash bellekte hız için)
        val meta = baseReserved + 2 * fatSize
        val pad = ((2048 - meta % 2048) % 2048).toInt()
        val reserved = baseReserved + pad
        val dataSectors = part - reserved - 2 * fatSize
        val clusters = dataSectors / spc
        if (clusters < 65_525) throw IOException("FAT32 için disk çok küçük")
        if (clusters + 2 > fatSize * 128) throw IOException("FAT hesabı tutmadı")
        return Plan(PART_START, part, spc, reserved, fatSize, clusters)
    }

    /** progress(yüzde, açıklama) */
    fun format(dev: BlockDevice, safeMode: Boolean, label: String = "TV DISK",
               progress: (Int, String) -> Unit = { _, _ -> }): Plan {
        if (dev.blockSize != 512) throw IOException("Sektör boyutu 512 değil")
        val p = plan(dev.blockCount, safeMode)
        val spc = p.sectorsPerCluster
        val now = Calendar.getInstance()
        val date = (((now.get(Calendar.YEAR) - 1980) shl 9) or ((now.get(Calendar.MONTH) + 1) shl 5) or
            now.get(Calendar.DAY_OF_MONTH))
        val time = ((now.get(Calendar.HOUR_OF_DAY) shl 11) or (now.get(Calendar.MINUTE) shl 5) or
            (now.get(Calendar.SECOND) / 2))
        val volId = Random.nextInt()
        val lbl = label.uppercase().padEnd(11).take(11)

        // 1) Eski bölüm tablosu ve eski biçimin izlerini sil
        progress(2, "Eski biçim siliniyor")
        zero(dev, 0, PART_START)
        // Disk sonundaki eski GPT yedeğini de temizle (bölüm içindeyse zaten boş küme alanı)
        val tailStart = minOf(maxOf(PART_START + p.partSectors, dev.blockCount - 2048), dev.blockCount - 64)
        if (tailStart < dev.blockCount) zero(dev, tailStart, dev.blockCount - tailStart)

        // 2) Ayrılmış alan + iki FAT tablosunu sıfırla
        val metaCount = p.reserved + 2 * p.fatSize
        zero(dev, p.partStart, metaCount) { done ->
            progress(5 + (done * 80 / metaCount).toInt(), "Dosya tablosu hazırlanıyor")
        }

        // 3) Kök klasör + alt klasörlerin kümelerini sıfırla
        progress(86, "Klasörler hazırlanıyor")
        val usedClusters = 1 + FOLDERS.size
        zero(dev, p.clusterLba(2), usedClusters.toLong() * spc)

        // 4) Önyükleme sektörü (ana + yedek)
        progress(90, "Disk bilgileri yazılıyor")
        val boot = bootSector(p, volId, lbl)
        val fsinfo = fsInfo(p.clusters - usedClusters, 2L + usedClusters)
        val third = ByteArray(512).also { it[510] = 0x55; it[511] = 0xAA.toByte() }
        for (base in longArrayOf(0, 6)) {
            dev.write(p.partStart + base, 1, boot)
            dev.write(p.partStart + base + 1, 1, fsinfo)
            dev.write(p.partStart + base + 2, 1, third)
        }

        // 5) FAT tabloları: 0,1 ayrılmış; 2 = kök; 3.. = klasörler
        val fat = ByteArray(512)
        put32(fat, 0, 0x0FFFFFF8)
        put32(fat, 4, 0x0FFFFFFF)
        for (c in 2 until 2 + usedClusters) put32(fat, c * 4, 0x0FFFFFFF)
        dev.write(p.partStart + p.reserved, 1, fat)
        dev.write(p.partStart + p.reserved + p.fatSize, 1, fat)

        // 6) Kök klasör: birim etiketi + klasörler
        val root = ByteArray(512)
        dirEntry(root, 0, lbl.toByteArray(Charsets.US_ASCII), 0x08, 0, date, time)
        FOLDERS.forEachIndexed { i, name ->
            dirEntry(root, 32 * (i + 1), name83(name), 0x10, 3L + i, date, time)
        }
        dev.write(p.clusterLba(2), 1, root)

        // 7) Her alt klasöre "." ve ".." girdileri
        FOLDERS.forEachIndexed { i, _ ->
            val c = 3L + i
            val d = ByteArray(512)
            dirEntry(d, 0, ".          ".toByteArray(Charsets.US_ASCII), 0x10, c, date, time)
            dirEntry(d, 32, "..         ".toByteArray(Charsets.US_ASCII), 0x10, 0, date, time)
            dev.write(p.clusterLba(c), 1, d)
        }

        // 8) En son MBR'yi yaz (yarıda kesilirse yarım disk tanınmasın)
        progress(96, "Bölüm tablosu yazılıyor")
        dev.write(0, 1, mbr(p))
        dev.flush()
        progress(100, "Bitti")
        return p
    }

    private fun zero(dev: BlockDevice, start: Long, count: Long, onProgress: ((Long) -> Unit)? = null) {
        val chunk = 256 // 128 KB
        val z = ByteArray(chunk * 512)
        var done = 0L
        var lastReport = -1L
        while (done < count) {
            val n = minOf(chunk.toLong(), count - done).toInt()
            dev.write(start + done, n, z)
            done += n
            if (onProgress != null && done * 100 / count != lastReport) {
                lastReport = done * 100 / count
                onProgress(done)
            }
        }
    }

    private fun mbr(p: Plan): ByteArray {
        val b = ByteArray(512)
        put32(b, 0x1B8, Random.nextInt()) // disk imzası
        val e = 0x1BE
        b[e] = 0x00 // önyüklenebilir değil
        chs(b, e + 1, p.partStart)
        b[e + 4] = 0x0C // FAT32 (LBA)
        chs(b, e + 5, p.partStart + p.partSectors - 1)
        put32(b, e + 8, p.partStart.toInt())
        put32(b, e + 12, p.partSectors.toInt())
        b[510] = 0x55; b[511] = 0xAA.toByte()
        return b
    }

    /** 255 kafa / 63 sektör geometrisiyle CHS; sığmazsa FE FF FF. */
    private fun chs(b: ByteArray, o: Int, lba: Long) {
        val cyl = lba / (255 * 63)
        if (cyl > 1023) {
            b[o] = 0xFE.toByte(); b[o + 1] = 0xFF.toByte(); b[o + 2] = 0xFF.toByte(); return
        }
        val head = (lba / 63) % 255
        val sec = (lba % 63) + 1
        b[o] = head.toByte()
        b[o + 1] = (sec or ((cyl shr 8) shl 6)).toByte()
        b[o + 2] = (cyl and 0xFF).toByte()
    }

    private fun bootSector(p: Plan, volId: Int, lbl: String): ByteArray {
        val b = ByteArray(512)
        b[0] = 0xEB.toByte(); b[1] = 0x58; b[2] = 0x90.toByte()
        "MSWIN4.1".toByteArray(Charsets.US_ASCII).copyInto(b, 3)
        put16(b, 11, 512)
        b[13] = p.sectorsPerCluster.toByte()
        put16(b, 14, p.reserved)
        b[16] = 2                       // FAT sayısı
        put16(b, 17, 0)                 // kök girdi sayısı (FAT32'de 0)
        put16(b, 19, 0)                 // 16 bit toplam sektör
        b[21] = 0xF8.toByte()           // ortam: sabit disk
        put16(b, 22, 0)
        put16(b, 24, 63)                // iz başına sektör
        put16(b, 26, 255)               // kafa sayısı
        put32(b, 28, p.partStart.toInt()) // gizli sektörler
        put32(b, 32, p.partSectors.toInt())
        put32(b, 36, p.fatSize.toInt())
        put16(b, 40, 0)                 // ext flags
        put16(b, 42, 0)                 // sürüm
        put32(b, 44, 2)                 // kök küme
        put16(b, 48, 1)                 // FSInfo sektörü
        put16(b, 50, 6)                 // yedek önyükleme
        b[64] = 0x80.toByte()           // sürücü no
        b[66] = 0x29                    // genişletilmiş imza
        put32(b, 67, volId)
        lbl.toByteArray(Charsets.US_ASCII).copyInto(b, 71)
        "FAT32   ".toByteArray(Charsets.US_ASCII).copyInto(b, 82)
        // Basit önyükleme kodu: dur (hlt; jmp $)
        b[90] = 0xF4.toByte(); b[91] = 0xEB.toByte(); b[92] = 0xFD.toByte()
        b[510] = 0x55; b[511] = 0xAA.toByte()
        return b
    }

    private fun fsInfo(freeClusters: Long, nextFree: Long): ByteArray {
        val b = ByteArray(512)
        put32(b, 0, 0x41615252)
        put32(b, 484, 0x61417272)
        put32(b, 488, freeClusters.toInt())
        put32(b, 492, nextFree.toInt())
        put32(b, 508, 0xAA550000.toInt())
        return b
    }

    private fun name83(name: String): ByteArray {
        val n = name.uppercase().take(8).padEnd(8) + "   "
        return n.toByteArray(Charsets.US_ASCII)
    }

    private fun dirEntry(b: ByteArray, o: Int, name11: ByteArray, attr: Int, cluster: Long, date: Int, time: Int) {
        name11.copyInto(b, o, 0, 11)
        b[o + 11] = attr.toByte()
        if (attr != 0x08) {
            put16(b, o + 14, time)      // oluşturma saati
            put16(b, o + 16, date)      // oluşturma tarihi
            put16(b, o + 18, date)      // son erişim
            put16(b, o + 20, ((cluster shr 16) and 0xFFFF).toInt())
        }
        put16(b, o + 22, time)
        put16(b, o + 24, date)
        put16(b, o + 26, (cluster and 0xFFFF).toInt())
        put32(b, o + 28, 0)
    }

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
    }

    private fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
        b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
    }
}
