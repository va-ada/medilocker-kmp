package com.medilocker.crypto

/** Shared fixtures. */
object T {
    /** The desktop wiring. Android supplies LazySodiumAndroid to the same class. */
    val primitives: CryptoPrimitives by lazy {
        val sodium = com.goterl.lazysodium.LazySodiumJava(com.goterl.lazysodium.SodiumJava())
        LibsodiumPrimitives(sodium, sodium, sodium)
    }

    /**
     * Argon2id at the accepted floor rather than the production default, so the
     * suite runs in seconds. Still exercises the real KDF — these are not stubs.
     */
    val fastKdf = WrappedKey.KdfParams(
        memoryKib = LibsodiumPrimitives.MIN_MEMORY_KIB,
        iterations = LibsodiumPrimitives.MIN_ITERATIONS
    )

    const val GOOD_PASSWORD = "a-perfectly-adequate-passphrase"

    fun password(text: String = GOOD_PASSWORD): CharArray = text.toCharArray()

    fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
