package com.medilocker.crypto

import com.medilocker.crypto.T.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PrimitivesTest {

    private val p = T.primitives

    // ---- HKDF correctness ---------------------------------------------------
    // HKDF is the one construction assembled here from a lower primitive rather
    // than taken whole from the library, so it is pinned to the RFC vectors.
    // Everything in the key hierarchy derives through it.

    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        val okm = p.hkdfSha256(
            ikm = T.hex("0b".repeat(22)),
            salt = T.hex("000102030405060708090a0b0c"),
            info = T.hex("f0f1f2f3f4f5f6f7f8f9"),
            outputLength = 42
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            okm.toHex()
        )
    }

    @Test
    fun `hkdf matches RFC 5869 test case 3 with empty salt and info`() {
        val okm = p.hkdfSha256(
            ikm = T.hex("0b".repeat(22)),
            salt = ByteArray(0),
            info = ByteArray(0),
            outputLength = 42
        )
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31" +
                "b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8",
            okm.toHex()
        )
    }

    // ---- Domain separation --------------------------------------------------

    @Test
    fun `each key purpose derives a distinct subkey`() {
        val dek = p.randomBytes(32)
        val derived = KeyPurpose.entries.map {
            KeyDerivation.subkey(dek, it, p).material().toHex()
        }
        assertEquals(
            derived.size, derived.toSet().size,
            "two key purposes derived the same key — domain separation is broken"
        )
    }

    @Test
    fun `subkey derivation is deterministic for the same dek`() {
        val dek = p.randomBytes(32)
        assertContentEquals(
            KeyDerivation.subkey(dek, KeyPurpose.FIELD, p).material(),
            KeyDerivation.subkey(dek, KeyPurpose.FIELD, p).material()
        )
    }

    // ---- AEAD ---------------------------------------------------------------

    @Test
    fun `nonces are never repeated across encryptions`() {
        // The defect this whole design exists to prevent: the previous version
        // used IV.fromLength(16), an all-zero IV identical for every record.
        val seen = HashSet<String>()
        repeat(1000) {
            assertTrue(
                seen.add(p.randomBytes(CryptoPrimitives.NONCE_BYTES).toHex()),
                "CSPRNG produced a duplicate nonce"
            )
        }
    }

    @Test
    fun `aead rejects a wrong key`() {
        val nonce = p.randomBytes(24)
        val sealed = p.aeadEncrypt(p.randomBytes(32), nonce, "secret".toByteArray(), ByteArray(0))
        assertFailsWith<AuthenticationFailure> {
            p.aeadDecrypt(p.randomBytes(32), nonce, sealed, ByteArray(0), "test")
        }
    }

    @Test
    fun `aead rejects every single-bit flip in the ciphertext`() {
        val key = p.randomBytes(32)
        val nonce = p.randomBytes(24)
        val sealed = p.aeadEncrypt(key, nonce, "Blood glucose 126 mg/dL".toByteArray(), ByteArray(0))

        for (index in sealed.indices) {
            val tampered = sealed.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
            assertFailsWith<AuthenticationFailure>("byte $index was not authenticated") {
                p.aeadDecrypt(key, nonce, tampered, ByteArray(0), "test")
            }
        }
    }

    // ---- Argon2id floors ----------------------------------------------------

    @Test
    fun `argon2id refuses parameters below the floor`() {
        val salt = p.randomBytes(16)
        assertFailsWith<WeakParameter> {
            p.argon2id("pw".toByteArray(), salt, memoryKib = 8, iterations = 3, outputLength = 32)
        }
        assertFailsWith<WeakParameter> {
            p.argon2id("pw".toByteArray(), salt, memoryKib = 64 * 1024, iterations = 1, outputLength = 32)
        }
    }

    @Test
    fun `argon2id refuses parameters above the ceiling`() {
        // Found by the wrapper-blob tamper test: flipping the high byte of the
        // memory field turns 32 MiB into 16 GiB, and libsodium will sit there
        // trying to do it. Since these parameters are read from a stored blob,
        // an attacker with write access could lock the user out of their own
        // records with a denial of service that breaks no cryptography at all.
        val salt = p.randomBytes(16)
        assertFailsWith<WeakParameter> {
            p.argon2id("pw".toByteArray(), salt, memoryKib = 16_842_752, iterations = 3, outputLength = 32)
        }
        assertFailsWith<WeakParameter> {
            p.argon2id("pw".toByteArray(), salt, memoryKib = 64 * 1024, iterations = 16_777_218, outputLength = 32)
        }
    }

    @Test
    fun `argon2id salt changes the derived key`() {
        val pw = "same password".toByteArray()
        val a = p.argon2id(pw, p.randomBytes(16), 32 * 1024, 2, 32)
        val b = p.argon2id(pw, p.randomBytes(16), 32 * 1024, 2, 32)
        assertNotEquals(a.toHex(), b.toHex(), "different salts must produce different keys")
    }

    // ---- SecureBytes --------------------------------------------------------

    @Test
    fun `destroyed key material is zeroed and unusable`() {
        val raw = p.randomBytes(32)
        val secure = SecureBytes(raw)
        secure.destroy()

        assertTrue(raw.all { it == 0.toByte() }, "backing array was not zeroed")
        assertFailsWith<KeyDestroyed> { secure.material() }
    }

    @Test
    fun `useThenDestroy wipes even when the block throws`() {
        val secure = SecureBytes(p.randomBytes(32))
        assertFailsWith<IllegalStateException> {
            secure.useThenDestroy { error("boom") }
        }
        assertFailsWith<KeyDestroyed> { secure.material() }
    }

    @Test
    fun `key material is never rendered by toString`() {
        val secure = SecureBytes(T.hex("c7".repeat(32)))
        val rendered = secure.toString()
        assertFalse(rendered.contains("c7"), "toString leaked key material")
        assertFalse(
            Regex("[0-9a-fA-F]{8,}").containsMatchIn(rendered),
            "toString contains a long hex run: $rendered"
        )
    }
}
