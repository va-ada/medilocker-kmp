package com.medilocker.crypto

import java.io.InputStream
import java.io.OutputStream

/**
 * Encrypts documents. SECURITY.md 6.
 *
 * A 50 MB scan must never be fully resident — the previous version called
 * `putData(file.bytes!)` with the whole file in memory, and uploaded it in
 * plaintext besides. Here the file is processed in chunks and never fully
 * materialised.
 *
 * ```
 *  header (25 bytes)
 *       0    4 magic "MLKD"
 *       4    1 version = 1
 *       5    4 chunk plaintext size
 *       9   16 nonce prefix (random per file)
 *
 *  then repeating: chunk ciphertext + 16-byte tag
 * ```
 *
 * Per-chunk nonce = noncePrefix(16) ‖ chunkIndex(8 big endian) = 24 bytes,
 * unique per chunk and per file without a counter anyone has to maintain.
 *
 * Per-chunk AAD = `medilocker:v1:file:{fileId}:{chunkIndex}:{isFinal}`
 *
 * That AAD blocks three separate attacks (T7):
 *
 *  - **Reordering** — the chunk index appears in both the nonce and the AAD.
 *  - **Truncation** — the last chunk is sealed with `isFinal=1`. A truncated
 *    file ends on a chunk sealed with `isFinal=0` but is decrypted expecting
 *    `isFinal=1`, so the AAD mismatches and it is rejected. Without this
 *    marker, cutting a lab report in half yields a file that decrypts
 *    perfectly and reads as complete — the dangerous case, because the
 *    abnormal result is usually at the end.
 *  - **Cross-file splicing** — the fileId appears in the AAD.
 *
 * The chunking protocol is platform independent. Only the [InputStream] and
 * [OutputStream] parameters are JVM-shaped; the KMP port swaps them for okio
 * `Source` and `Sink` without touching the logic below.
 */
