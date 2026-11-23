package com.erkam.tvdisk.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.erkam.tvdisk.disk.BlockDevice
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB belleğe root gerektirmeden doğrudan erişim (USB Mass Storage, Bulk-Only + SCSI).
 * Arayüz "force" ile alındığı için Android diski o an bırakır; iş bitince disk çıkarılıp takılmalı.
 */
class UsbMassStorage private constructor(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
) : BlockDevice {

    override var blockSize = 512
        private set
    override var blockCount = 0L
        private set
    var vendor = ""
        private set
    var product = ""
        private set

    private var tag = 1
    private val lun = 0
    private var closed = false

    companion object {
        private const val TIMEOUT = 10_000
        private const val CHUNK = 16_384
        private const val MAX_BLOCKS_PER_CMD = 128 // 64 KB

        fun findInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val f = device.getInterface(i)
                if (f.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    f.interfaceProtocol == 0x50 && f.endpointCount >= 2
                ) return f
            }
            return null
        }

        fun isMassStorage(device: UsbDevice) = findInterface(device) != null

        fun find(manager: UsbManager): UsbDevice? =
            manager.deviceList.values.firstOrNull { isMassStorage(it) }

        fun open(manager: UsbManager, device: UsbDevice): UsbMassStorage {
            val iface = findInterface(device) ?: throw IOException("Bu cihaz bir USB bellek değil")
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp == null || outEp == null) throw IOException("USB bellek uç noktaları bulunamadı")
            val conn = manager.openDevice(device) ?: throw IOException("USB bağlantısı açılamadı")
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                throw IOException("USB belleğe erişilemedi")
            }
            val dev = UsbMassStorage(conn, iface, inEp, outEp)
            try {
                dev.init()
            } catch (e: Exception) {
                dev.close()
                throw e
            }
            return dev
        }
    }

    private fun init() {
        val inq = ByteArray(36)
        try {
            if (scsi(byteArrayOf(0x12, 0, 0, 0, 36, 0), inq, true)) {
                vendor = String(inq, 8, 8, Charsets.US_ASCII).trim()
                product = String(inq, 16, 16, Charsets.US_ASCII).trim()
            }
        } catch (_: IOException) { }

        // Disk hazır olana kadar bekle (ilk komutlarda "unit attention" dönebilir)
        for (i in 0 until 20) {
            if (scsi(ByteArray(6), null, true)) break
            requestSense()
            Thread.sleep(250)
        }

        val cap = ByteArray(8)
        var ok = false
        for (i in 0 until 6) {
            if (scsi(ByteArray(10).also { it[0] = 0x25 }, cap, true)) { ok = true; break }
            requestSense()
            Thread.sleep(300)
        }
        if (!ok) throw IOException("Diskin kapasitesi okunamadı")
        val bb = ByteBuffer.wrap(cap).order(ByteOrder.BIG_ENDIAN)
        val last = bb.int.toLong() and 0xFFFFFFFFL
        blockSize = bb.int
        blockCount = last + 1
        if (blockSize != 512) throw IOException("Desteklenmeyen sektör boyutu: $blockSize")
        if (blockCount < 1024) throw IOException("Disk boş ya da takılı değil")
    }

    override fun read(lba: Long, count: Int, buf: ByteArray, off: Int) {
        var done = 0
        while (done < count) {
            val n = minOf(MAX_BLOCKS_PER_CMD, count - done)
            rw(0x28, lba + done, n, buf, off + done * blockSize, true)
            done += n
        }
    }

    override fun write(lba: Long, count: Int, buf: ByteArray, off: Int) {
        var done = 0
        while (done < count) {
            val n = minOf(MAX_BLOCKS_PER_CMD, count - done)
            rw(0x2A, lba + done, n, buf, off + done * blockSize, false)
            done += n
        }
    }

    override fun flush() {
        try { scsi(ByteArray(10).also { it[0] = 0x35 }, null, false) } catch (_: IOException) { }
    }

    fun close() {
        if (closed) return
        closed = true
        try { conn.releaseInterface(iface) } catch (_: Exception) { }
        try { conn.close() } catch (_: Exception) { }
    }

    private fun rw(op: Int, lba: Long, n: Int, buf: ByteArray, off: Int, dirIn: Boolean) {
        if (lba < 0 || lba + n > blockCount) throw IOException("Disk sınırı dışına erişim")
        val cb = byteArrayOf(
            op.toByte(), 0,
            (lba shr 24).toByte(), (lba shr 16).toByte(), (lba shr 8).toByte(), lba.toByte(),
            0, (n shr 8).toByte(), n.toByte(), 0,
        )
        var tries = 0
        while (true) {
            val ok = try {
                scsi(cb, buf, dirIn, off, n * blockSize)
            } catch (e: IOException) {
                if (++tries >= 3) throw e
                Thread.sleep(300)
                continue
            }
            if (ok) return
            requestSense()
            if (++tries >= 3) {
                throw IOException(if (dirIn) "Diskten okunamadı (sektör $lba)" else "Diske yazılamadı (sektör $lba)")
            }
            Thread.sleep(200)
        }
    }

    private fun requestSense() {
        try { scsi(byteArrayOf(0x03, 0, 0, 0, 18, 0), ByteArray(18), true) } catch (_: IOException) { }
    }

    /** Bir SCSI komutu çalıştırır; başarılıysa true. */
    private fun scsi(cb: ByteArray, data: ByteArray?, dirIn: Boolean, off: Int = 0,
                     len: Int = data?.size ?: 0): Boolean {
        if (closed) throw IOException("Disk bağlantısı kapalı")
        val t = tag++
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(0x43425355)
        cbw.putInt(t)
        cbw.putInt(len)
        cbw.put(if (dirIn) 0x80.toByte() else 0)
        cbw.put(lun.toByte())
        cbw.put(cb.size.toByte())
        cbw.put(cb)
        val cbwBytes = cbw.array()
        if (conn.bulkTransfer(outEp, cbwBytes, 0, 31, TIMEOUT) != 31) {
            resetRecovery()
            throw IOException("USB belleğe komut gönderilemedi. Disk çıkmış olabilir.")
        }

        if (len > 0 && data != null) {
            var done = 0
            val ep = if (dirIn) inEp else outEp
            while (done < len) {
                val n = minOf(CHUNK, len - done)
                val r = conn.bulkTransfer(ep, data, off + done, n, TIMEOUT)
                if (r < 0) { clearHalt(ep); break }
                done += r
                if (dirIn && r < n) break // kısa paket: veri bitti
            }
        }

        val csw = ByteArray(13)
        var r = conn.bulkTransfer(inEp, csw, 0, 13, TIMEOUT)
        if (r < 0) {
            clearHalt(inEp)
            r = conn.bulkTransfer(inEp, csw, 0, 13, TIMEOUT)
        }
        if (r != 13) {
            resetRecovery()
            throw IOException("USB bellek yanıt vermedi")
        }
        val b = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != 0x53425355) {
            resetRecovery()
            throw IOException("USB bellekten bozuk yanıt geldi")
        }
        val status = csw[12].toInt()
        if (status == 2) { resetRecovery(); return false }
        return status == 0
    }

    private fun clearHalt(ep: UsbEndpoint) {
        conn.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, TIMEOUT)
    }

    private fun resetRecovery() {
        conn.controlTransfer(0x21, 0xFF, 0, iface.id, null, 0, TIMEOUT)
        clearHalt(inEp)
        clearHalt(outEp)
    }
}
