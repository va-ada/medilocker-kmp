package com.medilocker.crypto

/**
 * Subkey derivation from the DEK.
 *
 * SECURITY.md 2.2 invariant 2: every purpose gets its own key, derived with a
 * distinct HKDF `info` string. No key is ever used for two purposes, so a
 * weakness or an oracle in one subsystem cannot be pivoted into another — the
 * blind-index key cannot decrypt fields, and the database passphrase cannot
 * decrypt documents.
 *
 * These strings are a wire format. Changing one orphans every value derived
 * under the old string, so a change requires a version bump and a migration.
 */
enum class KeyPurpose(internal val info: String) {
    /** AEAD key for individual record fields. */
    FIELD("medilocker:v1:field"),

    /** AEAD key for document contents. */
    FILE("medilocker:v1:file"),

    /** HMAC key for blind indexes. */
    BLIND_INDEX("medilocker:v1:index"),

    /** SQLCipher passphrase for the local database. */
    DATABASE("medilocker:v1:db")
}

internal object KeyDerivation {

    private val HKDF_SALT = "medilocker:v1:hkdf".toByteArray(Charsets.UTF_8)

    /**
     * Derive the subkey for [purpose] from the vault DEK.
     *
     * The DEK is already uniform random, so the extract step is not strictly
     * required — it is kept because staying on the standard construction is
     * worth more than saving one HMAC.
     */
    fun subkey(
        dek: ByteArray,
        purpose: KeyPurpose,
        primitives: CryptoPrimitives
    ): SecureBytes = SecureBytes(
        primitives.hkdfSha256(
            ikm = dek,
            salt = HKDF_SALT,
            info = purpose.info.toByteArray(Charsets.UTF_8),
            outputLength = CryptoPrimitives.KEY_BYTES
        )
    )
}