class FileCipher internal constructor(
    private val key: SecureBytes,
    private val primitives: CryptoPrimitives,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE
) {

    init {
        require(chunkSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) {
            "chunk size $chunkSize outside [$MIN_CHUNK_SIZE, $MAX_CHUNK_SIZE]"
        }
    }

    /** @return number of plaintext bytes encrypted. */
    fun encrypt(source: InputStream, sink: OutputStream, fileId: String): Long {
        validateFileId(fileId)

        val noncePrefix = primitives.randomBytes(NONCE_PREFIX_BYTES)
        val header = ByteArray(HEADER_BYTES)
        Fmt.magicBytes(MAGIC).copyInto(header, 0)
        header[OFF_VERSION] = VERSION.toByte()
        Fmt.putInt(header, OFF_CHUNK_SIZE, chunkSize)
        noncePrefix.copyInto(header, OFF_NONCE_PREFIX)
        sink.write(header)

        var index = 0L
        var total = 0L
        var current = source.readAtMost(chunkSize)

        while (true) {
            // Read ahead one chunk to learn whether the current one is final.
            val next = source.readAtMost(chunkSize)
            val isFinal = next.isEmpty()

            val sealed = primitives.aeadEncrypt(
                key.material(),
                chunkNonce(noncePrefix, index),
                current,
                chunkAad(fileId, index, isFinal)
            )
            sink.write(sealed)
            total += current.size
            current.wipe()

            if (isFinal) break
            current = next
            index++
        }
        sink.flush()
        return total
    }

    /** @return number of plaintext bytes recovered. */
    fun decrypt(source: InputStream, sink: OutputStream, fileId: String): Long {
        validateFileId(fileId)

        val header = source.readAtMost(HEADER_BYTES)
        if (header.size < HEADER_BYTES) {
            throw MalformedBlob("encrypted file '$fileId'", "truncated header")
        }
        Fmt.requireMagic(header, MAGIC, "encrypted file '$fileId'")
        Fmt.requireVersion(header[OFF_VERSION].toInt(), VERSION, "encrypted file '$fileId'")

        val storedChunkSize = Fmt.getInt(header, OFF_CHUNK_SIZE)
        if (storedChunkSize !in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) {
            throw MalformedBlob(
                "encrypted file '$fileId'",
                "declared chunk size $storedChunkSize is out of range"
            )
        }
        val noncePrefix = header.copyOfRange(OFF_NONCE_PREFIX, HEADER_BYTES)
        val sealedSize = storedChunkSize + CryptoPrimitives.TAG_BYTES

        var index = 0L
        var total = 0L
        var current = source.readAtMost(sealedSize)

        if (current.isEmpty()) {
            throw FileIntegrityFailure("file '$fileId' has a header but no chunks")
        }

        while (true) {
            val next = source.readAtMost(sealedSize)
            val isFinal = next.isEmpty()

            if (current.size < CryptoPrimitives.TAG_BYTES) {
                throw FileIntegrityFailure(
                    "file '$fileId' chunk $index is ${current.size} bytes, shorter than the tag"
                )
            }
            // A non-final chunk must be exactly full. A short chunk in the
            // middle means bytes were removed.
            if (!isFinal && current.size != sealedSize) {
                throw FileIntegrityFailure(
                    "file '$fileId' chunk $index is short but is not the last chunk — bytes were removed"
                )
            }

            val plain = try {
                primitives.aeadDecrypt(
                    key.material(),
                    chunkNonce(noncePrefix, index),
                    current,
                    chunkAad(fileId, index, isFinal),
                    "file '$fileId' chunk $index"
                )
            } catch (e: AuthenticationFailure) {
                // The overwhelmingly likely cause is truncation: this chunk was
                // sealed as non-final and is now being opened as final.
                throw FileIntegrityFailure(
                    "file '$fileId' failed authentication at chunk $index — " +
                        "the file was truncated, reordered, or modified"
                )
            }

            sink.write(plain)
            total += plain.size
            plain.wipe()

            if (isFinal) break
            current = next
            index++
        }
        sink.flush()
        return total
    }

    private fun chunkNonce(prefix: ByteArray, index: Long): ByteArray {
        val nonce = ByteArray(CryptoPrimitives.NONCE_BYTES)
        prefix.copyInto(nonce, 0)
        Fmt.putLong(nonce, NONCE_PREFIX_BYTES, index)
        return nonce
    }

    private fun chunkAad(fileId: String, index: Long, isFinal: Boolean): ByteArray =
        "$AAD_PREFIX$fileId:$index:${if (isFinal) 1 else 0}".toByteArray(Charsets.UTF_8)

    private fun validateFileId(fileId: String) {
        require(fileId.isNotBlank()) { "fileId must not be blank — binding would be meaningless" }
        require(!fileId.contains(':')) { "fileId must not contain ':' — it would make the AAD ambiguous" }
    }

    companion object {
        private const val MAGIC = "MLKD"
        private const val VERSION = 1
        private const val OFF_VERSION = 4
        private const val OFF_CHUNK_SIZE = 5
        private const val OFF_NONCE_PREFIX = 9
        private const val NONCE_PREFIX_BYTES = 16
        private const val HEADER_BYTES = 25

        const val DEFAULT_CHUNK_SIZE = 64 * 1024
        const val MIN_CHUNK_SIZE = 1024
        const val MAX_CHUNK_SIZE = 4 * 1024 * 1024

        private const val AAD_PREFIX = "medilocker:v1:file:"
    }
}

/**
 * Reads up to [count] bytes, returning fewer only at end of stream.
 *
 * [InputStream.read] is permitted to return less than requested for reasons
 * that have nothing to do with EOF, so a single read cannot be used to detect
 * the last chunk. Getting this wrong would make the final-chunk marker fire on
 * ordinary short reads and reject valid files.
 */
private fun InputStream.readAtMost(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val read = read(buffer, filled, count - filled)
        if (read < 0) break
        filled += read
    }
    return if (filled == count) buffer else buffer.copyOf(filled)
}
