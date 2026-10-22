package com.medilocker.crypto

import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.Random
import com.sun.jna.NativeLong
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * JVM and Android binding.
 *
 * AEAD, Argon2id and randomness come from libsodium. HKDF and HMAC come from
 * the platform JCE, which is present on both the JVM and Android and is the
 * same construction CryptoKit provides on iOS — so subkeys derive identically
 * on every platform.
 *
 * The constructor takes lazysodium's *interfaces* rather than a concrete
 * LazySodiumJava. That is deliberate: the desktop build ships JNA bindings and
 * the Android build ships JNI bindings in a different artifact, and only the
 * interfaces are common to both. Binding the concrete class here would have
 * made this file un-runnable on a phone.
 *
 *   JVM / tests  →  LazySodiumJava
 *   Android      →  LazySodiumAndroid
 */
class LibsodiumPrimitives(
    private val aead: AEAD.Native,
    private val pwHash: PwHash.Native,
    private val random: Random
) : CryptoPrimitives {

    override fun randomBytes(count: Int): ByteArray {
        require(count > 0) { "count must be positive" }
        return random.randomBytesBuf(count)
    }

    override fun aeadEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        requireKey(key); requireNonce(nonce)

        val out = ByteArray(plaintext.size + CryptoPrimitives.TAG_BYTES)
        val outLen = LongArray(1)
        val ok = aead.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            out, outLen,
            plaintext, plaintext.size.toLong(),
            aad, aad.size.toLong(),
            null, nonce, key
        )
        if (!ok) throw CryptoBackendFailure("xchacha20poly1305 encrypt failed")
        check(outLen[0].toInt() == out.size) { "unexpected ciphertext length ${outLen[0]}" }
        return out
    }

    override fun aeadDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
        context: String
    ): ByteArray {
        requireKey(key); requireNonce(nonce)
        if (ciphertext.size < CryptoPrimitives.TAG_BYTES) {
            throw MalformedBlob(context, "ciphertext shorter than the authentication tag")
        }

        val out = ByteArray(ciphertext.size - CryptoPrimitives.TAG_BYTES)
        val ok = aead.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            out, LongArray(1),
            null,
            ciphertext, ciphertext.size.toLong(),
            aad, aad.size.toLong(),
            nonce, key
        )
        // Wipe before throwing so no partial plaintext survives in the buffer.
        if (!ok) {
            out.wipe()
            throw AuthenticationFailure(context)
        }
        return out
    }

    override fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        memoryKib: Int,
        iterations: Int,
        outputLength: Int
    ): ByteArray {
        require(salt.size == PwHash.SALTBYTES) {
            "Argon2id salt must be ${PwHash.SALTBYTES} bytes, got ${salt.size}"
        }
        if (memoryKib < MIN_MEMORY_KIB) {
            throw WeakParameter(
                "Argon2id memory of $memoryKib KiB is below the floor of $MIN_MEMORY_KIB KiB. " +
                    "Refusing to derive a key that is cheap to attack offline."
            )
        }
        if (iterations < MIN_ITERATIONS) {
            throw WeakParameter("Argon2id iterations of $iterations is below the floor of $MIN_ITERATIONS.")
        }
        // Ceilings matter as much as floors. These parameters are read from a
        // stored blob, so an attacker with write access to it can otherwise
        // request 16 GiB or 16 million iterations and hang or OOM the app on
        // every unlock attempt — a denial of service that locks the user out of
        // their own medical records without breaking any cryptography.
        if (memoryKib > MAX_MEMORY_KIB) {
            throw WeakParameter(
                "Argon2id memory of $memoryKib KiB exceeds the ceiling of $MAX_MEMORY_KIB KiB. " +
                    "Refusing to attempt an allocation this large."
            )
        }
        if (iterations > MAX_ITERATIONS) {
            throw WeakParameter("Argon2id iterations of $iterations exceeds the ceiling of $MAX_ITERATIONS.")
        }

        val out = ByteArray(outputLength)
        val ok = pwHash.cryptoPwHash(
            out, out.size,
            password, password.size,
            salt,
            iterations.toLong(),
            NativeLong(memoryKib.toLong() * 1024L),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13
        )
        if (!ok) {
            out.wipe()
            throw CryptoBackendFailure(
                "Argon2id failed — usually insufficient memory for $memoryKib KiB"
            )
        }
        return out
    }

    /** RFC 5869. Extract then expand. */
    override fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int
    ): ByteArray {
        require(outputLength in 1..(255 * SHA256_LEN)) {
            "HKDF output length $outputLength out of range"
        }
        val prk = hmacSha256(salt, ikm)
        try {
            val blocks = (outputLength + SHA256_LEN - 1) / SHA256_LEN
            val output = ByteArray(outputLength)
            var previous = ByteArray(0)
            var written = 0
            for (i in 1..blocks) {
                previous = hmacSha256(prk, previous + info + byteArrayOf(i.toByte()))
                val take = minOf(SHA256_LEN, outputLength - written)
                previous.copyInto(output, written, 0, take)
                written += take
            }
            return output
        } finally {
            prk.wipe()
        }
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // An all-zero key is legal for HKDF-Extract's default salt, but
        // SecretKeySpec rejects a zero-length key, so pad that one case.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(SHA256_LEN) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    override fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    private fun requireKey(key: ByteArray) =
        require(key.size == CryptoPrimitives.KEY_BYTES) {
            "key must be ${CryptoPrimitives.KEY_BYTES} bytes, got ${key.size}"
        }

    private fun requireNonce(nonce: ByteArray) =
        require(nonce.size == CryptoPrimitives.NONCE_BYTES) {
            "nonce must be ${CryptoPrimitives.NONCE_BYTES} bytes, got ${nonce.size}"
        }

    companion object {
        private const val SHA256_LEN = 32

        /** Floors reject a downgraded vault; ceilings reject a resource-exhaustion vault. */
        const val MIN_MEMORY_KIB = 32 * 1024
        const val MIN_ITERATIONS = 2

        /** Well above the 64 MiB / 3 production default, so cost can still be raised later. */
        const val MAX_MEMORY_KIB = 512 * 1024
        const val MAX_ITERATIONS = 10
    }
}

/** The underlying library failed in a way that is not an authentication result. */
class CryptoBackendFailure(detail: String) : CryptoException(detail)
