package com.medilocker.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileCipherTest {

    private val p = T.primitives
    private val created = Vault.create(T.password(), p, T.fastKdf)

    /** Small chunks so multi-chunk behaviour is exercised without large fixtures. */
    private val chunk = FileCipher.MIN_CHUNK_SIZE
    private val cipher = created.vault.fileCipher(chunk)

    private fun encrypt(data: ByteArray, fileId: String = "doc-1"): ByteArray {
        val out = ByteArrayOutputStream()
        val written = cipher.encrypt(ByteArrayInputStream(data), out, fileId)
        assertEquals(data.size.toLong(), written)
        return out.toByteArray()
    }

    private fun decrypt(blob: ByteArray, fileId: String = "doc-1"): ByteArray {
        val out = ByteArrayOutputStream()
        cipher.decrypt(ByteArrayInputStream(blob), out, fileId)
        return out.toByteArray()
    }

    // ---- Round trips, including the boundary cases --------------------------

    @Test
    fun `round trips across sizes including exact chunk boundaries`() {
        // Exact multiples are where an off-by-one in the final-chunk logic hides.
        val sizes = listOf(
            0, 1, 100,
            chunk - 1, chunk, chunk + 1,
            2 * chunk - 1, 2 * chunk, 2 * chunk + 1,
            5 * chunk + 37
        )
        for (size in sizes) {
            val data = p.randomBytes(size.coerceAtLeast(1)).copyOf(size)
            assertContentEquals(data, decrypt(encrypt(data)), "round trip failed at size $size")
        }
    }

    @Test
    fun `a realistic multi megabyte document round trips`() {
        val data = p.randomBytes(3 * 1024 * 1024)
        val sealed = encrypt(data)
        assertContentEquals(data, decrypt(sealed))
        assertTrue(sealed.size > data.size, "ciphertext must carry per-chunk tags")
    }

    @Test
    fun `ciphertext differs for identical plaintext`() {
        val data = p.randomBytes(chunk * 2)
        assertTrue(!encrypt(data).contentEquals(encrypt(data)), "file nonce prefix is not random")
    }

    // ---- T7: truncation -----------------------------------------------------

    @Test
    fun `a truncated file is rejected rather than decrypting as complete`() {
        // The dangerous case: cut a lab report in half and, without a final
        // chunk marker, it decrypts perfectly and reads as a whole document.
        // Abnormal results usually sit at the end.
        val data = p.randomBytes(chunk * 4)
        val sealed = encrypt(data)
        val sealedChunk = chunk + CryptoPrimitives.TAG_BYTES

        // Drop the last two sealed chunks, leaving a clean chunk boundary so
        // nothing but the final-chunk marker can detect the loss.
        val truncated = sealed.copyOf(25 + sealedChunk * 2)

        assertFailsWith<FileIntegrityFailure> { decrypt(truncated) }
    }

    @Test
    fun `dropping only the last chunk is still detected`() {
        val data = p.randomBytes(chunk * 3)
        val sealed = encrypt(data)
        val sealedChunk = chunk + CryptoPrimitives.TAG_BYTES
        assertFailsWith<FileIntegrityFailure> {
            decrypt(sealed.copyOf(25 + sealedChunk * 2))
        }
    }

    @Test
    fun `cutting mid chunk is detected`() {
        val data = p.randomBytes(chunk * 3)
        val sealed = encrypt(data)
        assertFailsWith<CryptoException> { decrypt(sealed.copyOf(sealed.size - 7)) }
    }

    @Test
    fun `a header with no chunks is rejected`() {
        val sealed = encrypt(p.randomBytes(chunk))
        assertFailsWith<FileIntegrityFailure> { decrypt(sealed.copyOf(25)) }
    }

    // ---- T7: reordering -----------------------------------------------------

    @Test
    fun `swapping two chunks is detected`() {
        val data = p.randomBytes(chunk * 3)
        val sealed = encrypt(data)
        val sealedChunk = chunk + CryptoPrimitives.TAG_BYTES

        val reordered = sealed.copyOf()
        val first = sealed.copyOfRange(25, 25 + sealedChunk)
        val second = sealed.copyOfRange(25 + sealedChunk, 25 + sealedChunk * 2)
        second.copyInto(reordered, 25)
        first.copyInto(reordered, 25 + sealedChunk)

        assertFailsWith<CryptoException> { decrypt(reordered) }
    }

    // ---- T7: cross-file splicing --------------------------------------------

    @Test
    fun `a chunk cannot be spliced in from another file`() {
        val data = p.randomBytes(chunk * 2)
        val a = encrypt(data, "doc-a")
        val b = encrypt(data, "doc-b")
        val sealedChunk = chunk + CryptoPrimitives.TAG_BYTES

        val spliced = a.copyOf()
        b.copyOfRange(25, 25 + sealedChunk).copyInto(spliced, 25)

        assertFailsWith<CryptoException> { decrypt(spliced, "doc-a") }
    }

    @Test
    fun `a whole file will not decrypt under a different file id`() {
        val sealed = encrypt(p.randomBytes(chunk * 2), "doc-a")
        assertFailsWith<CryptoException> { decrypt(sealed, "doc-b") }
    }

    // ---- Tampering and malformed input --------------------------------------

    @Test
    fun `a flipped bit in any chunk is detected`() {
        val sealed = encrypt(p.randomBytes(chunk * 2))
        for (index in listOf(25, 25 + chunk / 2, sealed.size - 1)) {
            val tampered = sealed.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
            assertFailsWith<CryptoException>("byte $index was not authenticated") {
                decrypt(tampered)
            }
        }
    }

    @Test
    fun `a corrupted header is rejected`() {
        val sealed = encrypt(p.randomBytes(chunk))

        val badMagic = sealed.copyOf().also { it[0] = 'X'.code.toByte() }
        assertFailsWith<MalformedBlob> { decrypt(badMagic) }

        val badVersion = sealed.copyOf().also { it[4] = 99 }
        assertFailsWith<MalformedBlob> { decrypt(badVersion) }

        // A declared chunk size outside the accepted range would otherwise let
        // an attacker force a multi-gigabyte allocation.
        val badChunkSize = sealed.copyOf().also { Fmt.putInt(it, 5, Int.MAX_VALUE) }
        assertFailsWith<MalformedBlob> { decrypt(badChunkSize) }

        assertFailsWith<MalformedBlob> { decrypt(ByteArray(10)) }
    }

    @Test
    fun `a file sealed under one vault does not open under another`() {
        val other = Vault.create(T.password(), p, T.fastKdf)
        val sealed = encrypt(p.randomBytes(chunk))
        val out = ByteArrayOutputStream()
        assertFailsWith<CryptoException> {
            other.vault.fileCipher(chunk).decrypt(ByteArrayInputStream(sealed), out, "doc-1")
        }
        other.vault.close()
    }

    @Test
    fun `file id may not be blank or contain a colon`() {
        val out = ByteArrayOutputStream()
        assertFailsWith<IllegalArgumentException> {
            cipher.encrypt(ByteArrayInputStream(ByteArray(1)), out, "")
        }
        assertFailsWith<IllegalArgumentException> {
            cipher.encrypt(ByteArrayInputStream(ByteArray(1)), out, "doc:1")
        }
    }

    @Test
    fun `chunk size outside the accepted range is refused`() {
        assertFailsWith<IllegalArgumentException> { created.vault.fileCipher(64) }
        assertFailsWith<IllegalArgumentException> { created.vault.fileCipher(64 * 1024 * 1024) }
    }
}
