package com.erkam.tvdisk.disk

/** Sektör sektör okunup yazılabilen bir disk (USB bellek ya da test için dosya). */
interface BlockDevice {
    val blockSize: Int
    val blockCount: Long
    fun read(lba: Long, count: Int, buf: ByteArray, off: Int = 0)
    fun write(lba: Long, count: Int, buf: ByteArray, off: Int = 0)
    fun flush()
}
