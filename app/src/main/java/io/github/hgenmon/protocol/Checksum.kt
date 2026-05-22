package io.github.hgenmon.protocol

internal object Checksum {

    /** XOR the bytes together and return a single byte. */
    fun xor(bytes: ByteArray): Byte {
        var acc: Byte = 0
        for (b in bytes) acc = (acc.toInt() xor b.toInt()).toByte()
        return acc
    }

    /** Format a byte as 2 uppercase hex ASCII chars (e.g. 0x08 → "08"). */
    fun hexByte(b: Byte): String {
        val v = b.toInt() and 0xFF
        return "%02X".format(v)
    }
}
