package com.medilocker.crypto

/**
 * Binary layout helpers. Every multi-byte integer in every MediLocker blob is
 * big-endian, unconditionally, so a vault written on one platform reads on the
 * other with no endianness negotiation.
 */
internal object Fmt {

    fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    fun getInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)

    fun putLong(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            target[offset + i] = (value ushr (56 - 8 * i)).toByte()
        }
    }

    /** Verifies a 4-byte ASCII magic, throwing with useful context if absent. */
    fun requireMagic(blob: ByteArray, expected: String, what: String) {
        if (blob.size < 4) throw MalformedBlob(what, "shorter than a magic number")
        val actual = String(blob, 0, 4, Charsets.US_ASCII)
        if (actual != expected) {
            throw MalformedBlob(what, "expected magic '$expected' but found '$actual'")
        }
    }

    fun requireVersion(actual: Int, supported: Int, what: String) {
        if (actual != supported) {
            throw MalformedBlob(
                what,
                "version $actual is not supported by this build (supports $supported)"
            )
        }
    }

    fun requireLength(blob: ByteArray, expected: Int, what: String) {
        if (blob.size != expected) {
            throw MalformedBlob(what, "expected exactly $expected bytes, got ${blob.size}")
        }
    }

    fun magicBytes(magic: String): ByteArray = magic.toByteArray(Charsets.US_ASCII)
}
