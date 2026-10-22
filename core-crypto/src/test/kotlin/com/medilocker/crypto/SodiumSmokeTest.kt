package com.medilocker.crypto

import com.goterl.lazysodium.SodiumJava
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.PwHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * De-risking test. Confirms the native libsodium binding actually loads on this
 * machine and exposes the two primitives SECURITY.md depends on:
 * XChaCha20-Poly1305 (AEAD) and Argon2id (crypto_pwhash).
 *
 * If this fails, the primitive layer falls back to BouncyCastle and the spec's
 * §3.1 choice has to be revisited. Nothing else is written until this passes.
 */
class SodiumSmokeTest {

    private val sodium = LazySodiumJava(SodiumJava())

    @Test
    fun `libsodium native library loads`() {
        val out = sodium.randomBytesBuf(32)
        assertTrue(out.any { it != 0.toByte() }, "CSPRNG returned all zeros")
    }

    @Test
    fun `xchacha20poly1305 round trips and detects tampering`() {
        val key = sodium.randomBytesBuf(AEAD.XCHACHA20POLY1305_IETF_KEYBYTES)
        val nonce = sodium.randomBytesBuf(AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES)

        assertEquals(24, nonce.size, "XChaCha20 nonce must be 24 bytes per SECURITY.md 3.1")

        val message = "Paracetamol 500mg BD x 5 days".toByteArray()
        val aad = "medilocker:v1:field:rec123:title".toByteArray()

        val cipher = ByteArray(message.size + AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val cipherLen = LongArray(1)
        val ok = sodium.getSodium().crypto_aead_xchacha20poly1305_ietf_encrypt(
            cipher, cipherLen, message, message.size.toLong(),
            aad, aad.size.toLong(), null, nonce, key
        )
        assertEquals(0, ok, "encrypt failed")
        assertEquals(message.size + 16, cipherLen[0].toInt(), "tag must be 16 bytes")

        // correct AAD -> plaintext recovered
        val plain = ByteArray(message.size)
        val plainLen = LongArray(1)
        val dec = sodium.getSodium().crypto_aead_xchacha20poly1305_ietf_decrypt(
            plain, plainLen, null, cipher, cipherLen[0],
            aad, aad.size.toLong(), nonce, key
        )
        assertEquals(0, dec, "decrypt failed")
        assertEquals(String(message), String(plain))

        // wrong AAD -> must be rejected (this is what blocks ciphertext relocation, T6)
        val wrongAad = "medilocker:v1:field:rec123:category".toByteArray()
        val bad = sodium.getSodium().crypto_aead_xchacha20poly1305_ietf_decrypt(
            ByteArray(message.size), LongArray(1), null, cipher, cipherLen[0],
            wrongAad, wrongAad.size.toLong(), nonce, key
        )
        assertTrue(bad != 0, "decryption under a different AAD must fail")

        // flipped ciphertext bit -> must be rejected
        cipher[3] = (cipher[3].toInt() xor 0x01).toByte()
        val tampered = sodium.getSodium().crypto_aead_xchacha20poly1305_ietf_decrypt(
            ByteArray(message.size), LongArray(1), null, cipher, cipherLen[0],
            aad, aad.size.toLong(), nonce, key
        )
        assertTrue(tampered != 0, "tampered ciphertext must fail authentication")
    }

    @Test
    fun `argon2id is available and is deterministic for a fixed salt`() {
        val salt = ByteArray(PwHash.SALTBYTES) { it.toByte() }
        val password = "correct horse battery staple".toByteArray()

        fun derive(): ByteArray {
            val out = ByteArray(32)
            val rc = sodium.getSodium().crypto_pwhash(
                out, out.size.toLong(),
                password, password.size.toLong(),
                salt,
                3L,                                              // iterations, SECURITY.md 3.3
                com.sun.jna.NativeLong(64L * 1024 * 1024),       // 64 MiB
                PwHash.Alg.PWHASH_ALG_ARGON2ID13.value
            )
            assertEquals(0, rc, "argon2id failed (likely insufficient memory)")
            return out
        }

        val a = derive()
        val b = derive()
        assertTrue(a.contentEquals(b), "Argon2id must be deterministic for same input")
        assertTrue(a.any { it != 0.toByte() }, "derived key is all zeros")
    }
}
