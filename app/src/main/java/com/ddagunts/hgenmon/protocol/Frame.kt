package com.ddagunts.hgenmon.protocol

/**
 *
 * See `docs/protocol.md` for the protocol writeup.
 */
data class Frame(
    val function: Function,
    val group: Char,
    val dataNo: String,
    val data: String,
) {

    init {
        require(dataNo.length == 2 && dataNo.all { it.isAsciiHex() }) {
            "dataNo must be 2 hex chars (was '$dataNo')"
        }
        require(data.length == 2 && data.all { it.isAsciiHex() }) {
            "data must be 2 hex chars (was '$data')"
        }
    }

    enum class Function(val ascii: Char) {
        READ_1('B'),
        READ_16('C'),
        WRITE_1('D'),
    }

    /** Serialize to the 10-byte BLE wire frame (STX … EOT, with computed checksum). */
    fun encode(): ByteArray {
        val body = byteArrayOf(
            function.ascii.code.toByte(),
            group.code.toByte(),
            dataNo[0].code.toByte(),
            dataNo[1].code.toByte(),
            data[0].code.toByte(),
            data[1].code.toByte(),
        )
        val cs = Checksum.hexByte(Checksum.xor(body))
        return byteArrayOf(STX) + body + byteArrayOf(cs[0].code.toByte(), cs[1].code.toByte()) + byteArrayOf(EOT)
    }

    companion object {
        const val STX: Byte = 0x01
        const val EOT: Byte = 0x04
        const val FRAME_LEN = 10
        /** Direction prefix that the BT module prepends to indication payloads on `DIAGNOSTIC_RESPONSE`. */
        const val RESPONSE_DIRECTION: Byte = 0x80.toByte()

        /**
         * Parse a 10-byte wire frame (STX + body + EOT). Returns null if framing/checksum is bad.
         *
         * Pass the bytes WITHOUT the response direction prefix. If you have a response indication
         * payload that starts with [RESPONSE_DIRECTION], strip the first byte first.
         */
        fun decode(bytes: ByteArray): Frame? {
            if (bytes.size != FRAME_LEN) return null
            if (bytes[0] != STX || bytes[FRAME_LEN - 1] != EOT) return null
            val body = bytes.copyOfRange(1, 7)        // 6 body bytes
            val csChars = bytes.copyOfRange(7, 9)     // 2 cs hex chars
            val expectedCs = Checksum.hexByte(Checksum.xor(body))
            val gotCs = String(byteArrayOf(csChars[0], csChars[1]), Charsets.US_ASCII)
            if (!expectedCs.equals(gotCs, ignoreCase = true)) return null

            val func = Function.entries.firstOrNull { it.ascii.code.toByte() == body[0] } ?: return null
            val group = body[1].toInt().toChar()
            val dataNo = String(byteArrayOf(body[2], body[3]), Charsets.US_ASCII).uppercase()
            val data   = String(byteArrayOf(body[4], body[5]), Charsets.US_ASCII).uppercase()
            return Frame(func, group, dataNo, data)
        }

        /** Build a READ_1 request frame: read 1 byte at (group, dataNo). */
        fun readByte(group: Char, dataNo: String): Frame =
            Frame(Function.READ_1, group, dataNo.uppercase(), "00")

        /** Build a WRITE_1 frame: write 1 byte at (group, dataNo). */
        fun writeByte(group: Char, dataNo: String, value: Int): Frame {
            require(value in 0..0xFF) { "value must fit in a byte (was $value)" }
            return Frame(Function.WRITE_1, group, dataNo.uppercase(), "%02X".format(value))
        }
    }
}

private fun Char.isAsciiHex(): Boolean =
    this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'
