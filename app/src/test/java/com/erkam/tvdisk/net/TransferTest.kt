package com.erkam.tvdisk.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.random.Random

class TransferTest {

    private fun server(sink: FileSink, events: MutableList<String>): ServerSocket {
        val ss = ServerSocket(0)
        Thread {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                s.use {
                    ReceiverCore.handle(it, sink, object : ReceiveEvents {
                        override fun started(name: String, size: Long) { events += "start $name" }
                        override fun progress(name: String, done: Long, total: Long, bytesPerSec: Double) {}
                        override fun finished(name: String, ok: Boolean, message: String) { events += "end $name $ok $message" }
                    })
                }
            }
        }.start()
        return ss
    }

    private fun dirSink(dir: File, slowDisk: Boolean = false, failAfter: Long = -1) = object : FileSink {
        override fun reject(name: String, size: Long) = if (size > 200_000_000) Proto.NO_SPACE to "" else null
        override fun create(name: String): SinkTarget {
            val f = File(dir, name)
            val fos = FileOutputStream(f)
            val wrapped = object : OutputStream() {
                var n = 0L
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    n += len
                    if (failAfter in 0..n) throw IOException("No space left on device")
                    if (slowDisk) Thread.sleep(2)
                    fos.write(b, off, len)
                }
                override fun close() = fos.close()
            }
            return object : SinkTarget {
                override val out: OutputStream = wrapped
                override val displayName = name
                override fun delete() { f.delete() }
            }
        }
    }

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b)

    @Test fun sendsFileIntact() {
        val dir = createTempDir()
        val ev = mutableListOf<String>()
        val ss = server(dirSink(dir, slowDisk = true), ev)
        val data = Random(1).nextBytes(37_000_123)
        val t0 = System.nanoTime()
        val name = SenderCore.send("127.0.0.1", ss.localPort, "film.mp4", data.size.toLong(),
            ByteArrayInputStream(data), { false }) { _, _, _ -> }
        println("süre ${(System.nanoTime() - t0) / 1e6} ms")
        assertEquals("film.mp4", name)
        assertArrayEquals(md5(data), md5(File(dir, "film.mp4").readBytes()))
        Thread.sleep(100)
        println(ev)
        assertTrue(ev.last().startsWith("end film.mp4 true"))
        ss.close()
    }

    @Test fun rejectsTooBig() {
        val dir = createTempDir()
        val ss = server(dirSink(dir), mutableListOf())
        val e = runCatching {
            SenderCore.send("127.0.0.1", ss.localPort, "big.mp4", 300_000_000, ByteArrayInputStream(ByteArray(0)), { false }) { _, _, _ -> }
        }.exceptionOrNull()
        println("ret: ${e?.message}")
        assertEquals("Flash diskte yer kalmadı", e?.message)
        ss.close()
    }

    @Test fun diskFullMidway() {
        val dir = createTempDir()
        val ev = mutableListOf<String>()
        val ss = server(dirSink(dir, failAfter = 5_000_000), ev)
        val data = Random(2).nextBytes(20_000_000)
        val e = runCatching {
            SenderCore.send("127.0.0.1", ss.localPort, "x.mp4", data.size.toLong(), ByteArrayInputStream(data), { false }) { _, _, _ -> }
        }.exceptionOrNull()
        Thread.sleep(200)
        println("hata: ${e?.message} / $ev")
        assertTrue(e != null)
        assertTrue(!File(dir, "x.mp4").exists())
        ss.close()
    }

    @Test fun beaconRoundTrip() {
        val b = Proto.beacon("HUAWEI Y6|2019", 47300, true, 123456789L, "TV DISK")
        val p = Proto.parseBeacon(b, "192.168.1.5")!!
        assertEquals("HUAWEI Y6 2019", p.name); assertEquals(47300, p.port); assertTrue(p.diskReady)
        assertEquals(123456789L, p.free); assertEquals("TV DISK", p.diskName)
    }
}
