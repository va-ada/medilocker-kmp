package com.medilocker.crypto

/**
 * Encrypts individual record fields. SECURITY.md 5.
 *
 * ```
 *  offset size field
 *       0    4 magic "MLKF"
 *       4    1 version = 1
 *       5   24 nonce
 *      29    N ciphertext + 16-byte tag
 * ```
 *
 * The point of this class is the AAD:
 *
 *     medilocker:v1:field:{recordId}:{fieldName}
 *
 * which defeats threat T6. A ciphertext sealed as record `abc`'s `title` will
 * not authenticate as record `abc`'s `category`, nor as record `xyz`'s `title`.
 * An attacker holding database write access therefore cannot shuffle encrypted
 * values between fields or between patients — an attack that matters in a
 * medical context, because swapping a diagnosis between two records causes real
 * harm while every individual value remains "validly encrypted".
 *
 * There is no API here that encrypts without binding. That is deliberate: an
 * unbound overload would be reached for whenever the binding was inconvenient.
 */
class FieldCipher internal constructor(
    private val key: SecureBytes,
    private val primitives: CryptoPrimitives
) {

    fun encrypt(plaintext: String, recordId: String, fieldName: String): ByteArray {
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        return try {
            encryptBytes(bytes, recordId, fieldName)
        } finally {
            bytes.wipe()
        }
    }

    fun encryptBytes(plaintext: ByteArray, recordId: String, fieldName: String): ByteArray {
        val nonce = primitives.randomBytes(CryptoPrimitives.NONCE_BYTES)
        val sealed = primitives.aeadEncrypt(key.material(), nonce, plaintext, aad(recordId, fieldName))

        val blob = ByteArray(HEADER + sealed.size)
        Fmt.magicBytes(MAGIC).copyInto(blob, 0)
        blob[OFF_VERSION] = VERSION.toByte()
        nonce.copyInto(blob, OFF_NONCE)
        sealed.copyInto(blob, HEADER)
        return blob
    }

    /** @throws AuthenticationFailure if the blob was tampered with, or belongs to a different field or record. */
    fun decrypt(blob: ByteArray, recordId: String, fieldName: String): String {
        val bytes = decryptBytes(blob, recordId, fieldName)
        return try {
            String(bytes, Charsets.UTF_8)
        } finally {
            bytes.wipe()
        }
    }

    fun decryptBytes(blob: ByteArray, recordId: String, fieldName: String): ByteArray {
        val what = "field '$fieldName' of record '$recordId'"
        if (blob.size < HEADER + CryptoPrimitives.TAG_BYTES) {
            throw MalformedBlob(what, "blob is ${blob.size} bytes, shorter than a header plus tag")
        }
        Fmt.requireMagic(blob, MAGIC, what)
        Fmt.requireVersion(blob[OFF_VERSION].toInt(), VERSION, what)

        val nonce = blob.copyOfRange(OFF_NONCE, HEADER)
        val sealed = blob.copyOfRange(HEADER, blob.size)
        return primitives.aeadDecrypt(key.material(), nonce, sealed, aad(recordId, fieldName), what)
    }

    private fun aad(recordId: String, fieldName: String): ByteArray {
        require(recordId.isNotBlank()) { "recordId must not be blank — binding would be meaningless" }
        require(fieldName.isNotBlank()) { "fieldName must not be blank — binding would be meaningless" }
        require(!recordId.contains(':')) { "recordId must not contain ':' — it would make the AAD ambiguous" }
        require(!fieldName.contains(':')) { "fieldName must not contain ':' — it would make the AAD ambiguous" }
        return "$AAD_PREFIX$recordId:$fieldName".toByteArray(Charsets.UTF_8)
    }

    private companion object {
        const val MAGIC = "MLKF"
        const val VERSION = 1
        const val OFF_VERSION = 4
        const val OFF_NONCE = 5
        const val HEADER = 29
        const val AAD_PREFIX = "medilocker:v1:field:"
    }
}
