package com.medilocker.crypto

/**
 * The complete set of cryptographic primitives this layer is allowed to use.
 *
 * Everything above this interface is protocol: formats, key hierarchy, binding.
 * Everything below is delegated to an audited implementation. No protocol code
 * ever touches a cipher directly, and no primitive is implemented in this
 * project.
 *
 * This is an interface rather than `expect`/`actual` so the protocol can be
 * tested against a deterministic fake. Converting it to `expect`/`actual` for
 * the KMP move is mechanical.
 *
 * Platform bindings:
 *   Android / JVM — libsodium via lazysodium, plus javax.crypto for HKDF/HMAC
 *   iOS           — libsodium via cinterop, plus CryptoKit for HKDF/HMAC
 *
 * The wire format is byte-identical across both because the AEAD and the
 * password KDF come from the same libsodium on each side.
 */
interface CryptoPrimitives {

    /** Platform CSPRNG. SECURITY.md 3.4 — no custom PRNG, ever. */
    fun randomBytes(count: Int): ByteArray

    /**
     * XChaCha20-Poly1305. [nonce] must be [NONCE_BYTES] and must be freshly
     * random for every call — SECURITY.md 3.1.
     *
     * @return ciphertext with the 16-byte tag appended.
     */
    fun aeadEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /**
     * @throws AuthenticationFailure if the tag, the key, or the AAD is wrong.
     *   There is no non-throwing failure mode.
     */
    fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray, context: String): ByteArray

    /** Argon2id. SECURITY.md 3.3. */
    fun argon2id(password: ByteArray, salt: ByteArray, memoryKib: Int, iterations: Int, outputLength: Int): ByteArray

    /** HKDF-SHA256 for subkey derivation with domain separation. SECURITY.md 3.2. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputLength: Int): ByteArray

    /** HMAC-SHA256, used only for blind indexes. SECURITY.md 7.2. */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** Non-short-circuiting comparison. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean

    companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 24
        const val TAG_BYTES = 16
        const val SALT_BYTES = 16
    }
}
