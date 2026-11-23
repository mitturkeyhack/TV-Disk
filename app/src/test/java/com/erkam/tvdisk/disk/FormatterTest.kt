package com.erkam.tvdisk.disk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class FileDevice(file: File, sectors: Long) : BlockDevice {
    private val raf = RandomAccessFile(file, "rw").also { it.setLength(sectors * 512) }
    override val blockSize = 512
    override val blockCount = sectors
    override fun read(lba: Long, count: Int, buf: ByteArray, off: Int) {
        raf.seek(lba * 512); raf.readFully(buf, off, count * 512)
    }
    override fun write(lba: Long, count: Int, buf: ByteArray, off: Int) {
        require(lba >= 0 && lba + count <= blockCount) { "sınır dışı yazma $lba+$count" }
        raf.seek(lba * 512); raf.write(buf, off, count * 512)
    }
    override fun flush() { raf.fd.sync() }
    fun close() = raf.close()
}

class FormatterTest {
    // Gerçek 64 GB'lık bir flash diskin sektör sayısı
    private val sectors64 = 121_110_528L

    private fun run(name: String, safe: Boolean, sectors: Long, preGpt: Boolean = false): DiskInspector.Info {
        val f = File("/tmp/$name.img"); f.delete()
        val d = FileDevice(f, sectors)
        if (preGpt) {
            // exFAT/GPT taklidi
            val s = ByteArray(512); s[0x1C2] = 0xEE.toByte(); s[510] = 0x55; s[511] = 0xAA.toByte()
            d.write(0, 1, s)
        }
        val before = DiskInspector.inspect(d)
        println("$name önce: $before")
        val p = Fat32Formatter.format(d, safe) { pct, msg -> if (pct % 25 == 0) println("  %$pct $msg") }
        println("$name plan: $p")
        val info = DiskInspector.inspect(d)
        println("$name sonra: $info")
        d.close()
        return info
    }

    @Test fun full64() {
        val i = run("full64", false, sectors64, preGpt = true)
        assertEquals("MBR", i.scheme); assertEquals("FAT32", i.fs)
    }

    @Test fun safe64() {
        val i = run("safe64", true, sectors64)
        assertEquals("FAT32", i.fs); assertTrue(i.tvReady)
    }

    @Test fun small8() {
        val i = run("small8", true, 15_728_640L)
        assertTrue(i.tvReady)
    }
}
